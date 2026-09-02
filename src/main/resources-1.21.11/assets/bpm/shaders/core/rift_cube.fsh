#version 330

// GENERATED from ../../../../../resources/assets/bpm/shaders/core/rift_cube.fsh -- do not edit the body here.
//
// From 1.21.9 a core shader's uniforms arrive in std140 blocks rather than one at a time: ModelViewMat and
// ColorModulator come from DynamicTransforms, ProjMat from Projection, GameTime from Globals. The bodies
// below are the 1.21.1 files unchanged; only these first lines differ, which is the point.

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>

// A pocket dimension in a box.
//
// The cube is six flat quads and nothing more — but each fragment fires the view ray ONWARD through the
// face it landed on, intersects a unit box behind it, and shades whichever inner wall it hits. That is
// interior mapping: the room is not modelled, it is solved per pixel, so it parallaxes exactly like a real
// space and the box reads as deeper than it is. Walk around it and you are genuinely looking into a
// different corner of the room each time.
//
// Each of the six walls has its own palette, which is the point: the thing looks like a different place
// from every side, because you are seeing a different wall. Everything is quantised to a voxel grid — the
// interior is built of blocks, not gradients.

in vec3 vPos;
in vec2 vUv;
in vec4 vColor;
in vec3 vNormal;

out vec4 fragColor;

const float GRID = 7.0;   // blocks per unit along an inner wall

float hash(vec3 p) {
    return fract(sin(dot(p, vec3(127.1, 311.7, 74.7))) * 43758.5453);
}

// One palette per inner wall. Deliberately not a blue set — the frame is cold, the rooms are warm.
vec3 wallTint(int axis, float sgn) {
    if (axis == 0) return sgn > 0.0 ? vec3(1.00, 0.62, 0.20) : vec3(0.58, 0.34, 0.86);  // amber / violet
    if (axis == 1) return sgn > 0.0 ? vec3(1.00, 0.86, 0.52) : vec3(0.16, 0.19, 0.26);  // lamp / deepslate
    return sgn > 0.0 ? vec3(0.42, 0.86, 0.46) : vec3(0.25, 0.85, 0.80);                 // verdant / entanglium
}

void main() {
    // Where on the cube's surface this fragment is, in the cube's own -1..1 frame. The renderer packs it
    // into the vertex colour, which interpolates linearly across a face and so stays exact.
    vec3 local = vColor.rgb * 2.0 - 1.0;
    vec3 rd = normalize(vPos);

    // Slab intersection, forward only: where does the ray leave the box? Division by zero is fine here —
    // an axis-parallel ray gives an infinity that loses the min, which is the answer we want.
    vec3 inv = 1.0 / rd;
    vec3 tExit = max((-1.0 - local) * inv, (1.0 - local) * inv);
    float t = min(min(tExit.x, tExit.y), tExit.z);
    vec3 hit = local + rd * t;

    // Which wall did it land on, and how far away is it?
    vec3 a = abs(hit);
    int axis = (a.x > a.y && a.x > a.z) ? 0 : (a.y > a.z ? 1 : 2);
    float sgn = (axis == 0) ? sign(hit.x) : (axis == 1) ? sign(hit.y) : sign(hit.z);
    vec3 tint = wallTint(axis, sgn);

    // Voxelise the wall, and light the blocks unevenly so it reads as a built room.
    vec3 cell = floor(hit * GRID);
    float lit = hash(cell + vec3(float(axis) * 13.0));
    float pulse = 0.72 + 0.28 * sin(GameTime * 900.0 + lit * 25.0);
    vec3 col = tint * (0.35 + 0.75 * lit * pulse);

    // Mortar between the blocks, so the voxels read as blocks rather than a noise field.
    vec3 f = abs(fract(hit * GRID) - 0.5);
    float seam = max(max(f.x, f.y), f.z);
    col *= smoothstep(0.5, 0.42, seam) * 0.55 + 0.65;

    // Depth: the far wall is dimmer than the near one, which is what sells the box as having a volume.
    col *= mix(1.15, 0.35, clamp(t * 0.5, 0.0, 1.0));

    // A core hanging in the middle — the thing the pocket dimension is FOR. Found by the closest approach
    // of the same ray to the origin, so it sits convincingly behind the glass from every face.
    float near = length(local - rd * dot(local, rd));
    float core = smoothstep(0.30, 0.0, near);
    col += vec3(0.85, 0.97, 1.00) * core * (0.55 + 0.45 * sin(GameTime * 1600.0));

    // The frame: a bright edge where the faces meet, which is what keeps it reading as a solid object
    // rather than a floating window. Cold, against the warm interior.
    vec2 e = abs(vUv - 0.5) * 2.0;
    float frame = smoothstep(0.80, 0.99, max(e.x, e.y));
    col = mix(col, vec3(0.35, 0.95, 0.88), frame * 0.9);

    float alpha = clamp(max(max(col.r, col.g), col.b), 0.0, 1.0) * vColor.a * ColorModulator.a;
    if (alpha < 0.004) discard;
    fragColor = vec4(col * ColorModulator.rgb, alpha);
}
