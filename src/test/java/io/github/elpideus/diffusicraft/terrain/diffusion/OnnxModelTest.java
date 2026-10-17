package io.github.elpideus.diffusicraft.terrain.diffusion;

import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtProvider;
import ai.onnxruntime.OrtSession;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnnxModelTest {
	private static final EnumSet<OrtProvider> ALL = EnumSet.allOf(OrtProvider.class);

	@Test
	void requestedDeviceComesFirstAndCpuLast() {
		assertEquals(List.of("CPU"), OnnxModel.candidates(OnnxModel.Device.CPU, ALL));
		assertEquals("CUDA", OnnxModel.candidates(OnnxModel.Device.CUDA, ALL).getFirst());
		assertEquals(List.of("MI_GRAPH_X", "ROCM", "CUDA"), OnnxModel.candidates(OnnxModel.Device.ROCM, ALL).subList(0, 3));
		assertEquals("CPU", OnnxModel.candidates(OnnxModel.Device.AUTO, ALL).getLast());
	}

	@Test
	void onlyProvidersInTheLoadedLibraryAreTried() {
		EnumSet<OrtProvider> cpuBuild = EnumSet.of(OrtProvider.CPU);
		assertEquals(List.of("CPU"), OnnxModel.candidates(OnnxModel.Device.ROCM, cpuBuild));
		assertEquals(List.of("MI_GRAPH_X", "CPU"), OnnxModel.candidates(OnnxModel.Device.AUTO, EnumSet.of(OrtProvider.CPU, OrtProvider.MI_GRAPH_X)));
	}

	/** The generic append is private in the Java API; this checks the reflective call still reaches native code. */
	@Test
	void addByNameReachesOnnxRuntime() throws OrtException {
		OnnxModel.environment();
		try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
			// The bundled CPU build knows the name but has no MIGraphX library next to it.
			OrtException e = assertThrows(OrtException.class, () -> OnnxModel.addByName(options, "MIGraphX", Map.of("device_id", "0")));
			assertTrue(!e.getMessage().contains("cannot add"), e::getMessage);
		}
	}
}
