# Inference runtimes

Terrain Diffusion runs on [ONNX Runtime](https://onnxruntime.ai) 1.31.0. The model files are the same for every
device; only the native ONNX Runtime build differs:

| Runtime | Hardware | How you get it | Status |
|---|---|---|---|
| **CPU** | Windows x64, Linux x64/arm64, macOS on Apple silicon | Bundled in the mod jar | Works everywhere |
| **NVIDIA CUDA** | GeForce 16 / RTX 20 series (Turing) or newer, driver 580+ | One click in the resource screen | Main GPU target |
| **AMD ROCm** | Radeon / Instinct GPUs that ROCm supports, Linux only | You provide a ROCm build of ONNX Runtime | Experimental, untested |

With the device set to *Automatic*, Diffusicraft also tries any other accelerator the loaded build contains (CoreML,
DirectML, WebGPU) before falling back to the CPU.

The game picks a runtime when it first loads the models. ONNX Runtime loads its native library once per process, so
**switching runtime needs a restart**. The resource screen says when one is needed.

## Where to manage them

- **First launch:** the resource screen opens by itself while the models are missing, or while an NVIDIA GPU has no
  runtime installed. *Download recommended* gets both.
- **Later:** Create World > *More* > *Diffusicraft Resources...*, or Mod Menu > Diffusicraft > *Configure* >
  *Models and runtimes...*.
- **Dedicated servers:** the models download at start. Set `"device": "CUDA"` in `config/diffusicraft.json` to
  download the CUDA runtime too (see [CONFIG.md](CONFIG.md)).

Everything is stored once per user and shared by every Minecraft instance; see [CONFIG.md](CONFIG.md#storagepath).

## NVIDIA CUDA

The CUDA runtime is assembled from official packages. Every file is pinned by size and SHA-256 in
[`runtime-manifest.json`](../src/main/resources/diffusicraft/runtime-manifest.json):

| Component | Source | License |
|---|---|---|
| ONNX Runtime 1.31.0 (CUDA 13) | `onnxruntime_gpu` jar on Maven Central | MIT |
| CUDA 13 runtime, cuBLAS, cuRAND, NVRTC | NVIDIA's `nvidia-*` wheels on PyPI | NVIDIA CUDA Toolkit EULA |
| cuDNN 9.27 | NVIDIA's `nvidia-cudnn-cu13` wheel on PyPI | NVIDIA cuDNN Software License Agreement |

Before anything is downloaded, the game lists these licenses and links to them. The player must accept.

**Download size.**

| Platform | Download | On disk | With CUDA 13 already installed |
|---|---|---|---|
| Linux x64 | 1.2 GB | 2.0 GB | 680 MB download, 1.1 GB on disk |
| Windows x64 | 1.0 GB | 1.5 GB | 500 MB download, 740 MB on disk |

Two things keep it small:

- **Only the needed files are fetched.** [`RemoteZip`](../src/main/java/io/github/elpideus/diffusicraft/runtime/RemoteZip.java)
  uses HTTP range requests to fetch only the libraries this platform loads. It skips the other platforms' natives in
  the ONNX Runtime jar, and the headers and static libraries in NVIDIA's wheels.
- **Existing installs are reused.** A CUDA toolkit or cuDNN already on the system is used when its libraries are
  found, and that part is skipped. Turn this off with `useSystemLibraries`. The places searched:
  - **Linux:** the `ldconfig` cache, `LD_LIBRARY_PATH`, `CUDA_HOME`/`CUDA_PATH`, `/opt/cuda`, `/usr/local/cuda` and
    the distribution library directories.
  - **Windows:** `CUDA_PATH`, `Program Files\NVIDIA\CUDNN` and `PATH`.

**How it loads.** No environment variables are needed. [`NativeRuntime`](../src/main/java/io/github/elpideus/diffusicraft/runtime/NativeRuntime.java)
loads each vendor library by absolute path, in dependency order, before ONNX Runtime starts. The dynamic linker then
reuses those libraries when the CUDA execution provider asks for them by name. This works the same on Linux and
Windows, and nothing outside the Minecraft process changes.

**Requirements.** CUDA 13 dropped Maxwell, Pascal and Volta GPUs (GTX 9xx/10xx, Titan V) and needs driver R580 or
newer. The resource screen detects both through `nvidia-smi`. On an unsupported GPU or driver it says why, and it
does not recommend the download.

**Tuning.** CUDA sessions:

- grow their memory arena only as needed;
- are capped at 60% of video memory by default (`gpuMemoryLimitMb`), leaving room for the game and shader packs;
- run cuDNN's exhaustive convolution search once per window shape;
- use TF32 tensor cores on Ampere and newer.

See [PERFORMANCE.md](PERFORMANCE.md).

## AMD ROCm

> **Experimental.** This path compiles and its error handling is tested, but it has not been run on AMD hardware.
> Reports are welcome.

Microsoft does not publish ROCm builds of ONNX Runtime for Java, and AMD only publishes Python wheels. You need to
build ONNX Runtime **1.31.0**, the same version as the mod, with the Java bindings. From an ONNX Runtime checkout
at tag `v1.31.0`, on a machine with ROCm installed:

```bash
./build.sh --config Release --build_shared_lib --build_java --parallel --skip_tests \
    --use_migraphx --migraphx_home /opt/rocm
```

ONNX Runtime 1.23 and newer only have the **MIGraphX** execution provider for AMD GPUs; the older ROCm provider was
removed. Diffusicraft uses whichever one the library contains.

Copy these files from `build/Linux/Release` into the ROCm runtime folder. The *Folder* button on the ROCm row opens
it. By default it is `<storage>/runtimes/linux-x64/rocm`; `rocmNativePath` changes it.

- `libonnxruntime.so`
- `libonnxruntime4j_jni.so`
- `libonnxruntime_providers_shared.so`
- `libonnxruntime_providers_migraphx.so` (or `libonnxruntime_providers_rocm.so` from an older build)

The ROCm libraries themselves (MIGraphX, HIP, rocBLAS, MIOpen) must be findable by the dynamic linker. ROCm's
installer registers them in `/etc/ld.so.conf.d`.

The row shows *Installed* once the files are in place. Then set the device to *AMD ROCm* (or leave it on *Automatic*)
and restart. The log line `Loaded model 'base' on MI_GRAPH_X` confirms it worked. If the provider fails to start,
the model loads on the CPU and the log says why.

## CPU

The CPU build ships inside the mod jar (ONNX Runtime's Java package: 17 to 45 MB of natives per platform). It uses every
physical core by default; `cpuThreads` sets another count.

On a 12th-gen Core i7, a 15 km × 15 km area takes about 14 seconds. That is enough to play, but slower to explore
fast or to fill a Distant Horizons LOD. See [PERFORMANCE.md](PERFORMANCE.md).

## For developers: the manifest

`runtime-manifest.json` (schema 1) lists **runtimes**. A runtime with no components (ROCm) is user-provided.
Otherwise it is a list of **components** installed in this order:

```jsonc
{
  "id": "cudnn-9.27.0-cuda13",              // also the install directory name; change it when the content changes
  "name": "NVIDIA cuDNN 9.27",
  "role": "libraries",                       // or "onnxruntime": the directory ONNX Runtime loads from
  "license": "...", "license_url": "...",
  "system_libraries": {"linux-x64": ["libcudnn.so.9", ...]},   // optional: skip the download if all are found
  "archives": {
    "linux-x64": [{
      "url": "https://files.pythonhosted.org/...whl", "size": 0, "sha256": "...",
      "files": [{"entry": "nvidia/cudnn/lib/libcudnn.so.9", "name": "libcudnn.so.9",
                 "size": 0, "packed": 0, "sha256": "..."}]   // load order: dependencies first
    }]
  }
}
```

**What the installer checks.**

- Every extracted file is checked against its own size and SHA-256.
- The archive's own hash is only checked when a server ignores range requests and the whole archive is downloaded
  instead.
- Once a component is verified, an `.installed` marker records each file's hash, size and modification time. Later
  status checks only compare metadata.

**Updating a pinned version.**

1. Change the URLs and give the component a new `id`, so the old install is not mistaken for the new one.
2. Re-pin every `size`, `packed` and `sha256`. Read the ZIP central directory for `packed`; hash the extracted
   entries for `sha256`.
3. Run `./gradlew test -PwithModels --tests '*RuntimeInstallTest' -Pdiffusicraft.installRuntime=cuda` to install it
   for real.
4. Keep `onnxruntime_version` in `gradle.properties` equal to the ONNX Runtime component's version. The Java classes
   and the JNI library must match.
