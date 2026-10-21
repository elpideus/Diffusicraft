#version 120

// Darkens what the sun does not reach, by the depth the shadow pass left.

uniform sampler2D colortex0;
uniform sampler2D depthtex0;
uniform sampler2D shadowtex0;
uniform mat4 gbufferProjectionInverse;
uniform mat4 gbufferModelViewInverse;
uniform mat4 shadowModelView;
uniform mat4 shadowProjection;

varying vec2 texcoord;

void main() {
	vec3 color = texture2D(colortex0, texcoord).rgb;
	float depth = texture2D(depthtex0, texcoord).r;
	if (depth < 1.0) {
		vec4 view = gbufferProjectionInverse * vec4(vec3(texcoord, depth) * 2.0 - 1.0, 1.0);
		vec4 player = gbufferModelViewInverse * vec4(view.xyz / view.w, 1.0);
		vec4 sun = shadowProjection * (shadowModelView * player);
		vec3 shadowPos = sun.xyz / sun.w * 0.5 + 0.5;
		if (all(greaterThan(shadowPos, vec3(0.0))) && all(lessThan(shadowPos, vec3(1.0)))) {
			float occluder = texture2D(shadowtex0, shadowPos.xy).r;
			color *= shadowPos.z - 0.001 > occluder ? 0.55 : 1.0;
		}
	}
	/* DRAWBUFFERS:0 */
	gl_FragData[0] = vec4(color, 1.0);
}
