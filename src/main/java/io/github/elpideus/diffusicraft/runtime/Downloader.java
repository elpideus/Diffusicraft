package io.github.elpideus.diffusicraft.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * HTTP downloads with resume, retries, SHA-256 verification and cancellation through a {@link Job}.
 *
 * <p>Progress invariant: a job's {@code done} counter includes every byte currently sitting in one of its
 * {@code .part} files. Callers count bytes left over from an earlier session once, before the first fetch;
 * {@link #fetch} then reports only what it adds (or throws away).
 */
public final class Downloader {
	private static final Logger LOGGER = LoggerFactory.getLogger(Downloader.class);
	public static final String USER_AGENT = "Diffusicraft (+https://github.com/elpideus/Diffusicraft)";
	private static final int ATTEMPTS = 5;
	private static final int BUFFER_SIZE = 1 << 16;
	private static final HttpClient CLIENT = HttpClient.newBuilder()
		.followRedirects(HttpClient.Redirect.NORMAL)
		.connectTimeout(Duration.ofSeconds(30))
		.build();

	/** The server answered a range request with the whole resource. */
	public static final class RangeNotSupportedException extends IOException {
		RangeNotSupportedException(URI uri) {
			super("Server ignores range requests: " + uri);
		}
	}

	@FunctionalInterface
	interface IoAction {
		void run() throws IOException;
	}

	private Downloader() {
	}

	/**
	 * Downloads {@code uri} to {@code target} and verifies its size and SHA-256 before moving it into place.
	 * Resumes from {@code target.part} left by an interrupted run.
	 */
	public static void download(URI uri, Path target, long size, String sha256, Job job) throws IOException {
		Path part = target.resolveSibling(target.getFileName() + ".part");
		countExisting(part, size, job);
		retry(job, "Download of " + target.getFileName(), () -> {
			fetch(uri, 0, size, part, job, false);
			job.checkCancelled();
			String actual = sha256(part);
			if (!actual.equalsIgnoreCase(sha256)) {
				job.progress(-Files.size(part));
				Files.delete(part);
				throw new IOException("Checksum mismatch for " + target.getFileName() + ": got " + actual + ", expected " + sha256);
			}
		});
		moveAtomic(part, target);
	}

	/** Counts the bytes of a partial file left by an earlier run towards {@code job}; deletes it if it is too long. */
	static void countExisting(Path part, long expected, Job job) throws IOException {
		if (!Files.isRegularFile(part)) {
			return;
		}
		long have = Files.size(part);
		if (have > expected) {
			Files.delete(part);
		} else {
			job.progress(have);
		}
	}

	/**
	 * Appends bytes {@code [offset + size(part), offset + length)} of {@code uri} to {@code part}.
	 *
	 * @param requireRange whether a server that ignores the range header is an error; otherwise a full response
	 *                     restarts {@code part} from the beginning (only valid when {@code offset == 0})
	 */
	static void fetch(URI uri, long offset, long length, Path part, Job job, boolean requireRange) throws IOException {
		long have = Files.isRegularFile(part) ? Files.size(part) : 0;
		if (have > length) {
			job.progress(-have);
			Files.delete(part);
			have = 0;
		}
		if (have == length) {
			return;
		}
		long start = offset + have;
		HttpRequest.Builder request = request(uri);
		boolean ranged = requireRange || start > 0;
		if (ranged) {
			request.header("Range", "bytes=" + start + "-" + (offset + length - 1));
		}
		HttpResponse<InputStream> response = send(request.build());
		int status = response.statusCode();
		boolean append;
		if (status == 206 && ranged) {
			String contentRange = response.headers().firstValue("Content-Range").orElse("");
			if (!contentRange.startsWith("bytes " + start + "-")) {
				response.body().close();
				throw new IOException("Unexpected Content-Range '" + contentRange + "' for " + uri);
			}
			append = have > 0;
		} else if (status == 200) {
			if (requireRange || offset != 0) {
				response.body().close();
				throw new RangeNotSupportedException(uri);
			}
			append = false;
		} else {
			response.body().close();
			throw new IOException("HTTP " + status + " for " + uri);
		}
		if (!append && have > 0) {
			job.progress(-have);
			have = 0;
		}
		long remaining = length - have;
		try (InputStream in = job.track(response.body());
			 OutputStream out = Files.newOutputStream(part, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
				 append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING)) {
			byte[] buffer = new byte[BUFFER_SIZE];
			int read;
			while (remaining > 0 && (read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining))) >= 0) {
				job.checkCancelled();
				out.write(buffer, 0, read);
				remaining -= read;
				job.progress(read);
			}
		} finally {
			job.untrack();
		}
		if (remaining > 0) {
			throw new IOException("Connection closed with " + remaining + " of " + length + " bytes missing from " + uri);
		}
	}

	/** Reads {@code [offset, offset + length)} of {@code uri} into memory. Used for ZIP metadata. */
	static byte[] readRange(URI uri, long offset, int length, Job job) throws IOException {
		HttpRequest request = request(uri).header("Range", "bytes=" + offset + "-" + (offset + length - 1)).build();
		HttpResponse<InputStream> response = send(request);
		if (response.statusCode() == 200) {
			response.body().close();
			throw new RangeNotSupportedException(uri);
		}
		if (response.statusCode() != 206) {
			response.body().close();
			throw new IOException("HTTP " + response.statusCode() + " for " + uri);
		}
		try (InputStream in = job.track(response.body())) {
			byte[] bytes = in.readNBytes(length);
			if (bytes.length != length) {
				throw new IOException("Short read from " + uri + ": " + bytes.length + " of " + length + " bytes");
			}
			return bytes;
		} finally {
			job.untrack();
		}
	}

	/** Runs {@code action} up to five times with growing pauses. Cancellation and missing range support are not retried. */
	static void retry(Job job, String what, IoAction action) throws IOException {
		IOException failure = null;
		for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
			job.checkCancelled();
			try {
				action.run();
				return;
			} catch (RangeNotSupportedException e) {
				throw e;
			} catch (IOException e) {
				job.checkCancelled();
				failure = e;
				LOGGER.warn("{} failed (attempt {}/{}): {}", what, attempt, ATTEMPTS, e.toString());
				if (attempt < ATTEMPTS) {
					pause(job, 1000L << attempt);
				}
			}
		}
		throw failure;
	}

	private static void pause(Job job, long millis) throws IOException {
		long end = System.currentTimeMillis() + millis;
		try {
			while (System.currentTimeMillis() < end) {
				job.checkCancelled();
				Thread.sleep(100);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted", e);
		}
	}

	private static HttpRequest.Builder request(URI uri) {
		return HttpRequest.newBuilder(uri)
			.header("User-Agent", USER_AGENT)
			.timeout(Duration.ofSeconds(60))
			.GET();
	}

	private static HttpResponse<InputStream> send(HttpRequest request) throws IOException {
		try {
			return CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while connecting to " + request.uri(), e);
		}
	}

	public static String sha256(Path path) throws IOException {
		MessageDigest digest = sha256Digest();
		try (InputStream in = new DigestInputStream(Files.newInputStream(path), digest)) {
			in.transferTo(OutputStream.nullOutputStream());
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	static MessageDigest sha256Digest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	public static void moveAtomic(Path from, Path to) throws IOException {
		try {
			Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
