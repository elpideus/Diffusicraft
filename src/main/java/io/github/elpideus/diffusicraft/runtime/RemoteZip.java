package io.github.elpideus.diffusicraft.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Extracts single entries from a ZIP archive (a Python wheel, a Maven jar) on an HTTP server without downloading
 * the whole archive. Range requests fetch the end-of-central-directory record, then the central directory, then
 * only the compressed bytes of the wanted entries. The ONNX Runtime GPU jar is 400 MB, but one platform needs
 * about half of it; NVIDIA's wheels also carry headers and static libraries that are never loaded.
 *
 * <p>Compressed bytes are staged in a resumable {@code <name>.packed.part}, then inflated while the CRC-32 and
 * SHA-256 are checked.
 */
final class RemoteZip {
	private static final int EOCD_SIGNATURE = 0x06054b50;
	private static final int ZIP64_LOCATOR_SIGNATURE = 0x07064b50;
	private static final int ZIP64_EOCD_SIGNATURE = 0x06064b50;
	private static final int CENTRAL_SIGNATURE = 0x02014b50;
	private static final int LOCAL_SIGNATURE = 0x04034b50;
	private static final long MAX_CENTRAL_DIRECTORY = 64L << 20;

	record Entry(String name, int method, long crc, long packedSize, long size, long localHeaderOffset) {
	}

	private final URI uri;
	private final long archiveSize;
	private Map<String, Entry> entries;

	RemoteZip(URI uri, long archiveSize) {
		this.uri = uri;
		this.archiveSize = archiveSize;
	}

	/** Entries by name. Fetches the central directory on first use. */
	Map<String, Entry> entries(Job job) throws IOException {
		if (entries == null) {
			entries = readCentralDirectory(job);
		}
		return entries;
	}

	private Map<String, Entry> readCentralDirectory(Job job) throws IOException {
		int tailLength = (int) Math.min(archiveSize, 22 + 0xFFFF + 20);
		ByteBuffer tail = le(Downloader.readRange(uri, archiveSize - tailLength, tailLength, job));
		int eocd = -1;
		for (int i = tailLength - 22; i >= 0; i--) {
			if (tail.getInt(i) == EOCD_SIGNATURE) {
				eocd = i;
				break;
			}
		}
		if (eocd < 0) {
			throw new IOException("Not a ZIP archive: " + uri);
		}
		long count = tail.getShort(eocd + 10) & 0xFFFF;
		long cdSize = tail.getInt(eocd + 12) & 0xFFFFFFFFL;
		long cdOffset = tail.getInt(eocd + 16) & 0xFFFFFFFFL;
		if (count == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
			int locator = eocd - 20;
			if (locator < 0 || tail.getInt(locator) != ZIP64_LOCATOR_SIGNATURE) {
				throw new IOException("Missing ZIP64 locator in " + uri);
			}
			ByteBuffer zip64 = le(Downloader.readRange(uri, tail.getLong(locator + 8), 56, job));
			if (zip64.getInt(0) != ZIP64_EOCD_SIGNATURE) {
				throw new IOException("Corrupt ZIP64 end record in " + uri);
			}
			count = zip64.getLong(32);
			cdSize = zip64.getLong(40);
			cdOffset = zip64.getLong(48);
		}
		if (cdSize > MAX_CENTRAL_DIRECTORY || cdOffset + cdSize > archiveSize) {
			throw new IOException("Implausible central directory (" + cdSize + " bytes at " + cdOffset + ") in " + uri);
		}
		ByteBuffer cd = le(Downloader.readRange(uri, cdOffset, (int) cdSize, job));
		Map<String, Entry> result = new HashMap<>();
		int p = 0;
		for (long i = 0; i < count; i++) {
			if (cd.getInt(p) != CENTRAL_SIGNATURE) {
				throw new IOException("Corrupt central directory in " + uri);
			}
			int method = cd.getShort(p + 10) & 0xFFFF;
			long crc = cd.getInt(p + 16) & 0xFFFFFFFFL;
			long packed = cd.getInt(p + 20) & 0xFFFFFFFFL;
			long size = cd.getInt(p + 24) & 0xFFFFFFFFL;
			int nameLength = cd.getShort(p + 28) & 0xFFFF;
			int extraLength = cd.getShort(p + 30) & 0xFFFF;
			int commentLength = cd.getShort(p + 32) & 0xFFFF;
			long offset = cd.getInt(p + 42) & 0xFFFFFFFFL;
			byte[] nameBytes = new byte[nameLength];
			cd.get(p + 46, nameBytes);
			String name = new String(nameBytes, StandardCharsets.UTF_8);
			// ZIP64 extended information: the fields present replace the 0xFFFFFFFF placeholders, in this order.
			int e = p + 46 + nameLength;
			int extraEnd = e + extraLength;
			while (e + 4 <= extraEnd) {
				int tag = cd.getShort(e) & 0xFFFF;
				int length = cd.getShort(e + 2) & 0xFFFF;
				if (tag == 0x0001) {
					int q = e + 4;
					if (size == 0xFFFFFFFFL) {
						size = cd.getLong(q);
						q += 8;
					}
					if (packed == 0xFFFFFFFFL) {
						packed = cd.getLong(q);
						q += 8;
					}
					if (offset == 0xFFFFFFFFL) {
						offset = cd.getLong(q);
					}
				}
				e += 4 + length;
			}
			result.put(name, new Entry(name, method, crc, packed, size, offset));
			p += 46 + nameLength + extraLength + commentLength;
		}
		return result;
	}

