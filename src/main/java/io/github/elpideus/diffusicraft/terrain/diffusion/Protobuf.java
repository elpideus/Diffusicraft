package io.github.elpideus.diffusicraft.terrain.diffusion;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Just enough of the protobuf wire format to rewrite ONNX models, which are protobuf messages. ONNX Runtime's Java
 * API has no graph editing and the game ships no protobuf library, so {@link HalfPrecision} reads and writes the
 * fields itself. Fields are views into the source array; nothing is copied until a message is written.
 */
final class Protobuf {
	static final int VARINT = 0;
	static final int FIXED64 = 1;
	static final int LENGTH_DELIMITED = 2;
	static final int FIXED32 = 5;

	private Protobuf() {
	}

	/**
	 * One field of a message in {@code buffer}: its whole encoding is {@code [tagStart, end)}, its payload
	 * {@code [start, end)}. {@code value} holds a varint field's value.
	 */
	record Field(byte[] buffer, int number, int wireType, long value, int tagStart, int start, int end) {
		List<Field> message() {
			return parse(buffer, start, end);
		}

		String string() {
			return new String(buffer, start, end - start, StandardCharsets.UTF_8);
		}

		int length() {
			return end - start;
		}
	}

	static List<Field> parse(byte[] buffer) {
		return parse(buffer, 0, buffer.length);
	}

	static List<Field> parse(byte[] buffer, int from, int to) {
		List<Field> fields = new ArrayList<>();
		int[] p = {from};
		while (p[0] < to) {
			int tagStart = p[0];
			long key = varint(buffer, p);
			int number = (int) (key >>> 3), wireType = (int) (key & 7);
			switch (wireType) {
				case VARINT -> {
					long value = varint(buffer, p);
					fields.add(new Field(buffer, number, wireType, value, tagStart, p[0], p[0]));
				}
				case FIXED64, FIXED32 -> {
					int size = wireType == FIXED64 ? 8 : 4;
					fields.add(new Field(buffer, number, wireType, 0, tagStart, p[0], p[0] + size));
					p[0] += size;
				}
				case LENGTH_DELIMITED -> {
					long size = varint(buffer, p);
					if (size < 0 || size > to - p[0]) {
						throw new IllegalArgumentException("truncated field " + number);
					}
					fields.add(new Field(buffer, number, wireType, 0, tagStart, p[0], p[0] + (int) size));
					p[0] += (int) size;
				}
				default -> throw new IllegalArgumentException("unsupported wire type " + wireType);
			}
		}
		if (p[0] != to) {
			throw new IllegalArgumentException("truncated message");
		}
		return fields;
	}

	/** Reads a varint at {@code p[0]} and advances it. */
	static long varint(byte[] buffer, int[] p) {
		long result = 0;
		for (int shift = 0; shift < 64; shift += 7) {
			byte b = buffer[p[0]++];
			result |= (long) (b & 0x7f) << shift;
			if (b >= 0) {
				return result;
			}
		}
		throw new IllegalArgumentException("varint too long");
	}

	/** The varints of a repeated integer field, packed or not. */
	static List<Long> varints(List<Field> fields, int number) {
		List<Long> values = new ArrayList<>();
		for (Field f : fields) {
			if (f.number() != number) {
				continue;
			}
			if (f.wireType() == VARINT) {
				values.add(f.value());
			} else if (f.wireType() == LENGTH_DELIMITED) {
				int[] p = {f.start()};
				while (p[0] < f.end()) {
					values.add(varint(f.buffer(), p));
				}
			}
		}
		return values;
	}

	static Field first(List<Field> fields, int number) {
		for (Field f : fields) {
			if (f.number() == number) {
				return f;
			}
		}
		return null;
	}

	/** Builds one message. Fields are appended in call order. */
	static final class Writer {
		private final ByteArrayOutputStream out = new ByteArrayOutputStream();

		Writer varint(int number, long value) {
			tag(number, VARINT);
			raw(value);
			return this;
		}

		Writer bytes(int number, byte[] value) {
			return bytes(number, value, 0, value.length);
		}

		Writer bytes(int number, byte[] value, int from, int to) {
			tag(number, LENGTH_DELIMITED);
			raw(to - from);
			out.write(value, from, to - from);
			return this;
		}

		Writer string(int number, String value) {
			return bytes(number, value.getBytes(StandardCharsets.UTF_8));
		}

		Writer message(int number, Writer message) {
			return bytes(number, message.toByteArray());
		}

		/** Copies a parsed field unchanged. */
		Writer copy(Field field) {
			out.write(field.buffer(), field.tagStart(), field.end() - field.tagStart());
			return this;
		}

		byte[] toByteArray() {
			return out.toByteArray();
		}

		private void tag(int number, int wireType) {
			raw((long) number << 3 | wireType);
		}

		private void raw(long value) {
			while ((value & ~0x7fL) != 0) {
				out.write((int) (value & 0x7f) | 0x80);
				value >>>= 7;
			}
			out.write((int) value);
		}
	}
}
