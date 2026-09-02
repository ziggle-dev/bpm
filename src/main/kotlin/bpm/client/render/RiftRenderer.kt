package bpm.client.render

import bpm.platform.client.addVertex
import bpm.platform.client.setColor

import bpm.client.fx.Rift
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import kotlin.math.asin
import kotlin.math.atan2

/**
 * Draws a rift, in whichever style is switched on.
 *
 * Both styles hand the shader the same three things through the vertex channels — see [RiftShader] — and
 * both bake their pose into the vertex positions rather than using a model matrix, so the shader can treat
 * the camera as sitting at the origin of the positions it receives.
 */
object RiftRenderer {

    /** The style the client is drawing. `/bpm rift <cube|tear>` flips it live. */
    var style: RiftStyle = RiftStyle.TEAR

    // The six faces of the unit cube, each as four corners in the cube's own -1..1 frame, wound so the
    // face's own UV runs sensibly. `axis` and `sign` say which face it is, for the facing test.
    private class Face(val axis: Int, val sign: Float, val corners: Array<FloatArray>)

    private val FACES: List<Face> = listOf(
        Face(0, +1f, arrayOf(f(1f, -1f, -1f), f(1f, -1f, 1f), f(1f, 1f, 1f), f(1f, 1f, -1f))),
        Face(0, -1f, arrayOf(f(-1f, -1f, 1f), f(-1f, -1f, -1f), f(-1f, 1f, -1f), f(-1f, 1f, 1f))),
        Face(1, +1f, arrayOf(f(-1f, 1f, -1f), f(1f, 1f, -1f), f(1f, 1f, 1f), f(-1f, 1f, 1f))),
        Face(1, -1f, arrayOf(f(-1f, -1f, 1f), f(1f, -1f, 1f), f(1f, -1f, -1f), f(-1f, -1f, -1f))),
        Face(2, +1f, arrayOf(f(1f, -1f, 1f), f(-1f, -1f, 1f), f(-1f, 1f, 1f), f(1f, 1f, 1f))),
        Face(2, -1f, arrayOf(f(-1f, -1f, -1f), f(1f, -1f, -1f), f(1f, 1f, -1f), f(-1f, 1f, -1f))),
    )

