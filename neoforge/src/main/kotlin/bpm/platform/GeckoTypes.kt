package bpm.platform

/**
 * The GeckoLib types whose PACKAGE moved, across the THREE layouts this ladder spans.
 *
 * Not two. 4.x is one shape; 5.4 (1.21.11) is another; and 5.1-5.2 (1.21.5 and 1.21.8) is a THIRD that
 * is not a waypoint between them -- it moved `AnimationController` out to `animatable.processing`, where
 * 5.4 moved it back to `animation`, while leaving `PlayState`, `GeoBone` and `BakedGeoModel` exactly
 * where 4.x had them. So a condition keyed on the Minecraft version has to name 1.21.5 and 1.21.9
 * separately, and neither arm is a superset of the other. 5.1.0 and 5.2.2 ARE identical to each other,
 * which is why one arm covers both bands.
 *
 * GeckoLib 5 reorganised its packages without changing what most of these types do: an
 * `AnimatableManager` is still an animatable manager, a `GeoBone` still a bone. Six of them moved and
 * one was renamed, and nothing else the mod names did either -- so this is the whole of the 5.x surface
 * as far as bpm is concerned, which is a much smaller thing than "the GeoRenderState rewrite" suggests.
 *
 * Aliased rather than imported directly for the same reason as [ResourceLocation]: the shared tree is
 * not entitled to know which package this version keeps a type in, and a typealias is checked where a
 * find-and-replace across a hundred imports is not.
 *
 * `AnimationState` became `AnimationTest` and is NOT aliased, because nothing names it: every handler
 * takes it as an inferred lambda parameter and calls `setAndContinue` on it, which survived the rename
 * unchanged. A type nobody writes down costs nothing to rename.
 */
//? if >=26.1 {
/*// GeckoLib 5.5. Structurally identical to 5.4 below it -- every sub-path is the same -- but the root
// package became `com.geckolib`. A fourth arm rather than a fourth layout.
typealias AnimatableManager<T> = com.geckolib.animatable.manager.AnimatableManager<T>
typealias ControllerRegistrar = com.geckolib.animatable.manager.AnimatableManager.ControllerRegistrar
typealias PlayState = com.geckolib.animation.`object`.PlayState
typealias GeoBone = com.geckolib.cache.model.GeoBone
typealias BakedGeoModel = com.geckolib.cache.model.BakedGeoModel
typealias AnimationController<T> = com.geckolib.animation.AnimationController<T>
typealias AnimationStateHandler<T> = com.geckolib.animation.AnimationController.AnimationStateHandler<T>
*///?} elif >=1.21.9 {
/*typealias AnimatableManager<T> = software.bernie.geckolib.animatable.manager.AnimatableManager<T>
typealias ControllerRegistrar = software.bernie.geckolib.animatable.manager.AnimatableManager.ControllerRegistrar
typealias PlayState = software.bernie.geckolib.animation.`object`.PlayState
typealias GeoBone = software.bernie.geckolib.cache.model.GeoBone
typealias BakedGeoModel = software.bernie.geckolib.cache.model.BakedGeoModel
typealias AnimationController<T> = software.bernie.geckolib.animation.AnimationController<T>
typealias AnimationStateHandler<T> = software.bernie.geckolib.animation.AnimationController.AnimationStateHandler<T>
*///?} elif >=1.21.5 {
/*typealias AnimatableManager<T> = software.bernie.geckolib.animatable.manager.AnimatableManager<T>
typealias ControllerRegistrar = software.bernie.geckolib.animatable.manager.AnimatableManager.ControllerRegistrar
typealias PlayState = software.bernie.geckolib.animation.PlayState
typealias GeoBone = software.bernie.geckolib.cache.`object`.GeoBone
typealias BakedGeoModel = software.bernie.geckolib.cache.`object`.BakedGeoModel
typealias AnimationController<T> = software.bernie.geckolib.animatable.processing.AnimationController<T>
typealias AnimationStateHandler<T> = software.bernie.geckolib.animatable.processing.AnimationController.AnimationStateHandler<T>
*///?} elif >=1.21 {
typealias AnimatableManager<T> = software.bernie.geckolib.animation.AnimatableManager<T>
typealias ControllerRegistrar = software.bernie.geckolib.animation.AnimatableManager.ControllerRegistrar
typealias PlayState = software.bernie.geckolib.animation.PlayState
typealias GeoBone = software.bernie.geckolib.cache.`object`.GeoBone
typealias GeoRenderer<T> = software.bernie.geckolib.renderer.GeoRenderer<T>
typealias BakedGeoModel = software.bernie.geckolib.cache.`object`.BakedGeoModel
typealias AutoGlowingGeoLayer<T> = software.bernie.geckolib.renderer.layer.AutoGlowingGeoLayer<T>
typealias AnimationController<T> = software.bernie.geckolib.animation.AnimationController<T>
typealias AnimationStateHandler<T> = software.bernie.geckolib.animation.AnimationController.AnimationStateHandler<T>
//?} else {
/*// GeckoLib 4.8, which is where the 1.20.1 line stops. The animation half still lives under `core`
// here -- `core.animation` and `core.object` -- and moved up a level for the 1.21 releases. The cache
// and renderer halves did not move, which is why only five of these nine differ from the arm above.
typealias AnimatableManager<T> = software.bernie.geckolib.core.animation.AnimatableManager<T>
typealias ControllerRegistrar = software.bernie.geckolib.core.animation.AnimatableManager.ControllerRegistrar
typealias PlayState = software.bernie.geckolib.core.`object`.PlayState
typealias GeoBone = software.bernie.geckolib.cache.`object`.GeoBone
typealias GeoRenderer<T> = software.bernie.geckolib.renderer.GeoRenderer<T>
typealias BakedGeoModel = software.bernie.geckolib.cache.`object`.BakedGeoModel
typealias AutoGlowingGeoLayer<T> = software.bernie.geckolib.renderer.layer.AutoGlowingGeoLayer<T>
typealias AnimationController<T> = software.bernie.geckolib.core.animation.AnimationController<T>
typealias AnimationStateHandler<T> = software.bernie.geckolib.core.animation.AnimationController.AnimationStateHandler<T>
*///?}

