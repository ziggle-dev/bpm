package bpm.platform.client

import bpm.client.render.BoltStyle
import bpm.client.render.drawBolt
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.world.entity.Entity

/**
 * An entity renderer, named without saying how many type arguments that takes.
 *
 * 1.21.2 split every entity renderer in two: an extract phase that reads the entity on the tick thread
 * into an `EntityRenderState`, and a draw phase that only ever sees that state. `EntityRenderer` gained
 * the state as a second type parameter, so the name alone no longer means the same thing on both sides.
 *
 * Registration does not care which -- it only ever passes the renderer along -- so a star projection is
 * honest here and keeps [RendererSink] out of it.
 */
//? if >=1.21.2 {
/*typealias EntityRendererOf<T> = EntityRenderer<T, *>
*///?} else {
typealias EntityRendererOf<T> = EntityRenderer<T>
//?}

/**
 * A block entity renderer, likewise.
 *
 * 1.21.9 did to block entities what 1.21.2 did to entities: `BlockEntityRenderer` gained a render-state
 * type parameter. Unlike the entity case this one is NOT star-projected, because NeoForge's registration
 * has to infer it and a star gives it nothing to infer from. Naming it costs nothing, because every
 * block entity renderer in this mod is a [GeoBlockRendererBase] and they all share one state class.
 */
//? if >=1.21.9 {
/*typealias BlockEntityRendererOf<T> = net.minecraft.client.renderer.blockentity.BlockEntityRenderer<T, BpmBlockRenderState>
*///?} else {
typealias BlockEntityRendererOf<T> = net.minecraft.client.renderer.blockentity.BlockEntityRenderer<T>
//?}

/**
 * A bolt drawn as an energy spear laid along its velocity.
 *
 * The one thing this renderer needs from the entity is its motion, and the split above is exactly about
 * when you are allowed to ask for it. Before 1.21.2 the draw call has the entity in hand; after, the
 * motion has to be copied into a render state during extraction and read back at draw time. Same two
 * facts either way -- a velocity and a style -- so every path hands them to the same shared drawing code
 * and none of them carries any of the geometry. 1.21.9 adds a third: the draw itself is no longer
 * performed here but described to a collector, which is what [WorldDraw] exists to hide.
 */
//? if >=1.21.9 {
/*class BoltRenderState : net.minecraft.client.renderer.entity.state.EntityRenderState() {
    var motion: net.minecraft.world.phys.Vec3 = net.minecraft.world.phys.Vec3.ZERO
    var style: BoltStyle? = null
}

open class BoltRendererBase<T : Entity>(
    context: EntityRendererProvider.Context,
    private val style: (T) -> BoltStyle,
) : EntityRenderer<T, BoltRenderState>(context) {

    override fun createRenderState(): BoltRenderState = BoltRenderState()

    override fun extractRenderState(entity: T, state: BoltRenderState, partialTick: Float) {
        super.extractRenderState(entity, state, partialTick)
        state.motion = entity.deltaMovement
        state.style = style(entity)
    }

    /** `render` became `submit`: the draw is described now and performed later. */
    override fun submit(
        state: BoltRenderState,
        pose: PoseStack,
        collector: net.minecraft.client.renderer.SubmitNodeCollector,
        cameraState: net.minecraft.client.renderer.state.CameraRenderState,
    ) {
        state.style?.let { drawBolt(pose, CollectorDraw(collector), state.motion, it) }
        super.submit(state, pose, collector, cameraState)
    }
}
*///?} elif >=1.21.2 {
/*class BoltRenderState : net.minecraft.client.renderer.entity.state.EntityRenderState() {
    var motion: net.minecraft.world.phys.Vec3 = net.minecraft.world.phys.Vec3.ZERO
    var style: BoltStyle? = null
}

open class BoltRendererBase<T : Entity>(
    context: EntityRendererProvider.Context,
    private val style: (T) -> BoltStyle,
) : EntityRenderer<T, BoltRenderState>(context) {

    override fun createRenderState(): BoltRenderState = BoltRenderState()

    override fun extractRenderState(entity: T, state: BoltRenderState, partialTick: Float) {
        super.extractRenderState(entity, state, partialTick)
        state.motion = entity.deltaMovement
        state.style = style(entity)
    }

    override fun render(state: BoltRenderState, pose: PoseStack, buffers: MultiBufferSource, light: Int) {
        state.style?.let { drawBolt(pose, BufferedDraw(buffers), state.motion, it) }
        super.render(state, pose, buffers, light)
    }
}
*///?} else {
open class BoltRendererBase<T : Entity>(
    context: EntityRendererProvider.Context,
    private val style: (T) -> BoltStyle,
) : EntityRenderer<T>(context) {

    /** Never sampled -- the lightning render type is plain colour -- but the base class demands one. */
    override fun getTextureLocation(entity: T): bpm.platform.ResourceLocation =
        net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS

    override fun render(entity: T, yaw: Float, partialTick: Float, pose: PoseStack, buffers: MultiBufferSource, light: Int) {
        drawBolt(pose, BufferedDraw(buffers), entity.deltaMovement, style(entity))
        super.render(entity, yaw, partialTick, pose, buffers, light)
    }
}
//?}
