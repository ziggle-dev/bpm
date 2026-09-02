// GENERATED from ../../../../../resources/assets/bpm/shaders/core/rift_tear.fsh -- do not edit the body here.
//
// From 1.21.9 a core shader's uniforms arrive in std140 blocks rather than one at a time: ModelViewMat and
// ColorModulator come from DynamicTransforms, ProjMat from Projection, GameTime from Globals. The bodies
// below are the 1.21.1 files unchanged; only these first lines differ, which is the point.
#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>

// A tear in the world: a seeded, block-shaped hole with a shaft of stars behind it.
//
// **The outline is seeded, not random.** The renderer hashes the anchor's block position into the vertex
// colour, so a rift that opens on the same block is the same tear every time and two side by side are
// visibly different holes. Nothing is stored. It is quantised BEFORE anything reads position, so the edge
// is cut along cell boundaries and the interior is square cells.
//
// **How the camera drives it, and why the first attempt did not.**
// Vanilla's end portal does not raymarch. Each of its fifteen layers samples one texture through
// `texProj0.xy / texProj0.w` — the fragment's own CLIP-SPACE position divided by w, i.e. where it sits on
// the screen — with a different scale and offset per layer. Because a screen position changes the instant
// the camera moves or turns, the layers slide against each other for free, and the depth you read is the
// difference in how fast they slide. That is the whole trick.
//
// The first version here used only a view-ray offset, which collapses to nothing when you look at the tear
// head-on — which is most of the time — so it flattened into a wash of colour. This one takes vanilla's
// route (screen projection, weighted more heavily the deeper the layer) and keeps the view ray as a second
// term, so it has parallax both when you look straight at it and when you walk past.
//
// The other reason it looked flat: `sin`-based hashing on inputs in the tens of thousands loses all its
// precision on real hardware, so every cell returned near enough the same number and no stars ever passed
// the threshold. The hash below has no trig in it and the seed is normalised to 0..1 first.

in vec3 vPos;
in vec2 vUv;
in vec4 vColor;
in vec3 vNormal;
in vec4 vClip;

out vec4 fragColor;

const float PIX = 13.0;    // cells across the half-width — the pixel size of the whole effect
const int LAYERS = 8;

/** Overall brightness of the star field. The tear sits among dark navy blocks and should not out-glow them. */
const float STAR_GAIN = 0.55;

/** No trig, and stable for the coordinate ranges the layers actually use. */
float hash21(vec2 p) {
    vec3 q = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973));
    q += dot(q, q.yzx + 33.33);
    return fract((q.x + q.y) * q.z);
}

float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash21(i), hash21(i + vec2(1, 0)), f.x),
               mix(hash21(i + vec2(0, 1)), hash21(i + vec2(1, 1)), f.x), f.y);
}

/**
 * A star colour per depth, and the two directions get different sets.
 *
 * Both live inside the mod's own palette — the dark navy of the plating and the entanglium teal of its
 * energy. The direction is read as HUE rather than temperature: taking things in runs blue, letting them
 * out runs green. The earlier amber-and-violet set was legible but belonged to a different mod; it lit up
 * every block around it and made the tear the brightest thing on screen, which a hole should never be.
 */
vec3 layerTint(int i, bool inward) {
    float f = float(i) / float(LAYERS - 1);
    vec3 a = inward ? vec3(0.16, 0.34, 0.62) : vec3(0.14, 0.44, 0.32);
    vec3 b = inward ? vec3(0.20, 0.56, 0.76) : vec3(0.20, 0.66, 0.46);
    vec3 c = inward ? vec3(0.30, 0.80, 0.84) : vec3(0.36, 0.84, 0.60);
    return f < 0.5 ? mix(a, b, f * 2.0) : mix(b, c, (f - 0.5) * 2.0);
}