    private val UVS = arrayOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f)

    private fun f(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)

    fun draw(rift: Rift, at: Vec3, anchorPos: BlockPos, facing: Vec3, scale: Float, draw: bpm.platform.client.ImmediateDraw, pose: PoseStack, partialTick: Float) {
        val open = rift.openness(partialTick)
        if (open <= 0.01f) return

        // The tear stays exactly where it was put. What changes is how it sits: a slow wobble and a
        // breathe, both scaled by how much is going through it — see `Rift.yawWobble`.
        val scaled = (open * rift.breathe(partialTick)).coerceAtLeast(0.02f)
        val alpha = (open.coerceIn(0f, 1.4f) / 1.4f * 255).toInt().coerceIn(0, 255)
        val buf = draw.consumer(bpm.platform.client.RiftLooks.typeFor(style))
        when (style) {
            RiftStyle.CUBE -> cube(rift, at, scale * scaled, alpha, buf, pose, partialTick)
            RiftStyle.TEAR -> tear(rift, at, anchorPos, facing, scale * scaled, alpha, buf, pose, partialTick)
        }
    }

    /**
     * A box you can see into: the three faces turned toward the camera, each carrying its own position in
     * the cube's frame so the shader can solve the room behind it.
     *
     * Only the near faces are emitted. Drawing all six would additively blend the far walls' interiors
     * through the near ones and wash the whole thing out — and the far three are exactly what the shader is
     * already drawing, from the inside.
     *
     * The cube is deliberately axis-aligned to the world and never billboards. That is what makes the local
     * frame and the world frame the same set of axes, so the shader's ray needs no basis change; it is also
     * what makes it read as a *thing in the world* rather than a sprite following you.
     */
    private fun cube(rift: Rift, at: Vec3, half: Float, alpha: Int, buf: VertexConsumer, pose: PoseStack, partialTick: Float) {
        pose.pushPose()
        pose.translate(at.x, at.y, at.z)
        // A slow tumble, per rift, so it never sits square to the world and the faces keep changing.
        val spin = rift.phase + (rift.age + partialTick) * 0.012f * rift.flowSpeed.toFloat()
        pose.mulPose(Axis.YP.rotation(spin))
        pose.mulPose(Axis.XP.rotation(spin * 0.6f))
        val m = pose.last().pose()

        // `at` is the cube's centre in camera-relative space, so the camera sits at -at in the cube's frame
        // once the pose is undone. The facing test only needs the sign per axis, which survives the tumble
        // because we test in the cube's own frame using the inverse-transformed eye.
        val eye = Matrix4f(m).invert().transformPosition(org.joml.Vector3f(0f, 0f, 0f))

        for (face in FACES) {
            val toEye = when (face.axis) {
                0 -> eye.x
                1 -> eye.y
                else -> eye.z
            }
            // Only the faces the camera is on the outside of.
            if (toEye * face.sign <= 0f) continue
            for (i in 0 until 4) {
                val c = face.corners[i]
                val (u, v) = UVS[i]
                buf.addVertex(m, c[0] * half, c[1] * half, c[2] * half)
                    .setUv(u, v)
                    // The cube's own frame, remapped to 0..1 so it survives the byte channel.
                    .setColor(pack(c[0]), pack(c[1]), pack(c[2]), alpha)
                    .setNormal(0f, 0f, 1f)
            }
        }
        pose.popPose()
    }

    /**
     * A tear pinned to the face it opened on, its outline seeded by that block so it is the same hole every
     * time and no two neighbouring rifts are the same shape.
     *
     * Pinned rather than billboarded on purpose: the depth layers parallax against the eye, and a quad that
     * always faces you has no eye direction to speak of. Seen edge-on it thins to a line, which is what a
     * tear should do.
     *
     * **The channels carry the camera, not appearance.** UV is the fragment's own position in the tear's
     * plane; Normal is the direction to the camera in that same frame; and the blue channel is how FAR the
     * camera is, in half-widths. With all three the shader reconstructs a real perspective ray per fragment
     * — so the layers fan out as you come close and flatten as you back off, instead of sliding rigidly.
     * That is the end portal's trick, and it is why the camera has to reach the shader at all.
     */
    private fun tear(rift: Rift, at: Vec3, anchorPos: BlockPos, facing: Vec3, half: Float, alpha: Int, buf: VertexConsumer, pose: PoseStack, partialTick: Float) {
        pose.pushPose()
        pose.translate(at.x, at.y, at.z)
        pose.mulPose(Axis.YP.rotationDegrees(Math.toDegrees(atan2(facing.x, facing.z)).toFloat()))
        pose.mulPose(Axis.XP.rotationDegrees(Math.toDegrees(-asin(facing.y.coerceIn(-1.0, 1.0))).toFloat()))
        // The wobble, in the tear's own frame so it reads the same whichever way the mouth faces.
        pose.mulPose(Axis.YP.rotationDegrees(rift.yawWobble(partialTick)))
        pose.mulPose(Axis.XP.rotationDegrees(rift.tiltWobble(partialTick)))
        pose.mulPose(Axis.ZP.rotation(rift.phase))
        val m = pose.last().pose()

        // The camera, in the tear's own frame. The origin of the incoming positions IS the camera, so
        // inverting the pose and transforming the origin lands it exactly where the shader needs it.
        val cam = Matrix4f(m).invert().transformPosition(org.joml.Vector3f(0f, 0f, 0f))
        val dist = (cam.length() / half).coerceIn(0f, MAX_CAM_HALFWIDTHS)
        cam.normalize()

        val seed = seedOf(anchorPos)
        // Blue carries the camera distance in quarter half-widths, with the top bit flagging the direction —
        // that one bit is what makes a mouth taking things in a different colour from one letting them out.
        val packedDist = (dist * 4f).toInt().coerceIn(0, 127) or (if (rift.inward) 0x80 else 0)

        for (i in 0 until 4) {
            val (u, v) = UVS[i]
            val lx = u * 2f - 1f
            val ly = v * 2f - 1f
            buf.addVertex(m, lx * half, ly * half, 0f)
                .setUv(lx, ly)
                .setColor(seed and 0xFF, (seed shr 8) and 0xFF, packedDist, alpha)
                .setNormal(cam.x, cam.y, cam.z)
        }
        pose.popPose()
    }

    /**
     * Sixteen bits of shape from a block position.
     *
     * A hash rather than a stored value: the same block always produces the same tear, on every client, with
     * nothing synced and nothing saved. The multipliers are odd and coprime so neighbouring blocks — which
     * is exactly where two rifts sit side by side — do not land on the same shape.
     */
    private fun seedOf(pos: BlockPos): Int {
        var h = pos.x * 73856093 xor pos.y * 19349663 xor pos.z * 83492791
        h = h xor (h ushr 13)
        h *= 1274126177
        return (h xor (h ushr 16)) and 0xFFFF
    }

    /** Past this the tear is a few pixels anyway, and the byte would overflow. */
    private const val MAX_CAM_HALFWIDTHS = 31.5f

    /** A -1..1 coordinate into the 0..255 the colour channel carries. */
    private fun pack(v: Float): Int = ((v * 0.5f + 0.5f) * 255f).toInt().coerceIn(0, 255)
}
