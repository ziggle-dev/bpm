package bpm.platform.client

import bpm.platform.idOf

import bpm.platform.ResourceLocation
import bpm.platform.GeoAnimatable
import bpm.platform.GeoModel

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
    /*override fun getModelResource(state: bpm.platform.GeoRenderState): ResourceLocation = geckoAsset(geo)

    override fun getTextureResource(state: bpm.platform.GeoRenderState): ResourceLocation =
        if (state.getOrDefaultGeckolibData(ALT_TEXTURE, false) == true) (altTexture() ?: tex) else tex

    /**
     * Pack the alternate-texture question's answer into the render state.
     *
     * This is the moment the animatable is still in hand. Asking later is not possible, and that is the
     * whole shape of rendering on this band.
     */
    override fun addAdditionalStateData(
        animatable: T,
        relatedObject: Any?,
        state: bpm.platform.GeoRenderState,
    ) {
        super.addAdditionalStateData(animatable, relatedObject, state)
        state.addGeckolibData(ALT_TEXTURE, altApplies(animatable))
    }
    *///?} elif >=1.21.5 {
    /*override fun getModelResource(state: bpm.platform.GeoRenderState): ResourceLocation = geckoAsset(geo)

    override fun getTextureResource(state: bpm.platform.GeoRenderState): ResourceLocation =
        if (state.getOrDefaultGeckolibData(ALT_TEXTURE, false) == true) (altTexture() ?: tex) else tex

    // The same hook as the band above, one argument shorter: 5.4 added the related object, 5.1-5.2 pass
    // only the animatable. Everything it does is the same -- this is the moment the animatable is still
    // in hand, and asking later is not possible.
    override fun addAdditionalStateData(
        animatable: T,
        state: bpm.platform.GeoRenderState,
    ) {
        super.addAdditionalStateData(animatable, state)
        state.addGeckolibData(ALT_TEXTURE, altApplies(animatable))
    }
    *///?} elif >=1.21.2 {
    /*override fun getModelResource(animatable: T, renderer: software.bernie.geckolib.renderer.GeoRenderer<T>?): ResourceLocation = geckoAsset(geo)

    override fun getTextureResource(animatable: T, renderer: software.bernie.geckolib.renderer.GeoRenderer<T>?): ResourceLocation =
        textureFor(animatable)
    *///?} else {
    override fun getModelResource(animatable: T, renderer: software.bernie.geckolib.renderer.GeoRenderer<T>?): ResourceLocation = geckoAsset(geo)

    override fun getTextureResource(animatable: T, renderer: software.bernie.geckolib.renderer.GeoRenderer<T>?): ResourceLocation =
        textureFor(animatable)

    override fun getModelResource(animatable: T): ResourceLocation = geckoAsset(geo)

    override fun getTextureResource(animatable: T): ResourceLocation = textureFor(animatable)
    //?}
}

//? if >=1.21.5 {
/*/** The ticket the alternate-texture answer rides on, from extraction to draw. */
private val ALT_TEXTURE: bpm.platform.DataTicket<Boolean> =
    bpm.platform.DataTicket.create("bpm_alt_texture", Boolean::class.javaObjectType)
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
fun actorStack(actor: bpm.platform.MolangActor<*>): net.minecraft.world.item.ItemStack? {
    //? if >=1.21.5 {
    /*return actor.renderState().getOrDefaultGeckolibData(ITEM_STACK, null)
    *///?} else {
    return actor.animationState().getData(bpm.platform.DataTickets.ITEMSTACK)
    //?}
}

//? if >=1.21.5 {
/*/** The ticket the drawn stack rides on, from the item renderer to whoever asks about it. */
internal val ITEM_STACK: bpm.platform.DataTicket<net.minecraft.world.item.ItemStack> =
    bpm.platform.DataTicket.create("bpm_item_stack", net.minecraft.world.item.ItemStack::class.java)
*///?}

/**
 * The id GeckoLib wants for a model or animation file, given the path this mod files it under.
 *
 * Until GeckoLib 5 a `GeoModel` answered with the RESOURCE PATH -- `bpm:geo/block/x.geo.json` -- and
 * GeckoLib opened exactly that. From 5.x it scans two fixed folders, `assets/<ns>/geckolib/models`
 * and `assets/<ns>/geckolib/animations`, and keys what it finds by the path with the folder prefix and
 * the `.geo`/`.animation`/`.json` suffixes stripped. So the same file is now asked for as
 * `bpm:block/x`, and a full path is not merely tolerated-with-a-warning: the lookup misses and GeckoLib
 * throws.
 *
 * The mod keeps writing the path it files the asset under, because that is the true thing about it, and
 * this turns it into whatever the band's cache is keyed by. The build copies the two folders into
 * `geckolib/` on the bands that read them there -- see the resource wiring in the loader build scripts.
 */
fun geckoAsset(path: ResourceLocation): ResourceLocation {
    //? if >=1.21.5 {
    /*var p = path.path
    for (prefix in listOf("geo/", "animations/")) if (p.startsWith(prefix)) p = p.removePrefix(prefix)
    for (suffix in listOf(".geo.json", ".animation.json", ".json")) if (p.endsWith(suffix)) { p = p.removeSuffix(suffix); break }
    return idOf(path.namespace, p)
    *///?} else {
    return path
    //?}
}


/**
 * Install the mod's Molang variables.
 *
 * The two GeckoLib eras disagree about what a Molang variable IS, and it is not a rename.
 *
 * 5.x has `MolangQueries.setActorVariable`: a query is answered per ACTOR, and the actor hands back the
 * animatable being drawn, so one registration serves every model.
 *
 * 4.8 has `MolangParser.setValue`, which takes a bare `DoubleSupplier` and no actor at all -- its
 * variables are global. Per-animatable values go through the model instead: `GeoModel` there has an
 * `applyMolangQueries` hook that runs each frame with the animatable in hand, which is where
 * [applyMolang] sets them. So on that band this call registers nothing and the model does the work.
 */
fun installMolang(variables: Map<String, (bpm.client.render.MolangCtx) -> Double>) {
    //? if >=1.21 {
    for ((name, value) in variables) {
        bpm.platform.MolangQueries.setActorVariable<Any>(name) { actor ->
            value(bpm.client.render.MolangCtx(actor.animatable(), actorStack(actor)))
        }
    }
    //?} else {
    /*// Nothing global to register: see applyMolang, which the model calls per frame.
    molangVariables = variables
    *///?}
}

//? if >=1.21 {
/** No-op above 4.8: the actor variables installed above already carry the animatable. */
@Suppress("UNUSED_PARAMETER")
fun applyMolang(animatable: Any?) = Unit
//?} else {
/*private var molangVariables: Map<String, (bpm.client.render.MolangCtx) -> Double> = emptyMap()

/**
 * Set every variable for [animatable], for the frame about to be drawn.
 *
 * Called from the model's `applyMolangQueries`, which is the only point on 4.8 where the animatable and
 * the parser are both in hand. The suppliers capture the animatable rather than reading it later,
 * because `setValue` keeps them and a stale capture would animate the wrong thing.
 */
fun applyMolang(animatable: Any?) {
    val parser = software.bernie.geckolib.core.molang.MolangParser.INSTANCE
    for ((name, value) in molangVariables) {
        parser.setValue(name) { value(bpm.client.render.MolangCtx(animatable, null)) }
    }
}
*///?}
