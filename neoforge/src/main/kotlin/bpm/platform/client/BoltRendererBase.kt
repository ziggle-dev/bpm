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
 * A bolt drawn as an energy spear laid along its velocity.
 *
 * The one thing this renderer needs from the entity is its motion, and the split above is exactly about
 * when you are allowed to ask for it. Before 1.21.2 the draw call has the entity in hand; after, the
 * motion has to be copied into a render state during extraction and read back at draw time. Same two
 * facts either way -- a velocity and a style -- so both paths hand them to the same shared drawing code
 * and neither carries any of the geometry.
 */
//? if >=1.21.2 {
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
        state.style?.let { drawBolt(pose, buffers, state.motion, it) }
        super.render(state, pose, buffers, light)
    }
}
*///?} else {
open class BoltRendererBase<T : Entity>(
    context: EntityRendererProvider.Context,
    private val style: (T) -> BoltStyle,
) : EntityRenderer<T>(context) {

    /** Never sampled -- the lightning render type is plain colour -- but the base class demands one. */
    override fun getTextureLocation(entity: T): net.minecraft.resources.ResourceLocation =
        net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS

    override fun render(entity: T, yaw: Float, partialTick: Float, pose: PoseStack, buffers: MultiBufferSource, light: Int) {
        drawBolt(pose, buffers, entity.deltaMovement, style(entity))
        super.render(entity, yaw, partialTick, pose, buffers, light)
    }
}
//?}
