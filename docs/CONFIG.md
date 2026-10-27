# Configuration

Diffusicraft keeps its settings in `config/diffusicraft.json` inside the game directory. With
[Mod Menu](https://modrinth.com/mod/modmenu) installed, every one of them can be changed in the settings screen
(*Mods* > Diffusicraft > *Configure*), which saves the file for you and says in each tooltip when a change takes
effect. The resource screen (Create World > *More* > *Diffusicraft Resources...*, or *Models and runtimes...* in the
settings screen) has the device, the GPU memory and whether it shows at startup. If you edit the file by hand, do it
while the game is closed.

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
  "shareBuilds": false,
  "farTerrain": true,
  "farTerrainDistance": 8192,
  "verticalRenderDistance": 0,
  "generateAhead": true,
  "moddedBiomes": 50,
  "reliefMap": true,
  "reliefMapAddress": "127.0.0.1",
  "reliefMapPort": 0,
  "reliefMapUrl": ""
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
for it, those with Diffusicraft, so that their far terrain, or their Voxy's, shows it as in singleplayer, without what
other players built unless [`shareBuilds`](#sharebuilds) is on. Set it to
`false` to send none: their far terrain then ends where the chunks do, and their Voxy shows only the chunks it has
loaded. The server samples the grids on two threads of its own, so
they never hold up the ticks, but they do use the GPU or the CPU as chunks do. Read when the server starts. See
[COMPATIBILITY.md](COMPATIBILITY.md#voxy).

### `farTerrainRadius`

How far from a player, in blocks, the server sends far terrain: up to 65536. The default, 8192, is as far as Voxy draws
by default. Beyond it, a player's Voxy shows only the chunks it has loaded. Read when the server starts.

### `shareBuilds`

Show in the far terrain the server sends what players built and dug, wherever they did. In the settings screen this
is *Show others' builds*. Off, the default, the server sends the terrain as generated, and each player's far terrain
lays over it what their own client saw, as they last saw it: what others build far away stays hidden until the player
goes there, so that nobody finds a base from afar. On, which suits a server among friends, the server sends the chunks
as they were last saved, and tells the players within reach when a page of them changes, so that their far terrain
shows a new build within moments of the server saving it. Voxy keeps what it was sent: it shows a place as it was when
it first asked. Read when the server starts. See [LOD.md](LOD.md#on-a-server-lodfeed-remotelods-and-seenchunks).

### `farTerrain`

Draw Diffusicraft's own far terrain past the chunks of Diffusicraft worlds: in singleplayer straight from the
world's terrain, with what players built, and on a server from the far terrain it sends (see `serveFarTerrain`),
with what this client saw itself laid over it unless the server shares what players built (`shareBuilds`).
It is always off while Distant Horizons or Voxy is installed, which draw their own. Takes effect at once. See
[LOD.md](LOD.md).

### `farTerrainDistance`

How far the far terrain reaches, in blocks: from 512 to 65536. The default is 8192. The fog closes in at its end
instead of at the end of the chunks. On a server, the far terrain ends where the server stops sending it
(`farTerrainRadius`) if that is nearer. Takes effect at once.

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

### `moddedBiomes`

How much of a Diffusicraft world, in percent, is open to the biomes of other mods (Biomes O' Plenty, Terralith,
Terrestria and the like), in regions about a kilometre across. `0` keeps their biomes out, `100` lets them in wherever
their climate fits, and everywhere else the world keeps Diffusicraft's biomes. It does nothing without biome mods. Read
when a world opens; a change moves the borders of the regions in chunks generated after it, never in those already
there. See [COMPATIBILITY.md](COMPATIBILITY.md#biome-mods).

### `reliefMap`

Offers the [relief map](MAP.md) through the `/diffusicraft map` command. Set it to `false` to take the command away
and never start the map's web server. Read whenever someone asks for the map. `/diffusicraft find` stays, without
its links to the map.

### `reliefMapAddress`

The address the relief map's web server listens on. The default, `127.0.0.1`, lets only this machine open the map;
`0.0.0.0` lets every machine that can reach it, if it has a link. Read when the map starts, with its first link.

### `reliefMapPort`

The port the relief map's web server listens on. `0`, the default, picks a free one each time the map starts. Set one
on a server, so that the firewall can let it through. Read when the map starts.

### `reliefMapUrl`

The address the map's links point to, such as `https://map.example.net` behind a reverse proxy. Empty, the default,
means the address and port the map listens on, or the server's `server-ip` when it listens on `0.0.0.0`. Read when
the map starts. See [MAP.md](MAP.md#on-a-server).

## Dedicated servers

Servers have no resource screen. At start they:

1. Download the models if they are missing (progress goes to the log).
2. Download the CUDA runtime only when `device` is `CUDA`. With `AUTO` on a machine with an NVIDIA GPU, they log how
   to enable it.

The same storage rules apply. On a server, consider setting `storagePath` to a directory next to the server, so
that it does not depend on the service user's home directory.

A server sends the far terrain of its Diffusicraft worlds to the clients with Diffusicraft that ask for it;
[`serveFarTerrain`](#servefarterrain) and [`farTerrainRadius`](#farterrainradius) set whether and how far, and
[`shareBuilds`](#sharebuilds) whether it shows what players built.

Operators can open the [relief map](MAP.md) with `/diffusicraft map`. It listens only on the server's own machine
until [`reliefMapAddress`](#reliefmapaddress) and [`reliefMapUrl`](#reliefmapurl) say otherwise.
