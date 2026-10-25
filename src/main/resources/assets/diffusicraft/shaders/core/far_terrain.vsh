#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>
#include <minecraft:terrainglobals.glsl>
#include <minecraft:chunksection.glsl>

// X and Z in blocks from the tile's corner, Y from ChunkPosition.y, and the light: sky + 16 * block.
layout(location = 0) in uvec4 Position;
layout(location = 1) in vec4 Color;
// The material of the block and the direction its face looks to, as Direction numbers it.
layout(location = 2) in uvec4 Extra;

uniform sampler2D Sampler2;

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec4 vertexColor;
layout(location = 3) out vec3 relativePosition;
layout(location = 4) flat out vec3 faceNormal;

const vec3 NORMALS[6] = vec3[](vec3(0, -1, 0), vec3(0, 1, 0), vec3(0, 0, -1), vec3(0, 0, 1), vec3(-1, 0, 0), vec3(1, 0, 0));
// How much light each side of a block takes, as vanilla shades the faces of its chunks.
const float SHADE[6] = float[](0.5, 1.0, 0.8, 0.8, 0.6, 0.6);
// The material of water, whose top stands 8/9 of a block high as vanilla draws still water, to meet the chunks' water.
const uint WATER = 12u;
const float WATER_DROP = 1.0 / 9.0;

void main() {
    vec3 pos = vec3(Position.xyz) + vec3(ChunkPosition - CameraBlockPos) + CameraOffset;
    if (Extra.x == WATER && Extra.y == 1u) {
        pos.y -= WATER_DROP;
    }
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);
    vertexColor = Color * sample_lightmap(Sampler2, ivec2(int(Position.w >> 4u) * 16, int(Position.w & 15u) * 16));
    relativePosition = pos;
    vertexColor.rgb *= SHADE[min(Extra.y, 5u)];
    faceNormal = NORMALS[min(Extra.y, 5u)];
}
