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
CUDA runtime (preloading 15 vendor libraries) took 254 ms. The table predates the
[half-precision decoder](#half-precision-decoder), which makes each further region on CUDA 1.2× faster.

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
- `-Pdiffusicraft.gpuMemoryLimitMb=4096`;
- `-Pdiffusicraft.halfPrecision=false`, to run the decoder in fp32 on CUDA;
- `-Pdiffusicraft.providerOptions=prefer_nhwc=1,...`, to try execution provider options before making them defaults;
- `-Pdiffusicraft.benchReference=/path/to/file`. The first run saves the regions' elevations there; later runs print
  how far theirs are from them. Save a reference with the settings you trust, then check a change against it.

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
  - The decoder runs in [half precision](#half-precision-decoder).
- **Sharing the GPU.** The CUDA arena is capped at 60% of video memory by default (`gpuMemoryLimitMb`). Rendering,
  Distant Horizons and shader packs keep the rest, and inference never starves the renderer of memory.
- **Fast startup.** The status shown in the UI is computed off the render thread from file metadata. Model files are
  hashed once, then trusted through `.verified` markers.

## Half-precision decoder

On CUDA, the decoder runs in half precision (fp16). The first time the models load on CUDA, `HalfPrecision` makes an
fp16 copy of the decoder next to it, `decoder_model.fp16.onnx` (56 MB), in under a second:

1. ONNX Runtime folds the constant parts of the graph, on the CPU and in fp32. The networks normalise their weights
   on every run; once folded, the weights are normalised once, at full precision. Folding alone changes nothing:
   the output is identical to the bit, and the folded weights take half the space.
2. The folded model's float tensors and ops become fp16. Reductions, powers, square roots and the sines and cosines
   of the noise embedding stay in fp32 between casts, and so do the inputs and outputs. ONNX Runtime's Java API cannot
   edit graphs and the game ships no protobuf library, so `Protobuf` reads and writes the model's fields itself.

A sidecar file records the conversion's version and the source file it was made from. If either changes, the copy
is made again. If anything fails, the decoder runs in fp32 as before. Measured on an idle RTX 3060, against fp32 on
the same card:

| | fp32 | fp16 decoder |
|---|---|---|
| Each further region | 1.57 s | 1.30 s |
| Decoder run (2 windows) | 133 ms | 93 ms |
| Elevation difference, RMS | | 0.05 m |
| Largest difference | | 0.36 m |

The CPU and CUDA already differ by 0.1 to 0.4 m RMS (up to 1.0 m), because CUDA multiplies with TF32 tensor cores.
The fp16 decoder stays well within that. It can be turned off with [`halfPrecision`](CONFIG.md#halfprecision).

The other two networks stay in fp32:

- **The base model** moves the terrain by 200 to 390 m RMS in fp16: the land itself changes, not just its detail.
  Running only its convolutions and matrix products in fp16, with everything else in fp32, keeps it within 2 m, but
  saves only 8% of its time. The casts cost almost as much as the half-precision maths saves.
- **The coarse model** takes 3% of the time and moves the terrain by 2 to 4 m in fp16.

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
- **The tiles around.** The rivers and lakes of a tile read the terrain of the two rings of tiles around it, 24 tiles,
  for the [lakes that reach past its sides](WORLDGEN.md#rivers), so the plan takes in two rings of tiles around the
  view. A new world therefore waits for five by five tiles at least before its first chunk. Those 25 tiles take some
  two minutes on the CPU at 5 s a tile, and about 14 s on the GPU; with blocks of two by two tiles alone, nine tiles
  took 45 s and 5 s. The lakes themselves cost little: a block of four tiles takes about 30 ms on one core and a block
  of nine about 65 ms, and there is one block of each size per tile. Where the rain would fill one of its basins, the
  block draws the rivers of the tiles the water reaches, some 12 ms a tile with rivers, to tell whether one drains it;
  that adds 2 to 4 ms a tile on average. A tile in or next to a lake of the coarse map also waits for all the lake's
  tiles, up to 64, which the prefetcher computes right after the lake's first tile in the plan, then the lake's water
  from them. The largest lakes take 23 to 27 s on the GPU, all but a second of it for their tiles, and some five minutes
  on the CPU.
- **In the background.** One thread at the lowest priority computes the planned tiles one at a time, and switches to
  a newer plan as soon as one arrives. Model runs take turns, so a tile that chunk generation needs right away takes
  up to twice as long while the prefetcher works. Planning the earliest tiles first keeps that rare.
- **Out of the way of the cache.** A plan covers at most a quarter of a field's 160 tiles, so the plan never evicts the
  tiles in use. The tiles of a lake come on top, up to 64, and may push some of those out; the lake needs its own only
  until it has found its water. A tile that chunk generation reaches first is computed there, as before, and the
  prefetcher skips it.
- **Quiet failures.** If inference fails, for example because the models are missing, the prefetcher leaves that
  world alone for a minute. Chunk generation reports the error as before.
- **An off switch.** [`generateAhead`](CONFIG.md#generateahead) in the config.

How much it has to do depends on the size. A tile is 240 blocks across at Vanilla, and twice as wide at each size up
to 7680 at Ultra. An elytra with rockets (about 33 blocks per second) crosses a tile of 512 blocks in 15.5 s. A view of 12
chunks spans two or three rows of tiles, so each column the player flies into costs two or three tiles of inference.
`TerrainField.tileWaitNanos()` counts the time chunk generation spent waiting for tiles.

`TerrainPrefetchBenchmarkTest` flies 1536 blocks east over fresh terrain at 33 blocks per second, in real time, with
a view of 12 chunks that spans three rows of tiles. It starts from a spawn area that is already generated. It flies at
Medium now; these numbers were measured before the sizes, in the Standard world, whose tiles were 512 blocks across.
On the CPU of the benchmark above (`nice 19`, about 5 s per tile, while a game was running):

| | On demand | Generating ahead |
|---|---|---|
| Time chunk generation waited | 29.0 s of 47 s (62%) | 8.7 s of 47 s (19%) |
| Longest stall | 16.3 s | 8.7 s |
| Tiles computed ahead | 0 of 9 | 10 of 11 |

On the CPU, an elytra flight over tiles of 512 blocks needs a little more inference than the CPU delivers: each column
of three tiles costs about 15 s, and the player crosses it in 15.5 s. Prefetching cuts the waits by more than two
thirds, but it cannot remove them; the one stall left comes right after takeoff, before the first column ahead is
done. The GPU computes a tile in under a second, and the larger sizes have larger tiles, so there the prefetcher
keeps well ahead. Reproduce with:

```bash
./gradlew test -PwithModels --tests '*TerrainPrefetchBenchmarkTest' -Pdiffusicraft.device=CPU
```

Options: `-Pdiffusicraft.flightTiles=3`, `-Pdiffusicraft.flightSpeed=33` and `-Pdiffusicraft.viewDistance=12`.

## Next steps

The [half-precision decoder](#half-precision-decoder) is done; the base model stays in fp32, as explained there.
What is left, and why it waits:

- **TensorRT.** ONNX Runtime's GPU package contains the TensorRT provider, but the provider needs TensorRT 10's own
  libraries. They are a 3.5 GB download on Linux and 1.8 GB on Windows, nearly doubling what an NVIDIA player
  downloads, and TensorRT spends minutes building an engine for every window shape the first time.
- **I/O binding and CUDA graphs.** Keeping tensors on the GPU between runs, and replaying fixed-shape runs as graphs,
  needs ONNX Runtime's I/O binding and device tensors. Its Java API (1.31) has neither, so this would take JNI code of
  our own.

Already done or tried:

- **Larger requests.** `TerrainField` asks the pipeline for tiles of 256 × 256 pixels with a 32-pixel margin, never
  for single chunks, and windows shared by neighbouring tiles are computed once and cached by `WindowedTensor`.
- **Other CUDA options.** NHWC convolutions (`prefer_nhwc`) run at the same speed and move the terrain by 0.4 m;
  tunable ops change nothing.