void main() {
    vec2 p = vUv;

    vec2 q = (floor(p * PIX) + 0.5) / PIX;
    float r = length(q);
    float ang = atan(q.y, q.x);

    float seed = (vColor.r * 255.0 + vColor.g * 255.0 * 256.0) / 65535.0;   // 0..1, precision-safe

    // `packed` is a RESERVED WORD in GLSL, so declaring one killed this whole block: the declaration failed
    // to parse, and `inward` and `dist` on the next two lines went undefined with it.
    float flags = floor(vColor.b * 255.0 + 0.5);
    bool inward = flags >= 128.0;
    float dist = mod(flags, 128.0) * 0.25;

    // The jagged outline. Two octaves against the angle — big lobes, then bite marks.
    float edge = 0.74
        + 0.20 * (vnoise(vec2(ang * 2.3, seed * 91.0)) - 0.5)
        + 0.11 * (vnoise(vec2(ang * 7.1, seed * 57.0)) - 0.5);
    if (r > edge) discard;

    // Where this fragment sits on the SCREEN. Changes with every camera move or turn — vanilla's driver.
    vec2 scr = vClip.xy / vClip.w;

    // The view ray in the tear's own frame, as a second parallax term for when you walk past it side-on.
    vec3 cam = normalize(vNormal) * max(dist, 1.0);
    vec3 rd = normalize(vec3(q, 0.0) - cam);
    vec2 par = rd.xy / max(abs(rd.z), 0.20);

    float t = GameTime * 1200.0;
    // Near-black, and blue or green rather than violet or ember: the hole is a dark thing with a little
    // light in it, not a lamp.
    vec3 voidA = inward ? vec3(0.010, 0.020, 0.040) : vec3(0.010, 0.030, 0.024);
    vec3 voidB = inward ? vec3(0.035, 0.075, 0.130) : vec3(0.030, 0.100, 0.075);
    vec3 col = mix(voidA, voidB, smoothstep(0.0, edge, r));

    for (int i = 0; i < LAYERS; i++) {
        float f = float(i) / float(LAYERS - 1);
        float depth = 0.15 + f * 1.5;

        // Deeper layers lean on the screen projection and shallow ones on the tear's own surface, so the
        // near cells stay stuck to the hole while the far ones swim like something a long way off.
        vec2 base = mix(q, scr * 0.75, f * 0.85) + par * depth * 0.35;

        // Each layer at its own scale, spun a little, and drifting — the difference in rates is the depth.
        float rot = seed * 6.28 + f * 2.1 + t * 0.02 * (0.3 + f);
        vec2 sp = vec2(base.x * cos(rot) - base.y * sin(rot), base.x * sin(rot) + base.y * cos(rot));
        vec2 cell = floor(sp * (PIX * (0.45 + f * 2.4)) + vec2(t * (0.04 + f * 0.05), t * 0.03));

        float h = hash21(cell + seed * 71.0 + float(i) * 17.0);
        // Sparse: a scatter of lit cells in a dark shaft, not a field of them.
        float star = step(0.955 - f * 0.02, h);
        float twinkle = 0.70 + 0.30 * sin(t * 3.0 + h * 40.0);
        col += layerTint(i, inward) * star * (1.0 - f * 0.5) * twinkle * STAR_GAIN;
    }

    // The cut itself, in the same hue as the rest of that mouth. It glows only enough to draw the edge —
    // it used to be the brightest thing in the frame.
    vec3 rim = inward ? vec3(0.22, 0.55, 0.78) : vec3(0.24, 0.70, 0.50);
    float lip = smoothstep(edge - 0.16, edge, r);
    col += rim * lip * (0.30 + 0.22 * vnoise(vec2(ang * 9.0, t * 0.5)));

    float alpha = clamp(max(max(col.r, col.g), col.b) * 0.95, 0.0, 1.0) * vColor.a * ColorModulator.a;
    if (alpha < 0.004) discard;
    fragColor = vec4(col * ColorModulator.rgb, alpha);
}
