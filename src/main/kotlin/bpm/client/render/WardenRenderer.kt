package bpm.client.render

import bpm.Bpm
import bpm.world.entity.QuantumWardenEntity
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.resources.ResourceLocation
import software.bernie.geckolib.renderer.GeoEntityRenderer

private fun rl(path: String) = ResourceLocation.fromNamespaceAndPath(Bpm.ID, path)

class WardenModel : PathGeoModel<QuantumWardenEntity>(
    rl("geo/entity/quantum_warden.geo.json"),
    rl("animations/entity/quantum_warden.animation.json"),
    rl("textures/entity/quantum_warden.png"),
)

/** The Warden: translucent for the bolts' alpha, the glow mask on top, a wide shadow for a wide boss. */
class WardenRenderer(context: EntityRendererProvider.Context) : GeoEntityRenderer<QuantumWardenEntity>(context, WardenModel()) {
    init {
        addRenderLayer(GlowLayer(this))
        shadowRadius = 1.4f
    }

    override fun getRenderType(animatable: QuantumWardenEntity, texture: ResourceLocation, bufferSource: MultiBufferSource?, partialTick: Float): RenderType =
        RenderType.entityTranslucent(texture)
}
