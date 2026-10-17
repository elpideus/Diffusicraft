# Third-party notices

Diffusicraft includes or adapts code and uses models from the following projects.

## Terrain Diffusion / terrain-diffusion-mc — MIT
- https://github.com/xandergos/terrain-diffusion
- https://github.com/xandergos/terrain-diffusion-mc
- Copyright (c) 2025 Alexander Goslin

The Java inference pipeline under `io.github.elpideus.diffusicraft.terrain.diffusion`
is adapted from terrain-diffusion-mc (itself a port of the Python reference
implementation). Model weights (`xandergos/terrain-diffusion-30m-onnx` on Hugging
Face, MIT) are downloaded at runtime and are not redistributed in the jar.

Paper: A. Goslin, *Terrain Diffusion* (arXiv:2512.08309, SIGGRAPH 2026).

## DPM-Solver / diffusers scheduler — Apache-2.0
`EDMScheduler` is derived from code Copyright 2024 TSAIL Team and The HuggingFace
Team, licensed under the Apache License, Version 2.0
(http://www.apache.org/licenses/LICENSE-2.0).

## FastNoiseLite — MIT
- https://github.com/Auburn/FastNoiseLite — Copyright (c) 2023 Jordan Peck, Contributors

## ONNX Runtime — MIT
- https://github.com/microsoft/onnxruntime — Copyright (c) Microsoft Corporation

## NVIDIA CUDA and cuDNN — NVIDIA license agreements (downloaded, not redistributed)
The optional NVIDIA CUDA runtime is downloaded on the player's request from NVIDIA's own packages on PyPI, after the
player accepts the NVIDIA CUDA Toolkit EULA and the NVIDIA cuDNN Software License Agreement in game. Diffusicraft does
not include or redistribute any NVIDIA software. See docs/RUNTIMES.md.
