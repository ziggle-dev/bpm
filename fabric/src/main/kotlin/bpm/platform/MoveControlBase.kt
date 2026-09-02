package bpm.platform

/*
 * `MoveControl` gained a type parameter at 26.1, and that is the whole of the difference.
 *
 * Everything the Warden's control reaches for -- `operation`, the `Operation` enum, `wantedX/Y/Z`,
 * `speedModifier` -- is still there and still protected. They only READ as unresolved on that band
 * because the receiver type fails to resolve first, which is worth saying out loud: fourteen compile
 * errors in `QuantumWardenEntity` came from this one missing `<T>`.
 *
 * A shared file cannot declare both spellings, so the difference lives here in a file of its own with
 * top-level directives -- the same shape as `OffScreenAware`, and for the same reason: a directive
 * nested inside a commented arm ends that arm at its first close-comment.
 */

//? if >=26.1 {
/*abstract class MoveControlBase<T : net.minecraft.world.entity.Mob>(mob: T) :
    net.minecraft.world.entity.ai.control.MoveControl<T>(mob)
*///?} else {
abstract class MoveControlBase<T : net.minecraft.world.entity.Mob>(mob: T) :
    net.minecraft.world.entity.ai.control.MoveControl(mob)
//?}
