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
//?} else {
/*fun VertexConsumer.addVertex(matrix: org.joml.Matrix4f, x: Float, y: Float, z: Float): VertexConsumer =
    vertex(matrix, x, y, z)

fun VertexConsumer.addVertex(x: Float, y: Float, z: Float): VertexConsumer =
    vertex(x.toDouble(), y.toDouble(), z.toDouble())

fun VertexConsumer.setColor(red: Int, green: Int, blue: Int, alpha: Int): VertexConsumer =
    color(red, green, blue, alpha)

/** ARGB in one int, which is how the mod's own colours travel. */
fun VertexConsumer.setColor(argb: Int): VertexConsumer = color(argb)
*///?}
