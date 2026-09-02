package bpm.platform.client

import bpm.platform.ResourceLocation
import software.bernie.geckolib.animatable.GeoAnimatable
import software.bernie.geckolib.model.GeoModel

/**
 * A GeckoLib model at fixed asset paths, and the one way this mod varies them.
 *
 * The pair of methods that answer "which geometry, which texture" has been asked three different ways.
 * On 1.21.1 there is a one-argument form taking the animatable and a two-argument form taking the
 * animatable and the renderer. On 1.21.2 the one-argument form is gone rather than deprecated. On
 * 1.21.9 both are gone and the question is asked of the RENDER STATE -- the animatable no longer exists
 * by the time the model is drawn, so anything the model wants to know about it has to have been packed
 * into the state during extraction.
 *
 * That last change is the reason for [altApplies] and [altTexture] rather than an overridable
 * `texturePath(animatable)`. Exactly one model in this mod varies its texture -- the monitor, which has
 * a lit variant -- and what it varies on is a single boolean about the block. A boolean is something a
 * render state can carry; an open method taking the animatable is not. So the seam is shaped as "here is
 * an alternate texture, and here is the question that chooses it", which each band answers in its terms.
 *
 * `getAnimationResource` still takes the animatable on every band, so it stays with the shared subclass.
 */
abstract class PathGeoModelBase<T : GeoAnimatable>(
    private val geo: ResourceLocation,
    private val tex: ResourceLocation,
) : GeoModel<T>() {

    /** An alternate texture, used whenever [altApplies] answers true. Null when this model has none. */
    protected open fun altTexture(): ResourceLocation? = null

    /** Whether [altTexture] applies to the thing being drawn. Asked during extraction on 1.21.9+. */
    protected open fun altApplies(animatable: T): Boolean = false

    private fun textureFor(animatable: T): ResourceLocation =
        if (altApplies(animatable)) (altTexture() ?: tex) else tex

    //? if >=1.21.9 {
    /*override fun getModelResource(state: software.bernie.geckolib.renderer.base.GeoRenderState): ResourceLocation = geo

    override fun getTextureResource(state: software.bernie.geckolib.renderer.base.GeoRenderState): ResourceLocation =
        if (state.getOrDefaultGeckolibData(ALT_TEXTURE, false)) (altTexture() ?: tex) else tex

    /**
     * Pack the alternate-texture question's answer into the render state.
     *
     * This is the moment the animatable is still in hand. Asking later is not possible, and that is the
     * whole shape of rendering on this band.
     */
    override fun addAdditionalStateData(
        animatable: T,
        relatedObject: Any?,
        state: software.bernie.geckolib.renderer.base.GeoRenderState,
    ) {
        super.addAdditionalStateData(animatable, relatedObject, state)
        state.addGeckolibData(ALT_TEXTURE, altApplies(animatable))
    }
    *///?} elif >=1.21.2 {
    /*override fun getModelResource(animatable: T, renderer: software.bernie.geckolib.renderer.GeoRenderer<T>?): ResourceLocation = geo

    override fun getTextureResource(animatable: T, renderer: software.bernie.geckolib.renderer.GeoRenderer<T>?): ResourceLocation =
        textureFor(animatable)
    *///?} else {
    override fun getModelResource(animatable: T, renderer: software.bernie.geckolib.renderer.GeoRenderer<T>?): ResourceLocation = geo

    override fun getTextureResource(animatable: T, renderer: software.bernie.geckolib.renderer.GeoRenderer<T>?): ResourceLocation =
        textureFor(animatable)

    override fun getModelResource(animatable: T): ResourceLocation = geo

    override fun getTextureResource(animatable: T): ResourceLocation = textureFor(animatable)
    //?}
}

//? if >=1.21.9 {
/*/** The ticket the alternate-texture answer rides on, from extraction to draw. */
private val ALT_TEXTURE: software.bernie.geckolib.constant.dataticket.DataTicket<Boolean> =
    software.bernie.geckolib.constant.dataticket.DataTicket.create("bpm_alt_texture", Boolean::class.javaObjectType)
*///?}

/**
 * The item stack being drawn, for the Molang variables that ask something about it.
 *
 * GeckoLib used to publish the stack as a data ticket every renderer filled in; 5.x dropped that ticket
 * and hands the stack to the item renderer as render data instead. So on that band the stack is put on
 * the render state by [GeoItemRendererBase] and read back here, which is the same journey with the mod
 * carrying one more leg of it.
 *
 * Null when the thing being drawn is not an item, which is most of the time -- a block entity's actor
 * has no stack, and a variable that asks about one should read that as "no".
 */
fun actorStack(actor: software.bernie.geckolib.loading.math.MolangQueries.Actor<*>): net.minecraft.world.item.ItemStack? {
    //? if >=1.21.9 {
    /*return actor.renderState().getOrDefaultGeckolibData(ITEM_STACK, null)
    *///?} else {
    return actor.animationState().getData(software.bernie.geckolib.constant.DataTickets.ITEMSTACK)
    //?}
}

//? if >=1.21.9 {
/*/** The ticket the drawn stack rides on, from the item renderer to whoever asks about it. */
internal val ITEM_STACK: software.bernie.geckolib.constant.dataticket.DataTicket<net.minecraft.world.item.ItemStack> =
    software.bernie.geckolib.constant.dataticket.DataTicket.create("bpm_item_stack", net.minecraft.world.item.ItemStack::class.java)
*///?}
