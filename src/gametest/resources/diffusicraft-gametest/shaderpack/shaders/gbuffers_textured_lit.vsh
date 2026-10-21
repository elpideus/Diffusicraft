#version 120

// Terrain, entities and the hand, which Iris falls back to this program for: the blocks with their light, no fog.

varying vec2 texcoord;
varying vec2 lmcoord;
varying vec4 color;

void main() {
	gl_Position = ftransform();
	texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
	lmcoord = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
	color = gl_Color;
}
