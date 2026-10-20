package io.github.elpideus.diffusicraft.terrain.diffusion;

import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import io.github.elpideus.diffusicraft.terrain.diffusion.Protobuf.Field;
import io.github.elpideus.diffusicraft.terrain.diffusion.Protobuf.Writer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Half-precision (fp16) copies of a model, for NVIDIA GPUs. Only the decoder gets one: it is 1.4 times faster in
 * fp16 and moves the terrain by 0.05 m RMS, less than the CPU and the GPU already differ. The base model's terrain
 * drifts by hundreds of metres in fp16, so it stays in fp32; see docs/PERFORMANCE.md.
 *
 * <p>The copy is made once, next to the model, in two steps:
 * <ol>
 *   <li>ONNX Runtime folds the constant parts of the graph, on the CPU in fp32. The networks normalise their weights
 *   on every run; folding does that once, at full precision, and leaves the normalised weights.</li>
 *   <li>{@link #convert} turns the folded model's float tensors into fp16. Reductions, powers and square roots, and
 *   the sines and cosines of the noise embedding stay in fp32 between casts. Inputs and outputs stay fp32, so the
 *   copy is a drop-in replacement.</li>
 * </ol>
 */
final class HalfPrecision {
	private static final Logger LOGGER = LoggerFactory.getLogger(HalfPrecision.class);
	/** Bump whenever the conversion changes, so that copies made by older versions are rebuilt. */
	static final int VERSION = 1;
	static final int FLOAT = 1;
	static final int INT64 = 7;
	static final int FLOAT16 = 10;
	/** Folded models above this would not fit in one protobuf message; the decoder is about 112 MB. */
	private static final long MAX_SOURCE_BYTES = 1L << 30;
	/** Ops that stay in fp32: they sum many values, or lose too much of their argument in fp16. */
	static final Set<String> FLOAT_OPS = Set.of("Sin", "Cos", "ReduceL2", "ReduceSum", "ReduceMean", "Pow", "Sqrt",
		"Softmax");
	/** Ops whose output has the element type of their first input. Any op not listed here stops the conversion. */
	private static final Set<String> SAME_TYPE = Set.of("Add", "Sub", "Mul", "Div", "Neg", "Sqrt", "Pow", "Sigmoid",
		"Clip", "Sin", "Cos", "Softmax", "ReduceL2", "ReduceSum", "ReduceMean", "Conv", "MatMul", "Einsum", "Gather",
		"Unsqueeze", "Squeeze", "Reshape", "Slice", "Tile", "Concat", "Transpose", "Split", "Identity");

	// ModelProto, GraphProto, NodeProto, AttributeProto, TensorProto and ValueInfoProto field numbers (onnx.proto3).
	private static final int MODEL_GRAPH = 7;
	private static final int GRAPH_NODE = 1, GRAPH_INITIALIZER = 5, GRAPH_INPUT = 11, GRAPH_OUTPUT = 12,
		GRAPH_VALUE_INFO = 13;
	private static final int NODE_INPUT = 1, NODE_OUTPUT = 2, NODE_NAME = 3, NODE_OP_TYPE = 4, NODE_ATTRIBUTE = 5;
	private static final int ATTRIBUTE_NAME = 1, ATTRIBUTE_I = 3, ATTRIBUTE_T = 5, ATTRIBUTE_TYPE = 20,
		ATTRIBUTE_TYPE_INT = 2;
	private static final int TENSOR_DIMS = 1, TENSOR_DATA_TYPE = 2, TENSOR_FLOAT_DATA = 4, TENSOR_NAME = 8,
		TENSOR_RAW_DATA = 9, TENSOR_EXTERNAL_DATA = 13, TENSOR_DATA_LOCATION = 14;
	private static final int VALUE_NAME = 1, VALUE_TYPE = 2, TYPE_TENSOR = 1, TENSOR_TYPE_ELEMENT = 1;
	private static final String PREFIX = "diffusicraft_fp16/";

	private HalfPrecision() {
	}

	/**
	 * The fp16 copy of {@code source}, made now if it is missing or was made by another {@link #VERSION}. The copy
	 * lives next to the source as {@code <name>.fp16.onnx}; a {@code .source} sidecar records what it was made from.
	 *
	 * @throws IOException  if the copy cannot be written
	 * @throws OrtException if ONNX Runtime cannot fold the model
	 * @throws IllegalArgumentException if the model has an op or a tensor the conversion does not handle
	 */
	static Path ensure(Path source) throws IOException, OrtException {
		String name = source.getFileName().toString();
		String stem = name.endsWith(".onnx") ? name.substring(0, name.length() - 5) : name;
		Path target = source.resolveSibling(stem + ".fp16.onnx");
		Path marker = target.resolveSibling(target.getFileName() + ".source");
		String expected = "v" + VERSION + " " + Files.size(source) + " " + Files.getLastModifiedTime(source).toMillis();
		if (Files.isRegularFile(target) && Files.isRegularFile(marker)
			&& Files.readString(marker, StandardCharsets.UTF_8).strip().equals(expected)) {
			return target;
		}
		if (Files.size(source) > MAX_SOURCE_BYTES) {
			throw new IllegalArgumentException(name + " is too large to convert");
		}
		long start = System.nanoTime();
		Path folded = Files.createTempFile(source.getParent(), stem, ".folded.tmp");
		Path temp = Files.createTempFile(source.getParent(), stem, ".fp16.tmp");
		try {
			fold(source, folded);
			Files.write(temp, convert(Files.readAllBytes(folded)));
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			Files.writeString(marker, expected, StandardCharsets.UTF_8);
		} finally {
			Files.deleteIfExists(folded);
			Files.deleteIfExists(temp);
		}
		LOGGER.info("Made a half-precision copy of {} in {} ms", name, (System.nanoTime() - start) / 1_000_000);
		return target;
	}

	/**
	 * Saves {@code source} with its constant subgraphs computed. Basic optimisations are the portable ones: they
	 * only fold and remove nodes, and the result runs on any provider.
	 */
	private static void fold(Path source, Path folded) throws OrtException {
		try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
			options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT);
			options.setOptimizedModelFilePath(folded.toAbsolutePath().toString());
			OnnxModel.environment().createSession(source.toAbsolutePath().toString(), options).close();
		}
	}

	/**
	 * Converts a model's float tensors and ops to fp16, keeping {@link #FLOAT_OPS} and the graph's inputs and outputs
	 * in fp32. Fold the model first: weights normalised in the graph would be normalised in fp16.
	 *
	 * @throws IllegalArgumentException if the model has an op outside the known ones, external data, or a weight
	 *                                  beyond fp16's range
	 */
	static byte[] convert(byte[] model) {
		List<Field> modelFields = Protobuf.parse(model);
		Writer out = new Writer();
		for (Field f : modelFields) {
			if (f.number() == MODEL_GRAPH) {
				out.bytes(MODEL_GRAPH, convertGraph(f.message()));
			} else {
				out.copy(f);
			}
		}
		return out.toByteArray();
	}

	private static byte[] convertGraph(List<Field> graph) {
		Map<String, Integer> types = new HashMap<>();
		List<byte[]> initializers = new ArrayList<>();
		List<List<Field>> nodes = new ArrayList<>();
		List<Field> inputs = new ArrayList<>(), outputs = new ArrayList<>(), rest = new ArrayList<>();
		for (Field f : graph) {
			switch (f.number()) {
				case GRAPH_NODE -> nodes.add(f.message());
				case GRAPH_INITIALIZER -> {
					List<Field> tensor = f.message();
					Field nameField = Protobuf.first(tensor, TENSOR_NAME);
					int type = elementType(tensor);
					byte[] half = type == FLOAT ? toHalf(tensor) : null;
					initializers.add(half != null ? half : copyOf(f));
					types.put(nameField == null ? "" : nameField.string(), half != null ? FLOAT16 : type);
				}
				case GRAPH_INPUT -> inputs.add(f);
				case GRAPH_OUTPUT -> outputs.add(f);
				// Intermediate types change; ONNX Runtime infers them again.
				case GRAPH_VALUE_INFO -> {
				}
				default -> rest.add(f);
			}
		}

		List<Writer> converted = new ArrayList<>();
		Map<String, String> rename = new HashMap<>();
		for (Field input : inputs) {
			String name = valueName(input);
			int type = valueType(input);
			if (types.containsKey(name)) {
				// Old models list their initializers as inputs too; ONNX Runtime saves without them.
				throw new IllegalArgumentException("input " + name + " is also an initializer");
			}
			if (type == FLOAT) {
				String half = PREFIX + name;
				converted.add(cast(name, half, FLOAT16, PREFIX + "cast_in/" + name));
				rename.put(name, half);
				types.put(half, FLOAT16);
			} else {
				types.put(name, type);
			}
		}
		List<Writer> outputCasts = new ArrayList<>();
		for (Field output : outputs) {
			String name = valueName(output);
			if (valueType(output) == FLOAT) {
				String half = PREFIX + name;
				rename.put(name, half);
				outputCasts.add(cast(half, name, FLOAT, PREFIX + "cast_out/" + name));
			}
		}

		for (int k = 0; k < nodes.size(); k++) {
			convertNode(k, nodes.get(k), rename, types, converted);
		}
		converted.addAll(outputCasts);

		Writer out = new Writer();
		for (Writer node : converted) {
			out.message(GRAPH_NODE, node);
		}
		for (byte[] initializer : initializers) {
			out.bytes(GRAPH_INITIALIZER, initializer);
		}
		for (Field f : inputs) {
			out.copy(f);
		}
		for (Field f : outputs) {
			out.copy(f);
		}
		for (Field f : rest) {
			out.copy(f);
		}
		return out.toByteArray();
	}

	private static void convertNode(int index, List<Field> node, Map<String, String> rename, Map<String, Integer> types,
									List<Writer> out) {
		String op = Protobuf.first(node, NODE_OP_TYPE).string();
		List<String> inputs = new ArrayList<>(), outputs = new ArrayList<>();
		List<byte[]> attributes = new ArrayList<>();
		List<Field> rest = new ArrayList<>();
		for (Field f : node) {
			switch (f.number()) {
				case NODE_INPUT -> inputs.add(rename.getOrDefault(f.string(), f.string()));
				case NODE_OUTPUT -> outputs.add(rename.getOrDefault(f.string(), f.string()));
				case NODE_ATTRIBUTE -> attributes.add(copyOf(f));
				default -> rest.add(f);
			}
		}

		Integer outputType;
		switch (op) {
			case "Cast" -> {
				outputType = null;
				for (int a = 0; a < attributes.size(); a++) {
					List<Field> attribute = Protobuf.parse(attributes.get(a));
					if (Protobuf.first(attribute, ATTRIBUTE_NAME).string().equals("to")) {
						int to = (int) Protobuf.first(attribute, ATTRIBUTE_I).value();
						if (to == FLOAT) {
							to = FLOAT16;
							attributes.set(a, intAttribute("to", FLOAT16).toByteArray());
						}
						outputType = to;
					}
				}
			}
			case "Shape" -> outputType = INT64;
			case "Constant", "ConstantOfShape" -> {
				// ConstantOfShape without a value makes fp32 zeros, and Constant has forms other than a tensor.
				outputType = null;
				for (int a = 0; a < attributes.size(); a++) {
					List<Field> attribute = Protobuf.parse(attributes.get(a));
					Field value = Protobuf.first(attribute, ATTRIBUTE_T);
					if (value != null && Protobuf.first(attribute, ATTRIBUTE_NAME).string().equals("value")) {
						List<Field> tensor = value.message();
						outputType = elementType(tensor);
						if (outputType == FLOAT) {
							Writer w = new Writer();
							for (Field f : attribute) {
								if (f.number() == ATTRIBUTE_T) {
									w.bytes(ATTRIBUTE_T, toHalf(tensor));
								} else {
									w.copy(f);
								}
							}
							attributes.set(a, w.toByteArray());
							outputType = FLOAT16;
						}
					}
				}
			}
			default -> {
				if (!SAME_TYPE.contains(op)) {
					throw new IllegalArgumentException("unsupported op " + op);
				}
				outputType = inputs.isEmpty() ? null : types.get(inputs.getFirst());
			}
		}
		if (outputType == null) {
			throw new IllegalArgumentException("cannot tell the output type of " + op + " node " + index);
		}

		List<Writer> after = new ArrayList<>();
		if (FLOAT_OPS.contains(op) && outputType == FLOAT16) {
			for (int i = 0; i < inputs.size(); i++) {
				String input = inputs.get(i);
				if (!input.isEmpty() && types.getOrDefault(input, 0) == FLOAT16) {
					String full = PREFIX + "fp32/" + index + "/" + i;
					out.add(cast(input, full, FLOAT, PREFIX + "cast_fp32/" + index + "/" + i));
					inputs.set(i, full);
				}
			}
			for (int i = 0; i < outputs.size(); i++) {
				String output = outputs.get(i);
				String full = PREFIX + "fp32_out/" + index + "/" + i;
				after.add(cast(full, output, FLOAT16, PREFIX + "cast_fp16/" + index + "/" + i));
				outputs.set(i, full);
			}
		}

		Writer w = new Writer();
		for (String input : inputs) {
			w.string(NODE_INPUT, input);
		}
		for (String output : outputs) {
			w.string(NODE_OUTPUT, output);
		}
		for (Field f : rest) {
			w.copy(f);
		}
		for (byte[] attribute : attributes) {
			w.bytes(NODE_ATTRIBUTE, attribute);
		}
		out.add(w);
		out.addAll(after);
		for (String output : node.stream().filter(f -> f.number() == NODE_OUTPUT).map(Field::string).toList()) {
			types.put(rename.getOrDefault(output, output), outputType);
		}
	}

	/** A float tensor rewritten as fp16 raw data. */
	private static byte[] toHalf(List<Field> tensor) {
		if (Protobuf.first(tensor, TENSOR_EXTERNAL_DATA) != null) {
			throw new IllegalArgumentException("tensors with external data are not supported");
		}
		float[] values = floats(tensor);
		ByteBuffer half = ByteBuffer.allocate(values.length * 2).order(ByteOrder.LITTLE_ENDIAN);
		for (float v : values) {
			if (Math.abs(v) > 65504f) {
				throw new IllegalArgumentException("a weight of " + v + " is beyond fp16's range");
			}
			half.putShort(Float.floatToFloat16(v));
		}
		Writer w = new Writer();
		for (Field f : tensor) {
			switch (f.number()) {
				case TENSOR_DATA_TYPE, TENSOR_FLOAT_DATA, TENSOR_RAW_DATA, TENSOR_DATA_LOCATION -> {
				}
				default -> w.copy(f);
			}
		}
		w.varint(TENSOR_DATA_TYPE, FLOAT16);
		w.bytes(TENSOR_RAW_DATA, half.array());
		return w.toByteArray();
	}

	static float[] floats(List<Field> tensor) {
		Field raw = Protobuf.first(tensor, TENSOR_RAW_DATA);
		if (raw != null) {
			ByteBuffer b = ByteBuffer.wrap(raw.buffer(), raw.start(), raw.length()).order(ByteOrder.LITTLE_ENDIAN);
			float[] values = new float[raw.length() / 4];
			b.asFloatBuffer().get(values);
			return values;
		}
		List<Float> values = new ArrayList<>();
		for (Field f : tensor) {
			if (f.number() != TENSOR_FLOAT_DATA) {
				continue;
			}
			ByteBuffer b = ByteBuffer.wrap(f.buffer(), f.start(), f.length()).order(ByteOrder.LITTLE_ENDIAN);
			while (b.hasRemaining()) {
				values.add(b.getFloat());
			}
		}
		float[] out = new float[values.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = values.get(i);
		}
		return out;
	}

	static int elementType(List<Field> tensor) {
		Field type = Protobuf.first(tensor, TENSOR_DATA_TYPE);
		return type == null ? 0 : (int) type.value();
	}

	private static String valueName(Field value) {
		return Protobuf.first(value.message(), VALUE_NAME).string();
	}

	private static int valueType(Field value) {
		Field type = Protobuf.first(value.message(), VALUE_TYPE);
		Field tensor = type == null ? null : Protobuf.first(type.message(), TYPE_TENSOR);
		Field element = tensor == null ? null : Protobuf.first(tensor.message(), TENSOR_TYPE_ELEMENT);
		return element == null ? 0 : (int) element.value();
	}

	private static Writer cast(String input, String output, int to, String name) {
		return new Writer().string(NODE_INPUT, input).string(NODE_OUTPUT, output).string(NODE_NAME, name)
			.string(NODE_OP_TYPE, "Cast").message(NODE_ATTRIBUTE, intAttribute("to", to));
	}

	private static Writer intAttribute(String name, long value) {
		return new Writer().string(ATTRIBUTE_NAME, name).varint(ATTRIBUTE_I, value)
			.varint(ATTRIBUTE_TYPE, ATTRIBUTE_TYPE_INT);
	}

	private static byte[] copyOf(Field f) {
		byte[] copy = new byte[f.length()];
		System.arraycopy(f.buffer(), f.start(), copy, 0, copy.length);
		return copy;
	}
}