	/**
	 * Downloads and inflates one entry into {@code target}. Compressed bytes count towards {@code job} as they
	 * arrive; a staged file from an interrupted run is resumed.
	 */
	void extract(Entry entry, Path target, long size, String sha256, Job job) throws IOException {
		if (entry.size() != size) {
			throw new IOException(entry.name() + " is " + entry.size() + " bytes in " + uri + ", expected " + size);
		}
		if (entry.method() != 0 && entry.method() != 8) {
			throw new IOException("Unsupported compression method " + entry.method() + " for " + entry.name());
		}
		Path packed = target.resolveSibling(target.getFileName() + ".packed.part");
		Downloader.countExisting(packed, entry.packedSize(), job);
		Downloader.retry(job, "Download of " + entry.name(), () -> {
			Downloader.fetch(uri, dataOffset(entry, job), entry.packedSize(), packed, job, true);
			job.checkCancelled();
			try {
				inflate(packed, entry, target, sha256);
			} catch (IOException e) {
				// The staged bytes are corrupt; start this entry over.
				job.progress(-Files.size(packed));
				Files.delete(packed);
				throw e;
			}
		});
		Files.deleteIfExists(packed);
	}

	private long dataOffset(Entry entry, Job job) throws IOException {
		ByteBuffer header = le(Downloader.readRange(uri, entry.localHeaderOffset(), 30, job));
		if (header.getInt(0) != LOCAL_SIGNATURE) {
			throw new IOException("Corrupt local header for " + entry.name() + " in " + uri);
		}
		int nameLength = header.getShort(26) & 0xFFFF;
		int extraLength = header.getShort(28) & 0xFFFF;
		return entry.localHeaderOffset() + 30 + nameLength + extraLength;
	}

	/** Decompresses a staged entry next to {@code target}, verifies it and moves it into place. */
	static void inflate(Path packed, Entry entry, Path target, String sha256) throws IOException {
		Path part = target.resolveSibling(target.getFileName() + ".part");
		MessageDigest digest = Downloader.sha256Digest();
		CRC32 crc = new CRC32();
		long written = 0;
		Inflater inflater = new Inflater(true);
		try (InputStream raw = Files.newInputStream(packed);
			 InputStream in = entry.method() == 8 ? new InflaterInputStream(raw, inflater, 1 << 16) : raw;
			 OutputStream out = Files.newOutputStream(part)) {
			byte[] buffer = new byte[1 << 16];
			int read;
			while ((read = in.read(buffer)) > 0) {
				out.write(buffer, 0, read);
				digest.update(buffer, 0, read);
				crc.update(buffer, 0, read);
				written += read;
			}
		} finally {
			inflater.end();
		}
		String actual = HexFormat.of().formatHex(digest.digest());
		if (written != entry.size() || crc.getValue() != entry.crc() || !actual.equalsIgnoreCase(sha256)) {
			Files.deleteIfExists(part);
			throw new IOException("Verification failed for " + entry.name() + " (" + written + " bytes, sha256 " + actual + ")");
		}
		Downloader.moveAtomic(part, target);
	}

	private static ByteBuffer le(byte[] bytes) {
		return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
	}
}
