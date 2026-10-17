# Performance

## Benchmarks

`WorldPipelineBenchmarkTest` samples three adjacent 512 × 512 regions at 30 m per pixel. Each region is 15.4 km ×
15.4 km (236 km²) of finished elevation and climate. The first region pays for the continent-scale coarse stage;
the next two reuse it, which is the steady state while a player explores.

| | CPU | NVIDIA CUDA |
|---|---|---|
| Hardware | Core i7-12700K, 20 threads | GeForce RTX 3060 12 GB, driver 615 |
| Model load (3 sessions) | 4.2 s | 4.3 s |
| First region | 30.6 s | 5.6 s |
| **Each further region** | **13.9 s** | **2.26 s** |
| Coarse run (up to 4 windows) | 130 ms | 34 ms |
| Base run (up to 4 windows) | 839 ms | 154 ms |
| Decoder run (up to 2 windows) | 1343 ms | 197 ms |

CUDA is about 6× faster than a fast desktop CPU. Both numbers are **pessimistic**: they were measured while a game
was running. The CPU run had the lowest priority (`nice 19`), and the GPU was about 90% busy rendering. Loading the
CUDA runtime (preloading 15 vendor libraries) took 254 ms.

**What this means in game.** At the 1:1 scale, one region is 15 km across. An elytra with rockets needs several minutes to
cross it. Even on the CPU, terrain is ready long before the player gets there, provided generation runs
ahead of the player. The coarse stage of a region covers 7.7 km per pixel and is shared by everything near it. At
smaller scales, a region covers proportionally fewer blocks, so the GPU matters more. Distant Horizons' far LODs need
whole regions quickly, and the GPU matters there too.

Reproduce with:

```bash
./gradlew test -PwithModels --tests '*WorldPipelineBenchmarkTest' -Pdiffusicraft.device=CUDA
```

Options:

- `-Pdiffusicraft.device=CPU` (or `ROCM`);
- `-Pdiffusicraft.benchSize=256`;
- `-Pdiffusicraft.gpuMemoryLimitMb=4096`.

The test uses the models and runtimes installed in the shared storage directory. Download them in game first, or
with `RuntimeInstallTest`; see [DEVELOPMENT.md](DEVELOPMENT.md#tests).

## Where the time goes

Inside ONNX Runtime: **99% on the CPU and 97% on CUDA** of the benchmark's wall time is spent in model runs. The
Java side covers scheduling, noise, Laplacian decoding, blending and climate correction, and takes the remaining
1–3%.

That decides the choice of language. Rewriting the Java glue in Rust or C++ could save at most 3%, far below the 2×
that would justify a second toolchain. The Java hot loops already work on flat `float[]`
arrays. Speed has to come from the model runs themselves.

## What the code does for speed

- **No copies of the weights.** Sessions are created from the model file path. ONNX Runtime reads the 2 GB base
  model itself, and it is never copied onto the Java heap first.
- **Batching.** Windows claimed together share one inference call. Coarse windows go in batches of 4, latent windows
  in batches of 4, decoder windows in pairs.
- **One run at a time, fully parallel inside.** Each run uses every CPU core (ONNX Runtime's intra-op threads) or the
  whole GPU. A semaphore stops concurrent runs from fighting over them. Pre- and post-processing of other windows
  continues on the world-generation threads meanwhile.
- **Compute once.** `WindowedTensor` deduplicates concurrent requests for the same window and keeps a per-stage
  in-memory cache. With a `WindowStore`, it also keeps a disk cache, so revisiting terrain or reloading a world costs
  a file read rather than inference.
- **CUDA tuning.**
  - The memory arena grows by exactly what is requested.
  - cuDNN's exhaustive convolution search runs once per window shape (the shapes never change).
  - TF32 tensor cores are used on Ampere and newer.
  - Copies use the default stream.
- **Sharing the GPU.** The CUDA arena is capped at 60% of video memory by default (`gpuMemoryLimitMb`). Rendering,
  Distant Horizons and shader packs keep the rest, and inference never starves the renderer of memory.
- **Fast startup.** The status shown in the UI is computed off the render thread from file metadata. Model files are
  hashed once, then trusted through `.verified` markers.

## Next steps

Ordered by expected gain:

1. **Generate ahead of the player.** Request regions in the direction of travel on a background executor. Combined
   with the disk cache, chunk generation then rarely waits for inference.
2. **FP16 / TensorRT.** Half-precision weights or the TensorRT provider would roughly double CUDA throughput on RTX
   cards. The output must first be checked against the FP32 reference, because the coarse stage is sensitive to
   precision.
3. **I/O binding and CUDA graphs.** Keep intermediate tensors on the GPU between the two latent steps and the
   decoder, and replay fixed-shape runs as graphs to cut launch overhead.
4. **Fewer, larger requests.** Each `sample` call pads its rectangle by the window overlap. Requests of 256 × 256 or
   more amortise that padding; the world generator should batch chunk requests by region.
