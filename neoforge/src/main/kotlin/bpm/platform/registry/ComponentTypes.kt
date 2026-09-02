package bpm.platform.registry

import com.mojang.serialization.Codec

/**
 * What a data component IS, on a band that has none.
 *
 * Components arrived in 1.20.5. Below that an item's extra data is NBT on the stack and nothing else --
 * there is no `DataComponentType`, no builder, and no network codec, because the tag travels with the
 * stack for free.
 *
 * The shape here is chosen to keep `bpm.world.ModComponents` reading the same on both sides. Its nine
 * registrations say `persistent(...).networkSynchronized(...)`, and the PERSISTENT half is already
 * band-neutral: `Codec.STRING` and `GlobalPos.CODEC` exist on 1.20.1 exactly as they do now. Only the
 * network half does not, so the five stream codecs it names are reached through [Wire] instead of
 * spelled out, and on 1.20.1 they are inert placeholders that the builder ignores.
 *
 * A component's identity below 1.20.5 is its NAME -- the key it occupies in the stack's tag -- plus the
 * codec that turns a value into NBT and back. That is all [ComponentKey] carries there.
 */

//? if >=1.20.5 {
/** A component's identity. Vanilla's own type from 1.20.5. */
typealias ComponentKey<T> = net.minecraft.core.component.DataComponentType<T>

/** The builder `ModComponents` configures. */
typealias ComponentBuilder<T> = net.minecraft.core.component.DataComponentType.Builder<T>

/**
 * The stream codecs the components sync with.
 *
 * Named here rather than at the registration sites because `ByteBufCodecs` and `GlobalPos.STREAM_CODEC`
 * are themselves 1.20.5 types, and the registrations live in the shared tree where a directive cannot go.
 */
object Wire {
    val string: net.minecraft.network.codec.StreamCodec<in net.minecraft.network.RegistryFriendlyByteBuf, String> =
        net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8
    val varInt: net.minecraft.network.codec.StreamCodec<in net.minecraft.network.RegistryFriendlyByteBuf, Int> =
        net.minecraft.network.codec.ByteBufCodecs.VAR_INT
    val bool: net.minecraft.network.codec.StreamCodec<in net.minecraft.network.RegistryFriendlyByteBuf, Boolean> =
        net.minecraft.network.codec.ByteBufCodecs.BOOL
    val varLong: net.minecraft.network.codec.StreamCodec<in net.minecraft.network.RegistryFriendlyByteBuf, Long> =
        net.minecraft.network.codec.ByteBufCodecs.VAR_LONG
    val globalPos: net.minecraft.network.codec.StreamCodec<in net.minecraft.network.RegistryFriendlyByteBuf, net.minecraft.core.GlobalPos> =
        net.minecraft.core.GlobalPos.STREAM_CODEC
}
//?} else {
/*/**
 * A component's identity below 1.20.5: the tag key it lives under, and how its value becomes NBT.
 *
 * `name` is the full `namespace:path`, so two mods' components of the same name cannot collide inside
 * one stack's tag.
 */
class ComponentKey<T>(val name: String, val codec: Codec<T>)

/**
 * The builder, with the two methods `ModComponents` calls.
 *
 * `networkSynchronized` takes anything and does nothing: on this band the value rides inside the item's
 * NBT, which is already sent with the stack, so there is no separate channel to describe.
 */
class ComponentBuilder<T> {
    var codec: Codec<T>? = null
        private set

    fun persistent(codec: Codec<T>): ComponentBuilder<T> {
        this.codec = codec
        return this
    }

    @Suppress("UNUSED_PARAMETER")
    fun networkSynchronized(unused: Any?): ComponentBuilder<T> = this
}

/**
 * A [RegistryRef] holding a value that needs no registry.
 *
 * Below 1.20.5 a component key is not registered with anything -- it is a name and a codec, complete the
 * moment it is built -- so the ref that carries it is ready immediately rather than deferred.
 *
 * `holder()` throws, and deliberately: a `Holder` is a registry's handle on an entry, and there is no
 * entry here to hold. Nothing asks a component for one (checked across the shared tree and both
 * branches), so a loud failure is better than inventing a holder that would be wrong in a way nothing
 * would notice.
 */
internal class ReadyRef<T : Any>(private val value: T) : RegistryRef<T> {
    override val id: bpm.platform.ResourceLocation
        get() = error("a component key below 1.20.5 has no registry id")

    override fun get(): T = value

    override fun holder(): net.minecraft.core.Holder<T> =
        error("a component key below 1.20.5 is not a registry entry and has no Holder")
}

/** Inert on this band; see [ComponentBuilder.networkSynchronized]. */
object Wire {
    val string: Any? = null
    val varInt: Any? = null
    val bool: Any? = null
    val varLong: Any? = null
    val globalPos: Any? = null
}
*///?}


/*
 * Reading and writing a component on a stack.
 *
 * From 1.20.5 these are `ItemStack`'s own methods and these wrappers are one call deep. Below it there
 * is no such thing: the value lives in the stack's tag under the component's full `namespace:path`, and
 * the key's codec is what turns it into NBT and back.
 *
 * Named `comp`/`setComp` rather than `get`/`set` on purpose. An extension cannot shadow a member, so on
 * 1.20.5 and up `stack.get(key)` would silently keep resolving to vanilla's method and the seam would do
 * nothing -- which is exactly the sort of thing that compiles everywhere and is wrong on one band.
 */
//? if >=1.20.5 {
fun <T : Any> net.minecraft.world.item.ItemStack.comp(key: ComponentKey<T>): T? = get(key)

fun <T : Any> net.minecraft.world.item.ItemStack.compOr(key: ComponentKey<T>, fallback: T): T =
    getOrDefault(key, fallback)

fun <T : Any> net.minecraft.world.item.ItemStack.setComp(key: ComponentKey<T>, value: T) {
    set(key, value)
}

fun net.minecraft.world.item.ItemStack.hasComp(key: ComponentKey<*>): Boolean = has(key)

fun net.minecraft.world.item.ItemStack.removeComp(key: ComponentKey<*>) {
    remove(key)
}
//?} else {
/*fun <T : Any> net.minecraft.world.item.ItemStack.comp(key: ComponentKey<T>): T? {
    val tag = this.tag ?: return null
    val stored = tag.get(key.name) ?: return null
    // A tag written by an older or broken save decodes to nothing rather than throwing: a missing
    // component reads the same as an absent one, which is what every caller already handles.
    return key.codec.parse(net.minecraft.nbt.NbtOps.INSTANCE, stored).result().orElse(null)
}

fun <T : Any> net.minecraft.world.item.ItemStack.compOr(key: ComponentKey<T>, fallback: T): T =
    comp(key) ?: fallback

fun <T : Any> net.minecraft.world.item.ItemStack.setComp(key: ComponentKey<T>, value: T) {
    val encoded = key.codec.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, value).result().orElse(null)
        ?: return
    orCreateTag.put(key.name, encoded)
}

fun net.minecraft.world.item.ItemStack.hasComp(key: ComponentKey<*>): Boolean =
    this.tag?.contains(key.name) == true

fun net.minecraft.world.item.ItemStack.removeComp(key: ComponentKey<*>) {
    this.tag?.remove(key.name)
}
*///?}
