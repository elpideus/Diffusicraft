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
cross it. Even on the CPU, terrain is ready long before the player gets there, because it is
[generated ahead](#generating-ahead) of the player. The coarse stage of a region covers 7.7 km per pixel and is shared by everything near it. At
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

## Generating ahead

A tile of terrain takes seconds of inference, and every chunk that needs it waits until it is done. Without help, the
world stops appearing at a tile's edge while the player flies on. `TerrainPrefetcher` computes tiles before chunk
generation asks for them:

- **Where the players are heading.** Twice a second, `PlayerPrefetch` tells it where each player in a Diffusicraft
  world is and how fast they move, averaged over the last second or so. It plans the tiles that the player's view
  will reach along their path in the next 20 seconds, the earliest first. The view is the view distance the client
  asked for (within the server's) plus 64 blocks for the chunks generated beyond it. Speeds above 100 blocks per
  second count as 100, and a move of more than 200 blocks between two plans is a teleport, which says nothing of
  where the player goes next.
- **The tiles around.** The rivers and lakes of a tile read the terrain of the eight tiles around it, for the
  [lakes that reach past its sides](WORLDGEN.md#rivers), so the plan takes in a ring of tiles around the view. A new
  world therefore waits for three by three tiles at least before its first chunk, where one tile could do before.
  Nine tiles take some 45 s on the CPU at 5 s a tile, and about 5 s on the GPU. The lakes themselves cost little:
  a block of four tiles takes about 27 ms on one core, and there is one block per tile. A tile in or next to a lake
  of the coarse map also waits for all the lake's tiles, up to 16, which the plan does not take in yet.
- **In the background.** One thread at the lowest priority computes the planned tiles one at a time, and switches to
  a newer plan as soon as one arrives. Model runs take turns, so a tile that chunk generation needs right away takes
  up to twice as long while the prefetcher works. Planning the earliest tiles first keeps that rare.
- **Out of the way of the cache.** A plan covers at most a quarter of a field's 96 tiles, so prefetching never evicts
  the tiles in use. A tile that chunk generation reaches first is computed there, as before, and the prefetcher
  skips it.
- **Quiet failures.** If inference fails, for example because the models are missing, the prefetcher leaves that
  world alone for a minute. Chunk generation reports the error as before.
- **An off switch.** [`generateAhead`](CONFIG.md#generateahead) in the config.

How much it has to do depends on the scale. A tile is 512 blocks across at the standard scale, 1536 at Grand and 7680
at Earth 1:1. An elytra with rockets (about 33 blocks per second) crosses a standard tile in 15.5 s. A view of 12
chunks spans two or three rows of tiles, so each column the player flies into costs two or three tiles of inference.
`TerrainField.tileWaitNanos()` counts the time chunk generation spent waiting for tiles.

`TerrainPrefetchBenchmarkTest` flies 1536 blocks east over fresh terrain at 33 blocks per second, in real time, with
a view of 12 chunks that spans three rows of tiles. It starts from a spawn area that is already generated. On the CPU
of the benchmark above (`nice 19`, about 5 s per tile, while a game was running):

| | On demand | Generating ahead |
|---|---|---|
| Time chunk generation waited | 29.0 s of 47 s (62%) | 8.7 s of 47 s (19%) |
| Longest stall | 16.3 s | 8.7 s |
| Tiles computed ahead | 0 of 9 | 10 of 11 |

On the CPU, an elytra flight at the standard scale needs a little more inference than the CPU delivers: each column
of three tiles costs about 15 s, and the player crosses it in 15.5 s. Prefetching cuts the waits by more than two
thirds, but it cannot remove them; the one stall left comes right after takeoff, before the first column ahead is
done. The GPU computes a tile in under a second, and the larger scales have larger tiles, so there the prefetcher
keeps well ahead. Reproduce with:

```bash
./gradlew test -PwithModels --tests '*TerrainPrefetchBenchmarkTest' -Pdiffusicraft.device=CPU
```

Options: `-Pdiffusicraft.flightTiles=3`, `-Pdiffusicraft.flightSpeed=33` and `-Pdiffusicraft.viewDistance=12`.

## Next steps

Ordered by expected gain:

1. **FP16 / TensorRT.** Half-precision weights or the TensorRT provider would roughly double CUDA throughput on RTX
   cards. The output must first be checked against the FP32 reference, because the coarse stage is sensitive to
   precision.
2. **I/O binding and CUDA graphs.** Keep intermediate tensors on the GPU between the two latent steps and the
   decoder, and replay fixed-shape runs as graphs to cut launch overhead.
3. **Fewer, larger requests.** Each `sample` call pads its rectangle by the window overlap. Requests of 256 × 256 or
   more amortise that padding; the world generator should batch chunk requests by region.