/*
 * `GeoRenderer` and `AutoGlowingGeoLayer` are deliberately NOT aliased on this band.
 *
 * Both went from one type parameter to three, and a one-parameter alias cannot stand in for a
 * three-parameter type without inventing the other two. Nothing here needs them either: the only code
 * that named them was the glow layer, and [bpm.platform.client.BpmGlowLayer] now writes the full name
 * on each band because its own shape differs anyway. An alias that would have to lie is worse than no
 * alias -- see [BakedGeoModel], which moved package but kept its arity and so is still aliased.
 */

/**
 * A render type, under whichever package this version keeps it in.
 *
 * 1.21.9 moved `RenderType` into its own `rendertype` package and split the FACTORIES out into a
 * separate `RenderTypes` class. The two halves are aliased differently on purpose: the type is a name
 * the shared tree passes around and can alias, while the factories are calls and live in
 * [bpm.platform.client] with the other "name the effect, not the type" helpers.
 */
//? if >=1.21.9 {
/*typealias RenderType = net.minecraft.client.renderer.rendertype.RenderType
*///?} else {
typealias RenderType = net.minecraft.client.renderer.RenderType
//?}

/**
 * An animation controller, without naming what it is attached to.
 *
 * GeckoLib 5 dropped the animatable from every `AnimationController` constructor. It was redundant --
 * the controller is registered onto that animatable's own manager a line later, so it always knew --
 * and dropping it is the sort of tidy-up that is invisible until you have twenty call sites passing it.
 *
 * Taken here and ignored on the newer line rather than removed from the call sites, because the older
 * line still requires it and the shared tree has to read the same on both.
 */
fun <T : GeoAnimatable> animController(
    animatable: T,
    name: String,
    transitionTicks: Int,
    handler: AnimationStateHandler<T>,
): AnimationController<T> =
    //? if >=1.21.5 {
    /*AnimationController(name, transitionTicks, handler)
    *///?} else {
    AnimationController(animatable, name, transitionTicks, handler)
    //?}

/*
 * The GeckoLib types whose package did NOT move within the library, only underneath it.
 *
 * At 5.5 GeckoLib renamed its root: `software.bernie.geckolib` became `com.geckolib`. Everything below
 * that is untouched -- `animation.RawAnimation` is still `animation.RawAnimation` -- so unlike the three
 * layouts above, this one is a pure prefix change and needs only two arms.
 *
 * These are aliased for the same reason [ResourceLocation] is: a prefix that appears in a hundred imports
 * is exactly the sort of thing a build-level find-and-replace does almost correctly. A typealias is
 * resolved by the compiler, so a place it does not fit is an error rather than a silent edit.
 */
