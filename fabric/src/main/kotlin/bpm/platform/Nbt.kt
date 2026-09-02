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
//? if >=1.21.9 {
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
