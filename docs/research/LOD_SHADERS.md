# LOD rendering under Iris shader packs

Research for Diffusicraft's own far-terrain (LOD) renderer: how Distant Horizons (DH) and Voxy get Iris shader packs
to light, shade, shadow and fog their LODs, what Bliss and Complementary Unbound expect from them, and how a third
renderer can get the same treatment on Minecraft 26.3. Written 2026-10-25 from the sources listed in
[Sources](#sources); nothing here was run in game. A separate report covers DH's and Voxy's data and render engines.

## Contents

- [Summary](#summary)
- [1. Iris and Distant Horizons](#1-iris-and-distant-horizons)
- [2. Iris and Voxy](#2-iris-and-voxy)
- [3. Bliss and Complementary Unbound](#3-bliss-and-complementary-unbound)
- [4. Options for our own renderer](#4-options-for-our-own-renderer)
- [5. Rendering platform in 26.x](#5-rendering-platform-in-26x)
- [6. Not found or unconfirmed](#6-not-found-or-unconfirmed)
- [Sources](#sources)

## Summary

- **Packs implement two LOD contracts, and Iris owns only one.** DH's contract (`dh_terrain`, `dh_water`,
  `dh_shadow`, `dh_generic`; `dhProjection`, `dhDepthTex0/1` and friends) is implemented inside Iris, in
  `net.irisshaders.iris.compat.dh`. Voxy's contract (`voxy.json`, `voxy_opaque.glsl`, `voxy_translucent.glsl`;
  `vxProj`, `vxDepthTex*`) lives entirely in Voxy, which patches Iris with mixins.
- **Only the DH contract covers both packs.** Complementary Unbound r5.9.3 ships both. Bliss 2.1.2, the release, ships
  only DH programs; its Voxy support is on the `Unstable` branch.
- **Iris recognises DH by its mod id and links against DH's API classes.** It never checks an API version. The only
  version gate is `"breaks": {"distanthorizons": "<=3.2.0"}`. Every DH-specific value Iris gives a pack passes through
  a handful of static and instance getters in `DHCompat`, plus one `isModLoaded("distanthorizons")` check when it
  builds the standard macros.
- **Minecraft 26.3 uses reversed Z.** It has a `D32_FLOAT` main depth buffer, clears depth to 0, and uses a 0..1 clip
  range when `GL_ARB_clip_control` is present. Iris rewrites every pack program so packs still see forward Z: the
  vertex output, `gl_FragCoord.z`, `gl_FragDepth` and reads of `depthtex*`, `dhDepthTex*` and `vxDepthTex*`. The
  shadow pass stays forward Z.
- **Iris has a public API worth using.** API v0.4 (Iris 1.11.7) offers `assignPipeline`, `assignPipelineShadow`,
  `registerShadowRenderCallback` and `isReverseZDuringShaders`. It has no LOD hooks.
- **Iris refuses to run on Minecraft's Vulkan backend,** and DH forces its own raw-OpenGL engine whenever Iris is
  installed. So the shader path is OpenGL only. The minimum Minecraft guarantees there is GL 3.3 core.
- **Recommendation: option (b).** Drive the pack's own `dh_*` programs for our draws with Iris's internals, plus about
  ten small mixins (most on `DHCompat` and `StandardMacros`). Use DH's 16-byte vertex format and draw where DH draws:
  at the head of Sodium's `SodiumChunkSection.renderGroup`. Render the shadow pass through Iris's public
  `registerShadowRenderCallback`.
- **Also propose a neutral LOD API upstream (option c),** shaped like those mixins, so they can be deleted later.
  Details in [4.6](#46-recommendation-option-b-with-c-in-parallel).

## 1. Iris and Distant Horizons

Iris paths are under `common/src/main/java/net/irisshaders/iris/` of Iris's `26.3` branch. DH paths are relative to
the DH repository at tag `3.3.4`; `coreSubProjects/` is the `distant-horizons-core` submodule.

### 1.1 Detection and versions

- **Detection is by mod id, not by API version.**
  - `Iris.java:816` calls `DHCompat.run()` (`compat/dh/DHCompat.java:61-91`).
  - If `IrisPlatformHelpers.getInstance().isModLoaded("distanthorizons")` is true, `run()` uses `MethodHandles` to
    look up `DHCompatInternal`'s `clear`, `getStoredDepthTex`, `getRenderDistance`, `incompatiblePack`,
    `getFarPlane`, `getNearPlane`, `getDepthTexNoTranslucent` and `checkFrame`, then calls
    `LodRendererEvents.setupEventHandlers()`.
  - If any of that fails to link, Iris stops with "DH found, but one or more API methods are missing. Iris requires
    DH [2.0.4] or DH API version [1.1.0] or newer". The message is out of date.
  - Iris never reads DH's `ModInfo.API_MAJOR_VERSION`.
  - Without DH, the private static `dhPresent` stays false and every getter returns a constant.
- **The DH mixins are gated the same way.** `mixins.iris.compat.dh.json` applies only when that mod id is loaded
  (`compat/dh/DHMixinConfigPlugin.java:23-25`).
- **Version floor.** Iris's `fabric.mod.json` declares `"breaks": {"distanthorizons": "<=3.2.0"}`. That was commit
  `39e9551f3`, "Add breaks clause for DH 3.2.0", on 2026-09-14.
- **What Iris compiles against.** `DHApi.jar` in Iris's repository root is DH `3.2.1-b-dev`, API 7.1.0 (constants in
  its `coreapi/ModInfo.class`). It was last replaced in `5b89e916b`, "Add support for textures in DH", on 2026-09-12.
  DH 3.3.4 is API 7.2.0 (`coreSubProjects/api/src/main/java/com/seibel/distanthorizons/coreapi/ModInfo.java:46-55`).
- **On and off.**
  - `DHCompatInternal.checkFrame()` (`compat/dh/DHCompatInternal.java:136-155`) runs at the head of every
    `LevelRenderer.render` (`mixin/MixinLevelRenderer.java:106-108`). It reloads the pack when DH's
    `renderingEnabled` config or the GUI scale changes.
  - `gl/shader/StandardMacros.java:65-68` defines `DISTANT_HORIZONS` and `DISTANT_HORIZONS_TEXTURES` only when the mod
    is loaded and DH rendering is on.
  - The material ids `DH_BLOCK_*` are defined always (`StandardMacros.java:82-97`).
  - The macros are computed once, when the pack loads (`StandardMacros.createStandardEnvironmentDefines()`, called
    from `Iris.java:347` and `shaderpack/IrisDefines.java:26`). They feed both GLSL and `shaders.properties`, so
    turning LODs on or off needs `Iris.reload()`.
- **DH's side.**
  - DH sees Iris through `IIrisAccessor`
    (`fabric/src/main/java/com/seibel/distanthorizons/fabric/wrappers/modAccessor/IrisAccessor.java`). It calls the
    public `IrisApi` methods `isShaderPackInUse()`, `isRenderingShadowPass()` and `isReverseZDuringShaders()`.
  - With Iris installed, DH 3.3.4 forces its native OpenGL engine instead of its default Blaze3D one, because "Iris
    only supports native OpenGL". It refuses to start if the user picked Blaze3D
    (`common/src/main/java/com/seibel/distanthorizons/common/AbstractModInitializer.java:456-484`).
  - So on DH's side, everything below is raw OpenGL.

### 1.2 How Iris compiles the dh_* programs and hands them to DH

- **Program names.**
  - `shaderpack/loading/ProgramId.java:54-57` adds four programs to the `Dh` group (`ProgramGroup.java:13`, prefix
    `dh`):
    - `dh_terrain`;
    - `dh_water`, which falls back to `dh_terrain`;
    - `dh_generic`, which also falls back to `dh_terrain`;
    - `dh_shadow`, which has no fallback.
  - They resolve per dimension folder like every other program (`pipeline/IrisRenderingPipeline.java:1322-1336`).
  - There is no `dh_clouds`, `dh_sky` or any other `dh_*` program.
- **When they are built.**
  - `IrisRenderingPipeline`'s constructor runs `new DHCompat(this, shadowDirectives.isDhShadowEnabled().orElse(true))`
    (`IrisRenderingPipeline.java:404`).
  - A pack can veto LOD shadows with `dhShadow.enabled=false` in `shaders.properties`
    (`shaderpack/properties/ShaderProperties.java:223`), and can set `dhClouds=off|on` (`:170-176`).
  - `DHCompatInternal`'s constructor (`DHCompatInternal.java:49-100`) returns early when DH rendering is off.
  - It marks the pack incompatible when it has neither `dh_terrain` nor `dh_water`. Iris then warns when the player
    joins a world (`Iris.java:760`, `mixin/MixinClientPacketListener.java:31`).
  - Otherwise it compiles four programs:
    - `dh_terrain`, the solid program;
    - `dh_generic`, built with `IrisGenericRenderProgram`, used for DH's clouds, beacons and API boxes;
    - `dh_water`, translucent, with the solid program reused when it is missing;
    - `dh_shadow`, if the pack has it and allows it.
- **Patching.**
  - `IrisLodRenderProgram.createProgram` (`compat/dh/IrisLodRenderProgram.java:142-171`) calls
    `TransformPatcher.patchDHTerrain` (`pipeline/transform/TransformPatcher.java:341-346`), which applies
    `DHTerrainTransformer` (`pipeline/transform/transformer/DHTerrainTransformer.java`).
  - The patcher accepts DH programs only in its compatibility-profile branch:
    - a `core` profile goes to the core branch (`TransformPatcher.java:152`);
    - so does `#version 150` or newer without a profile;
    - the core branch has no DH case and throws "Unknown patch type" (`:170`).
  - Both packs use compatibility sources: Bliss `#version 330 compatibility` and `120`, Complementary `#version 130`.
  - The transformer makes these substitutions:
    - `gl_ProjectionMatrix`, `gl_ModelViewMatrix` and their inverses become the `iris_*` uniforms;
    - `gl_ModelViewProjectionMatrix` becomes their product;
    - `gl_NormalMatrix` becomes `iris_NormalMatrix`;
    - the texture matrices become identity;
    - `gl_Vertex` becomes `vec4(modelOffset + _vert_position, 1.0)`;
    - `gl_Color` becomes `_vert_color`;
    - `gl_Normal` becomes `_vert_normal`;
    - `gl_MultiTexCoord0` becomes `vec4(0, 0, 0, 1)`;
    - `gl_MultiTexCoord1` and `2` become the lightmap coordinate.
  - It also provides `ftransform()` and an `int dhMaterialId`.
  - It injects `_vert_init()`, which decodes the vertex, and prepends a call to it in `main`
    (`DHTerrainTransformer.java:131-173`; the format is in [1.3](#13-vertex-input)).
  - In the fragment stage it injects `dhBlockAtlas`, `dh_hasTexture()`, `dh_blockFaceUv()` and `dh_sampleTexture()`
    (`DHTerrainTransformer.java:45-71`). These let a pack sample DH's own atlas of 16-pixel tiles, 256 to a row.
    Neither pack uses them.
- **Reversed Z.**
  - Since commit `f3763381d` ("The fancy depth transformer", 2026-09-08), every patched program goes through
    `DepthTransformer`, the `dh_*` programs included. The calls are `CommonTransformer.transformDepth` and
    `transformDepthPosition` (`pipeline/transform/transformer/CommonTransformer.java:107-121`).
  - For non-shadow programs it rewrites four things (`DepthTransformer.java:35-36`, `62-90`, `92-128`, `173-185`):
    - it appends `gl_Position.z = 0.5 * (gl_Position.w - gl_Position.z)` to the last vertex stage on a device with a
      0..1 clip range, and `gl_Position.z = -gl_Position.z` otherwise;
    - every read of `depthtex0/1/2`, `gdepthtex`, `dhDepthTex`, `dhDepthTex0/1`, `vxDepthTexOpaque` and
      `vxDepthTexTrans` becomes `1.0 - value`;
    - `gl_FragCoord.z` is flipped;
    - writes to `gl_FragDepth` are flipped.
  - Shadow programs (`shadow*`, `*_shadow`, `dh_shadow_*`; `CommonTransformer.java:123-129`) keep forward Z. On a 0..1
    device they only get the clip-range remap `0.5 * (z + w)`.
  - Result: packs keep writing forward-Z, −1..1 projections and reading forward-Z depth, while the textures underneath
    hold reversed Z.
- **Linking.** `IrisLodRenderProgram`'s constructor (`IrisLodRenderProgram.java:64-140`):
  - binds attribute locations `0 = vPosition`, `1 = iris_color` and `2 = irisExtra`;
  - links the stages; tessellation and geometry shaders are allowed;
  - adds the dynamic common uniforms with per-vertex fog, the pack's custom uniforms and the builtin replacement
    uniforms;
  - adds gbuffer samplers and images through `IrisRenderingPipeline.addGbufferOrShadowSamplers`
    (`IrisRenderingPipeline.java:742-771`), using the flip state of the pass the program draws in
    (`IrisRenderingPipeline.java:335-349`):
    - `flippedBeforeShadow` for `dh_shadow`;
    - `flippedAfterTranslucent` for `dh_water`;
    - `flippedAfterPrepare` for `dh_terrain`;
  - honours the `blend.<program>` and per-buffer blend directives.
- **Handing them to DH.**
  - DH has no slot for a terrain shader program that Iris could fill. Iris instead listens to DH's render events.
  - It registers its listeners in `compat/dh/LodRendererEvents.java:49-78`, once DH fires `DhApiAfterDhInitEvent`.
  - DH fires `DhApiBeforeBufferRenderEvent` for each LOD buffer, after binding its own program and VAO
    (`GlDhTerrainShaderProgram.java:393-396`, in
    `common/src/main/java/com/seibel/distanthorizons/common/render/openGl/terrain/`).
  - On that event (`LodRendererEvents.java:212-234`), Iris calls `glUseProgram` on the matching Iris program and sets
    `modelOffset`.
  - DH's VAO stays bound, so the pack program reads DH's vertex buffer through locations 0 to 2.
  - Only the generic program is injected as a proper override:
    `OverrideInjector.INSTANCE.bind(IDhApiGenericObjectShaderProgram.class, ...)` (`LodRendererEvents.java:247-250`).

### 1.3 Vertex input

**The format.** DH's OpenGL terrain VAO (`GlDhTerrainShaderProgram.java:153-178`) and quad builder
(`LodQuadBuilder.java:387-520`) define a single 16-byte vertex. The quad builder and `ColumnBox` below are in
`coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/dataObjects/render/bufferBuilding/`.

- byte order is little-endian;
- vertices are drawn as indexed quads, 6 indices for every 4 vertices;
- positions are relative to one origin per buffer.

| Location | Iris input | GL type | Bytes |
|---|---|---|---|
| 0 | `uvec4 vPosition` | `GL_UNSIGNED_SHORT`, integer | 0-7 |
| 1 | `vec4 iris_color` | `GL_UNSIGNED_BYTE`, normalised | 8-11 |
| 2 | `uvec4 irisExtra` | `GL_UNSIGNED_BYTE`, integer | 12-15 |

**What each location holds.**

- **`vPosition.xyz`:** whole blocks from the buffer's minimum corner.
- **`vPosition.w`, the "meta" short:**
  - bits 0-3: sky light;
  - bits 4-7: block light;
  - bits 8-13: the "micro offset", two bits per axis in the layout `0b00zzyyxx` (01 means +, 11 means −).
- **`iris_color`:** RGBA.
  - DH bakes in the block colour, the biome tint and Minecraft's per-face shade. The shade comes from
    `level.cardinalLighting().byFace(dir)` with the default `lodShading` setting
    (`ColumnBox.java:282`;
    `common/src/main/java/com/seibel/distanthorizons/common/wrappers/world/ClientLevelWrapper.java:764-779`).
  - Grass block sides can be coloured as dirt (`LodQuadBuilder.java:430-456`).
- **`irisExtra.x`:** the material id (`DH_BLOCK_*`).
- **`irisExtra.y`:** the face index 0-5, in the order down, up, north, south, west, east.
- **`irisExtra.z` and `irisExtra.w`:** the low and high bytes of DH's texture tile id; 0 means flat colour.

**What `_vert_init()` derives** (`DHTerrainTransformer.java:148-168`):

- **Position:** `vPosition.xyz` plus `mircoOffset` along x and z, following the sign bits. `mircoOffset` is a uniform
  set to 0.01 blocks. The y offset is decoded but never applied.
- **Normal:** `irisNormals[irisExtra.y]`.
- **Material:** `dhMaterialId = int(irisExtra.x)`.
- **Lightmap coordinate:** `((block + 0.5) / 16, (sky + 0.5) / 16)`. The pack reads it as `gl_MultiTexCoord1.xy`,
  block light first, as in OptiFine.
- **Colour:** `iris_color`.
- **Passed to the fragment stage:** `iris_vBlockPos` (the raw position) and `flat uvec2 iris_TexId` (tile id and
  face).

**What a pack does not get:** texture coordinates, `mc_Entity`, `mc_midTexCoord`, `at_tangent` or `at_midBlock`. A
pack sees each LOD face as an axis-aligned box face with a flat colour and a material class.

**Size limit.** Positions are 16-bit, so one buffer spans at most 65,535 blocks on each axis. Diffusicraft's tallest
size, Ultra at 32,736 blocks, fits in a single buffer.

### 1.4 Uniforms, samplers and macros

**Visible to every pack program** (gbuffers, deferred, composite, final), because they are registered with the common
uniforms and the render-target samplers:

- `dhNearPlane` (float): `CommonUniforms.java:185` calls `DHCompatInternal.getNearPlane()` (`:127-134`), which returns
  `DhApi.Delayed.renderProxy.getNearClipPlaneDistanceInBlocks(partialTick)`.
- `dhFarPlane` (float): `CommonUniforms.java:184` calls `getFarPlane()` (`:115-125`), which returns
  `(lodChunkRadius * 16 + 512) * sqrt(2)`.
- `dhRenderDistance` (int): `CommonUniforms.java:186` calls `getRenderDistance()` (`:102-113`).
  - With DH present it is DH's LOD radius **in blocks**, `chunkRenderDistance * 16`.
  - Without DH it is the vanilla radius in chunks.
- `dhProjection`, `dhProjectionInverse` and `dhPreviousProjection` (mat4): registered in
  `uniforms/MatrixUniforms.java:24,42-47`, filled by `DHCompat.getProjection()` (`DHCompat.java:48-59`).
  - The value is `new Matrix4f().setPerspective(fovy, m11 / m00, dhNearPlane, dhFarPlane)`.
  - The field of view and aspect ratio come from the captured `gbufferProjection`, with its reversed Z undone.
  - The result is forward Z, clip range −1..1.
- `dhDepthTex` and its alias `dhDepthTex0` (sampler2D): `samplers/IrisSamplers.java:110`, via
  `DHCompat.getDepthTex()`. This is DH's own depth texture, with every LOD in it, translucent ones included.
- `dhDepthTex1` (sampler2D): `IrisSamplers.java:111`, via `getDepthTexNoTranslucent()`. This is a copy of DH's depth
  taken before DH's translucent pass (`DHCompatInternal.copyTranslucents`, `:260-269`).

**Set only in `dh_*` programs,** by `IrisLodRenderProgram.fillUniformData` (`:224-259`) and `setModelPos`
(`:265-267`):

- `iris_ProjectionMatrix` (which replaces `gl_ProjectionMatrix`) and its inverse:
  - in the main pass, the same perspective as `dhProjection`, built from the near and far planes of DH's current
    render-pass event (`LodRendererEvents.java:315-319`);
  - in `dh_shadow`, `MatrixUtils.toMinusOneToOne(ShadowRenderer.PROJECTION)`.
- `iris_ModelViewMatrix` (which replaces `gl_ModelViewMatrix`), its inverse, and `iris_NormalMatrix`:
  - in the main pass, `gbufferModelView`, which is the camera rotation only;
  - in `dh_shadow`, `ShadowRenderer.MODELVIEW`.
- `modelOffset` (vec3), set per buffer: the buffer's minimum corner minus the camera position.
- `mircoOffset` (float, spelled that way): 0.01.
- `worldYOffset` (float): always −1000. The transformer never uses it.
- `clipDistance` (float): DH's near clip distance.
- `dhBlockAtlas` (sampler2D on unit 0): `renderProxy.getDhBlockRatioAtlasTextureGlId()`.

**The rest of the gbuffer set** comes through `addGbufferOrShadowSamplers`:

- the `colortex*` targets, `depthtex0/1/2` and `noisetex`;
- `shadowtex*` and `shadowcolor*`, when the program uses them;
- every common and custom uniform: `gbufferModelView`, `cameraPosition`, `far`, the fog colour and the rest.

`gtexture` exists, but it is not DH's atlas, because the program is built with `hasTexture=false`. The lightmap
sampler is bound (`hasLightmap=true`, `IrisLodRenderProgram.java:121`).

**Macros** (`StandardMacros.java:65-97`): `DISTANT_HORIZONS`, `DISTANT_HORIZONS_TEXTURES`, and the material ids:

| Id | Name | Id | Name |
|---|---|---|---|
| 0 | `DH_BLOCK_UNKNOWN` | 8 | `DH_BLOCK_SNOW` |
| 1 | `DH_BLOCK_LEAVES` | 9 | `DH_BLOCK_SAND` |
| 2 | `DH_BLOCK_STONE` | 10 | `DH_BLOCK_TERRACOTTA` |
| 3 | `DH_BLOCK_WOOD` | 11 | `DH_BLOCK_NETHER_STONE` |
| 4 | `DH_BLOCK_METAL` | 12 | `DH_BLOCK_WATER` |
| 5 | `DH_BLOCK_DIRT` | 13 | `DH_BLOCK_GRASS` |
| 6 | `DH_BLOCK_LAVA` | 14 | `DH_BLOCK_AIR` |
| 7 | `DH_BLOCK_DEEPSLATE` | 15 | `DH_BLOCK_ILLUMINATED` |

**A unit slip in the shadow range.**

- When the pack leaves the shadow near and far planes at −1, the shadow ortho projection and `shadowModelView` take
  them from `±DHCompat.getRenderDistance() * 16` (`MatrixUniforms.java:25-32`,
  `uniforms/IrisInternalUniforms.java:70-80`, `shadows/ShadowRenderer.java:436`).
- With DH present, `getRenderDistance()` is already in blocks, so the range comes out 16 times DH's radius.
- It is harmless, since it only widens the shadow depth range, but a replacement should give the same value to stay
  identical.

### 1.5 Where DH draws in Iris's frame

With a pack on, Minecraft 26.3, Sodium 0.9.x and DH 3.3.4 draw a frame in this order. The sources are Iris's
`mixin/MixinLevelRenderer.java`, `pipeline/IrisRenderingPipeline.java` and `shadows/ShadowRenderer.java`, and DH's
`fabric/src/main/java/com/seibel/distanthorizons/fabric/mixins/client/MixinChunkSectionsToRender.java:148-196`.

1. **Head of `LevelRenderer.render`** (`MixinLevelRenderer.java:106-134`):
   - `DHCompat.checkFrame()`;
   - Iris captures `gbufferModelView` and the un-reversed `gbufferProjection`;
   - `beginLevelRendering()` runs the `begin` passes (`IrisRenderingPipeline.java:1020`).
2. **`iris_setup`.** A frame-graph pass after Minecraft's clear pass calls `onBeginClear()`
   (`MixinLevelRenderer.java:141-151`).
3. **The shadow pass,** run immediately before `LevelRenderer.addMainPass` (`MixinLevelRenderer.java:186-193`).
   `renderShadows()` runs the whole pass, then the `prepare` passes (`IrisRenderingPipeline.java:1026-1032`).
   - **Inside `ShadowRenderer`**, in order:
     - it builds a `net.caffeinemc.mods.sodium.client.util.SodiumChunkSection` and calls `renderGroup(OPAQUE, ...)`
       with culling off (`ShadowRenderer.java:516-530`);
     - it runs the API shadow callbacks (`:532-537`);
     - it draws entities and block entities;
     - it calls `renderGroup(TRANSLUCENT, ...)` (`:618-622`).
   - **DH's hook.** DH injects at the head of `SodiumChunkSection.renderGroup` with `order = 800`, so Sodium cannot
     cancel the call before DH sees it. Whenever a pack is in use it calls `ClientApi.renderLods()` before the OPAQUE
     group and `renderDeferredLodsForShaders()` before the TRANSLUCENT group.
   - **What Iris does during the shadow pass** (`LodRendererEvents.java:236-263`, `:299-307`, `:330-336`):
     - binds `dh_shadow`;
     - binds the shadow framebuffer;
     - hands DH its shadow frustum for culling.
   - **Without `dh_shadow`,** or with `dhShadow.enabled=false`, Iris cancels DH's shadow-pass render
     (`LodRendererEvents.java:155-175`).
4. **Main pass, `executeSolid`.**
   - Iris wraps each `ChunkSectionsToRender.renderGroup` call in a phase, for example `TERRAIN_SOLID`
     (`MixinLevelRenderer.java:217-222`).
   - DH's same hook draws the opaque LODs with `dh_terrain` into Iris's G-buffer, using DH's own depth texture.
   - This happens immediately before Sodium's opaque terrain.
5. **Entities, block entities and outlines.**
6. **The translucent step.** Iris wraps `executeClassicTransparency` (`MixinLevelRenderer.java:266-279`):
   - `beginHand()`;
   - the solid hand;
   - `beginTranslucents()`, which copies the pre-translucent depth to `depthtex1` and runs the `deferred` passes
     (`IrisRenderingPipeline.java:1058-1084`);
   - Sodium's TRANSLUCENT group. At its head DH copies its depth into `dhDepthTex1`, then draws its translucent LODs
     (water) with `dh_water` into the `flippedAfterTranslucent` targets (`LodRendererEvents.java:326-357`).
7. **The rest of the frame:**
   - weather and the world border;
   - the translucent hand;
   - `finalizeLevelRendering()`, which runs the `composite` and `final` passes (`MixinLevelRenderer.java:165-185`,
     `IrisRenderingPipeline.java:1086-1091`).

**What this means for each kind of LOD geometry:**

- **Opaque LODs** are G-buffer geometry drawn before `deferred`, so they get the pack's deferred lighting, SSAO and
  the rest.
- **LOD water** is forward-shaded geometry, drawn after `deferred` with the pack's water program.
- **LOD shadows** go into the same `shadowtex0`/`shadowtex1` as vanilla terrain.
- **`dh_generic` objects** (clouds, beacon beams, API boxes) draw in DH's generic pass, into `dhGenericFramebuffer`.
  Iris cancels DH's cloud object when the pack sets `dhClouds=off`, or leaves `dhClouds` unset and sets `clouds=off`
  (`DHCompatInternal.java:289-291`, `LodRendererEvents.java:114-137`).

### 1.6 Depth buffers

- **DH's depth texture.**
  - DH renders into its own `DEPTH32F` texture, never into Minecraft's
    (`common/src/main/java/com/seibel/distanthorizons/common/render/openGl/GlDhMetaRenderer.java:368-377`).
  - Iris attaches that texture to each of its DH framebuffers with `GlFramebuffer.addDepthAttachmentBypass`
    (`gl/framebuffer/GlFramebuffer.java:42-48`). It does this in `DHCompatInternal.reconnectDHTextures` (`:161-176`),
    which runs on DH's `DhApiBeforeTextureClearEvent` (`LodRendererEvents.java:99-112`).
  - In the opaque pass Iris clears only that depth texture and cancels DH's own colour-and-depth clear, so the
    G-buffer colours drawn by `begin` and `prepare` survive (`LodRendererEvents.java:194-210`).
- **Depth direction.**
  - In the main pass `IrisApi.isReverseZDuringShaders()` returns true (`apiimpl/IrisApiV0Impl.java:97-99`), so DH
    uses reversed Z: depth test `GREATER`, clear value 0
    (`common/src/main/java/com/seibel/distanthorizons/common/render/openGl/GlDhRenderApiDefinition.java:39-53`,
    `GlDhMetaRenderer.java:200-209`).
  - In the shadow pass it returns false, and DH uses forward Z with `LESS`.
  - Iris's position remap ([1.2](#12-how-iris-compiles-the-dh_-programs-and-hands-them-to-dh)) makes the pack's
    forward-Z output match in both cases.
- **The two LOD depth samplers.**
  - `dhDepthTex0` is the final LOD depth.
  - `dhDepthTex1` is LOD depth without the LOD translucents. `copyTranslucents` refreshes it, with `glCopyTexImage2D`
    the first time and `DepthCopyStrategy.fastest` after that (`DHCompatInternal.java:260-269`).
  - Minecraft's `depthtex0/1/2` never contain LODs.
- **Combining the two depths is the pack's job.**
  - Iris itself never merges them.
  - The pack's deferred and composite programs sample both `depthtex*` and `dhDepthTex*`, and decide per pixel which
    surface is nearer.
  - They interpret the first with `gbufferProjection` and the second with `dhProjection`
    ([section 3](#3-bliss-and-complementary-unbound)).
  - DH depth is never depth-tested against vanilla depth, so the packs also discard LOD fragments near the player
    themselves ([3.4](#34-blending-lods-into-vanilla-terrain)).

### 1.7 How DH's framebuffer is overridden

**The override.**

- Every time DH binds its framebuffer, it looks up an `IDhApiFramebuffer` override with
  `OverrideInjector.INSTANCE.get(IDhApiFramebuffer.class)`, and uses it when `overrideThisFrame()` returns true
  (`GlDhMetaRenderer.java:176-182`).
- On `DhApiBeforeRenderSetupEvent` (`LodRendererEvents.java:236-263`), Iris unbinds last frame's overrides, then binds
  a `DhFrameBufferWrapper` (`compat/dh/DhFrameBufferWrapper.java`):
  - around the terrain framebuffer in the main pass;
  - around the shadow framebuffer during the shadow pass, together with `ShadowRenderer.FRUSTUM` as an
    `IDhApiShadowCullingFrustum`.
- The wrapper ignores DH's attach and destroy calls; it only binds.

**The framebuffers,** all built from Iris's render targets:

- **`dhTerrainFramebuffer` and `dhGenericFramebuffer`.**
  - Built by `IrisRenderingPipeline.createDHFramebuffer(source, false)` (`:1342-1345`), which calls
    `RenderTargets.createDHFramebuffer(flippedAfterPrepare, drawBuffers)` (`targets/RenderTargets.java:298-306`).
  - Colour: the program's `DRAWBUFFERS`/`RENDERTARGETS`, on the main or the alternate side according to the flip
    state.
  - Depth: none until DH's texture is attached.
- **`dhWaterFramebuffer`.** The same, built with `flippedAfterTranslucent`. Iris binds it itself in the translucent
  pass (`LodRendererEvents.java:353`).
- **`dhShadowFramebuffer`.** Built by `ShadowRenderTargets.createDHFramebuffer(ImmutableSet.of(), {0, 1})`
  (`shadows/ShadowRenderTargets.java:236-248`).
  - Colour: `shadowcolor0` and `shadowcolor1`.
  - Depth: the shadow map's main depth, so LOD shadows land in `shadowtex0`.

**DH also patches Iris.**
`fabric/src/main/java/com/seibel/distanthorizons/fabric/mixins/client/MixinIrisFrameBuffer.java` redirects the
`framebufferTexture2D` call in `GlFramebuffer.addDepthAttachment` so that the old depth and stencil attachments are
detached first.

### 1.8 Other hooks

- **DH settings Iris overrides while a pack is on** (`LodRendererEvents.java:84-97`, `265-283`):
  - DH fog off;
  - DH SSAO off;
  - `setDeferTransparentRendering(true)`, so water is drawn in the separate translucent call.
- **DH steps that are skipped:**
  - Iris cancels DH's "apply to Minecraft" composite (`:365-382`), so DH never writes its own colour into the frame;
  - DH itself skips its far fade and anti-aliasing when a pack is on
    (`coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/render/renderer/LodRenderer.java:278-305`).
- **Shadow frustums.** Four Iris mixins make Iris's shadow frustums implement `IDhApiShadowCullingFrustum`, so DH can
  cull with them: `compat/dh/mixin/MixinAdvancedShadowCullingFrustum.java`, `MixinBoxCullingFrustum`,
  `MixinCullEverythingFrustum` and `MixinNonCullingFrustum`.
- **DH's clip planes.**
  - With a pack on, DH places its near plane at a fixed overdraw of 0.2 of the vanilla radius, then reduces it for the
    field of view
    (`coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/util/RenderUtil.java:132-150`).
  - When Iris is installed, DH puts its far plane exactly where Iris's `dhFarPlane` is (`RenderUtil.java:284-305`,
    citing [IrisShaders/Iris#2534](https://github.com/IrisShaders/Iris/issues/2534)).
  - As a result, `dhProjection` and the matrix DH draws with agree.

## 2. Iris and Voxy

Voxy is All Rights Reserved. This section describes its design and names its identifiers; it quotes none of its code.
Paths are relative to the Voxy repository at `5bf33350` (branch `263`):

- `C/` = `src/main/java/me/cortex/voxy/client/`
- `CM/` = `src/main/java/me/cortex/voxy/common/`
- `S/` = `src/main/resources/assets/voxy/shaders/`

### 2.1 Where the code lives

**In Voxy, which patches Iris.**

- Iris has no Voxy integration and no API aimed at Voxy.
- Its one concession: `DepthTransformer` lists `vxDepthTexOpaque` and `vxDepthTexTrans` among the samplers whose reads
  it inverts (`pipeline/transform/transformer/DepthTransformer.java:35-36`, commit `4a9c8d1c6`, 2026-09-12).
- Everything else is Voxy mixins and accessors into Iris internals. They apply only when the `iris` mod is loaded
  (`CM/MixinConfig.java:22-33`, `src/main/resources/client.voxy.mixins.json`).

**The mixins** (all in `C/mixin/iris/`):

- `MixinShaderPackSourceNames`, into `ShaderPackSourceNames.findPotentialStarts()`: adds `voxy.json` and the two
  `voxy_*.glsl` files to Iris's start files.
- `MixinProgramSet`, into `ProgramSet.<init>` before `locateDirectives()`: parses the pack's Voxy patch for each
  dimension.
- `MixinStandardMacros`, into `StandardMacros.createStandardEnvironmentDefines()`: defines `VOXY` = 3.
- `MixinMatrixUniforms`, at the head of `CommonUniforms.addNonDynamicUniforms`: registers the `vx*` uniforms.
- `MixinIrisSamplers`, at the tail of `IrisSamplers.addRenderTargetSamplers`: registers `vxDepthTexOpaque` and
  `vxDepthTexTrans`.
- `MixinIrisRenderingPipeline`, into `IrisRenderingPipeline.<init>` and `beginLevelRendering()`: builds Voxy's
  pipeline data and applies the frame's camera.
- `MixinIris`, into the `getProgramSet` call in `Iris.createPipeline`: turns a bad Voxy patch into Iris's vanilla
  fallback.
- Accessors for `IrisRenderingPipeline.renderTargets`, `CustomUniforms.locationMap`,
  `DepthTransformer.invertDepthReads` and `DepthTransformer.DEPTH_SAMPLERS`.

Voxy also calls internal Iris classes directly: `RenderTargets`, `CustomUniforms`, `CommonUniforms`,
`addGbufferOrShadowSamplers` and `WorldRenderingSettings.getBlockStateIds()`. All of these calls are in
`C/iris/IrisVoxyRenderPipelineData.java`.

**Detection.**

- Iris is present when `isModLoaded("iris")` is true (`C/core/util/IrisUtil.java:26`).
- A pack is active when `Iris.getCurrentPack()` is present (`:61-66`).
- The pack supports Voxy only if it ships `voxy.json`; otherwise Voxy draws with its own unshaded pipeline
  (`C/core/RenderPipelineFactory.java:14-46`).
- The shadow pass is detected through `ShadowRenderer.ACTIVE` (`IrisUtil.java:30-36`).

**Versions.**

- `src/main/resources/fabric.mod.json:46-49` declares `"breaks": {"iris": ">=1.12-"}`. That refuses Iris 1.12 and its
  snapshots. There is no minimum version.
- Voxy compiles against `iris:1.11.7+26.3-fabric` (`gradle.properties:27`).
- It pins Minecraft 26.3 and an exact Sodium build.
- Every Iris update can break it, which is why it locks out the next minor version.

**On failure** Voxy turns the shader pack off (`RenderPipelineFactory.java:39-42`,
`C/mixin/minecraft/MixinLevelRenderer.java:96-101`).

### 2.2 What a pack provides

**Files.**

- They live in each dimension folder, and Iris preprocesses them like any start file, so `#include`, pack options and
  the `VOXY` macro all work inside them.
- `voxy.json` is required.
- `voxy_opaque.glsl` and `voxy_translucent.glsl` are optional. When present they replace the JSON's `opaquePatchData`
  and `translucentPatchData` (`C/iris/IrisShaderPatch.java:322-379`).

**`voxy.json` keys.** Gson parses the file in lenient mode (`IrisShaderPatch.java:166-213`).

- `version`: must be 1.
- `opaqueDrawBuffers` and `translucentDrawBuffers`: colortex indices. Entry *i* is written by `layout(location = i)`.
- `uniforms`: Iris uniform names, which Voxy packs into a std140 block.
- `samplers`: Iris sampler names with their GLSL types.
- `opaquePatchData` and `translucentPatchData`: the GLSL snippets.
- `ssbos`.
- `blending`: per draw-buffer slot, translucent pass only.
- `taaOffset`: a function body that Voxy adds to its vertex and culling shaders.
- `excludeLodsFromVanillaDepth`, `renderScale`, `useViewportDims`, `skipShaderDepthHackFix`, `useDynamicFarPlane`.

**The patch is a fragment snippet, not a whole program.**

- Voxy appends the pack's text after its own LOD fragment shader, with `PATCHED_SHADER` defined
  (`C/core/IrisVoxyRenderPipeline.java:235-275`).
- The pack implements one function, `voxy_emitFragment(VoxyFragmentParameters)`, and declares its own outputs.
- Before calling it, Voxy has already done the bounds test, the discard against vanilla sections and the alpha test
  (`S/lod/gl46/quads.frag:147-186`).
- The only vertex-stage hook is the TAA offset.

**What the pack receives.** The struct's fields (`quads.frag:80-93`):

- `sampledColour`: sampled from Voxy's own baked atlas of block models;
- `tile`;
- `uv`;
- `face`: 0-5, in Minecraft's direction order;
- `modelId`;
- `lightMap`: block light and sky light, as lightmap UVs;
- `tinting`: the biome tint;
- `customId`: the pack's `block.properties` id, or 0 when the block is unmapped. Voxy reads it from Iris's block-state
  map (`C/core/IrisVoxyRenderPipeline.java:83-85`).

Packs rebuild positions from `gl_FragCoord` and the inverse matrices. No normal vector, vanilla UV, tangent or entity
id is passed.

**Reversed Z.** Voxy rewrites the pack's text for reversed Z the same way Iris does (`IrisShaderPatch.java:406-430`).

### 2.3 Uniforms and samplers

**Uniforms.** Voxy registers them in Iris's custom-uniform holder, so they are visible in every pack program and in
`shaders.properties` expressions (`C/iris/VoxyUniforms.java:64-97`). All update once per frame:

- `vxRenderDistance`: int, in chunks; default 512.
- `vxNearPlane`: 8 or 16 blocks.
- `vxFarPlane`: 48,000 blocks, or computed with `useDynamicFarPlane`.
- `vxProj`, `vxProjInv`, `vxProjPrev`: the vanilla projection, view bobbing included, with Voxy's near and far planes
  and with reversed Z undone. Forward Z, −1..1.
- `vxModelView`, `vxModelViewInv`, `vxModelViewPrev`: the camera rotation only.
- `vxViewProj`, `vxViewProjInv`, `vxViewProjPrev`: the product of the two.

**Samplers** (`C/iris/VoxySamplers.java:9-54`). They are added to every program that gets render-target samplers, when
the pack has a Voxy patch:

- `vxDepthTexOpaque`: the depth of Voxy's opaque framebuffer, `D24S8`.
- `vxDepthTexTrans`: the depth after the translucent LODs.

**Inside the patch:**

- the uniforms the pack asked for arrive through a UBO at binding 7;
- its samplers are on texture units 6 and up;
- `gtexture` is not usable, and there is no vanilla block atlas.

### 2.4 Where Voxy draws, and depth

**When it draws: in the main pass, during Sodium's opaque group.**

- Voxy forces Sodium's CUTOUT terrain pass to run. Right before `ShaderChunkRenderer.end` of that pass, it runs its
  whole pipeline: opaque LODs, then translucent LODs (`C/mixin/sodium/MixinDefaultChunkRenderer.java:51-80`).
- This happens inside Iris's `TERRAIN_SOLID` phase.
- Voxy uses neither Iris's programs nor its phases. It binds its own framebuffers:
  - colour attachments: Iris's colortex textures for `opaqueDrawBuffers` and `translucentDrawBuffers`, on the
    `flippedAfterPrepare` side;
  - depth-stencil: its own.
- **So translucent LODs draw before the pack's deferred passes,** unlike DH's water.

**How it avoids overlapping vanilla terrain.**

- A fullscreen pass stencils out every pixel where vanilla depth is not far, so LODs only fill what would otherwise be
  sky (`C/core/AbstractRenderPipeline.java:147-178`).
- Sodium's visible sections are rasterised into a depth buffer, and LOD fragments nearer than it are discarded
  (`C/core/rendering/bounding/BoundRenderer.java`, `quads.frag:159-163`).
- The near plane is 8 or 16 blocks.
- Voxy never stops Sodium from drawing a section.

**Depth.**

- After the opaque LODs, Voxy resets its depth to far under every vanilla pixel, so `vxDepthTexOpaque` holds only
  LODs (`C/core/IrisVoxyRenderPipeline.java:140-151`).
- Unless the pack sets `excludeLodsFromVanillaDepth`, a final pass reprojects LOD depth into the vanilla projection
  and writes it into `depthtex0` before Iris takes its copies, so it also lands in `depthtex1/2`
  (`IrisVoxyRenderPipeline.java:169-189`). Complementary sets the flag.

**Shadows: none.** `VoxyRenderSystem.getViewport()` returns null while Iris renders shadows
(`C/core/VoxyRenderSystem.java:525-530`). LODs neither cast shadows nor get drawn into the shadow map.

### 2.5 Limits

- **Height.** Heights are packed into 8 signed bits counting 32-block sections, which gives about y −4096 to 4095
  (`CM/world/WorldEngine.java:92-105`, `S/lod/pos_util.glsl:9`).
- **Render distance:** up to 2,048 chunks.
- **OpenGL.** Its LOD shaders are `#version 460 core`. They need `ARB_indirect_parameters` and GL 4.5 direct state
  access (`C/core/gl/Capabilities.java:57-116`).
- **Vulkan.** Voxy does not run on Minecraft's Vulkan backend. It makes the game fall back to OpenGL
  (`C/mixin/minecraft/session/MixinMinecraft.java:19-25`).

## 3. Bliss and Complementary Unbound

Paths are relative to each pack's `shaders/` folder:

- `CU/`: Complementary Unbound r5.9.3;
- `Bliss/`: the Bliss v2.1.2 release, identical to the git branch `Stable` (`81e403ed`) apart from a `template/`
  folder;
- `BlissU/`: the git branch `Unstable` at `8132c57f` (2026-09-21).

**How the uniform lists were checked.** Each program's includes were inlined with Iris's standard macros. The program
was then compiled offline with `glslangValidator`, and the uniforms reachable from `main` were listed, at the pack's
default settings. Other settings can pull in more.

### 3.1 Programs shipped

| Program | CU r5.9.3 | Bliss 2.1.2 | Bliss `Unstable` |
|---|---|---|---|
| `dh_terrain`, `dh_water` | world0, world-1, world1 | world0, world-1, world1 | same |
| `dh_shadow` | none | world0 | world0 |
| `dh_generic` | none (falls back) | none (falls back) | world0 |
| Voxy files | all three dimensions | **none** | all dimensions |

**Complementary.**

- Each `world*/dh_*.{vsh,fsh}` is a `#version 130` wrapper. It defines the stage, the dimension and `DH_TERRAIN` or
  `DH_WATER`, then includes `program/dh_terrain.glsl` or `program/dh_water.glsl`.
- Its `program/voxy.json` (`CU/program/voxy.json:2-122`):
  - sets `excludeLodsFromVanillaDepth: true`, `opaqueDrawBuffers [0, 6]`, `translucentDrawBuffers [0]` and a
    `taaOffset`;
  - asks for about 50 uniforms;
  - asks for the samplers `gaux2`, `gaux4`, `colortex13/18/19`, `depthtex1`, `vxDepthTex*`, `noisetex`,
    `shadowtex0/1` and `shadowcolor0`.

**Bliss.**

- `world*/dh_terrain` and `dh_water` are `#version 330 compatibility` wrappers around `dimensions/DH_solid.*` and
  `dimensions/DH_translucent.*`.
- `world0/dh_shadow` is self-contained `#version 120` code.
- **Bliss 2.1.2 has no Voxy support at all:** no Voxy files and no `VOXY` or `vx*` reference anywhere. Under Bliss,
  Voxy falls back to its own unshaded renderer.
- Voxy support exists only on `Unstable`: `BlissU/dimensions/voxy.json`, the `voxy_*.glsl` files, and a `deferred3`
  pass that merges Voxy's forward-lit translucents.

### 3.2 How they detect a LOD mod

**Complementary.**

- It tests `#ifdef DISTANT_HORIZONS` and `#ifdef VOXY` in GLSL.
- Inside its LOD programs it uses its own macros: `DH_TERRAIN`, `DH_WATER`, `VOXY_PATCH`, `VOXY_OPAQUE` and
  `VOXY_TRANSLUCENT` (`CU/lib/common.glsl:33,615,641`).
- Its `renderDistance` becomes `dhRenderDistance` with DH, `vxRenderDistance * 16 - 256` with Voxy, and `far`
  otherwise (`CU/lib/common.glsl:732-738`).
- Its DH properties sit under `#ifdef DISTANT_HORIZONS` (`CU/shaders.properties:138-143`).
- Both mods at once shows an error on screen (`CU/program/final.glsl:42-46`).

**Bliss.**

- It tests `#ifdef DISTANT_HORIZONS`. Its `DISTANT_HORIZONS_SUPPORT` option can `#undef` that
  (`Bliss/lib/settings.glsl:211-214`).
- `Bliss/shaders.properties:50-75` turns the dh programs on only `#if defined DISTANT_HORIZONS && defined IS_IRIS`.
- `Unstable` adds `lib/macro_lod_mod.glsl`, which maps `LOD_DEPTHTEX0/1` and `LOD_PROJECTION*` to either the DH or the
  Voxy names, and makes having both mods a compile error.

**Neither pack checks which mod defined the macro.** A defined `DISTANT_HORIZONS` plus the DH uniforms and samplers
is all either pack looks for.

### 3.3 What each program reads

**Complementary `dh_terrain`** (all dimensions):

- **Vertex inputs:** `gl_Vertex`, `gl_Color`, `gl_Normal`, `gl_MultiTexCoord1` and `dhMaterialId`. The lightmap goes
  through `gl_TextureMatrix[1]` and is then remapped as `(lm - 1/32) * 1.06667`
  (`CU/lib/util/commonFunctions.glsl:15-18`).
- **DH uniforms:** `dhProjection`, `dhProjectionInverse` and `dhRenderDistance`.
- **Matrices:** `gbufferModelView` and its inverse, `gl_ModelViewMatrix`, `gl_NormalMatrix`.
- **Camera and screen:** `cameraPosition` (also its `Int`, `Fract` and previous variants), `far`, `viewWidth`,
  `viewHeight`, `frameCounter`, `framemod8`.
- **World state:** time, weather, the sky and fog colours, the biome floats, held items.
- **Samplers:** `noisetex` only.
- **Not read:** `dhNearPlane`, `dhFarPlane`, the shadow samplers, any atlas.
- **Output:** `DRAWBUFFERS:0`, already lit (forward shading).

**Complementary `dh_water`** reads all of that, plus `depthtex0`, `dhDepthTex`, `dhDepthTex1`, `gaux2` (the scene
colour, for reflections), `gaux4` (the water normals), `gl_Fog`, `rainStrength`, `blindness` and `darknessFactor`.

**Bliss `dh_terrain`:**

- **Vertex inputs:** `gl_Vertex`, `gl_Color`, `gl_Normal`, `gl_MultiTexCoord1` (raw) and `dhMaterialId`.
- **Matrices:** `gl_ModelViewMatrix`, `gl_ProjectionMatrix` (DH's projection), `gl_NormalMatrix`,
  `gbufferModelViewInverse`.
- **Other uniforms:** `cameraPosition`, `far`, and the custom uniforms `texelSize` and `framemod8`.
- **Samplers:** none.
- **Output:** `RENDERTARGETS:1,7,8`, the packed G-buffer (`Bliss/dimensions/DH_solid.fsh:156`).

**Bliss `dh_water`:**

- **DH uniforms:** `dhProjection`, its inverse and its previous-frame matrix, `dhNearPlane`, `dhFarPlane`,
  `dhRenderDistance`.
- **Other uniforms:** `gbufferModelView` with its inverse and its previous-frame matrix, and the camera positions.
- **Samplers:** `depthtex0`, `colortex4/5/12`, `noisetex`.
- **Output:** `RENDERTARGETS:2,7`.

**Bliss `dh_shadow`:**

- **Inputs:** `gl_Vertex`, `gl_Color` and `gl_MultiTexCoord0`. An alpha below 1 in `gl_Color` marks water, which is
  discarded.
- **Uniforms and samplers:** `shadowModelViewInverse`, `far`, `tex`.

**Voxy programs** (Complementary, and Bliss `Unstable`) read `face`, `sampledColour * tinting`, `lightMap` and
`customId` from Voxy's fragment struct. They never read `tile`, `uv` or `modelId`.

### 3.4 Blending LODs into vanilla terrain

**Near boundary.** DH has no depth test against vanilla depth, so both packs discard LOD fragments that fall inside
the vanilla radius. They measure that radius with the vanilla `far` uniform.

- **Complementary:**
  - `dh_terrain` dithers LODs in between 0.4 and 0.6 × `far`, using a cylinder distance
    (`CU/program/dh_terrain.glsl:110-114`);
  - with TAA on, vanilla terrain dithers out near `far` (`CU/program/gbuffers_terrain.glsl:317-327`);
  - `dh_water` also discards wherever `depthtex0 < 1` (`CU/program/dh_water.glsl:103`).
- **Bliss:**
  - LOD fragments closer than `far - 32` (clamped) are discarded. This is its "overdraw prevention", on by default
    (`Bliss/dimensions/DH_solid.fsh:159-170`);
  - LOD water closer than `far - 64` is discarded;
  - near `far`, vanilla water turns transparent so that the LOD water shows through
    (`Bliss/dimensions/all_translucent.fsh:845-849`);
  - `dh_shadow` culls LODs within `far`.
- **What follows for a renderer:**
  - LOD geometry must exist from about 0.4 × `far` outward, for Complementary;
  - the vanilla `far` uniform must keep meaning the vanilla radius.

**Depth combination.** Both packs read the two depth buffers per pixel.

- **Complementary:**
  - `deferred1` reads `dhDepthTex` (or `vxDepthTexTrans`) wherever `depthtex0` is 1, and rebuilds the view position
    with `dhProjectionInverse` (`CU/program/deferred1.glsl:217-224,322-391`);
  - `composite1` takes the nearer of the vanilla and LOD distances for volumetric light and reflections
    (`CU/program/composite1.glsl:113-125,206-219`);
  - TAA reprojects LOD pixels with `dhDepthTex1`, `dhProjectionInverse` and `dhPreviousProjection`
    (`CU/lib/antialiasing/taa.glsl:67-78,176-221`).
- **Bliss:**
  - `deferred1` stores a quarter-resolution linear LOD depth from `dhDepthTex`, using `dhNearPlane` and `dhFarPlane`
    (`Bliss/dimensions/deferred1.fsh:46-57`);
  - its lighting composite takes `dhDepthTex1` where vanilla is sky or the LOD is nearer, and rebuilds positions with
    `dhProjectionInverse` (`Bliss/dimensions/composite1.fsh:769-793`,
    `Bliss/lib/DistantHorizons_projections.glsl:7-33`);
  - the same swap recurs in its fog, volumetrics, TAA and depth of field.
- **TAA jitter.** Both packs jitter `dhProjection` themselves for TAA (`CU/program/dh_terrain.glsl:160-162`,
  `Bliss/dimensions/DH_solid.vsh:58-63`). The renderer must therefore hand them an unjittered projection.

**Fog.**

- **Complementary:**
  - border fog runs to `dhRenderDistance`, as `1 - exp(-3 (d / renderDistance)^4)` with LODs;
  - atmospheric fog switches to absolute distance (`CU/lib/atmospherics/fog/mainFog.glsl:15-22,89-140`).
- **Bliss:**
  - border fog fills the last third of `dhRenderDistance` (`Bliss/dimensions/composite1.fsh:1305-1323`);
  - volumetric fog marches to `max(far, dhRenderDistance)` (`Bliss/lib/overworld_fog.glsl:95-99`).

**Clouds.**

- **Complementary** sets `dhClouds=off`, and traces its volumetric clouds against LOD depth, so they intersect LOD
  terrain (`CU/program/deferred1.glsl:355-356,397-405`).
- **Bliss** sets `clouds=off`, which also stops DH's clouds. With `CLOUDS_INTERSECT_TERRAIN` its clouds stop at
  `dhRenderDistance + 512`.

**Ambient occlusion.**

- **Complementary** gives DH LODs no SSAO; Voxy LODs do get it.
- **Bliss** samples `dhDepthTex1` for SSAO and indirect light when `DH_AMBIENT_OCCLUSION` is on, which is the default
  (`Bliss/dimensions/composite.fsh:276-282,381-386`).

**Water reflections.**

- **Complementary:** `dh_water` ray-marches against `dhDepthTex` with `dhProjection`. Vanilla water falls back to
  `dhDepthTex1` where vanilla depth is empty (`CU/lib/materials/materialMethods/reflections.glsl:104-119,186-191`).
- **Bliss:** `dh_water` traces its linear LOD depth when `DH_SCREENSPACE_REFLECTIONS` is on. Vanilla water does not
  reflect LODs in the release.

**Shadows.**

- **Complementary:**
  - LODs cast none: `dhShadow.enabled = false`, and there is no `dh_shadow`;
  - LODs never sample the shadow map. Sunlight on them is approximated from sky light, as `shadowMult *= skylight^8`
    (`CU/lib/lighting/mainLighting.glsl:84-85,150,298-299`);
  - `deferred1` adds 32-step screen-space shadows, ray-marched in `dhDepthTex` (`CU/program/deferred1.glsl:116-169`).
- **Bliss:**
  - LODs cast shadows only with `DISTANT_HORIZONS_SHADOWMAP`, which is off by default
    (`Bliss/lib/settings.glsl:721-727`);
  - LOD pixels inside `shadowDistance` (128 by default) receive the regular shadow map;
  - beyond it, sunlight falls back to `min(max(sky - 0.8, 0) * 25, 1)`;
  - screen-space contact shadows march `dhDepthTex1` (`Bliss/dimensions/composite1.fsh:380-425,1000-1048,1240-1258`).

### 3.5 What they need per vertex

- **Position.**
  - Camera-relative, through `gl_Vertex`, with `gl_ModelViewMatrix = gbufferModelView`. Complementary computes
    `gbufferModelViewInverse * gl_ModelViewMatrix * gl_Vertex` (`CU/program/dh_terrain.glsl:157`).
  - Bliss snaps positions to whole blocks for its noise (`Bliss/dimensions/DH_solid.vsh:47-54`), so vertices must sit
    on integer world coordinates.
- **Normal.**
  - Axis-aligned.
  - Complementary, and Bliss's `dh_terrain`, use `gl_NormalMatrix * gl_Normal`.
  - Bliss's `dh_water` uses `gl_Normal` directly as a world-space normal.
- **Colour.**
  - In both packs, vertex RGB is the albedo: biome tint baked in, no texture.
  - Water needs alpha below 1; Bliss's `dh_shadow` uses that to drop water.
- **Lightmap.**
  - Read from `gl_MultiTexCoord1`: block light in x, sky light in y, each as `(n + 0.5) / 16`.
  - Sky light matters a great deal. It is Complementary's sunlight beyond the shadow map, and Bliss's sunlight beyond
    `shadowDistance`.
  - Block light 15 is emissive in Bliss.
- **Material id** (`dhMaterialId`).
  - Complementary (`CU/program/dh_terrain.glsl:95-103`, `CU/program/dh_water.glsl:139-141`):
    - `LEAVES`: subsurface scattering;
    - `GRASS`: smoothness;
    - `ILLUMINATED`: emission 2.5;
    - `LAVA`: emission 1.5;
    - `WATER`: water shading in `dh_water`.
  - Bliss (`Bliss/dimensions/DH_solid.vsh:70-88`, `Bliss/dimensions/DH_translucent.vsh:67-69`):
    - `ILLUMINATED`;
    - `LEAVES` and `SNOW`: subsurface scattering;
    - `GRASS` and `LEAVES`: seasonal tint;
    - `WATER`.
- **Surface detail.**
  - No pack samples `dh_sampleTexture`, `dhBlockAtlas` or `gtexture` in its dh programs.
  - Complementary adds procedural brightness noise from `noisetex`, at 4 cells a block
    (`CU/program/dh_terrain.glsl:116-119`).
  - Bliss adds DH-style hash noise, its `DH_NOISE_TEXTURE` option (`Bliss/dimensions/DH_solid.fsh:118-154`).

### 3.6 `shaders.properties` and options

- **Complementary.**
  - Under `#ifdef DISTANT_HORIZONS` (`CU/shaders.properties:138-143`):
    - `blend.dh_water=SRC_ALPHA ONE_MINUS_SRC_ALPHA ONE ONE_MINUS_SRC_ALPHA`;
    - `dhShadow.enabled = false`;
    - `dhClouds = off`.
  - Also `separateAo = true`.
  - It has no LOD settings screen.
- **Bliss.**
  - In `Bliss/shaders.properties:50-75`:
    - `dhShadow.enabled` follows `DISTANT_HORIZONS_SHADOWMAP`;
    - `program.world*/dh_terrain|dh_water.enabled`;
    - `blend.dh_terrain = off`;
    - `blend.dh_water = SRC_ALPHA ONE_MINUS_SRC_ALPHA ONE_MINUS_DST_ALPHA ONE`.
  - Also `clouds=off` and `separateAo = true`.
  - It has a "Distant Horizons" settings screen (`Bliss/shaders.properties:344-347`) with overdraw prevention, LOD
    AO, SSS, SSR, noise and the LOD shadow map.

### 3.7 Problems found offline

- Complementary's End `voxy_translucent` fails to compile offline: `texCoord` is undeclared, reached through
  `CU/lib/atmospherics/volumetricLight/volumetricLight.glsl:60`. Not confirmed in game.
- Bliss `Unstable`'s `world0/dh_shadow.vsh:11-14` redeclares `LOD_DEPTHTEX0/1`. This only matters with the LOD shadow
  map on. Not confirmed in game.

## 4. Options for our own renderer

What the renderer has to achieve, from sections 1 to 3:

- the pack's `dh_*` programs run on our geometry;
- they write the pack's G-buffer and water targets;
- they see `DISTANT_HORIZONS`, `dhNearPlane`, `dhFarPlane`, `dhRenderDistance`, `dhProjection*` and
  `dhDepthTex0/1`, all consistent with how we drew;
- they get DH's vertex inputs;
- they run at the points of the frame where DH runs.

### 4.1 (a) Present our renderer to Iris through DH's API surface

**Would Iris accept a provider that is not DH?** Structurally, yes. It checks only the mod id, never an API version.

- A Fabric `provides: ["distanthorizons"]` entry satisfies `isModLoaded`.
- Iris then links against the DH API classes in these packages:
  - `com.seibel.distanthorizons.api.*`;
  - `com.seibel.distanthorizons.coreapi.DependencyInjection.OverrideInjector`.
- We would have to supply, under DH's package names:
  - `DhApi.Delayed.configs`, with `graphics().renderingEnabled()`, `chunkRenderDistance()`, `fog().drawMode()` and
    `ambientOcclusion().enabled()`;
  - `DhApi.Delayed.renderProxy`, with `getNearClipPlaneDistanceInBlocks`, `getDhDepthTextureId`,
    `setDeferTransparentRendering` and `getDhBlockRatioAtlasTextureGlId`;
  - `DhApi.events`, firing 12 event types in DH's order with DH's parameter objects;
  - the `OverrideInjector`.

**Why it does not work in practice:**

- **The version gate.** Iris's `breaks distanthorizons <=3.2.0` applies to the provided id. A `provides` entry takes
  the providing mod's version, Diffusicraft's 0.1.0-alpha, so Iris would refuse to load. Getting around that needs a
  nested shim mod that claims to be DH 3.3.x.
- **Every other mod would be fooled too.** Voxy, both packs (which raise an error when they see both LOD mods), and
  any mod with DH integration would then call DH API methods we do not implement.
- **Real DH can never be installed beside it.**
- **Licence and project rules.** The DH API is LGPL-3.0. Shipping it breaks the project rule that the DH API is
  `compileOnly` and never bundled. Cloning its packages in MIT code mirrors another project's API surface: legally
  murky, and confusing for users.
- **Maintenance.** We would have to track two moving targets: DH's API, and the way Iris uses it. Iris changed that
  use in September 2026, when it added textures and reversed Z.

**Verdict.** Feasibility: low. Effort: medium to high. Legal and maintenance risk: high. Rejected.

### 4.2 (b) Mixin into Iris and drive the pack's dh_* programs ourselves

**What already exists, without any mixin.**

- Almost everything Iris does for DH is reachable through `public` members:
  - `TransformPatcher.patchDHTerrain`;
  - on `IrisRenderingPipeline`: `getDHTerrainShader`/`getDHWaterShader`/`getDHShadowShader`, `getCustomUniforms`,
    `getTextureMap`, `getTextureOverrides`, `addGbufferOrShadowSamplers`, the three flip snapshots,
    `createDHFramebuffer` and `createDHFramebufferShadow`;
  - `GlFramebuffer.addDepthAttachmentBypass`.
- Voxy already lives on exactly this kind of access ([2.1](#21-where-the-code-lives)).

**What does need mixins.** Only the values Iris reads from DH: the `DHCompat` getters and one `isModLoaded` call in
`StandardMacros` ([4.6](#46-recommendation-option-b-with-c-in-parallel)).

**What the pack sees.** Exactly what it sees under DH: the same programs, macros, uniforms, samplers, vertex inputs and
pass order. So both packs' existing DH code, tuned against DH, applies unchanged. That includes Bliss 2.1.2, which has
nothing else.

**Effort: medium.**

- a program wrapper of our own, doing what `IrisLodRenderProgram` does, without DH;
- about ten small mixins;
- a raw-GL draw path;
- one Sodium hook, used for the opaque and translucent groups, and one Iris API callback;
- DH's vertex format in our mesher.

**Risk.**

- **Iris internals are not API.** They changed for 26.x (the reversed-Z transformer, DH textures). Every Iris update
  needs a check, which is why Voxy locks out the next minor version.
- **Legal:** none of note. These are mixins and calls from MIT code into LGPL Iris, the same position as the existing
  `compileOnly` DH integration. We must write our own program class rather than copy `IrisLodRenderProgram`.
- **Conflicts with DH.** When DH is installed, Iris's own DH compat owns `DHCompat`, so our path must stand down.

**Verdict.** Feasibility: high. Effort: medium. Legal risk: low. Maintenance risk: medium.

### 4.3 (c) Propose a generic LOD-renderer API upstream to Iris

**Iris has been moving this way.**

- API v0.3 and v0.4 added pipeline assignment, shadow pipelines and shadow callbacks
  ([IrisShaders/Iris#3239](https://github.com/IrisShaders/Iris/pull/3239), commit `e99511039`, 2026-07-18).
- It added `isReverseZDuringShaders` for DH.
- It hard-codes Voxy's depth sampler names into its depth transformer.

**What to propose.** A provider interface in `net.irisshaders.iris.api.v0`, perhaps `IrisLodRenderer`, registered
through `IrisApi`:

- **The provider supplies:** near and far planes, render distance, projection, its depth textures (with and without
  translucents), and whether it is active.
- **Iris returns a handle to bind the pack program and framebuffer** for opaque, water, shadow and generic geometry,
  with a `setModelOffset`.
- **The vertex format** is DH's 16-byte one, documented as the contract.
- **Macros.** Iris keeps defining `DISTANT_HORIZONS` for any provider, since packs test that name, and could add a
  neutral one.
- **DH would become one provider among others,** with Iris's `DHCompat` rewritten on top of the new interface.

**Assessment.**

- **Feasibility:** depends on the maintainers.
- **Effort for us:** medium. One LGPL pull request, which is fine to contribute.
- **Risk:** it may be refused or arrive late. It would only help on Iris releases that carry it, so we would still need
  (b) for Iris 1.11.7.
- **Best done alongside (b),** with the mixins written as a shim that maps one-to-one onto the proposed interface.

### 4.4 (d) Whatever Voxy does

**What Voxy does** ([section 2](#2-iris-and-voxy)):

- it has its own fragment-snippet contract, implemented by the packs;
- it injects its uniforms and samplers into Iris with mixins;
- it draws inside Sodium's opaque pass with its own programs and framebuffers;
- it casts no shadows.

**Following it means one of two things:**

- **(d1) Implement Voxy's pack contract ourselves.** We would read the pack's `voxy.json`, splice `voxy_opaque.glsl`
  after a fragment shader of ours that fills `VoxyFragmentParameters`, define `VOXY=3`, and provide `vx*` and
  `vxDepthTex*`.
  - It works with Complementary r5.9.3 and Bliss `Unstable`, **not with the Bliss 2.1.2 release**.
  - The contract is an internal detail of an All Rights Reserved mod: a struct layout, binding indices 6, 7 and 10,
    `PATCHED_SHADER`, `SHADER_DEFINE_VERSION` 3. Voxy changes it at will.
  - Re-creating it means mirroring that mod's private interface.
  - The packs' Voxy code assumes Voxy's model atlas (`sampledColour`) and Voxy's stencil cull.
  - Defining `VOXY` also tells packs that Voxy is installed.
- **(d2) Copy only the technique,** drawing in Sodium's pass with our own contract. No pack implements a Diffusicraft
  contract, so this lights nothing.

**Verdict.** Feasibility: medium for d1, none for d2. Effort: high. Legal and maintenance risk: high. Rejected.
Revisit only if Voxy documents the contract and Bliss ships it in a release.

### 4.5 (e) An extra option: Iris's public `assignPipeline`

**The idea.** Draw LODs with a RenderPearl `RenderPipeline` assigned to `IrisProgram.TERRAIN_SOLID` and
`TRANSLUCENT`, and to `IrisShadowProgram.SHADOW`, with `registerShadowRenderCallback` for the shadow pass. It is all
public, and it works through Minecraft's own renderer.

**Why it fails for far terrain:**

- **Clipping.** The packs would treat LODs as near terrain. Vanilla's projection has its far plane at
  `max(renderDistance * 16 * 4, cloudRange * 16)` (`Camera.update`), so anything farther is clipped.
- **Fog.** The packs fog terrain out at `far`.
- **Vertex format.** Iris matches programs by the vertex format at binding 0
  (`pipeline/programs/ShaderKey.java:280-310`), so LODs would need block-atlas UVs and `mc_Entity`
  (`IrisVertexFormats.TERRAIN`).
- **No LOD treatment.** None of the packs' LOD code, no `dhDepthTex`, no near-boundary discard.

**Verdict.** Viable at most for a band of LODs inside four times the render distance. Its shadow callback is still
useful in (b).

### 4.6 Recommendation: option (b), with (c) in parallel

**Why (b).**

- It is the only option that makes both packs' releases, Bliss 2.1.2 and Complementary r5.9.3, treat our terrain
  exactly as they treat DH's.
- It needs no impersonation and no Voxy internals.
- Its mixin surface is small and narrow.

**The plan, step by step.**

#### Gating

- **Which classes load.** A `client.compat.iris` package, loaded only when `iris` is loaded and `distanthorizons` is
  not. A mixin config plugin, like `DHMixinConfigPlugin`, makes that check.
  - Lock the Iris range the way Voxy does: `>=1.11.7 <1.12`.
  - Per CLAUDE.md, keep Iris `compileOnly`.
- **When the path runs.**
  - It runs when `IrisApi.getInstance().isShaderPackInUse()` is true, and the current pipeline is an
    `IrisRenderingPipeline` whose `getDHTerrainShader()` is present.
  - Otherwise our ordinary RenderPearl renderer draws, on OpenGL and on Vulkan alike.
- **Reloads.** Toggling LOD rendering must call `Iris.reload()`, because the macros are computed when the pack loads
  (`DHCompatInternal.checkFrame` does the same for DH).

#### Mixins into Iris 1.11.7+mc26.3

| # | Target | Injection |
|---|---|---|
| 1 | `compat.dh.DHCompat.getNearPlane()F` | `@Inject(HEAD, cancellable)` |
| 2 | `DHCompat.getFarPlane()F` | `@Inject(HEAD, cancellable)` |
| 3 | `DHCompat.getRenderDistance()I` | `@Inject(HEAD, cancellable)` |
| 4 | `DHCompat.getProjection()Lorg/joml/Matrix4f;` | `@Inject(HEAD, cancellable)` |
| 5 | `DHCompat.checkFrame()Z`, `hasRenderingEnabled()Z` | `@Inject(HEAD, cancellable)` |
| 6 | `DHCompat.getDepthTex()I`, `getDepthTexNoTranslucent()I` | `@Inject(HEAD, cancellable)` |
| 7 | `DHCompat.clearPipeline()V` | `@Inject(HEAD)` |
| 8 | `gl.shader.StandardMacros.createStandardEnvironmentDefines()` | `@ModifyExpressionValue` on `isModLoaded` |

What each mixin does:

1. **`getNearPlane()`** returns our near plane. That feeds `dhNearPlane`.
2. **`getFarPlane()`** returns our far plane. That feeds `dhFarPlane`.
3. **`getRenderDistance()`** returns our LOD radius in blocks, as DH does. That feeds `dhRenderDistance` and the
   shadow range ([1.4](#14-uniforms-samplers-and-macros)).
4. **`getProjection()`** returns `setPerspective(fov, aspect, near, far)` from the captured `gbufferProjection`, the
   same matrix we draw with. That feeds `dhProjection` and its inverse and previous-frame matrix.
5. **`checkFrame()` and `hasRenderingEnabled()`** return true while our path is active. `StandardMacros` and others
   read them.
6. **`getDepthTex()` and `getDepthTexNoTranslucent()`** return our two depth textures. That feeds `dhDepthTex0` and
   `dhDepthTex1`.
7. **`clearPipeline()`** frees our programs and framebuffers. Iris calls it from `IrisRenderingPipeline.destroy()` on
   every reload (`:1240`).
8. **`StandardMacros`** makes the `isModLoaded("distanthorizons")` check at `:65` return true when our path is
   enabled, so the pack sees `DISTANT_HORIZONS`. Only define `DISTANT_HORIZONS_TEXTURES` if we also implement
   `dhBlockAtlas` and the tile ids.

All of these are thin: they replace a return value and change nothing in Iris's logic. `DHCompat` itself imports no DH
class, so it is safe to target without DH installed.

#### Our program wrapper

The wrapper is our own code; it copies none of Iris's. It calls these Iris internals:

- **Sources:** `IrisRenderingPipeline.getDHTerrainShader()`, `getDHWaterShader()` and `getDHShadowShader()`.
- **Patching:** `TransformPatcher.patchDHTerrain(name, vsh, tcs, tes, gsh, fsh, pipeline.getTextureMap(),
  pipeline.getTextureOverrides(TextureStage.GBUFFERS_AND_SHADOW))`.
- **Compiling and linking:** `GlShader`, then `glBindAttribLocation` with 0 `vPosition`, 1 `iris_color` and
  2 `irisExtra`, then link.
- **Uniforms:**
  - `ProgramUniforms.builder`;
  - `CommonUniforms.addDynamicUniforms(builder, FogMode.PER_VERTEX)`;
  - `CustomUniforms.assignTo` and `mapholderToPass`;
  - `BuiltinReplacementUniforms.addBuiltinReplacementUniforms`.
- **Samplers and images:** `ProgramSamplers.builder(id, IrisSamplers.WORLD_RESERVED_TEXTURE_UNITS)` and
  `ProgramImages.builder`, filled by `pipeline.addGbufferOrShadowSamplers(...)` with the right flip snapshot.
- **Blending:** the directives' `BlendModeOverride` and `BufferBlendOverride`.
- **Framebuffers:**
  - `createDHFramebuffer(terrainSource, false)` and `createDHFramebuffer(waterSource, true)`, each with our depth
    texture attached through `addDepthAttachmentBypass`;
  - `createDHFramebufferShadow(shadowSource)`, which already carries the shadow depth.
- **Per frame:**
  - `samplers.update()`, `uniforms.update()`, `customUniforms.push(program)` and `images.update()`;
  - then our own `iris_ProjectionMatrix`, `iris_ModelViewMatrix` and their inverses, `iris_NormalMatrix`,
    `mircoOffset` and `clipDistance`;
  - `modelOffset` for every buffer.
- **Cleanup:** `ProgramUniforms.clearActiveUniforms()`, `ProgramSamplers.clearActiveSamplers()` and
  `BlendModeOverride.restore()`.

#### Vertex format to supply

DH's 16-byte vertex, exactly as in [1.3](#13-vertex-input):

- **Location 0:** `ushort4` integer: x, y, z and meta. Meta is sky light in bits 0-3, block light in bits 4-7 and the
  micro-offset signs in bits 8-13.
- **Location 1:** `ubyte4` normalised: RGBA.
- **Location 2:** `ubyte4` integer: material id, face index, tile id low byte, tile id high byte.

The rules for filling it, so both packs behave as they do with DH:

- **Positions:** whole blocks relative to the buffer origin, which keeps Bliss's block snapping correct.
- **Normals:** one face index per quad.
- **Colour:** biome-tinted albedo in RGB.
  - DH also bakes Minecraft's per-face shade in (`cardinalLighting().byFace`). Bake the same shade so the packs, which
    were tuned against DH, look identical.
  - Water alpha must be below 1.
- **Light:**
  - real sky light, because the packs light far terrain from it;
  - block light 15 only where something truly glows.
- **Material ids:** at least `LEAVES`, `GRASS`, `SNOW`, `WATER`, `LAVA`, `ILLUMINATED`, plus `STONE`, `DIRT`, `SAND`
  and the rest as appropriate.
- **Index buffer:** one shared buffer, 6 indices for every 4 vertices.
- **Draw calls:** one per buffer, each with its own `modelOffset`.
- **A buffer spans at most 65,535 blocks on each axis.** For an Ultra world that means one buffer per region column,
  with the origin at the region's minimum corner.
- **`gl_DrawID`-based multidraw is not possible yet.** It would need `modelOffset` to come from a buffer instead of a
  uniform, which means string-patching the transformed GLSL. Leave it for later.

#### Render-pass order

This order matches DH's, and the depth conventions match [1.6](#16-depth-buffers).

0. **Frame start, nothing drawn.** Cull our regions against the camera frustum. Compute the near and far planes and
   the projection that mixins 1 to 4 will return. Iris derives `dhPreviousProjection` itself, from the value it read
   the frame before (`MatrixUniforms.Previous`).
1. **Shadow pass.** This step only runs if the pack has `dh_shadow` and its `dhShadow.enabled` is not false, so
   Complementary never needs it, and Bliss only with its LOD shadow-map option.
   - **Hook:** Iris's public `IrisApi.registerShadowRenderCallback`. It is called after the opaque shadow terrain,
     in phase `TERRAIN_CUTOUT`, with culling off (`shadows/ShadowRenderer.java:532-537`).
   - **Framebuffer:** bind the one from `createDHFramebufferShadow` and draw with raw GL.
   - **Depth:** forward Z, test `LEQUAL`, writes on, no clear (Iris has already cleared and drawn the shadow map).
   - **Matrices:** `iris_ProjectionMatrix = MatrixUtils.toMinusOneToOne(projection)` and
     `iris_ModelViewMatrix = modelView`, both from the callback's arguments; `modelOffset` is relative to the
     callback's camera position.
   - **Culling:** cull our regions with Iris's shadow frustum.
   - **What to draw:** opaque LODs; water too, if the pack wants it (Bliss drops alpha below 1).
2. **The `prepare` passes:** run by Iris.
3. **Opaque LODs: at the head of `SodiumChunkSection.renderGroup` for `ChunkSectionLayerGroup.OPAQUE`,** not during
   the shadow pass.
   - **Hook:** a `@Pseudo` mixin into `net.caffeinemc.mods.sodium.client.util.SodiumChunkSection`, with `order = 800`,
     exactly as DH does. Iris's phase is then `TERRAIN_SOLID`.
   - **Framebuffer:** bind our terrain framebuffer. Clear only its depth, to 0.
   - **State:** depth test `GEQUAL`, depth writes on, back-face culling on, blending as `blend.dh_terrain` says, and
     the viewport set to the main target's size.
   - **Program and matrices:** bind `dh_terrain`, then push `iris_ProjectionMatrix` (our forward-Z, −1..1
     perspective, the same as `dhProjection`) and `iris_ModelViewMatrix = CapturedRenderingState.INSTANCE
     .getGbufferModelView()`.
   - **Draw** each visible buffer.
   - **Restore** ([5.1](#51-blaze3d-is-now-renderpearl)).
4. **The `deferred` passes:** run by Iris inside `beginTranslucents()`.
5. **LOD water: at the head of `SodiumChunkSection.renderGroup` for `TRANSLUCENT`.**
   - **First,** copy our depth into the `dhDepthTex1` texture.
   - **Framebuffer:** bind the water framebuffer, the `flippedAfterTranslucent` side, with our depth attached.
   - **Program:** bind `dh_water`. Blending follows `blend.dh_water`; depth test and writes are on; culling is off
     (Iris does the same for DH, `LodRendererEvents.java:343`).
   - **Draw** the water buffers.
6. **The composites** read `dhDepthTex0` and `dhDepthTex1`. Nothing else is needed from us.

#### Planes and precision

- **Near plane.** Take it from the vanilla radius as DH does: a fraction of `far`, at most 0.2 of it with a pack on.
  Complementary dithers LODs in from 0.4 × `far`, Bliss from `far - 32`. The vanilla `far` uniform must stay untouched.
- **Far plane.** `dhFarPlane` should cover our LOD radius, including the corners, as `(radius + margin) * sqrt(2)`.
- **Precision of the packs' depth math.**
  - Our depth texture should be `DEPTH32F`, reversed Z, as DH's is. Reversed Z keeps the stored depth itself precise.
  - The packs, though, reconstruct positions from depth they see as forward Z, and in forward Z, depth near 1.0 is
    precise to about 6e-8.
  - Rough sizing: with a near plane of 16 blocks, a point at 100 km lands about 1.6e-4 from 1.0. That reconstructs to
    within a few tens of metres.
  - So keep the near plane as large as the dither band allows. Check Ultra-scale views in game with both packs.

#### Testing

The client game test can already run Bliss (`-PdevMods=sodium,iris,bliss-shader`, see
[COMPATIBILITY.md](../COMPATIBILITY.md#shaders)), and the test's own pack has a `dh_terrain` program
(`src/gametest/resources/diffusicraft-gametest/shaderpack/shaders/`).
Point both at our renderer, uninstall DH, and compare screenshots with DH's under each pack.

#### Upstream (c)

Open an Iris issue proposing the provider interface from
[4.3](#43-c-propose-a-generic-lod-renderer-api-upstream-to-iris). Cite Voxy's and our needs. Offer the patch as a
rewrite of `DHCompat` on top of the interface.

## 5. Rendering platform in 26.x

The Minecraft claims below come from the 26.3 client jar, read with `javap`, unless a source path is given. The jar
has sha1 `e877b6a07acd633fb3bb475002175cec036e7b87`, from
`https://piston-meta.mojang.com/v1/packages/61fff1316bd50312624a858bbb5eb18fa45208ed/26.3.json`. Sodium paths are under
`common/src/main/java/net/caffeinemc/mods/sodium/` at tag `mc26.3-0.9.2`.

### 5.1 Blaze3D is now "RenderPearl"

**The packages.**

- The GPU API moved out of `com.mojang.blaze3d` into `com.mojang.renderpearl`:
  - `api`: the public interfaces;
  - `frontend`: validating wrappers;
  - `backend.opengl` and `backend.vulkan`: the two backends.
- `com.mojang.blaze3d.opengl` no longer exists. `com.mojang.blaze3d` keeps `systems.RenderSystem`, `framegraph`,
  `pipeline` (`RenderTarget`, `MainTarget`), `vertex` and `platform`.
- The window layer is SDL3 (`lwjgl-sdl`). There is no GLFW.

**The API** in `com.mojang.renderpearl.api`:

- **Device.** `RenderSystem.getDevice()` returns a `GpuDevice`, which offers `createCommandEncoder`, `createTexture`,
  `createTextureView`, `createBuffer`, `createSampler` and `compilePipeline`.
- **Commands.** `CommandEncoder.createRenderPass(...)` opens a `RenderPass`. A pass offers:
  - `setPipeline`, `setUniform`, `pushConstants`;
  - `setVertexBuffer`, `setIndexBuffer`;
  - `draw`, `drawIndexed`, `multiDrawIndexed`, `drawIndexedIndirect`.
- **Pipelines.** `RenderPipeline.builder` takes shaders, defines, bind groups, a `DepthStencilState`, colour targets,
  vertex formats and a push-constant size.
- **Resources.** `GpuBuffer` usages include `INDIRECT_PARAMETERS`, but there is **no storage-buffer usage**. Uniform
  types are only image-sampler, uniform buffer and texel buffer.
- **One pass at a time.** `FrontendCommandEncoder` allows a single open render pass, and no clears or copies while
  one is open.

**How pipeline shaders are compiled.**

- Pipeline GLSL is Vulkan-style. `GlslCompiler` compiles it with shaderc to SPIR-V for Vulkan 1.2.
- On OpenGL, `GlPipelineRecompiler` turns it back into GLSL 3.30 with SPIRV-Cross, emitting push constants as a UBO.
- As a result, RenderPearl pipelines have no SSBOs, no image load/store and no compute. Iris compiles pack GLSL
  directly instead.

**How a mod draws its own geometry.**

- **The supported path:** a `RenderPipeline`, and draws on a `RenderPass`. Sodium 0.9.2 draws only this way.
  `RenderSystem.getCompiledPipeline` compiles a pipeline on its first use and blocks until it is done.
- **Raw OpenGL still works, but only on the OpenGL backend.** `GlDevice.<init>` creates the LWJGL capabilities, and
  the context is current on the render thread.
  - DH and Voxy both draw with raw GL.
  - Texture ids come from `GlTextureView.glId()` and `GlTexture.glId()`; buffer ids from `GlBuffer.handle()`.

**State that raw GL can corrupt.**

- `GlStateManager` caches the blend state per draw buffer, the depth state, culling, polygon offset, scissor, active
  texture, the bound 2D textures, the colour mask, and the read and write framebuffers. It skips a framebuffer bind it
  believes redundant.
- The program, buffers, VAO and viewport are not cached; calls for them go straight through.
- `GlCommandEncoder` rebinds a pipeline only when it differs from `lastPipeline`. `GlRenderPass` tracks dirty flags for
  its vertex buffers, index buffer and uniforms.
- So raw GL **inside** an open pass can leave Minecraft drawing with our program or VAO bound.
  - Under Iris with a pack active, `mixin/MixinGlCommandEncoder.java:115-126` clears `lastPipeline` on every draw, and
    Sodium sets its vertex buffer per region, which is why DH's in-pass drawing works.
- `GlDevice.<init>` sets `glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE)` when `ARB_clip_control` exists. Never change it.
- Sodium caches the viewport (`mixin/features/render/viewport/GlStateManagerMixin.java:20-38`). Iris redirects the
  viewport, scissor, framebuffer and draw buffers inside `createRenderPass`
  (Iris `mixin/MixinGlCommandEncoder.java:65-111`).

**Rule for our raw-GL path:**

- set every state that Minecraft caches through `GlStateManager`'s own setters;
- bind framebuffers with `GlStateManager._glBindFramebuffer`;
- save the previous program, VAO, framebuffers and viewport, and restore them afterwards.

### 5.2 Vulkan

**Minecraft 26.3 ships a Vulkan backend** in `com.mojang.renderpearl.backend.vulkan`: `VulkanBackend`,
`VulkanDevice` and the rest.

- **Requirements:** Vulkan 1.2 with `VK_KHR_dynamic_rendering`, `VK_KHR_push_descriptor`, `VK_KHR_synchronization2`,
  `VK_EXT_vertex_attribute_divisor` and `VK_KHR_swapchain`.
- **Libraries:** `lwjgl-vulkan`, `-vma`, `-shaderc` and `-spvc` 3.4.3 are in the version JSON.

**How the backend is chosen.**

- `net.minecraft.client.PreferredGraphicsApi` offers `DEFAULT`, `OPENGL` and `VULKAN`, saved as
  `preferredGraphicsBackend` in `options.txt`. The game also takes `--graphicsBackend`.
- `getBackendsToTry()` tries OpenGL first and then Vulkan for `DEFAULT` and `OPENGL`, and Vulkan first for `VULKAN`.
- **So OpenGL is the default, and Vulkan is the fallback.**
- `DeviceInfo.backendName()` is "OpenGL" or "Vulkan".

**Iris does not force OpenGL. It switches itself off.**

- `IrisMixinPlugin` reads `options.txt` before any mixin applies. If `preferredGraphicsBackend` contains "vulkan", it
  applies only its `VKOnly*` mixins (`mixin/IrisMixinPlugin.java:20-74`).
- Its shader screen then says "Iris cannot run when using Vulkan" and offers to switch to OpenGL
  (`compat/sodium/config/ShaderPackScreenPlaceholder.java:30,45`).
- Its options page warns that choosing Vulkan removes shaders (`compat/sodium/config/IrisConfig.java:102-122`).
- If `options.txt` is missing, Iris assumes OpenGL.

**Other mods.**

- Sodium does not force OpenGL. It picks GL multidraw, `VK_EXT_multi_draw` or indirect draws from
  `DeviceInfo.features()` (`client/gpu/device/backend/DrawBackend.java:15-29`).
- Voxy forces OpenGL by making Vulkan's device creation fail.
- DH reads the backend name.

**Consequence for us.** Shaders mean OpenGL: our Iris path is GL-only, and on Vulkan our RenderPearl renderer draws
without shaders.

### 5.3 Which OpenGL can be relied on

**Minecraft requires exactly GL 3.3 core.**

- `GlBackend` has `VERSION_MAJOR = 3` and `VERSION_MINOR = 3`, asks SDL for a forward-compatible core profile, and
  fails with "Failed to create OpenGL 3.3 context" below that.
- Every extension is optional, with a fallback.

**Extensions it uses when present:**

- `ARB_buffer_storage`: persistent mapping;
- `ARB_direct_state_access`;
- `ARB_vertex_attrib_binding`;
- `ARB_clip_control`: depth range 0..1;
- `ARB_shader_draw_parameters`;
- `ARB_draw_indirect`, `ARB_multi_draw_indirect`;
- `ARB_base_instance`;
- `KHR_debug`;
- `EXT_texture_filter_anisotropic`.

`DeviceInfo.underlyingExtensions()` and `features()` report which ones are available.

**Sodium requires nothing beyond Minecraft.** Its only GL check is for the optional no-error context
(`client/gui/SodiumConfigBuilder.java:692-697`).

**Iris has no hard minimum. Its optional features** (Iris `gl/IrisRenderSystem.java:59-75,299-305,336`):

- direct state access: GL 4.5 or the ARB extension;
- compute: GL 4.3;
- tessellation: GL 4.0;
- image load/store: GL 4.2;
- SSBOs: GL 4.4;
- per-buffer blending: GL 4.0;
- `glCopyImageSubData`: GL 4.3, otherwise a fallback.

A pack that lists such a feature in `iris.features.required` refuses to load without it.

**What to assume.**

- In practice, a 3.3 core request returns the driver's highest core version. Iris recommends checking function
  pointers rather than `GLCapabilities.OpenGL43` (Iris `gl/texture/DepthCopyStrategy.java:15-30`).
- For our raw-GL path:
  - assume only GL 3.3 core;
  - use `glDrawElementsBaseVertex` and `glMultiDrawElementsBaseVertex`;
  - use DSA, `glMultiDrawElementsIndirect`, `gl_DrawID` and `ARB_buffer_storage` only after probing for them.

**macOS (Apple GL 4.1 core)** has no `ARB_clip_control`, so depth runs −1..1 and reversed Z loses most of its
precision advantage. It also lacks buffer storage, DSA, multidraw indirect and compute. Those gaps follow from
Apple's 4.1 extension set; they were not checked on hardware.

### 5.4 Sodium 0.9.2's chunk renderer

**How it hooks Minecraft.** `mixin/core/render/world/LevelRendererMixin.java`:

- creates the `SodiumWorldRenderer` (`:102-116`);
- wraps `prepareChunkRenders` so that it returns a `SodiumChunkSection` (`:137-151`);
- overwrites `isSectionCompiledAndVisible` to call `isSectionReady` (`:157-160`).

`LevelExtractorMixin.java:137-160` moves culling and section uploads into `SodiumWorldRenderer.setupTerrain`.

**The draw path:**

1. `client/util/SodiumChunkSection.java:38-48`: `renderGroup(group, renderPass, ...)` calls
   `SodiumWorldRenderer.drawChunkLayer`.
2. `client/render/SodiumWorldRenderer.java:291-306`: OPAQUE draws the SOLID pass, then CUTOUT; TRANSLUCENT draws the
   TRANSLUCENT pass.
3. `client/render/chunk/DefaultChunkRenderer.java:110-187`:
   - sets the pipeline with `setPipeline(RenderSystem.getCompiledPipeline(...))`;
   - shares one quad index buffer;
   - sets the uniforms `u_Globals`, `u_SectionTimeInfo`, `u_LightTex` and `u_BlockTex`;
   - for each region, sets its vertex buffer, pushes 20 bytes of push constants, and draws.
4. On OpenGL each region is drawn with `RenderPass.multiDrawIndexed`, which becomes `glMultiDrawElementsBaseVertex`
   (`client/gpu/device/batch/GLDrawBatch.java:34-38`).

**What it uses.**

- **Pipeline** (`client/render/chunk/ShaderChunkRenderer.java:67-127`): quads, the default depth state, culling on.
- **Vertex format:** `CompactChunkVertex`, 20 bytes
  (`client/render/chunk/vertex/format/impl/CompactChunkVertex.java:12-18`).
- **GL state:** Sodium sets none itself.

**There is no third-party render API.**

- `net.caffeinemc.mods.sodium.api.*` has no render events, culling hooks or visibility queries.
- **Usable public classes, though not stable API:**
  - on `SodiumWorldRenderer`: `instanceNullable()`, `isSectionReady(x, y, z)`, `isBoxVisible`;
  - on `RenderSectionManager`: `isSectionBuilt`, `getRenderLists()`, `onChunkAdded`, `onChunkRemoved`.
- **The portable query** is vanilla `LevelRenderer.isSectionCompiledAndVisible`. Under Sodium it means "built", not
  "visible".
- **What DH and Voxy hook instead:**
  - DH, the head of `SodiumChunkSection.renderGroup`;
  - Voxy, `RenderSectionManager` and `DefaultChunkRenderer.render`.
- **Fabric API's `LevelRenderEvents`** wrap the vanilla `renderGroup` calls, but expose no `RenderPass`.

### 5.5 Sodium and Iris together

**How Iris replaces Sodium's shaders.**

- `mixin/MixinShaderManager_Overrides.java:61-110` hooks `RenderSystem.getCompiledPipelineNullable`.
- When a pack is active and the pipeline's namespace contains "sodium", it swaps in the pack's program:
  `SODIUM_TERRAIN_SOLID`, `CUTOUT`, `TRANSLUCENT` or their `SHADOW_` variants (`pipeline/IrisPipelines.java:240-255`).
  These are `gbuffers_terrain*`, `gbuffers_water` and `shadow*`.
- `compat/sodium/mixin/MixinChunkMeshFormats.java:15-18` switches Sodium to Iris's extended terrain format, built by
  `vertices/sodium/terrain/FormatAnalyzer.java:69-91`: Sodium's 20 bytes, plus `mc_Entity`, `iris_Normal`,
  `mc_midTexCoord` and `at_midBlock`, as the pack needs them.

**Depth under Iris.**

- Minecraft is reversed Z:
  - `RenderSystem.DEFAULT_DEPTH_CLEAR_VALUE = 0.0`;
  - `DepthStencilState.DEFAULT` compares `GREATER_THAN_OR_EQUAL`;
  - `Projection.getMatrix` swaps near and far.
- The planes: near is 0.05; far is `max(renderDistance * 16 * 4, cloudRange * 16)` (`Camera.update`).
- The main target is `MainTarget("Main", RGBA8_UNORM, D32_FLOAT)`. Its depth texture can be sampled and copied.
- The clip range is 0..1 with `ARB_clip_control` and on Vulkan, and −1..1 otherwise.
- Iris keeps reversed Z and rewrites the packs instead (1.2). In the shadow pass Iris flips the depth compare
  (`mixin/MixinGlRenderPipeline.java:54-70`), and `isReverseZDuringShaders()` returns false.

**Frame order.** [1.5](#15-where-dh-draws-in-iriss-frame) has it.

- The shadow pass runs while the frame graph is being built, before clear and sky execute.
- Improved Transparency (the OIT path) is disabled whenever shaders are on
  (`mixin/fabulous/MixinDisableFabulousGraphics.java:38-41`).

**Where a LOD renderer slots in:**

- **With a pack:** DH's hook, as in [4.6](#46-recommendation-option-b-with-c-in-parallel).
- **Without a pack:** as DH does without one, at the head of `LevelRenderer.prepareTranslucents`, while no render pass
  is open (DH `fabric/.../mixins/client/MixinLevelRenderer.java:275-323`); or in our own frame-graph pass.

**Vanilla 26.3 has no LOD or far-chunk feature of its own.** Its render distance is still `IntRange(2, 32)`.

## 6. Not found or unconfirmed

- **Iris versions.** The exact commit of the Iris 1.11.7+mc26.3 release was not found. The `26.3` branch at
  `671a5c87` still says `MOD_VERSION 1.11.6`. The released jar's API and DH classes match the source checked: the same
  `IrisApi` methods, the same `DHCompat`, `IrisLodRenderProgram` attribute names, macros and depth-sampler list.
  Commits after the release, such as `770155cc9` (2026-10-04, "DH disable cull not using GlStateManager"), are not in
  1.11.7.
- **In-game behaviour.** None of this was run in game. The draw order and states in 4.6 follow DH's code. They have
  not been tried with a third renderer.
- **Fallback to Vulkan.** What Iris does if `DEFAULT` falls back to Vulkan at runtime, with its OpenGL mixins applied:
  unconfirmed. A crash is likely.
- **`at_tangent`.** How Iris rebuilds it for Sodium terrain: unconfirmed. It does not affect the dh programs, which
  have no tangents.
- **Directional shade.** Whether Iris changes `level.cardinalLighting()`, and so the per-face shade DH bakes into
  colours under a pack: not checked.
- **Pack bugs.** Complementary's End `voxy_translucent` and Bliss `Unstable`'s `dh_shadow` compile errors
  ([3.7](#37-problems-found-offline)) are from offline compiles only.
- **macOS.** The GL extension gaps on macOS are inferred from Apple's 4.1 extension set, not checked on hardware.

## Sources

| Source | Version | Where |
|---|---|---|
| Iris | branch `26.3`, `671a5c87747d33983d7ed659461a64f46dfcb1f0` | https://github.com/IrisShaders/Iris/tree/26.3 |
| Iris release | `iris-fabric-1.11.7+mc26.3.jar`, sha1 `fa03640b` | Modrinth version `vTN4NRGW` |
| Distant Horizons | tag `3.3.4`, `eb1007cb6cc3` | https://gitlab.com/distant-horizons-team/distant-horizons |
| DH core | `64410621767ee697e8bc175c2a3ebe4c5e2ce520` | https://gitlab.com/jeseibel/distant-horizons-core |
| Voxy | branch `263`, `5bf33350a1cc` | https://github.com/MCRcortex/voxy (ARR, not quoted) |
| Sodium | tag `mc26.3-0.9.2`, `03198cb36ab831c99694403d18e4cf4a1726df72` | https://github.com/CaffeineMC/sodium |
| Sodium | branch `dev`, `148151cf25c56f8b670986b348dcc589278c867e` | same |
| Bliss | release 2.1.2, sha512 `dafc60be4980ec40` | Modrinth version `kC2Y8q1P` |
| Bliss git | `Stable` `81e403ed`, `Unstable` `8132c57f` | https://github.com/X0nk/Bliss-Shader |
| Complementary Unbound | release r5.9.3, sha512 `a085d1d8cda67cd1` | Modrinth version `B1kyfoUZ` |
| Minecraft | 26.3 client, sha1 `e877b6a07acd633fb3bb475002175cec036e7b87` | Mojang version manifest |

Full commit hashes: Distant Horizons `3.3.4` is `eb1007cb6cc3e0419effe2b40ab485cf24e55b43`; Voxy is
`5bf33350a1cc7bb7e2b5d947b236c56d878b8a7d`.

Download links:

- Iris: https://cdn.modrinth.com/data/YL57xq9U/versions/vTN4NRGW/iris-fabric-1.11.7%2Bmc26.3.jar
- Bliss: https://cdn.modrinth.com/data/ZvMtQlho/versions/kC2Y8q1P/Bliss_v2.1.2_%28Chocapic13_Shaders_edit%29.zip
- Complementary: https://cdn.modrinth.com/data/R6NEzAwj/versions/B1kyfoUZ/ComplementaryUnbound_r5.9.3.zip
- Minecraft: https://piston-meta.mojang.com/v1/packages/61fff1316bd50312624a858bbb5eb18fa45208ed/26.3.json

Iris commits cited:

- `f3763381d`, 2026-09-08: the depth transformer;
- `4a9c8d1c6`, 2026-09-12: `patchDepth`, and the Voxy depth names;
- `5b89e916b`, 2026-09-12: DH textures;
- `39e9551f3`, 2026-09-14: the DH 3.2.0 `breaks` clause;
- `e99511039`, 2026-07-18: `assignPipelineShadow` and the shadow callbacks
  ([IrisShaders/Iris#3239](https://github.com/IrisShaders/Iris/pull/3239)).
