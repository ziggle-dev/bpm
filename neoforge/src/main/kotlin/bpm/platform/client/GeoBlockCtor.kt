package bpm.platform.client

import bpm.platform.GeoAnimatable
import bpm.platform.GeoModel

/*
 * One constructor, two super-calls, and the reason this is a file of its own.
 *
 * GeckoLib 5.5 dropped `GeoBlockRenderer(GeoModel)` for `GeoBlockRenderer(Context, GeoModel)`. That is
 * the ONLY difference between 5.4 and 5.5 for this mod's block renderers -- `submit` and
 * `extractRenderState` have identical signatures on both -- so duplicating the hundred-line base to
 * change one super-call would be the expensive way to say it.
 *
 * The context is threaded through on both bands and dropped by the older one. It costs nothing: every
 * construction site already has one, because `RendererSink.blockEntity` hands a context to its factory
 * lambda and the block renderers have simply been ignoring it.
 *
 * Bounded at 1.21.9 because below it the whole submit model, and `BpmBlockRenderState` with it, does not
 * exist. Top-level directives, like `OffScreenAware`, so no arm is nested inside another's comment.
 */

//? if >=26.1 {
/*abstract class GeoBlockRendererCtor<T>(
    context: net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider.Context,
    model: GeoModel<T>,
) : bpm.platform.GeoBlockRendererOf<T, BpmBlockRenderState>(context, model)
    where T : net.minecraft.world.level.block.entity.BlockEntity, T : GeoAnimatable
*///?} elif >=1.21.9 {
/*abstract class GeoBlockRendererCtor<T>(
    @Suppress("UNUSED_PARAMETER")
    context: net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider.Context,
    model: GeoModel<T>,
) : bpm.platform.GeoBlockRendererOf<T, BpmBlockRenderState>(model)
    where T : net.minecraft.world.level.block.entity.BlockEntity, T : GeoAnimatable
*///?}
