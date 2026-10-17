package io.github.elpideus.diffusicraft.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CudaSupportTest {
	private static Hardware.Gpu nvidia(String driver, int computeCapability) {
		return new Hardware.Gpu(Hardware.Vendor.NVIDIA, "test", 8L << 30, driver, computeCapability);
	}

	@Test
	void parsesComputeCapability() {
		assertEquals(86, Hardware.parseComputeCapability("8.6"));
		assertEquals(120, Hardware.parseComputeCapability("12.0"));
		assertEquals(0, Hardware.parseComputeCapability("[N/A]"));
		assertEquals(0, Hardware.parseComputeCapability(""));
	}

	@Test
	void cuda13NeedsTuringAndDriver580() {
		assertEquals(Resources.CudaSupport.SUPPORTED, Resources.cudaSupport(nvidia("580.65.06", 75)));
		assertEquals(Resources.CudaSupport.GPU_TOO_OLD, Resources.cudaSupport(nvidia("580.65.06", 61)));
		assertEquals(Resources.CudaSupport.DRIVER_TOO_OLD, Resources.cudaSupport(nvidia("570.10", 89)));
		assertEquals(Resources.CudaSupport.NOT_NVIDIA, Resources.cudaSupport(new Hardware.Gpu(Hardware.Vendor.AMD, "test", 0, "", 0)));
	}

	@Test
	void unknownValuesCountAsSupported() {
		assertEquals(Resources.CudaSupport.SUPPORTED, Resources.cudaSupport(nvidia("", 0)));
	}
}
