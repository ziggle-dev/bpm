package bpm.platform.client

//? if >=1.21.9 {
/*/**
 * A `SubmitNodeCollector` that draws instead of collecting.
 *
 * From 1.21.9 an item is drawn by resolving it to an `ItemStackRenderState` and SUBMITTING that; the
 * only exit from a resolved layer is a package-private `submit` taking a collector. The level render
 * this mod draws its effects from is handed a `MultiBufferSource` and no collector, and NeoForge exposes
 * a collector on six events, all of them about entities or the player's hand. So there is no collector
 * to be had where the items need drawing.
 *
 * But `SubmitNodeCollector` is an INTERFACE, and the thing it is handed -- for an item, the quads, the
 * render type, the tints and the foil -- is exactly what `ItemRenderer.renderItem` takes alongside a
 * buffer source, and that method is public and static. So rather than find a collector, be one: catch
 * the submission and draw it immediately into the buffer source we already hold.
 *
 * The alternative considered and rejected was drawing the items from the controller's block entity
 * renderer, which does get a collector. A block entity renderer is frustum-culled and range-limited to
 * about sixty-four blocks, and a link in this mod can be hundreds of blocks long -- the motes would have
 * vanished exactly when the two ends were far enough apart to be worth watching.
 *
 * Everything other than an item is ignored rather than implemented. This is not a general-purpose
 * collector and is handed nothing else: it is passed straight to `ItemStackRenderState.submit` and
 * nowhere near the level's own submission. `submitCustomGeometry` is honoured anyway, because a special
 * item model -- which is what a GeckoLib item is -- takes that path and it costs one line.
 */
internal class ImmediateCollector(
    private val buffers: net.minecraft.client.renderer.MultiBufferSource,
) : net.minecraft.client.renderer.SubmitNodeCollector {

    override fun order(order: Int): net.minecraft.client.renderer.OrderedSubmitNodeCollector = this

    override fun submitItem(
        pose: com.mojang.blaze3d.vertex.PoseStack,
        context: net.minecraft.world.item.ItemDisplayContext,
        light: Int,
        overlay: Int,
        outline: Int,
        tints: IntArray,
        quads: List<net.minecraft.client.renderer.block.model.BakedQuad>,
        renderType: net.minecraft.client.renderer.rendertype.RenderType,
        foil: net.minecraft.client.renderer.item.ItemStackRenderState.FoilType,
    ) {
        net.minecraft.client.renderer.entity.ItemRenderer.renderItem(
            context, pose, buffers, light, overlay, tints, quads, renderType, foil,
        )
    }

    override fun submitCustomGeometry(
        pose: com.mojang.blaze3d.vertex.PoseStack,
        renderType: net.minecraft.client.renderer.rendertype.RenderType,
        renderer: net.minecraft.client.renderer.SubmitNodeCollector.CustomGeometryRenderer,
    ) {
        renderer.render(pose.last(), buffers.getBuffer(renderType))
    }

    // ---- everything else is not submitted here, and saying so is the honest implementation -------------

    override fun submitShadow(
        pose: com.mojang.blaze3d.vertex.PoseStack,
        strength: Float,
        pieces: List<net.minecraft.client.renderer.entity.state.EntityRenderState.ShadowPiece>,
    ) = Unit

    override fun submitNameTag(
        pose: com.mojang.blaze3d.vertex.PoseStack,
        at: net.minecraft.world.phys.Vec3?,
        light: Int,
        text: net.minecraft.network.chat.Component,
        seeThrough: Boolean,
        background: Int,
        distance: Double,
        camera: net.minecraft.client.renderer.state.CameraRenderState,
    ) = Unit

    override fun submitText(
        pose: com.mojang.blaze3d.vertex.PoseStack,
        x: Float,
        y: Float,
        text: net.minecraft.util.FormattedCharSequence,
        dropShadow: Boolean,
        mode: net.minecraft.client.gui.Font.DisplayMode,
        light: Int,
        colour: Int,
        background: Int,
        outline: Int,
    ) = Unit

    override fun submitFlame(
        pose: com.mojang.blaze3d.vertex.PoseStack,
        state: net.minecraft.client.renderer.entity.state.EntityRenderState,
        orientation: org.joml.Quaternionf,
    ) = Unit

    override fun submitLeash(
        pose: com.mojang.blaze3d.vertex.PoseStack,
        leash: net.minecraft.client.renderer.entity.state.EntityRenderState.LeashState,
    ) = Unit

    override fun <S : Any> submitModel(
        model: net.minecraft.client.model.Model<in S>,
        state: S,
        pose: com.mojang.blaze3d.vertex.PoseStack,
        renderType: net.minecraft.client.renderer.rendertype.RenderType,
        light: Int,
        overlay: Int,
        colour: Int,
        sprite: net.minecraft.client.renderer.texture.TextureAtlasSprite?,
        outline: Int,
        crumbling: net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay?,
    ) = Unit

    override fun submitModelPart(
        part: net.minecraft.client.model.geom.ModelPart,
        pose: com.mojang.blaze3d.vertex.PoseStack,
        renderType: net.minecraft.client.renderer.rendertype.RenderType,
        light: Int,
        overlay: Int,
        sprite: net.minecraft.client.renderer.texture.TextureAtlasSprite?,
        skipRender: Boolean,
        skipChildren: Boolean,
        colour: Int,
        crumbling: net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay?,
        outline: Int,
    ) = Unit

    override fun submitBlock(
        pose: com.mojang.blaze3d.vertex.PoseStack,
        state: net.minecraft.world.level.block.state.BlockState,
        light: Int,
        overlay: Int,
        outline: Int,
    ) = Unit

    override fun submitMovingBlock(
        pose: com.mojang.blaze3d.vertex.PoseStack,
        state: net.minecraft.client.renderer.block.MovingBlockRenderState,
    ) = Unit

    override fun submitBlockModel(
        pose: com.mojang.blaze3d.vertex.PoseStack,
        renderType: net.minecraft.client.renderer.rendertype.RenderType,
        model: net.minecraft.client.renderer.block.model.BlockStateModel,
        red: Float,
        green: Float,
        blue: Float,
        light: Int,
        overlay: Int,
        outline: Int,
    ) = Unit

    override fun submitParticleGroup(
        group: net.minecraft.client.renderer.SubmitNodeCollector.ParticleGroupRenderer,
    ) = Unit
}
*///?}
