#version 120

// Distant Horizons' far terrain, drawn through the pack as packs that support it do.

varying vec4 color;

void main() {
	gl_Position = ftransform();
	color = gl_Color;
}