//? if >=26.1 {
/*typealias RawAnimation = com.geckolib.animation.RawAnimation
typealias GeckoLibUtil = com.geckolib.util.GeckoLibUtil
typealias AnimatableInstanceCache = com.geckolib.animatable.instance.AnimatableInstanceCache
typealias GeoAnimatable = com.geckolib.animatable.GeoAnimatable
typealias GeoItem = com.geckolib.animatable.GeoItem
typealias GeoBlockEntity = com.geckolib.animatable.GeoBlockEntity
typealias GeoEntity = com.geckolib.animatable.GeoEntity
typealias MolangQueries = com.geckolib.loading.math.MolangQueries
typealias DataTickets = com.geckolib.constant.DataTickets
typealias DataTicket<D> = com.geckolib.constant.dataticket.DataTicket<D>
typealias GeoRenderProvider = com.geckolib.animatable.client.GeoRenderProvider
typealias GeoModel<T> = com.geckolib.model.GeoModel<T>
typealias SingletonGeoAnimatable = com.geckolib.animatable.SingletonGeoAnimatable
*///?} elif >=1.21 {
typealias RawAnimation = software.bernie.geckolib.animation.RawAnimation
typealias GeckoLibUtil = software.bernie.geckolib.util.GeckoLibUtil
typealias AnimatableInstanceCache = software.bernie.geckolib.animatable.instance.AnimatableInstanceCache
typealias GeoAnimatable = software.bernie.geckolib.animatable.GeoAnimatable
typealias GeoItem = software.bernie.geckolib.animatable.GeoItem
typealias GeoBlockEntity = software.bernie.geckolib.animatable.GeoBlockEntity
typealias GeoEntity = software.bernie.geckolib.animatable.GeoEntity
typealias MolangQueries = software.bernie.geckolib.loading.math.MolangQueries
typealias DataTickets = software.bernie.geckolib.constant.DataTickets
typealias DataTicket<D> = software.bernie.geckolib.constant.dataticket.DataTicket<D>
typealias GeoRenderProvider = software.bernie.geckolib.animatable.client.GeoRenderProvider
typealias GeoModel<T> = software.bernie.geckolib.model.GeoModel<T>
typealias SingletonGeoAnimatable = software.bernie.geckolib.animatable.SingletonGeoAnimatable
//?} else {
/*// GeckoLib 4.8. Four of these thirteen sit under `core` here and one has a different NAME: what the
// 1.21 line calls `GeoRenderProvider` was `RenderProvider`. The other eight are already where the arm
// above puts them, which is why this reads as a near-copy.
typealias RawAnimation = software.bernie.geckolib.core.animation.RawAnimation
typealias GeckoLibUtil = software.bernie.geckolib.util.GeckoLibUtil
typealias AnimatableInstanceCache = software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache
typealias GeoAnimatable = software.bernie.geckolib.core.animatable.GeoAnimatable
typealias GeoItem = software.bernie.geckolib.animatable.GeoItem
typealias GeoBlockEntity = software.bernie.geckolib.animatable.GeoBlockEntity
typealias GeoEntity = software.bernie.geckolib.animatable.GeoEntity
typealias MolangQueries = software.bernie.geckolib.core.molang.MolangQueries
typealias DataTickets = software.bernie.geckolib.constant.DataTickets
typealias DataTicket<D> = software.bernie.geckolib.core.`object`.DataTicket<D>
// Forge's own client extension rather than one of GeckoLib's: on this loader an item's custom renderer
// is asked for through `IClientItemExtensions.getCustomRenderer`, which is what `initializeClient` fills
// in -- see the item bases. GeckoLib 4.8's Fabric jar has its own `RenderProvider` for the same job; the
// Forge jar has no such type, because Forge already had one.
typealias GeoRenderProvider = net.minecraftforge.client.extensions.common.IClientItemExtensions
typealias GeoModel<T> = software.bernie.geckolib.model.GeoModel<T>
typealias SingletonGeoAnimatable = software.bernie.geckolib.animatable.SingletonGeoAnimatable
*///?}

/*
 * The renderer family, aliased so the branch files' 1.21.9 arms do not name a package at all.
 *
 * Those arms already MATCH on 26.x -- 26.2 is greater than 1.21.9 -- and GeckoLib 5.5 is 5.4 with a
 * different root, so the only thing standing between them and working unchanged was the literal
 * `software.bernie.geckolib` in every signature. Aliasing removes it.
 *
 * Not declared below 1.21.5: render states and three-parameter renderers do not exist on 4.x, and the
 * arms that target it write GeckoLib 4's own names directly.
 */
