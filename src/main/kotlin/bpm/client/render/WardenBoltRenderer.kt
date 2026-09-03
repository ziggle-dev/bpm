package bpm.client.render

import bpm.platform.client.addVertex
import bpm.platform.client.endOfVertex
import bpm.platform.client.setColor

import bpm.world.entity.LinkerPulseEntity
import bpm.world.entity.WardenBoltEntity
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import bpm.platform.RenderType
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import kotlin.math.atan2
import kotlin.math.sqrt

/** How a bolt looks: an additive core with a wider, fainter sheath, both plain colour. */
class BoltStyle(
    val coreR: Float, val coreG: Float, val coreB: Float,
    val sheathR: Float, val sheathG: Float, val sheathB: Float,
    val radius: Float = 0.05f, val length: Float = 0.7f,
)

/**
 * Draw a bolt of [style] laid along [motion]. No texture: the lightning render type is plain colour,
 * added onto whatever is behind it, so it glows without a glow mask.
 *
 * A pose, somewhere to draw and trigonometry -- nothing here knows what a render state is, which is why
 * this is the half that stays shared while [bpm.platform.client.BoltRendererBase] carries the half that
 * changed.
 */
fun drawBolt(pose: PoseStack, draw: bpm.platform.client.WorldDraw, motion: Vec3, style: BoltStyle) {
    if (motion.lengthSqr() < 1e-6) return
    pose.pushPose()
    val yawDeg = Math.toDegrees(atan2(motion.x, motion.z)).toFloat()
    val pitchDeg = (-Math.toDegrees(atan2(motion.y, sqrt(motion.x * motion.x + motion.z * motion.z)))).toFloat()
    pose.mulPose(Axis.YP.rotationDegrees(yawDeg))
    pose.mulPose(Axis.XP.rotationDegrees(pitchDeg))
    draw.into(pose, bpm.platform.client.lightning()) { p, c ->
        box(c, p, style.radius, style.length, style.coreR, style.coreG, style.coreB, 1f)
        box(c, p, style.radius * 2.4f, style.length * 1.15f, style.sheathR, style.sheathG, style.sheathB, 0.3f)
    }
    pose.popPose()
}

/** A box [r] wide either side and [l] long either way along +Z, one flat colour, alpha [a]. */
private fun box(c: VertexConsumer, pose: PoseStack.Pose, r: Float, l: Float, red: Float, green: Float, blue: Float, a: Float) {
    val m = pose.pose()
    fun v(x: Float, y: Float, z: Float) { c.addVertex(m, x, y, z).setColor(red, green, blue, a).endOfVertex() }
    v(r, -r, -l); v(r, r, -l); v(r, r, l); v(r, -r, l)
    v(-r, -r, l); v(-r, r, l); v(-r, r, -l); v(-r, -r, -l)
    v(-r, r, -l); v(r, r, -l); v(r, r, l); v(-r, r, l)
    v(-r, -r, l); v(r, -r, l); v(r, -r, -l); v(-r, -r, -l)
    v(-r, -r, l); v(r, -r, l); v(r, r, l); v(-r, r, l)
    v(-r, r, -l); v(r, r, -l); v(r, -r, -l); v(-r, -r, -l)
}

/** A bolt renderer is now only a choice of style; the drawing is above and the plumbing is per band. */
open class EnergyBoltRenderer<T : Entity>(context: EntityRendererProvider.Context, style: (T) -> BoltStyle) :
    bpm.platform.client.BoltRendererBase<T>(context, style)

/** Teal for a volley bolt; orchid, thicker and shorter for the seeker. */
class WardenBoltRenderer(context: EntityRendererProvider.Context) : EnergyBoltRenderer<WardenBoltEntity>(
    context,
    { bolt -> if (bolt.homing) SEEKER else VOLLEY },
) {
    companion object {
        val VOLLEY = BoltStyle(0.55f, 1.0f, 0.92f, 0.30f, 0.95f, 0.85f, radius = 0.05f, length = 0.7f)
        val SEEKER = BoltStyle(0.85f, 0.45f, 1.0f, 0.65f, 0.25f, 0.95f, radius = 0.09f, length = 0.55f)
    }
}

/** The linker's pulse: white with a teal sheath, small and bright. */
class LinkerPulseRenderer(context: EntityRendererProvider.Context) : EnergyBoltRenderer<LinkerPulseEntity>(
    context,
    { pulse -> if (pulse.tracking) TRACK else PULSE },
) {
    companion object {
        val PULSE = BoltStyle(1.0f, 1.0f, 1.0f, 0.4f, 1.0f, 0.9f, radius = 0.06f, length = 0.45f)
        val TRACK = BoltStyle(1.0f, 1.0f, 1.0f, 1.0f, 0.75f, 0.35f, radius = 0.09f, length = 0.4f)
    }
}
