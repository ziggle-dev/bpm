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
