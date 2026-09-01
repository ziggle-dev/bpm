package bpm.platform

import net.minecraft.core.Direction
import net.minecraft.core.Vec3i
import net.minecraft.world.level.block.RenderShape

/**
 * Small version differences that the shared tree cannot express, given as values and helpers.
 *
 * The shared tree is compiled by both loaders and is therefore NOT processed for `//? if` directives —
 * a branch that wanted the other answer would get this one. So anything that differs by Minecraft
 * version has to be named here, in a branch's own source, and called from there.
 *
 * This works for values, and for functions whose shape stays the same. It does NOT work for an override
 * whose parameter list changed arity: a shared file can only declare one signature. Those are the
 * genuinely expensive ones, and there are a handful in `Traps.kt` and `QuantumWardenEntity.kt`.
 */

/**
 * "This block is drawn by its block entity, not by a baked model."
 *
 * `ENTITYBLOCK_ANIMATED` went away at 1.21.2 along with `BlockEntityWithoutLevelRenderer`; the surviving
 * spelling is `MODEL`, and the block-entity renderer draws over it. Substituting one for the other
 * blindly would change what 1.21.1 draws, which is why this is a switched value and not a rename.
 */
//? if >=1.21.2 {
/*val ANIMATED_BLOCK_SHAPE: RenderShape = RenderShape.MODEL

/** `Direction.normal` became private; `getUnitVec3i` is the accessor that replaced it. */
fun unitVector(direction: Direction): Vec3i = direction.unitVec3i

/**
 * The fifth parameter of `Block.neighborChanged`.
 *
 * It was the position the change came from; at 1.21.2 it became an `Orientation`, which carries the
 * direction as well. The arity did not change, only the type — which is why an alias is enough, and it is
 * enough only because every override of it in this mod passes the value straight to `super` and never
 * asks what it is.
 *
 * If one ever needs to READ it, that override moves to a branch. Aliasing a type you do not touch is
 * honest; aliasing one you do would be pretending two different things are the same.
 */
typealias NeighborSource = net.minecraft.world.level.redstone.Orientation?

/**
 * Looking one entry up in a dynamic registry.
 *
 * `RegistryAccess.registryOrThrow` became `lookupOrThrow` at 1.21.2, and the thing it returns changed with
 * it — a `Registry` before, a `HolderLookup` after — so the two call chains do not merely differ in
 * spelling. Both still answer the same question, which is what these take.
 */
fun <T> holderOrNull(
    access: net.minecraft.core.RegistryAccess,
    registry: net.minecraft.resources.ResourceKey<net.minecraft.core.Registry<T>>,
    entry: net.minecraft.resources.ResourceKey<T>,
): net.minecraft.core.Holder<T>? = access.lookupOrThrow(registry).get(entry).orElse(null)

fun <T> holderOrThrow(
    access: net.minecraft.core.RegistryAccess,
    registry: net.minecraft.resources.ResourceKey<net.minecraft.core.Registry<T>>,
    entry: net.minecraft.resources.ResourceKey<T>,
): net.minecraft.core.Holder<T> = access.lookupOrThrow(registry).getOrThrow(entry)

/** `NativeImage.getPixelRGBA` became `getPixel`; same packed ABGR either way. */
fun pixel(image: com.mojang.blaze3d.platform.NativeImage, x: Int, y: Int): Int = image.getPixel(x, y)
*///?} else {
val ANIMATED_BLOCK_SHAPE: RenderShape = RenderShape.ENTITYBLOCK_ANIMATED

fun unitVector(direction: Direction): Vec3i = direction.normal

typealias NeighborSource = net.minecraft.core.BlockPos

fun <T> holderOrNull(
    access: net.minecraft.core.RegistryAccess,
    registry: net.minecraft.resources.ResourceKey<net.minecraft.core.Registry<T>>,
    entry: net.minecraft.resources.ResourceKey<T>,
): net.minecraft.core.Holder<T>? = access.registryOrThrow(registry).getHolder(entry).orElse(null)

fun <T> holderOrThrow(
    access: net.minecraft.core.RegistryAccess,
    registry: net.minecraft.resources.ResourceKey<net.minecraft.core.Registry<T>>,
    entry: net.minecraft.resources.ResourceKey<T>,
): net.minecraft.core.Holder<T> = access.registryOrThrow(registry).getHolderOrThrow(entry)

fun pixel(image: com.mojang.blaze3d.platform.NativeImage, x: Int, y: Int): Int = image.getPixelRGBA(x, y)

//?}
