package io.github.elpideus.diffusicraft.terrain.diffusion;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import io.github.elpideus.diffusicraft.terrain.diffusion.Protobuf.Field;
import io.github.elpideus.diffusicraft.terrain.diffusion.Protobuf.Writer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HalfPrecisionTest {
	private static final long[] SHAPE = {1, 2, 4, 4};

	@Test
	void weightsBecomeHalfWhileTheEdgesStayFloat() {
		List<Field> graph = graph(HalfPrecision.convert(model("Sin")));
		Map<String, Integer> initializers = new HashMap<>();
		for (Field f : graph) {
			if (f.number() == 5) {
				List<Field> tensor = f.message();
				initializers.put(Protobuf.first(tensor, 8).string(), HalfPrecision.elementType(tensor));
			}
		}
		assertEquals(HalfPrecision.FLOAT16, initializers.get("w_raw"));
		assertEquals(HalfPrecision.FLOAT16, initializers.get("half"));
		for (Field f : graph) {
			if (f.number() == 11 || f.number() == 12) {
				assertEquals(HalfPrecision.FLOAT, elementType(f), "graph inputs and outputs stay fp32");
			}
		}

		Map<String, String> producers = new HashMap<>();
		List<List<Field>> nodes = new ArrayList<>();
		for (Field f : graph) {
			if (f.number() == 1) {
				List<Field> node = f.message();
				nodes.add(node);
				for (Field out : node) {
					if (out.number() == 2) {
						producers.put(out.string(), Protobuf.first(node, 4).string());
					}
				}
			}
		}
		for (List<Field> node : nodes) {
			String op = Protobuf.first(node, 4).string();
			if (HalfPrecision.FLOAT_OPS.contains(op)) {
				String input = Protobuf.first(node, 1).string();
				assertEquals("Cast", producers.get(input), op + " reads an fp32 copy of its input");
			}
			if (op.equals("Cast") && Protobuf.first(node, 1).string().equals("mul")) {
				List<Field> to = Protobuf.first(node, 5).message();
				assertEquals(HalfPrecision.FLOAT16, Protobuf.first(to, 3).value(), "casts to fp32 now cast to fp16");
			}
		}
	}

	@Test
	void convertedModelComputesNearlyTheSame() throws Exception {
		byte[] model = model("Sin");
		float[] full = run(model);
		float[] half = run(HalfPrecision.convert(model));
		assertEquals(full.length, half.length);
		for (int i = 0; i < full.length; i++) {
			assertEquals(full[i], half[i], 0.02f, "element " + i);
		}
	}

	@Test
	void unknownOpsStopTheConversion() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
			() -> HalfPrecision.convert(model("Erf")));
		assertTrue(e.getMessage().contains("Erf"), e::getMessage);
	}

	@Test
	void copyIsFoldedMadeOnceAndRemadeForANewVersion(@TempDir Path dir) throws Exception {
		Path source = dir.resolve("decoder_model.onnx");
		Files.write(source, model("Sin"));
		Path copy = HalfPrecision.ensure(source);
		assertEquals("decoder_model.fp16.onnx", copy.getFileName().toString());
		for (Field f : graph(Files.readAllBytes(copy))) {
			if (f.number() == 1) {
				assertFalse(Protobuf.first(f.message(), 4).string().equals("ReduceL2"),
					"the weight normalisation is folded away");
			}
		}
		float[] full = run(Files.readAllBytes(source));
		float[] half = run(Files.readAllBytes(copy));
		for (int i = 0; i < full.length; i++) {
			assertEquals(full[i], half[i], 0.02f, "element " + i);
		}
		try (var listing = Files.list(dir)) {
			assertEquals(3, listing.count(), "only the source, the copy and its marker are left");
		}

		Files.writeString(copy, "stale");
		assertEquals(copy, HalfPrecision.ensure(source));
		assertEquals("stale", Files.readString(copy), "a copy with a matching marker is reused");
		Path marker = dir.resolve("decoder_model.fp16.onnx.source");
		Files.writeString(marker, "v0 1 1");
		HalfPrecision.ensure(source);
		assertTrue(Files.size(copy) > 100, "a copy with another marker is made again");
	}

	private static float[] run(byte[] model) throws Exception {
		OrtEnvironment env = OnnxModel.environment();
		float[] x = new float[32];
		Random random = new Random(3);
		for (int i = 0; i < x.length; i++) {
			x[i] = (float) random.nextGaussian();
		}
		try (OrtSession.SessionOptions options = new OrtSession.SessionOptions();
			 OrtSession session = env.createSession(model, options);
			 OnnxTensor input = OnnxTensor.createTensor(env, FloatBuffer.wrap(x), SHAPE);
			 OrtSession.Result result = session.run(Map.of("x", input))) {
			FloatBuffer out = ((OnnxTensor) result.get(0)).getFloatBuffer();
			float[] values = new float[out.remaining()];
			out.get(values);
			return values;
		}
	}

	private static List<Field> graph(byte[] model) {
		return Protobuf.first(Protobuf.parse(model), 7).message();
	}

	private static int elementType(Field value) {
		List<Field> type = Protobuf.first(value.message(), 2).message();
		return (int) Protobuf.first(Protobuf.first(type, 1).message(), 1).value();
	}

	/**
	 * A small graph with the decoder's kinds of ops: weights normalised in the graph, a convolution, a cast to fp32,
	 * a root mean square, a constant filled to a shape, and {@code lastOp} on the result.
	 */
	static byte[] model(String lastOp) {
		Random random = new Random(7);
		float[] weights = new float[2 * 2 * 3 * 3];
		for (int i = 0; i < weights.length; i++) {
			weights[i] = (float) random.nextGaussian();
		}
		Writer graph = new Writer().string(2, "test")
			.message(1, node("ReduceL2", List.of("w_raw"), "w_norm", ints("axes", 1, 2, 3), intAttribute("keepdims", 1)))
			.message(1, node("Div", List.of("w_raw", "w_norm"), "w"))
			.message(1, node("Conv", List.of("x", "w", "b"), "conv", ints("pads", 1, 1, 1, 1)))
			.message(1, node("Mul", List.of("conv", "half"), "mul"))
			.message(1, node("Cast", List.of("mul"), "mul_fp32", intAttribute("to", HalfPrecision.FLOAT)))
			.message(1, node("Pow", List.of("mul_fp32", "two"), "square"))
			.message(1, node("ReduceMean", List.of("square"), "mean", ints("axes", 1), intAttribute("keepdims", 1)))
			.message(1, node("Sqrt", List.of("mean"), "rms"))
			.message(1, node("Add", List.of("mul", "rms"), "sum"))
			.message(1, node("Shape", List.of("x"), "shape"))
			.message(1, node("ConstantOfShape", List.of("shape"), "ones", new Writer().string(1, "value")
				.message(5, tensor("", new long[]{1}, 1f)).varint(20, 4)))
			.message(1, node("Add", List.of("sum", "ones"), "shifted"))
			.message(1, node(lastOp, List.of("shifted"), "y"))
			.message(5, tensor("w_raw", new long[]{2, 2, 3, 3}, weights))
			.message(5, tensor("b", new long[]{2}, 0.1f, -0.2f))
			.message(5, tensor("half", new long[0], 0.5f))
			.message(5, tensor("two", new long[0], 2f))
			.message(11, value("x"))
			.message(12, value("y"));
		return new Writer().varint(1, 8).string(2, "diffusicraft-test")
			.message(8, new Writer().string(1, "").varint(2, 17))
			.message(7, graph).toByteArray();
	}

	private static Writer node(String op, List<String> inputs, String output, Writer... attributes) {
		Writer w = new Writer();
		for (String input : inputs) {
			w.string(1, input);
		}
		w.string(2, output).string(3, op + "_" + output).string(4, op);
		for (Writer attribute : attributes) {
			w.message(5, attribute);
		}
		return w;
	}

	private static Writer intAttribute(String name, long value) {
		return new Writer().string(1, name).varint(3, value).varint(20, 2);
	}

	private static Writer ints(String name, long... values) {
		Writer w = new Writer().string(1, name);
		for (long v : values) {
			w.varint(8, v);
		}
		return w.varint(20, 7);
	}

	private static Writer tensor(String name, long[] dims, float... values) {
		ByteBuffer raw = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
		for (float v : values) {
			raw.putFloat(v);
		}
		Writer w = new Writer();
		for (long d : dims) {
			w.varint(1, d);
		}
		w.varint(2, HalfPrecision.FLOAT);
		if (!name.isEmpty()) {
			w.string(8, name);
		}
		return w.bytes(9, raw.array());
	}

	private static Writer value(String name) {
		Writer shape = new Writer();
		for (long d : SHAPE) {
			shape.message(1, new Writer().varint(1, d));
		}
		Writer tensorType = new Writer().varint(1, HalfPrecision.FLOAT).message(2, shape);
		return new Writer().string(1, name).message(2, new Writer().message(1, tensorType));
	}
}
