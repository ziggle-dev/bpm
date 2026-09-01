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
*///?} else {
val ANIMATED_BLOCK_SHAPE: RenderShape = RenderShape.ENTITYBLOCK_ANIMATED

fun unitVector(direction: Direction): Vec3i = direction.normal
//?}
