package io.github.elpideus.diffusicraft.map;

import io.github.elpideus.diffusicraft.Diffusicraft;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.jspecify.annotations.Nullable;

/**
 * The smallest HTTP/1.1 server the relief map needs, on {@code java.base} alone: GET and HEAD, keep-alive, no request
 * bodies. One virtual thread serves each connection, so a request may wait for a tile as long as it takes.
 * {@link Exchange#alive()} tells a waiting request whether the browser gave up on it.
 */
final class MapHttpServer implements AutoCloseable {
	private static final int MAX_CONNECTIONS = 64;
	private static final int MAX_LINE = 8192;
	private static final int MAX_HEADERS = 64;
	/** How long an idle keep-alive connection stays open, in milliseconds. */
	private static final int IDLE_MS = 30_000;

	@FunctionalInterface
	interface Handler {
		Response handle(Exchange exchange) throws Exception;
	}

	/** A response: its body is sent as it is, with {@code Content-Encoding: gzip} if {@code gzipped}. */
	record Response(int status, String type, byte[] body, boolean gzipped, Map<String, String> headers) {
		static Response of(int status, String type, byte[] body) {
			return new Response(status, type, body, false, Map.of());
		}

		static Response text(int status, String text) {
			return of(status, "text/plain; charset=utf-8", text.getBytes(StandardCharsets.UTF_8));
		}

		static Response json(String json) {
			return of(200, "application/json", json.getBytes(StandardCharsets.UTF_8));
		}
	}

	/** One request, as the handler sees it. */
	static final class Exchange {
		final String method;
		final String path;
		final Map<String, String> query;
		private final Socket socket;
		private final PushbackInputStream in;

		private Exchange(String method, String path, Map<String, String> query, Socket socket,
						 PushbackInputStream in) {
			this.method = method;
			this.path = path;
			this.query = query;
			this.socket = socket;
			this.in = in;
		}

		/** A query parameter, or {@code fallback} if the request has none. */
		String param(String name, String fallback) {
			return query.getOrDefault(name, fallback);
		}

		int intParam(String name) {
			String value = query.get(name);
			if (value == null) {
				throw new IllegalArgumentException("Missing parameter " + name);
			}
			return Integer.parseInt(value.trim());
		}

		long longParam(String name, long fallback) {
			String value = query.get(name);
			return value == null ? fallback : Long.parseLong(value.trim());
		}

		/**
		 * Whether the browser still waits for the response. Browsers close the connection when they abort a request,
		 * and send nothing else on it before the response, so a closed stream means the request was dropped.
		 */
		boolean alive() {
			try {
				if (in.available() > 0) {
					return true;
				}
				socket.setSoTimeout(1);
				int b = in.read();
				if (b < 0) {
					return false;
				}
				in.unread(b);
				return true;
			} catch (SocketTimeoutException e) {
				return true;
			} catch (IOException e) {
				return false;
			} finally {
				try {
					socket.setSoTimeout(IDLE_MS);
				} catch (SocketException ignored) {
					// The connection is closing anyway.
				}
			}
		}
	}

	private final ServerSocket socket;
	private final Handler handler;
	private final Semaphore connections = new Semaphore(MAX_CONNECTIONS);
	private final Thread acceptor;
	private volatile boolean closed;

	MapHttpServer(InetAddress address, int port, Handler handler) throws IOException {
		this.socket = new ServerSocket();
		this.socket.setReuseAddress(true);
		this.socket.bind(new InetSocketAddress(address, port), 50);
		this.handler = handler;
		this.acceptor = Thread.ofPlatform().daemon().name("Diffusicraft relief map").unstarted(this::accept);
		this.acceptor.start();
	}

	int port() {
		return socket.getLocalPort();
	}

	private void accept() {
		while (!closed) {
			Socket connection;
			try {
				connection = socket.accept();
			} catch (IOException e) {
				if (!closed) {
					Diffusicraft.LOGGER.warn("The relief map stopped accepting connections", e);
				}
				return;
			}
			if (!connections.tryAcquire()) {
				close(connection);
				continue;
			}
			Thread.ofVirtual().name("Diffusicraft relief map connection").start(() -> {
				try {
					serve(connection);
				} finally {
					connections.release();
					close(connection);
				}
			});
		}
	}

