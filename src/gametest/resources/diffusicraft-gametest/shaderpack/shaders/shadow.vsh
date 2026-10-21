#version 120

// The shadow pass: the blocks round the player seen from the sun, which has Iris walk the sections a second time.

varying vec2 texcoord;
varying vec4 color;

void main() {
	gl_Position = ftransform();
	texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
	color = gl_Color;
}
