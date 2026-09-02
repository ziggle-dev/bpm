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
//? if >=1.21.9 {
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
*///?} else {
typealias AnimatableManager<T> = software.bernie.geckolib.animation.AnimatableManager<T>
typealias ControllerRegistrar = software.bernie.geckolib.animation.AnimatableManager.ControllerRegistrar
typealias PlayState = software.bernie.geckolib.animation.PlayState
typealias GeoBone = software.bernie.geckolib.cache.`object`.GeoBone
typealias GeoRenderer<T> = software.bernie.geckolib.renderer.GeoRenderer<T>
typealias BakedGeoModel = software.bernie.geckolib.cache.`object`.BakedGeoModel
typealias AutoGlowingGeoLayer<T> = software.bernie.geckolib.renderer.layer.AutoGlowingGeoLayer<T>
typealias AnimationController<T> = software.bernie.geckolib.animation.AnimationController<T>
typealias AnimationStateHandler<T> = software.bernie.geckolib.animation.AnimationController.AnimationStateHandler<T>
//?}

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
fun <T : software.bernie.geckolib.animatable.GeoAnimatable> animController(
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
