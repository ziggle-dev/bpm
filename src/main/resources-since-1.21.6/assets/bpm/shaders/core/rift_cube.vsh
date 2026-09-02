#version 330

// GENERATED from ../../../../../resources/assets/bpm/shaders/core/rift_cube.vsh -- do not edit the body here.
//
// From 1.21.9 a core shader's uniforms arrive in std140 blocks rather than one at a time: ModelViewMat and
// ColorModulator come from DynamicTransforms, ProjMat from Projection, GameTime from Globals. The bodies
// below are the 1.21.1 files unchanged; only these first lines differ, which is the point.

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec2 UV0;
in vec4 Color;
in vec3 Normal;

// Position arrives already in CAMERA-RELATIVE WORLD space — the renderer bakes its pose into the vertices
// rather than using a model matrix — so the camera sits at the origin of vPos and the view ray to any
// fragment is just normalize(vPos). Both effects lean on that.
out vec3 vPos;
out vec2 vUv;
out vec4 vColor;
out vec3 vNormal;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vPos = Position;
    vUv = UV0;
    vColor = Color;
    vNormal = Normal;
}
