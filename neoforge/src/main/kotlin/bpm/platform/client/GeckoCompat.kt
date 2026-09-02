package bpm.platform.client

import bpm.platform.ResourceLocation
import software.bernie.geckolib.animatable.GeoAnimatable
import bpm.platform.BakedGeoModel
import software.bernie.geckolib.model.GeoModel
import bpm.platform.GeoRenderer
import bpm.platform.AutoGlowingGeoLayer

/**
 * The two places GeckoLib's own signatures changed between the 4.9 line (1.21.1) and the 4.8 line (1.21.4).
 *
 * GeckoLib's version numbers are per Minecraft version, not a ladder: 4.9.2 is the last build for 1.21.1
 * and 4.8.5 the last for 1.21.4, so the "older" number is the newer API. Both are terminal — there is no
 * shared version to move to — which is why these are switched rather than aligned.
 *
 * Everything else GeckoLib exposes to this mod is identical across the two: `GeoRenderer.getRenderType`,
 * `renderRecursively`, `GeoBlockRenderer`, `GeoEntityRenderer`, `MolangQueries`, `DataTickets`, and — the
 * one that mattered most — `GeoRenderProvider`. See [bpm.client.render.BpmItemRenderers].
 */

/**
 * A GeckoLib model at fixed asset paths.
 *
 * `getModelResource`/`getTextureResource` gained a `GeoRenderer` argument, and on the 4.8 line the old
 * one-argument form is gone rather than deprecated. The two-argument form exists on both, so it is the
 * one that carries the answer; on 1.21.1 the one-argument form is still abstract and has to be
 * implemented as well, which is what the directive adds.
 *
 * `getAnimationResource` takes one argument on both and stays with the shared subclass.
 */
abstract class PathGeoModelBase<T : GeoAnimatable>(
    private val geo: ResourceLocation,
    private val tex: ResourceLocation,
) : GeoModel<T>() {

    /** The geometry for [animatable]. Fixed by default; a subclass may vary it. */
    open fun modelPath(animatable: T): ResourceLocation = geo

    /** The texture for [animatable]. Fixed by default; the monitor varies it with its power state. */
    open fun texturePath(animatable: T): ResourceLocation = tex

    override fun getModelResource(animatable: T, renderer: GeoRenderer<T>?): ResourceLocation = modelPath(animatable)

    override fun getTextureResource(animatable: T, renderer: GeoRenderer<T>?): ResourceLocation = texturePath(animatable)

    //? if <1.21.2 {
    override fun getModelResource(animatable: T): ResourceLocation = modelPath(animatable)

    override fun getTextureResource(animatable: T): ResourceLocation = texturePath(animatable)
    //?}
}

/**
 * GeckoLib's glow layer, minus its one sharp edge — see [bpm.client.render.GlowLayer] for the why.
 *
 * `GeoRenderLayer.render` gained a trailing colour int on the 4.8 line. An arity change, so it lives
 * here and delegates to a shared decision that has no signature at all.
 */
abstract class GlowLayerBase<T : GeoAnimatable>(renderer: GeoRenderer<T>) : AutoGlowingGeoLayer<T>(renderer) {

    /** Whether this animatable's texture has anything worth glowing. */
    protected abstract fun glows(animatable: T): Boolean

    //? if >=1.21.2 {
    /*override fun render(
        poseStack: com.mojang.blaze3d.vertex.PoseStack,
        animatable: T,
        bakedModel: BakedGeoModel,
        renderType: bpm.platform.RenderType?,
        bufferSource: net.minecraft.client.renderer.MultiBufferSource,
        buffer: com.mojang.blaze3d.vertex.VertexConsumer?,
        partialTick: Float,
        packedLight: Int,
        packedOverlay: Int,
        colour: Int,
    ) {
        if (!glows(animatable)) return
        super.render(poseStack, animatable, bakedModel, renderType, bufferSource, buffer, partialTick, packedLight, packedOverlay, colour)
    }
    *///?} else {
    override fun render(
        poseStack: com.mojang.blaze3d.vertex.PoseStack,
        animatable: T,
        bakedModel: BakedGeoModel,
        renderType: bpm.platform.RenderType?,
        bufferSource: net.minecraft.client.renderer.MultiBufferSource,
        buffer: com.mojang.blaze3d.vertex.VertexConsumer?,
        partialTick: Float,
        packedLight: Int,
        packedOverlay: Int,
    ) {
        if (!glows(animatable)) return
        super.render(poseStack, animatable, bakedModel, renderType, bufferSource, buffer, partialTick, packedLight, packedOverlay)
    }
    //?}
}
