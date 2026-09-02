package bpm.platform

/**
 * Fire a triggered animation on an item.
 *
 * `triggerAnim` carries an unused type parameter on the 4.9 line and none on the 4.8 line. Kotlin
 * cannot infer a parameter that appears nowhere in the signature, so the older spelling needs an
 * explicit `<Any>` that the newer one rejects. Nothing else about the call differs.
 */
fun triggerItemAnim(
    animatable: SingletonGeoAnimatable,
    entity: net.minecraft.world.entity.Entity,
    id: Long,
    controller: String,
    animation: String,
) {
    //? if >=1.21.2 {
    /*animatable.triggerAnim(entity, id, controller, animation)
    *///?} else {
    animatable.triggerAnim<Any>(entity, id, controller, animation)
    //?}
}

/**
 * Register an item so a server-triggered animation reaches the client.
 *
 * `triggerAnim` on a SINGLETON animatable -- an item, as opposed to a block entity -- sends a packet
 * naming the animatable, and the client looks it up in a registry the item has to put itself into. Skip
 * this and the client logs "Attempting to retrieve unregistered synced animatable!" and the animation
 * simply never plays. Nothing else breaks, which is why it went unnoticed: the item renders and behaves,
 * it just never animates.
 *
 * Named in full per band rather than through the `SingletonGeoAnimatable` alias, because a typealias to a
 * Java class does not reliably carry that class's statics.
 */
fun registerSyncedItem(item: SingletonGeoAnimatable) {
    //? if >=26.1 {
    /*com.geckolib.animatable.SingletonGeoAnimatable.registerSyncedAnimatable(item)
    *///?} else {
    software.bernie.geckolib.animatable.SingletonGeoAnimatable.registerSyncedAnimatable(item)
    //?}
}
