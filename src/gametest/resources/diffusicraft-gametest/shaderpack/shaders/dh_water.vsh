#version 120

// Distant Horizons' far water, which packs that support it leave out where the chunks have drawn.

varying vec4 color;

void main() {
	gl_Position = ftransform();
	color = gl_Color;
}
