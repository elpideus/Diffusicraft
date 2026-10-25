#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>

layout(std140) uniform FarTerrainInfo {
    // The mask's radius in chunks and the chunk at its centre.
    ivec4 MaskInfo;
    // The lowest and the highest section Y the world draws its chunks in around the camera.
    ivec4 Rows;
};

// 1 where the world draws the chunk itself.
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
    ivec3 block = CameraBlockPos + ivec3(floor(inside));
    int section = block.y >> 4;
    ivec2 texel = (block.xz >> 4) - MaskInfo.yz + MaskInfo.x;
    int size = 2 * MaskInfo.x + 1;
    if (section >= Rows.x && section <= Rows.y && all(greaterThanEqual(texel, ivec2(0))) && all(lessThan(texel, ivec2(size)))
            && texelFetch(ChunkMask, texel, 0).r > 0.5) {
        discard;
    }
    fragColor = apply_fog(vertexColor, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart,
        FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
