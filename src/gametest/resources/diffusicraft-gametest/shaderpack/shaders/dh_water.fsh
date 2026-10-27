#version 120

// Leaves the far water out where the chunks have drawn, as packs that support Distant Horizons do: it is drawn after
// them, and would cover them where it lies behind them.

uniform sampler2D depthtex0;
uniform float viewWidth;
uniform float viewHeight;

varying vec4 color;

void main() {
	if (texture2D(depthtex0, gl_FragCoord.xy / vec2(viewWidth, viewHeight)).r < 1.0) {
		discard;
	}
	/* DRAWBUFFERS:0 */
	gl_FragData[0] = color;
}
