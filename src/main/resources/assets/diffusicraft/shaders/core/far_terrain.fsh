#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>

layout(std140) uniform FarTerrainInfo {
    // The render distance in chunks, the chunk the camera is in, and the chunks along each side of the mask.
    ivec4 Columns;
    // The lowest and the highest section Y the world draws around the camera, and how many rows the mask holds.
    ivec4 Rows;
    // The slots of the mask's north-western chunk and of its lowest row: its slots rotate as the camera moves.
    ivec4 Slots;
    // How far from the camera Sodium draws a section, less a little to spare, in blocks, or 0 where the world goes by
    // whole chunks alone.
    float Reach;
};

// 1 where the world draws the section itself. Texel (row * size + x, z) holds the section in those slots.
uniform sampler2D ChunkMask;

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec4 vertexColor;
layout(location = 3) in vec3 relativePosition;
layout(location = 4) flat in vec3 faceNormal;
layout(location = 5) in float foot;
layout(location = 6) flat in float grain;

layout(location = 0) out vec4 fragColor;

// How dark the foot of a face is, as the corner with the ground under it darkens the chunks' smooth lighting.
const float FOOT_LIGHT = 0.55;
// How far the brightness of a block strays from its colour, up or down: from block to block, then over patches 4, 16
// and 64 blocks across. A far face has one colour, and this is what the texture of the chunks would make of it from
// here, where each block is a few pixels and every mipmap of a texture is its mean.
const float BLOCK_GRAIN = 0.07;
const vec3 PATCH_GRAIN = vec3(0.06, 0.07, 0.08);
const ivec3 PATCH_BITS = ivec3(2, 4, 6);

// Whether Sodium draws the section from where the camera stands (TraversableTree): its nearest point lies within a
// cylinder around the camera Reach blocks across, and as high above and below it.
bool withinReach(ivec3 section) {
    if (Reach <= 0.0) {
        return true;
    }
    // The camera within its block, and the section's corner from the camera's block.
    vec3 camera = -CameraOffset;
    vec3 low = vec3(section * 16 - CameraBlockPos);
    vec3 toNearest = clamp(camera, low, low + 16.0) - camera;
    return dot(toNearest.xz, toNearest.xz) < Reach * Reach && abs(toNearest.y) < Reach;
}

// A number in [0, 1) for a cell, the same from wherever it is seen.
float hash(ivec2 cell) {
    uvec2 mixed = uvec2(cell) * uvec2(1597334677u, 3812015801u);
    uint value = (mixed.x ^ mixed.y) * 1597334677u;
    return float(value >> 8) / 16777216.0;
}

// Noise in [-1, 1] over cells 2^bits blocks across, around a point at block + within. As smooth as the bilinear fade
// between the corners of a cell: it shows as patches, not as squares.
float patches(ivec2 block, vec2 within, int bits) {
    ivec2 cell = block >> bits;
    vec2 t = (vec2(block - (cell << bits)) + within) / float(1 << bits);
    t = t * t * (3.0 - 2.0 * t);
    float low = mix(hash(cell), hash(cell + ivec2(1, 0)), t.x);
    float high = mix(hash(cell + ivec2(0, 1)), hash(cell + ivec2(1, 1)), t.x);
    return mix(low, high, t.y) * 2.0 - 1.0;
}

// What a pattern of cells this many blocks across keeps at this many blocks a pixel: all of it down to two pixels a
// cell, and nothing at one, where it would only shimmer as the camera moves.
float shown(float cell, float blocksPerPixel) {
    return 1.0 - smoothstep(0.5, 1.0, blocksPerPixel / cell);
}

// How bright the face is at a point within a block, at this many blocks a pixel.
float faceLight(ivec3 block, vec3 within, float blocksPerPixel) {
    vec3 across = abs(faceNormal);
    ivec2 plane = across.y > 0.5 ? block.xz : across.z > 0.5 ? block.xy : block.zy;
    vec2 point = across.y > 0.5 ? within.xz : across.z > 0.5 ? within.xy : within.zy;
    float light = 1.0 + BLOCK_GRAIN * (hash(plane) * 2.0 - 1.0) * shown(1.0, blocksPerPixel);
    for (int i = 0; i < 3; i++) {
        float amount = PATCH_GRAIN[i] * shown(float(1 << PATCH_BITS[i]), blocksPerPixel);
        if (amount > 0.0) {
            light += amount * patches(plane, point, PATCH_BITS[i]);
        }
    }
    return light;
}

void main() {
    // Before the fragment can be discarded, past which neighbouring pixels may tell nothing.
    float blocksPerPixel = length(fwidth(relativePosition));
    float footPerPixel = fwidth(foot);
    // A face lies on the edge of its block: step back into the block to find the section it belongs to.
    vec3 inside = relativePosition - CameraOffset - faceNormal * 0.25;
    vec3 blockOffset = floor(inside);
    ivec3 block = CameraBlockPos + ivec3(blockOffset);
    ivec3 section = block >> 4;
    ivec2 offset = section.xz - Columns.yz;
    // Within the render distance as the world measures it (ChunkTrackingView.isInViewDistance).
    ivec2 beyond = max(abs(offset) - 1, ivec2(0));
    if (beyond.x * beyond.x + beyond.y * beyond.y < Columns.x * Columns.x && section.y >= Rows.x && section.y <= Rows.y) {
        // Both operands are positive here, which GLSL's % needs.
        ivec2 slot = (Slots.xy + offset + Columns.x) % Columns.w;
        int row = (Slots.z + section.y - Rows.x) % Rows.z;
        if (texelFetch(ChunkMask, ivec2(row * Columns.w + slot.x, slot.y), 0).r > 0.5 && withinReach(section)) {
            discard;
        }
    }
    vec4 color = vertexColor;
    if (grain > 0.0) {
        // Over a pixel of more than a block or so the foot would only darken the mean of the face.
        float shade = mix(mix(FOOT_LIGHT, 1.0, smoothstep(0.0, 1.0, foot)), 1.0, smoothstep(0.5, 1.5, footPerPixel));
        color.rgb *= shade * faceLight(block, inside - blockOffset, blocksPerPixel);
    }
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart,
        FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
