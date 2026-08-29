package bpm.client.render

import bpm.world.entity.LinkerPulseEntity
import bpm.world.entity.WardenBoltEntity
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.Entity
import kotlin.math.atan2
import kotlin.math.sqrt

/** How a bolt looks: an additive core with a wider, fainter sheath, both plain colour. */
class BoltStyle(
    val coreR: Float, val coreG: Float, val coreB: Float,
    val sheathR: Float, val sheathG: Float, val sheathB: Float,
    val radius: Float = 0.05f, val length: Float = 0.7f,
)

/**
 * A bolt as an energy spear laid along its velocity. No texture: the lightning render type is plain colour,
 * added onto whatever is behind it, so it glows without a glow mask.
 */
open class EnergyBoltRenderer<T : Entity>(context: EntityRendererProvider.Context, private val style: (T) -> BoltStyle) : EntityRenderer<T>(context) {
    override fun getTextureLocation(entity: T): ResourceLocation = TextureAtlas.LOCATION_BLOCKS

    override fun render(entity: T, yaw: Float, partialTick: Float, pose: PoseStack, buffers: MultiBufferSource, light: Int) {
        val v = entity.deltaMovement
        if (v.lengthSqr() < 1e-6) return
        val s = style(entity)
        pose.pushPose()
        val yawDeg = Math.toDegrees(atan2(v.x, v.z)).toFloat()
        val pitchDeg = (-Math.toDegrees(atan2(v.y, sqrt(v.x * v.x + v.z * v.z)))).toFloat()
        pose.mulPose(Axis.YP.rotationDegrees(yawDeg))
        pose.mulPose(Axis.XP.rotationDegrees(pitchDeg))
        val c = buffers.getBuffer(RenderType.lightning())
        box(c, pose, s.radius, s.length, s.coreR, s.coreG, s.coreB, 1f)
        box(c, pose, s.radius * 2.4f, s.length * 1.15f, s.sheathR, s.sheathG, s.sheathB, 0.3f)
        pose.popPose()
        super.render(entity, yaw, partialTick, pose, buffers, light)
    }

    /** A box [r] wide either side and [l] long either way along +Z, one flat colour, alpha [a]. */
    private fun box(c: VertexConsumer, pose: PoseStack, r: Float, l: Float, red: Float, green: Float, blue: Float, a: Float) {
        val m = pose.last().pose()
        fun v(x: Float, y: Float, z: Float) { c.addVertex(m, x, y, z).setColor(red, green, blue, a) }
        v(r, -r, -l); v(r, r, -l); v(r, r, l); v(r, -r, l)
        v(-r, -r, l); v(-r, r, l); v(-r, r, -l); v(-r, -r, -l)
        v(-r, r, -l); v(r, r, -l); v(r, r, l); v(-r, r, l)
        v(-r, -r, l); v(r, -r, l); v(r, -r, -l); v(-r, -r, -l)
        v(-r, -r, l); v(r, -r, l); v(r, r, l); v(-r, r, l)
        v(-r, r, -l); v(r, r, -l); v(r, -r, -l); v(-r, -r, -l)
    }
}

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
