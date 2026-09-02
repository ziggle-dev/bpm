package bpm.platform

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import java.util.UUID

/**
 * Reading a tag, with a fallback, on either side of 1.21.5.
 *
 * 1.21.5 turned every `CompoundTag` getter into an `Optional` one and added a `...Or` variant beside it
 * that takes a fallback. The `...Or` forms mean exactly what the old plain getters meant, because the
 * old ones answered a type default when the key was missing.
 *
 * These deliberately do NOT reuse Minecraft's names. An extension that shares a member's name is
 * silently shadowed by it, so the shim would evaporate on precisely the version that needs a different
 * body -- and worse, an extension declared on only one version makes its own IMPORT unresolvable on the
 * other, which is how the first attempt at this broke 1.21.1. Distinct names exist on both versions, so
 * a call site and its import read the same everywhere.
 */
//? if >=1.21.5 {
/*fun CompoundTag.intOr(key: String, fallback: Int): Int = getIntOr(key, fallback)

fun CompoundTag.longOr(key: String, fallback: Long): Long = getLongOr(key, fallback)

fun CompoundTag.floatOr(key: String, fallback: Float): Float = getFloatOr(key, fallback)

fun CompoundTag.doubleOr(key: String, fallback: Double): Double = getDoubleOr(key, fallback)

fun CompoundTag.stringOr(key: String, fallback: String): String = getStringOr(key, fallback)

fun CompoundTag.boolOr(key: String, fallback: Boolean): Boolean = getBooleanOr(key, fallback)

fun CompoundTag.byteOr(key: String, fallback: Byte): Byte = getByteOr(key, fallback)

fun CompoundTag.compoundOr(key: String): CompoundTag = getCompoundOrEmpty(key)

fun CompoundTag.listOr(key: String): ListTag = getListOrEmpty(key)

fun CompoundTag.intsOr(key: String): IntArray = getIntArray(key).orElse(IntArray(0))

fun ListTag.compoundAt(index: Int): CompoundTag = getCompoundOrEmpty(index)

fun CompoundTag.bytesOr(key: String): ByteArray = getByteArray(key).orElse(ByteArray(0))

fun uuidOrNull(tag: CompoundTag, key: String): UUID? =
    tag.getIntArray(key).filter { it.size == 4 }.map { net.minecraft.core.UUIDUtil.uuidFromIntArray(it) }.orElse(null)
*///?} else {
fun CompoundTag.intOr(key: String, fallback: Int): Int = if (contains(key)) getInt(key) else fallback

fun CompoundTag.longOr(key: String, fallback: Long): Long = if (contains(key)) getLong(key) else fallback

fun CompoundTag.floatOr(key: String, fallback: Float): Float = if (contains(key)) getFloat(key) else fallback

fun CompoundTag.doubleOr(key: String, fallback: Double): Double = if (contains(key)) getDouble(key) else fallback

fun CompoundTag.stringOr(key: String, fallback: String): String = if (contains(key)) getString(key) else fallback

fun CompoundTag.boolOr(key: String, fallback: Boolean): Boolean = if (contains(key)) getBoolean(key) else fallback

fun CompoundTag.byteOr(key: String, fallback: Byte): Byte = if (contains(key)) getByte(key) else fallback

fun CompoundTag.compoundOr(key: String): CompoundTag = getCompound(key)

/** Every list this mod stores is a list of compounds, which is why the newer signature can drop the type. */
fun CompoundTag.listOr(key: String): ListTag = getList(key, net.minecraft.nbt.Tag.TAG_COMPOUND.toInt())

fun CompoundTag.intsOr(key: String): IntArray = getIntArray(key)

fun ListTag.compoundAt(index: Int): CompoundTag = getCompound(index)

fun CompoundTag.bytesOr(key: String): ByteArray = getByteArray(key)

fun uuidOrNull(tag: CompoundTag, key: String): UUID? = if (tag.hasUUID(key)) tag.getUUID(key) else null
//?}

/**
 * A UUID in a tag, written the way both versions read.
 *
 * `putUUID` went away with the getters, but the FORMAT did not: it always stored a four-int array, and
 * `UUIDUtil` still converts one both ways on every version. So this is not a migration -- a world
 * written by either version reads on the other.
 */
fun putUuid(tag: CompoundTag, key: String, id: UUID) =
    tag.putIntArray(key, net.minecraft.core.UUIDUtil.uuidToIntArray(id))

/**
 * The keys in a tag, and whether one is present with a given type.
 *
 * `allKeys` became `keySet`, and the two-argument `contains(key, type)` went away entirely when the
 * getters became `Optional`-returning: asking "is there a string at this key" is now the same act as
 * reading it, so the type check folded into the read.
 */
