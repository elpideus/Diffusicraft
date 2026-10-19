# Terrain Diffusion models

Diffusicraft's terrain comes from **Terrain Diffusion** by Alexander Goslin ([paper](https://arxiv.org/abs/2512.08309),
[code](https://github.com/xandergos/terrain-diffusion), [project page](https://xandergos.github.io/terrain-diffusion)).
It is a diffusion model trained on real-world elevation and climate data (ETOPO, WorldClim). It generates an
unbounded, seamless planet at 30 m per pixel, with elevation and climate. The climate follows the elevation:
mountains are colder, rain shadows are drier, and coasts are milder.

We chose it because it is the best fit we found. It does all four of these:

- generates infinitely and seamlessly;
- is deterministic per seed;
- outputs climate together with elevation;
- is MIT-licensed, weights included.

The other generators we looked at make fixed-size tiles or only a heightmap.

## Files

The ONNX export is [`xandergos/terrain-diffusion-30m-onnx`](https://huggingface.co/xandergos/terrain-diffusion-30m-onnx)
on Hugging Face, pinned to one revision in
[`model-manifest.json`](../src/main/resources/diffusicraft/model-manifest.json):

| File | Size | Role |
|---|---|---|
| `coarse_model.onnx` | 22 MB | Continent-scale elevation and climate, 1 px = 7.7 km |
| `base_model.onnx` | 2.03 GB | Latent terrain, 1 px = 240 m, conditioned on the coarse map |
| `decoder_model.onnx` | 224 MB | Full 30 m detail from the latents |
| `world_pipeline_config.json` | 1 KB | Scheduler and window settings |
| `pipeline_data.json` | 12 KB | Quantile tables of the real elevations and climate, which the conditioning map is matched to |

That is 2.3 GB in total. The weights are MIT-licensed and are downloaded at runtime, never shipped in the mod jar.

## Download and verification

[`ModelAssets`](../src/main/java/io/github/elpideus/diffusicraft/terrain/diffusion/ModelAssets.java) downloads from
`https://huggingface.co/<repository>/resolve/<revision>/<file>` into `<storage>/models/<revision>/`. The storage
directory is described in [CONFIG.md](CONFIG.md#storagepath).

- **Integrity.** Every file is checked against the manifest's size and SHA-256. A verified file gets a `.verified`
  sidecar holding its size and modification time. Later launches compare metadata only, so the 2 GB file is hashed
  once rather than at every start.
- **Resume.** Interrupted downloads resume from their `.part` file.
- **Shared storage.** Several game instances can share the storage directory. Only one downloads at a time; the
  others wait on `<storage>/.lock`.

## Changing the revision

The revision is part of the world: the same seed gives different terrain with different weights. So:

- Changing `revision` in the manifest invalidates every cached terrain window.
- Old worlds would get seams at the border between old and new chunks.
- A revision bump must come with a new manifest entry and a world-format version check. This is planned together with
  the world generator; see [ROADMAP.md](ROADMAP.md).

For experiments, `-Ddiffusicraft.modelDir=/path/to/models` points the game and the tests at another directory with
the same file names.

## The pipeline in Java

The inference pipeline is a port of the Python reference (`world_pipeline.py`) by way of
[terrain-diffusion-mc](https://github.com/xandergos/terrain-diffusion-mc), restructured for concurrency. See
[ARCHITECTURE.md](ARCHITECTURE.md#terrain-pipeline). The output for any rectangle is a
[`TerrainSample`](../src/main/java/io/github/elpideus/diffusicraft/terrain/diffusion/TerrainSample.java):

- **Elevation** in meters, where negative means below sea level.
- **Climate** as the WorldClim bioclimatic variables the model was trained on:
  - annual mean temperature (corrected for the local elevation with a regressed lapse rate);
  - temperature seasonality (BIO4);
  - annual precipitation;
  - precipitation seasonality (BIO15).

Biomes, snow lines, vegetation and soils are derived from these values (planned), rather than from Minecraft's
noise parameters.
