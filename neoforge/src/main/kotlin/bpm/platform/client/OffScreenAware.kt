package bpm.platform.client

import bpm.platform.GeoAnimatable
import bpm.platform.GeoModel

/*
 * One method, two signatures, and the reason this is a file of its own.
 *
 * `BlockEntityRenderer.shouldRenderOffScreen` takes the block entity until 1.21.6 and nothing after it.
 * That is the ONLY difference between 1.21.5 and 1.21.6-1.21.8 in the whole GeckoLib-5 renderer arm --
 * everything else about those two bands is identical -- so splitting that arm in two would duplicate
 * some two hundred and fifty lines to switch one parameter.
 *
 * An override has to live in the class that declares it, and the arm it would live in is already inside
 * a block comment on every band that does not match: a nested `//? if` there would put a comment
 * terminator in the middle of the outer comment and close it early. So the override moves OUT, into a
 * small intermediate
 * class in a file whose directives are top-level and safe, and [GeoBlockRendererBase] extends this
 * instead of extending GeckoLib's renderer directly.
 *
 * Declared only on the bands that need it: 1.21.4 and below take GeckoLib 4's renderer, and 1.21.9 has
 * its own base built on the submit model.
 */
//? if >=1.21.6 <1.21.9 {
/*abstract class OffScreenAwareBlockRenderer<T>(model: GeoModel<T>) :
    software.bernie.geckolib.renderer.GeoBlockRenderer<T>(model)
    where T : net.minecraft.world.level.block.entity.BlockEntity, T : GeoAnimatable {

    /** Whether this block draws even when vanilla would cull it from the frustum. */
    protected open fun alwaysRender(): Boolean = false

    override fun shouldRenderOffScreen(): Boolean = alwaysRender()
}
*///?} elif >=1.21.5 <1.21.6 {
/*abstract class OffScreenAwareBlockRenderer<T>(model: GeoModel<T>) :
    software.bernie.geckolib.renderer.GeoBlockRenderer<T>(model)
    where T : net.minecraft.world.level.block.entity.BlockEntity, T : GeoAnimatable {

    /** Whether this block draws even when vanilla would cull it from the frustum. */
    protected open fun alwaysRender(): Boolean = false

    override fun shouldRenderOffScreen(blockEntity: T): Boolean = alwaysRender()
}
*///?}
