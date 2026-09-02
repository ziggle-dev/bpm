package bpm.client.render

import bpm.Bpm
import bpm.world.entity.QuantumWardenEntity
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import bpm.platform.RenderType
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.core.particles.ParticleTypes
import bpm.platform.ResourceLocation
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f
import bpm.platform.GeoBone

private fun rl(path: String) = ResourceLocation.fromNamespaceAndPath(Bpm.ID, path)

class WardenModel : PathGeoModel<QuantumWardenEntity>(
    rl("geo/entity/quantum_warden.geo.json"),
    rl("animations/entity/quantum_warden.animation.json"),
    rl("textures/entity/quantum_warden.png"),
)

/** The Warden: translucent for the bolts' alpha, the glow mask on top, a wide shadow for a wide boss. */
class WardenRenderer(context: EntityRendererProvider.Context) : bpm.platform.client.GeoEntityRendererBase<QuantumWardenEntity>(context, WardenModel()) {
    init {
        addGlow()
        shadowRadius = 1.4f
    }

    override fun renderTypeFor(texture: ResourceLocation): RenderType =
        bpm.platform.client.entityTranslucent(texture)

    /**
     * Publish the two beam roots, and spark at them.
     *
     * The server spawns bolts from [bpm.world.entity.QuantumWardenEntity.claw], which is trigonometry
     * against the body yaw — 1.2 out, 0.4 forward, 1.8 up — and has to be, because the bone matrices only
     * exist here on the client. What that hand-written offset cannot do is follow the ANIMATION: the arms
     * swing through `attack_beam`, drop through `stagger` and vanish through `blink_out`, and a fixed offset
     * ignores all of it. So the authoritative bolt keeps its offset and the tell that reads at range — the
     * charge at each claw — comes off the bone, which means it tracks the arm wherever the clip puts it.
     */
    override fun onBones(bones: bpm.platform.client.BoneAccess, entityId: Int) {
        for (claw in listOf(BEAM_L, BEAM_R)) {
            bones.watch(claw) { at ->
                bpm.client.render.BoneAnchors.capture(entityId, claw, at)
                // The warden is looked up rather than held: a render state exists precisely so the draw
                // pass does not keep the entity, and one map lookup a frame is the honest price of that.
                (net.minecraft.client.Minecraft.getInstance().level?.getEntity(entityId) as? QuantumWardenEntity)
                    ?.let { spark(it, at) }
            }
        }
    }

    /** A charge at the claw — thicker once the plates are down, which is when it is worth closing on. */
    private fun spark(warden: QuantumWardenEntity, at: Vec3) {
        val level = warden.level()
        val chance = if (warden.shielded) SPARK_SHIELDED else SPARK_EXPOSED
        if (level.random.nextFloat() > chance) return
        level.addParticle(
            ParticleTypes.ELECTRIC_SPARK,
            at.x + (level.random.nextDouble() - 0.5) * 0.25,
            at.y + (level.random.nextDouble() - 0.5) * 0.25,
            at.z + (level.random.nextDouble() - 0.5) * 0.25,
            0.0, 0.01, 0.0,
        )
    }

    companion object {
        /** The roots of the two arm beams — where a bolt leaves the model. */
        const val BEAM_L = "beam_l"
        const val BEAM_R = "beam_r"
        private const val SPARK_SHIELDED = 0.06f
        private const val SPARK_EXPOSED = 0.22f
    }
}
