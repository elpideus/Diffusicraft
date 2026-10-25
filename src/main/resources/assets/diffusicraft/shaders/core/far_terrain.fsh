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
};

// 1 where the world draws the section itself. Texel (row * size + x, z) holds the section in those slots.
uniform sampler2D ChunkMask;

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec4 vertexColor;
layout(location = 3) in vec3 relativePosition;
layout(location = 4) flat in vec3 faceNormal;

layout(location = 0) out vec4 fragColor;

void main() {
    // A face lies on the edge of its block: step back into the block to find the section it belongs to.
    vec3 inside = relativePosition - CameraOffset - faceNormal * 0.25;
    ivec3 section = (CameraBlockPos + ivec3(floor(inside))) >> 4;
    ivec2 offset = section.xz - Columns.yz;
    // Within the render distance as the world measures it (ChunkTrackingView.isInViewDistance).
    ivec2 beyond = max(abs(offset) - 1, ivec2(0));
    if (beyond.x * beyond.x + beyond.y * beyond.y < Columns.x * Columns.x && section.y >= Rows.x && section.y <= Rows.y) {
        // Both operands are positive here, which GLSL's % needs.
        ivec2 slot = (Slots.xy + offset + Columns.x) % Columns.w;
        int row = (Slots.z + section.y - Rows.x) % Rows.z;
        if (texelFetch(ChunkMask, ivec2(row * Columns.w + slot.x, slot.y), 0).r > 0.5) {
            discard;
        }
    }
    fragColor = apply_fog(vertexColor, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart,
        FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
