# Configuration

Diffusicraft keeps its settings in `config/diffusicraft.json` inside the game directory. Most settings can be changed
in the resource screen (Create World > *More* > *Diffusicraft Resources...*, or through Mod Menu), which saves the
file for you. If you edit the file by hand, do it while the game is closed.

```json
{
  "device": "AUTO",
  "gpuIndex": 0,
  "gpuMemoryLimitMb": 0,
  "cpuThreads": 0,
  "halfPrecision": true,
  "showSetupScreen": true,
  "useSystemLibraries": true,
  "storagePath": "",
  "rocmNativePath": "",
  "distantHorizonsLods": true,
  "voxyLods": true,
  "serveFarTerrain": true,
  "farTerrainRadius": 8192,
  "verticalRenderDistance": 0,
  "generateAhead": true
}
```

Missing fields take the defaults above. Invalid values are clamped.

## Fields

### `device`

Which hardware generates terrain. In the resource screen this is *Device*.

| Value | Meaning |
|---|---|
| `AUTO` | The installed GPU runtime if there is one (CUDA, then ROCm), otherwise the CPU |
| `CPU` | Always the CPU, even when a GPU runtime is installed |
| `CUDA` | NVIDIA CUDA |
| `ROCM` | AMD ROCm/MIGraphX |

If the chosen device fails to start, the models load on the CPU and the log says why. A change takes effect after a
restart, because ONNX Runtime loads its native library once per process. See [RUNTIMES.md](RUNTIMES.md).

### `gpuIndex`

Which GPU to use on a machine with several, counting from 0.

### `gpuMemoryLimitMb`

A cap on the memory ONNX Runtime may claim on the GPU, so the game, Distant Horizons and shader packs keep theirs.
In the resource screen this is *GPU memory*.

| Value | Meaning |
|---|---|
| `0` | Automatic: 60% of the GPU's memory, at least 2 GB (7.2 GB on a 12 GB card) |
| `-1` | No cap |
| any other number | The cap in MiB |

It applies to CUDA. The MIGraphX provider takes no cap.

### `cpuThreads`

Threads per inference run on the CPU. `0` uses every physical core. Lower it to keep cores free for the game while
terrain generates.

### `halfPrecision`

Run the decoder, the last of the three networks, in half precision on NVIDIA GPUs. It makes terrain about 1.2×
faster on CUDA and moves it by a few centimetres, less than the CPU and the GPU already differ. The first load on
CUDA makes a half-precision copy of the decoder next to the models, which takes about a second. Turn it off to run
every network in full precision. Read when the models load. See
[PERFORMANCE.md](PERFORMANCE.md#half-precision-decoder).

### `showSetupScreen`

Whether the resource screen opens at launch while the models are missing, or while an NVIDIA GPU has no runtime
installed. In the resource screen this is *Show at startup*.

### `useSystemLibraries`

Reuse a CUDA toolkit and cuDNN already installed on the system instead of downloading them. Read at startup.

### `storagePath`

Where models and runtimes are stored. Read at startup. By default it is a per-user directory that every Minecraft
instance and launcher shares, so the 2.3 GB of models and the GPU runtime are downloaded once:

| OS | Default |
|---|---|
| Windows | `%LOCALAPPDATA%\Diffusicraft` |
| macOS | `~/Library/Application Support/Diffusicraft` |
| Linux | `$XDG_DATA_HOME/diffusicraft` (usually `~/.local/share/diffusicraft`) |

If the default directory cannot be written, `diffusicraft/` in the game directory is used instead. A relative
`storagePath` is resolved against the game directory. Inside the storage directory:

```text
models/<revision>/          Terrain Diffusion files (see MODELS.md)
runtimes/<platform>/<id>/   GPU runtime components, e.g. runtimes/linux-x64/cudnn-9.27.0-cuda13
runtimes/<platform>/rocm/   where a user-provided ROCm build goes
.lock                       held while an instance downloads; other instances wait
```

You can delete the directory at any time. The resource screen downloads what is missing again.

### `rocmNativePath`

The folder containing a ROCm build of ONNX Runtime. If empty, `runtimes/<platform>/rocm` inside the storage directory
is used. Read at startup. See [RUNTIMES.md](RUNTIMES.md#amd-rocm).

### `distantHorizonsLods`

With Distant Horizons installed, Diffusicraft draws its far terrain in Diffusicraft worlds straight from the terrain,
without generating chunks. Set it to `false` to let Distant Horizons generate chunks for its LODs instead, or to let
another LOD generator take over. Read at startup. See [COMPATIBILITY.md](COMPATIBILITY.md#distant-horizons).

### `voxyLods`

With Voxy installed, Diffusicraft fills Voxy's far terrain in Diffusicraft worlds straight from the terrain where
Voxy has not seen the chunks: in singleplayer from the world itself, on a server that runs Diffusicraft from the far
terrain the server sends. Set it to `false` to have Voxy show only the chunks it has loaded. Read when Voxy opens a
world. See [COMPATIBILITY.md](COMPATIBILITY.md#voxy).

### `serveFarTerrain`

On a server, or a world opened to LAN, send the far terrain of Diffusicraft worlds to the players whose clients ask
for it, those with Diffusicraft and Voxy, so that their Voxy draws it as in singleplayer. Set it to `false` to send
none: their Voxy then shows only the chunks it has loaded. The server samples the grids on two threads of its own, so
they never hold up the ticks, but they do use the GPU or the CPU as chunks do. Read when the server starts. See
[COMPATIBILITY.md](COMPATIBILITY.md#voxy).

### `farTerrainRadius`

How far from a player, in blocks, the server sends far terrain: up to 65536. The default, 8192, is as far as Voxy draws
by default. Beyond it, a player's Voxy shows only the chunks it has loaded. Read when the server starts.

### `verticalRenderDistance`

How many section rows above and below the camera the client keeps render data for. `0`, the default, means the render
distance, which is as far as the game draws vertically anyway, and so does any larger value. A smaller value saves
memory in dimensions taller than vanilla's, but terrain further above or below the camera is not drawn. It has no
effect with Sodium. Read at startup. See [HEIGHT.md](HEIGHT.md#the-clients-render-grid).

### `generateAhead`

Computes terrain in the background where the players are heading: around them out to their view distance, and along
their path for the next 20 seconds. Chunks then rarely wait for inference, even at elytra speed. It uses the GPU (or
the CPU) while you move, so set it to `false` on a slow machine if it gets in the way of the game more than waiting
for terrain does. Read when a world opens. See [PERFORMANCE.md](PERFORMANCE.md#generating-ahead).

## Dedicated servers

Servers have no resource screen. At start they:

1. Download the models if they are missing (progress goes to the log).
2. Download the CUDA runtime only when `device` is `CUDA`. With `AUTO` on a machine with an NVIDIA GPU, they log how
   to enable it.

The same storage rules apply. On a server, consider setting `storagePath` to a directory next to the server, so
that it does not depend on the service user's home directory.

A server sends the far terrain of its Diffusicraft worlds to the clients with Voxy and Diffusicraft that ask for it;
[`serveFarTerrain`](#servefarterrain) and [`farTerrainRadius`](#farterrainradius) set whether and how far.