	private void serve(Socket connection) {
		try {
			connection.setSoTimeout(IDLE_MS);
			connection.setTcpNoDelay(true);
			PushbackInputStream in = new PushbackInputStream(new BufferedInputStream(connection.getInputStream()), 1);
			OutputStream out = new BufferedOutputStream(connection.getOutputStream(), 1 << 16);
			while (!closed) {
				String line = readLine(in);
				if (line == null) {
					return;
				}
				if (line.isEmpty()) {
					continue;
				}
				String[] parts = line.split(" ");
				Map<String, String> headers = new HashMap<>();
				for (int n = 0; ; n++) {
					String header = readLine(in);
					if (header == null) {
						return;
					}
					if (header.isEmpty()) {
						break;
					}
					int colon = header.indexOf(':');
					if (n >= MAX_HEADERS || colon <= 0) {
						write(out, Response.text(400, "Bad request"), false, false);
						return;
					}
					headers.put(header.substring(0, colon).trim().toLowerCase(Locale.ROOT),
						header.substring(colon + 1).trim());
				}
				if (parts.length != 3 || !parts[2].startsWith("HTTP/1.")) {
					write(out, Response.text(400, "Bad request"), false, false);
					return;
				}
				String length = headers.get("content-length");
				if (length != null && !length.equals("0") || headers.containsKey("transfer-encoding")) {
					write(out, Response.text(413, "Requests carry no body here"), false, false);
					return;
				}
				boolean keepAlive = parts[2].equals("HTTP/1.1")
					? !"close".equalsIgnoreCase(headers.get("connection"))
					: "keep-alive".equalsIgnoreCase(headers.get("connection"));
				String method = parts[0];
				boolean head = method.equals("HEAD");
				Response response;
				if (!method.equals("GET") && !head) {
					response = Response.text(405, "Only GET and HEAD");
				} else {
					response = respond(method, parts[1], connection, in);
				}
				if (response == null) {
					// The browser dropped the request.
					return;
				}
				write(out, response, head, keepAlive);
				if (!keepAlive) {
					return;
				}
			}
		} catch (IOException e) {
			// The browser went away.
		}
	}

	private @Nullable Response respond(String method, String target, Socket connection, PushbackInputStream in) {
		int q = target.indexOf('?');
		String path = q < 0 ? target : target.substring(0, q);
		Map<String, String> query = new HashMap<>();
		if (q >= 0) {
			for (String pair : target.substring(q + 1).split("&")) {
				if (pair.isEmpty()) {
					continue;
				}
				int eq = pair.indexOf('=');
				String key = eq < 0 ? pair : pair.substring(0, eq);
				String value = eq < 0 ? "" : pair.substring(eq + 1);
				query.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value,
					StandardCharsets.UTF_8));
			}
		}
		try {
			return handler.handle(new Exchange(method, path, query, connection, in));
		} catch (IllegalArgumentException e) {
			return Response.text(400, String.valueOf(e.getMessage()));
		} catch (Exception e) {
			Diffusicraft.LOGGER.warn("The relief map could not answer {}", path, e);
			return Response.text(500, "Something went wrong; the game's log says what");
		}
	}

	private static void write(OutputStream out, Response response, boolean head, boolean keepAlive)
		throws IOException {
		StringBuilder header = new StringBuilder(256);
		header.append("HTTP/1.1 ").append(response.status()).append(' ').append(reason(response.status()))
			.append("\r\nContent-Type: ").append(response.type())
			.append("\r\nContent-Length: ").append(response.body().length)
			.append("\r\nCache-Control: no-store")
			.append("\r\nX-Content-Type-Options: nosniff")
			.append("\r\nReferrer-Policy: no-referrer")
			.append("\r\nConnection: ").append(keepAlive ? "keep-alive" : "close");
		if (response.gzipped()) {
			header.append("\r\nContent-Encoding: gzip");
		}
		response.headers().forEach((name, value) -> header.append("\r\n").append(name).append(": ").append(value));
		header.append("\r\n\r\n");
		out.write(header.toString().getBytes(StandardCharsets.ISO_8859_1));
		if (!head) {
			out.write(response.body());
		}
		out.flush();
	}

	private static String reason(int status) {
		return switch (status) {
			case 200 -> "OK";
			case 204 -> "No Content";
			case 400 -> "Bad Request";
			case 403 -> "Forbidden";
			case 404 -> "Not Found";
			case 405 -> "Method Not Allowed";
			case 413 -> "Content Too Large";
			case 503 -> "Service Unavailable";
			default -> status >= 500 ? "Internal Server Error" : "Status";
		};
	}

	/** A line up to CRLF (or LF), or {@code null} at the end of the stream. */
	private static @Nullable String readLine(PushbackInputStream in) throws IOException {
		StringBuilder line = new StringBuilder();
		while (true) {
			int b = in.read();
			if (b < 0) {
				return line.isEmpty() ? null : line.toString();
			}
			if (b == '\n') {
				int end = line.length();
				if (end > 0 && line.charAt(end - 1) == '\r') {
					line.setLength(end - 1);
				}
				return line.toString();
			}
			if (line.length() >= MAX_LINE) {
				throw new IOException("Request line too long");
			}
			line.append((char) b);
		}
	}

	private static void close(Socket connection) {
		try {
			connection.close();
		} catch (IOException ignored) {
			// Already gone.
		}
	}

	@Override
	public void close() {
		closed = true;
		try {
			socket.close();
		} catch (IOException ignored) {
			// Already closed.
		}
		acceptor.interrupt();
	}
}
