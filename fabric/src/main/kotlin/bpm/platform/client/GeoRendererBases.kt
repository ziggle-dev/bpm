package bpm.platform.client

import software.bernie.geckolib.animatable.GeoAnimatable
import software.bernie.geckolib.model.GeoModel

/**
 * The GeckoLib renderer base classes, and the render states 5.x wants with them.
 *
 * GeckoLib 5 moved to Minecraft's extract-then-draw model: a renderer is generic in a RENDER STATE as
 * well as an animatable, the state is filled on the tick thread, and the draw pass only ever sees the
 * state. `GeoBlockRenderer<T>` became `GeoBlockRenderer<T, R>`, and R must be both Minecraft's render
 * state for that kind of object AND GeckoLib's `GeoRenderState`.
 *
 * GeckoLib mixes `GeoRenderState` into vanilla's render states at RUNTIME, which does not help at
 * compile time -- verified rather than assumed, with a throwaway probe that failed exactly as a
 * non-injected interface would. So the states are declared here. They cost almost nothing:
 * `GeoRenderState` has exactly one abstract member, the data map, and everything else is a default.
 *
 * These bases exist so the shared renderers can name one type on every version. The alternative -- a
 * type argument that exists on one band and not the other -- cannot be written in a shared file at all.
 */

//? if >=1.21.9 {
/*/** A block entity's render state, carrying GeckoLib's per-frame data alongside Minecraft's. */
class BpmBlockRenderState :
    net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState(),
    software.bernie.geckolib.renderer.base.GeoRenderState {
    private val data = HashMap<software.bernie.geckolib.constant.dataticket.DataTicket<*>, Any>()
    override fun getDataMap(): MutableMap<software.bernie.geckolib.constant.dataticket.DataTicket<*>, Any> = data
}

/** The same for an entity. */
class BpmEntityRenderState :
    net.minecraft.client.renderer.entity.state.EntityRenderState(),
    software.bernie.geckolib.renderer.base.GeoRenderState {
    private val data = HashMap<software.bernie.geckolib.constant.dataticket.DataTicket<*>, Any>()
    override fun getDataMap(): MutableMap<software.bernie.geckolib.constant.dataticket.DataTicket<*>, Any> = data
}

abstract class GeoBlockRendererBase<T>(model: GeoModel<T>) :
    software.bernie.geckolib.renderer.GeoBlockRenderer<T, BpmBlockRenderState>(model)
    where T : net.minecraft.world.level.block.entity.BlockEntity, T : GeoAnimatable {
    override fun createRenderState(): BpmBlockRenderState = BpmBlockRenderState()
}

abstract class GeoEntityRendererBase<T>(
    context: net.minecraft.client.renderer.entity.EntityRendererProvider.Context,
    model: GeoModel<T>,
) : software.bernie.geckolib.renderer.GeoEntityRenderer<T, BpmEntityRenderState>(context, model)
    where T : net.minecraft.world.entity.Entity, T : GeoAnimatable {
    override fun createRenderState(): BpmEntityRenderState = BpmEntityRenderState()
}

abstract class GeoItemRendererBase<T>(model: GeoModel<T>) :
    software.bernie.geckolib.renderer.GeoItemRenderer<T>(model)
    where T : net.minecraft.world.item.Item, T : GeoAnimatable
*///?} else {
abstract class GeoBlockRendererBase<T>(model: GeoModel<T>) :
    software.bernie.geckolib.renderer.GeoBlockRenderer<T>(model)
    where T : net.minecraft.world.level.block.entity.BlockEntity, T : GeoAnimatable

abstract class GeoEntityRendererBase<T>(
    context: net.minecraft.client.renderer.entity.EntityRendererProvider.Context,
    model: GeoModel<T>,
) : software.bernie.geckolib.renderer.GeoEntityRenderer<T>(context, model)
    where T : net.minecraft.world.entity.Entity, T : GeoAnimatable

abstract class GeoItemRendererBase<T>(model: GeoModel<T>) :
    software.bernie.geckolib.renderer.GeoItemRenderer<T>(model)
    where T : net.minecraft.world.item.Item, T : GeoAnimatable
//?}
