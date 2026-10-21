#version 120

// Small, to keep the shadow pass cheap under software rendering.
const int shadowMapResolution = 1024;
const float shadowDistance = 96.0;

uniform sampler2D tex;

varying vec2 texcoord;
varying vec4 color;

void main() {
	vec4 albedo = texture2D(tex, texcoord) * color;
	if (albedo.a < 0.1) {
		discard;
	}
	gl_FragData[0] = albedo;
}
