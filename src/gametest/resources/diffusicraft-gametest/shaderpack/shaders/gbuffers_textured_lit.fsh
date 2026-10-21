#version 120

uniform sampler2D tex;
uniform sampler2D lightmap;

varying vec2 texcoord;
varying vec2 lmcoord;
varying vec4 color;

void main() {
	vec4 albedo = texture2D(tex, texcoord) * color;
	if (albedo.a < 0.1) {
		discard;
	}
	albedo *= texture2D(lightmap, lmcoord);
	/* DRAWBUFFERS:0 */
	gl_FragData[0] = albedo;
}
