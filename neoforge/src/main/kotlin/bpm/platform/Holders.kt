package bpm.platform

/**
 * Two things 1.20.5 wrapped in a holder, and what they were before it.
 *
 * A recipe used to BE the recipe; from 1.20.5 the manager hands back a `RecipeHolder` pairing it with
 * its id -- which is where the id went when `Recipe.getId()` was removed. A `MobEffectInstance` used to
 * name its effect directly; from 1.20.5 it names a `Holder<MobEffect>`.
 *
 * Both are the same shape of change and both are only ever unwrapped here, so the callers say
 * [recipeOf] and [effectOf] and stop caring which era they are on.
 */

//? if >=1.20.5 {
/** What the recipe manager hands back for a lookup. */
typealias RecipeEntry<T> = net.minecraft.world.item.crafting.RecipeHolder<T>

/** The recipe inside the entry. */
fun <T : net.minecraft.world.item.crafting.Recipe<*>> recipeOf(entry: RecipeEntry<T>): T = entry.value()

/** The effect an instance applies. */
fun effectOf(instance: net.minecraft.world.effect.MobEffectInstance): net.minecraft.world.effect.MobEffect =
    instance.effect.value()

/** A recipe entry whose type is not known statically. */
fun recipeAny(entry: RecipeEntry<*>): Any = entry.value()

/** A sound event, which is holder-wrapped from 1.20.5. */
fun soundOf(sound: net.minecraft.core.Holder<net.minecraft.sounds.SoundEvent>): net.minecraft.sounds.SoundEvent =
    sound.value()
//?} else {
/*/** Below 1.20.5 a lookup hands back the recipe itself; there is no wrapper. */
typealias RecipeEntry<T> = T

fun <T : net.minecraft.world.item.crafting.Recipe<*>> recipeOf(entry: RecipeEntry<T>): T = entry

fun effectOf(instance: net.minecraft.world.effect.MobEffectInstance): net.minecraft.world.effect.MobEffect =
    instance.effect

fun recipeAny(entry: RecipeEntry<*>): Any = entry as Any

/** Sounds are not wrapped on this band; the constant already IS the event. */
fun soundOf(sound: net.minecraft.sounds.SoundEvent): net.minecraft.sounds.SoundEvent = sound
*///?}
