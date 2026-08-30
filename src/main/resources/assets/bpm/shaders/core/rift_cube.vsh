#version 150

in vec3 Position;
in vec2 UV0;
in vec4 Color;
in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

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