//? if >=1.21.5 {
/*fun net.minecraft.nbt.CompoundTag.keys(): Set<String> = keySet()

fun net.minecraft.nbt.CompoundTag.hasString(key: String): Boolean = getString(key).isPresent

fun net.minecraft.nbt.CompoundTag.hasCompound(key: String): Boolean = getCompound(key).isPresent
*///?} else {
fun net.minecraft.nbt.CompoundTag.keys(): Set<String> = allKeys

fun net.minecraft.nbt.CompoundTag.hasString(key: String): Boolean =
    contains(key, net.minecraft.nbt.Tag.TAG_STRING.toInt())

fun net.minecraft.nbt.CompoundTag.hasCompound(key: String): Boolean =
    contains(key, net.minecraft.nbt.Tag.TAG_COMPOUND.toInt())
//?}

/**
 * An item stack to and from a tag, on either side of 1.21.5's codec turn.
 *
 * `ItemStack.parseOptional` and `saveOptional` were convenience wrappers around the stack codec, and
 * they went away when the codec became the only route. What they did is preserved exactly: an empty
 * stack writes an empty tag and an empty or unreadable tag reads back as empty, so a save from either
 * version loads on the other.
 */
//? if >=1.21.5 {
/*fun readStack(registries: net.minecraft.core.HolderLookup.Provider, tag: net.minecraft.nbt.CompoundTag): net.minecraft.world.item.ItemStack =
    if (tag.isEmpty) net.minecraft.world.item.ItemStack.EMPTY
    else net.minecraft.world.item.ItemStack.CODEC
        .parse(registries.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), tag)
        .result()
        .orElse(net.minecraft.world.item.ItemStack.EMPTY)

fun writeStack(registries: net.minecraft.core.HolderLookup.Provider, stack: net.minecraft.world.item.ItemStack): net.minecraft.nbt.Tag =
    if (stack.isEmpty) net.minecraft.nbt.CompoundTag()
    else net.minecraft.world.item.ItemStack.CODEC
        .encodeStart(registries.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), stack)
        .result()
        .orElseGet { net.minecraft.nbt.CompoundTag() }
*///?} elif >=1.20.5 {
fun readStack(registries: net.minecraft.core.HolderLookup.Provider, tag: net.minecraft.nbt.CompoundTag): net.minecraft.world.item.ItemStack =
    net.minecraft.world.item.ItemStack.parseOptional(registries, tag)

fun writeStack(registries: net.minecraft.core.HolderLookup.Provider, stack: net.minecraft.world.item.ItemStack): net.minecraft.nbt.Tag =
    stack.saveOptional(registries)
//?} else {
/*/**
 * A stack wrote itself into a tag and read itself back out of one, with no registries and no codec.
 *
 * `parseOptional`/`saveOptional` are the component-era pair; the older `of`/`save` say the same thing,
 * including the empty case -- `of` answers EMPTY for a tag with no id in it, and an empty stack writes
 * an empty compound so it reads back the same way.
 */
@Suppress("UNUSED_PARAMETER")
fun readStack(registries: net.minecraft.core.HolderLookup.Provider, tag: net.minecraft.nbt.CompoundTag): net.minecraft.world.item.ItemStack =
    if (tag.isEmpty) net.minecraft.world.item.ItemStack.EMPTY else net.minecraft.world.item.ItemStack.of(tag)

@Suppress("UNUSED_PARAMETER")
fun writeStack(registries: net.minecraft.core.HolderLookup.Provider, stack: net.minecraft.world.item.ItemStack): net.minecraft.nbt.Tag =
    if (stack.isEmpty) net.minecraft.nbt.CompoundTag() else stack.save(net.minecraft.nbt.CompoundTag())
*///?}

/**
 * A block position in a compound, by codec rather than by `NbtUtils`.
 *
 * `NbtUtils.writeBlockPos`/`readBlockPos` are gone at 1.21.9, and `BlockPos.CODEC` -- which exists on
 * every band and produces the same three-int array those two did -- says the identical thing without a
 * seam. Written here beside [putUuid] because that is where this mod keeps "a value in a tag".
 */
fun putBlockPos(tag: net.minecraft.nbt.CompoundTag, key: String, pos: net.minecraft.core.BlockPos) {
    net.minecraft.core.BlockPos.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, pos)
        .result().ifPresent { tag.put(key, it) }
}

/** The position under [key], or null when there is none or it will not parse. */
fun blockPosOrNull(tag: net.minecraft.nbt.CompoundTag, key: String): net.minecraft.core.BlockPos? {
    val value = tag.get(key) ?: return null
    return net.minecraft.core.BlockPos.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, value).result().orElse(null)
}
