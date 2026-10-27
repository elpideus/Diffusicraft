#version 120

// Distant Horizons' far terrain seen from the sun, which packs that support it draw into their shadow map.

varying vec4 color;

void main() {
	gl_Position = ftransform();
	color = gl_Color;
}
