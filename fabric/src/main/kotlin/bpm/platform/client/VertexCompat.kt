package bpm.platform.client

import com.mojang.blaze3d.vertex.VertexConsumer

/**
 * `VertexConsumer` under the names it had before 1.20.5.
 *
 * The methods are the same and do the same thing; they were renamed. `vertex` became `addVertex` and
 * `color` became `setColor`.
 *
 * Both arms declare them, and that is the point rather than an oversight. Declaring them only below
 * 1.20.5 seemed neater -- an extension cannot shadow a member, so the real methods would win above it --
 * but the call sites have to IMPORT the extension, and an import of something that does not exist is an
 * error. So the modern arm declares the same four and delegates.
 *
 * That delegation does not recurse: inside the body, `addVertex` on the receiver resolves to the MEMBER,
 * because members always win over extensions. At the call sites the member wins too, so these are dead
 * code above 1.20.5 -- present only so the import resolves.
 */

//? if >=1.20.5 {
fun VertexConsumer.addVertex(matrix: org.joml.Matrix4f, x: Float, y: Float, z: Float): VertexConsumer =
    this.addVertex(matrix, x, y, z)

fun VertexConsumer.addVertex(x: Float, y: Float, z: Float): VertexConsumer = this.addVertex(x, y, z)

fun VertexConsumer.setColor(red: Int, green: Int, blue: Int, alpha: Int): VertexConsumer =
    this.setColor(red, green, blue, alpha)

fun VertexConsumer.setColor(argb: Int): VertexConsumer = this.setColor(argb)

fun VertexConsumer.addVertex(
    pose: com.mojang.blaze3d.vertex.PoseStack.Pose,
    x: Float,
    y: Float,
    z: Float,
): VertexConsumer = this.addVertex(pose, x, y, z)

fun VertexConsumer.setColor(red: Float, green: Float, blue: Float, alpha: Float): VertexConsumer =
    this.setColor(red, green, blue, alpha)

fun VertexConsumer.setUv(u: Float, v: Float): VertexConsumer = this.setUv(u, v)

fun VertexConsumer.setUv2(u: Int, v: Int): VertexConsumer = this.setUv2(u, v)

fun VertexConsumer.setLight(packed: Int): VertexConsumer = this.setLight(packed)

fun VertexConsumer.setOverlay(packed: Int): VertexConsumer = this.setOverlay(packed)

fun VertexConsumer.setNormal(x: Float, y: Float, z: Float): VertexConsumer = this.setNormal(x, y, z)

fun VertexConsumer.setNormal(
    pose: com.mojang.blaze3d.vertex.PoseStack.Pose,
    x: Float,
    y: Float,
    z: Float,
): VertexConsumer = this.setNormal(pose, x, y, z)
//?} else {
/*fun VertexConsumer.addVertex(matrix: org.joml.Matrix4f, x: Float, y: Float, z: Float): VertexConsumer =
    vertex(matrix, x, y, z)

fun VertexConsumer.addVertex(x: Float, y: Float, z: Float): VertexConsumer =
    vertex(x.toDouble(), y.toDouble(), z.toDouble())

fun VertexConsumer.setColor(red: Int, green: Int, blue: Int, alpha: Int): VertexConsumer =
    color(red, green, blue, alpha)

/** ARGB in one int, which is how the mod's own colours travel. */
fun VertexConsumer.setColor(argb: Int): VertexConsumer = color(argb)

/** The pose overload takes the Matrix4f inside the pose here, as setNormal takes its Matrix3f. */
fun VertexConsumer.addVertex(
    pose: com.mojang.blaze3d.vertex.PoseStack.Pose,
    x: Float,
    y: Float,
    z: Float,
): VertexConsumer = vertex(pose.pose(), x, y, z)

fun VertexConsumer.setColor(red: Float, green: Float, blue: Float, alpha: Float): VertexConsumer =
    color(red, green, blue, alpha)

fun VertexConsumer.setUv(u: Float, v: Float): VertexConsumer = uv(u, v)

fun VertexConsumer.setUv2(u: Int, v: Int): VertexConsumer = uv2(u, v)

/** Light and overlay arrive packed into one int on both bands; 1.20.1 spells the setter differently. */
fun VertexConsumer.setLight(packed: Int): VertexConsumer = uv2(packed)

fun VertexConsumer.setOverlay(packed: Int): VertexConsumer = overlayCoords(packed)

fun VertexConsumer.setNormal(x: Float, y: Float, z: Float): VertexConsumer = normal(x, y, z)

/**
 * The pose overload takes a `Matrix3f` here, not the `Pose` itself.
 *
 * `PoseStack.Pose.normal()` is that matrix, so the two spellings mean the same transform.
 */
fun VertexConsumer.setNormal(
    pose: com.mojang.blaze3d.vertex.PoseStack.Pose,
    x: Float,
    y: Float,
    z: Float,
): VertexConsumer = normal(pose.normal(), x, y, z)
*///?}
