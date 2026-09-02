package bpm.platform.client

/**
 * GeckoLib's glow layer, minus its one sharp edge.
 *
 * A `_glowmask` with no visible pixel -- an "off" texture with nothing lit -- makes the stock layer throw
 * while registering the emissive texture, and crashes the client the first time the model is drawn,
 * including in the creative tab. This looks at the mask first and skips the glow when there is nothing to
 * glow; a missing mask is left to GeckoLib, which may still find glow sections in the base texture's mcmeta.
 *
 * The class lives per version rather than in the shared tree for two reasons, and it needs THREE bodies:
 * the draw method gained a colour argument at 1.21.2, the layer became generic in three parameters at
 * 1.21.5 (GeckoLib 5), and at 1.21.9 it stopped drawing in favour of submitting a task -- FOUR bodies,
 * because those last two changes did not arrive together. The decision it makes --
 * [bpm.client.render.Glowmasks.glows] -- is shared and has never changed.
 */
//? if >=1.21.9 {
/*class BpmGlowLayer<T : bpm.platform.GeoAnimatable, O : Any, R : bpm.platform.GeoRenderState>(
    renderer: software.bernie.geckolib.renderer.base.GeoRenderer<T, O, R>,
) : software.bernie.geckolib.renderer.layer.builtin.AutoGlowingGeoLayer<T, O, R>(renderer) {

    override fun submitRenderTask(
        pass: bpm.platform.RenderPassInfo<R>,
        collector: net.minecraft.client.renderer.SubmitNodeCollector,
    ) {
        if (!bpm.client.render.Glowmasks.glows(getTextureResource(pass.renderState()))) return
        super.submitRenderTask(pass, collector)
    }
}
*///?} elif >=1.21.5 {
/*// Three type parameters, as on the band above, but the layer still DRAWS rather than submitting, and
// AutoGlowingGeoLayer has not moved into `layer.builtin` yet. Both halves of that are why this file
// needs a fourth body rather than a relaxed condition on one of the others.
class BpmGlowLayer<T : bpm.platform.GeoAnimatable, O : Any, R : bpm.platform.GeoRenderState>(
    renderer: software.bernie.geckolib.renderer.base.GeoRenderer<T, O, R>,
) : software.bernie.geckolib.renderer.layer.AutoGlowingGeoLayer<T, O, R>(renderer) {

    override fun render(
        renderState: R,
        poseStack: com.mojang.blaze3d.vertex.PoseStack,
        bakedModel: bpm.platform.BakedGeoModel,
        renderType: bpm.platform.RenderType?,
        bufferSource: net.minecraft.client.renderer.MultiBufferSource,
        buffer: com.mojang.blaze3d.vertex.VertexConsumer?,
        packedLight: Int,
        packedOverlay: Int,
        colour: Int,
    ) {
        if (!bpm.client.render.Glowmasks.glows(getTextureResource(renderState))) return
        super.render(renderState, poseStack, bakedModel, renderType, bufferSource, buffer, packedLight, packedOverlay, colour)
    }
}
*///?} elif >=1.21.2 {
/*class BpmGlowLayer<T : bpm.platform.GeoAnimatable>(
    renderer: bpm.platform.GeoRenderer<T>,
) : bpm.platform.AutoGlowingGeoLayer<T>(renderer) {

    override fun render(
        poseStack: com.mojang.blaze3d.vertex.PoseStack,
        animatable: T,
        bakedModel: bpm.platform.BakedGeoModel,
        renderType: bpm.platform.RenderType?,
        bufferSource: net.minecraft.client.renderer.MultiBufferSource,
        buffer: com.mojang.blaze3d.vertex.VertexConsumer?,
        partialTick: Float,
        packedLight: Int,
        packedOverlay: Int,
        renderColor: Int,
    ) {
        if (!bpm.client.render.Glowmasks.glows(getTextureResource(animatable))) return
        super.render(poseStack, animatable, bakedModel, renderType, bufferSource, buffer, partialTick, packedLight, packedOverlay, renderColor)
    }
}
*///?} else {
class BpmGlowLayer<T : bpm.platform.GeoAnimatable>(
    renderer: bpm.platform.GeoRenderer<T>,
) : bpm.platform.AutoGlowingGeoLayer<T>(renderer) {

    override fun render(
        poseStack: com.mojang.blaze3d.vertex.PoseStack,
        animatable: T,
        bakedModel: bpm.platform.BakedGeoModel,
        renderType: bpm.platform.RenderType?,
        bufferSource: net.minecraft.client.renderer.MultiBufferSource,
        buffer: com.mojang.blaze3d.vertex.VertexConsumer?,
        partialTick: Float,
        packedLight: Int,
        packedOverlay: Int,
    ) {
        if (!bpm.client.render.Glowmasks.glows(getTextureResource(animatable))) return
        super.render(poseStack, animatable, bakedModel, renderType, bufferSource, buffer, partialTick, packedLight, packedOverlay)
    }
}
//?}