/*
 * `GeoRenderState` arrives with GeckoLib 5, one band earlier than the rest of this group, so it is
 * switched separately. The others are 5.4-and-up shapes.
 */
//? if >=26.1 {
/*typealias GeoRenderState = com.geckolib.renderer.base.GeoRenderState
*///?} elif >=1.21.5 {
/*typealias GeoRenderState = software.bernie.geckolib.renderer.base.GeoRenderState
*///?}

/*
 * A nested classifier is NOT reachable through a typealias in Kotlin -- `bpm.platform.MolangQueries.Actor`
 * does not resolve -- so the nested one gets an alias of its own.
 */
//? if >=26.1 {
/*typealias MolangActor<T> = com.geckolib.loading.math.MolangQueries.Actor<T>
*///?} elif >=1.21 {
typealias MolangActor<T> = software.bernie.geckolib.loading.math.MolangQueries.Actor<T>
//?} else {
/*// 4.8 has no actor at all: a Molang variable there is global, and per-animatable values are set by
// the model each frame. Nothing on this band names this type, so it does not exist here.
*///?}

//? if >=26.1 {
/*typealias RenderPassInfo<R> = com.geckolib.renderer.base.RenderPassInfo<R>
typealias GeoRendererOf<T, O, R> = com.geckolib.renderer.base.GeoRenderer<T, O, R>
typealias GeoBlockRendererOf<T, R> = com.geckolib.renderer.GeoBlockRenderer<T, R>
typealias GeoEntityRendererOf<T, R> = com.geckolib.renderer.GeoEntityRenderer<T, R>
// The item renderer fixes its render state, so it takes one parameter where the others take two.
typealias GeoItemRendererOf<T> = com.geckolib.renderer.GeoItemRenderer<T>
// Nested, so it needs its own name -- a typealias does not carry nested classifiers.
typealias GeoItemRenderData = com.geckolib.renderer.GeoItemRenderer.RenderData
typealias AutoGlowingGeoLayerOf<T, O, R> = com.geckolib.renderer.layer.builtin.AutoGlowingGeoLayer<T, O, R>
*///?} elif >=1.21.9 {
/*typealias RenderPassInfo<R> = software.bernie.geckolib.renderer.base.RenderPassInfo<R>
typealias GeoRendererOf<T, O, R> = software.bernie.geckolib.renderer.base.GeoRenderer<T, O, R>
typealias GeoBlockRendererOf<T, R> = software.bernie.geckolib.renderer.GeoBlockRenderer<T, R>
typealias GeoEntityRendererOf<T, R> = software.bernie.geckolib.renderer.GeoEntityRenderer<T, R>
typealias GeoItemRendererOf<T> = software.bernie.geckolib.renderer.GeoItemRenderer<T>
typealias GeoItemRenderData = software.bernie.geckolib.renderer.GeoItemRenderer.RenderData
typealias AutoGlowingGeoLayerOf<T, O, R> = software.bernie.geckolib.renderer.layer.builtin.AutoGlowingGeoLayer<T, O, R>
*///?}

/**
 * An item GeckoLib draws with a model, and the one method that asks it which renderer.
 *
 * 5.x asks through `GeoItem.createGeoRenderer(Consumer<GeoRenderProvider>)`. 4.8 asks through TWO
 * methods on `SingletonGeoAnimatable` -- `createRenderer(Consumer<Object>)`, which fills in the answer,
 * and `getRenderProvider()`, which hands it back later; `RenderProvider.of(item)` calls the second and
 * casts what it gets. The provider itself is the same object either way, which is why the mod's items
 * write [geoRenderProvider] and nothing else.
 */
//? if >=1.21 {
interface GeoRenderedItem : GeoItem {

    /** The provider naming this item's renderer. Called once, on the client, and cached from then on. */
    fun geoRenderProvider(): GeoRenderProvider

    override fun createGeoRenderer(consumer: java.util.function.Consumer<GeoRenderProvider>) {
        consumer.accept(geoRenderProvider())
    }
}
//?} else {
/*/**
 * On this loader the question is not GeckoLib's at all.
 *
 * Forge asks an item for its client extensions through `Item.initializeClient`, and reads the renderer
 * off `IClientItemExtensions.getCustomRenderer`. So this interface only names the provider, and the
 * item BASES answer `initializeClient` with it -- a base can override a method on `Item`, which an
 * interface cannot do without colliding with the one the class already inherits.
 */
interface GeoRenderedItem : GeoItem {

    fun geoRenderProvider(): GeoRenderProvider
}
*///?}
