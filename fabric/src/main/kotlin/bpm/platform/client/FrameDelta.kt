package bpm.platform.client

/**
 * How far through the current tick this frame is.
 *
 * 1.20.5 introduced `DeltaTracker`, which distinguishes the partial tick that keeps running while the
 * game is paused from the one that does not. Below it there is a single float and no such distinction.
 *
 * [partial] therefore takes the same flag on both bands and simply ignores it on the older one, where
 * `Minecraft.getPartialTick()` already answers the paused-aware value. The two callers pass different
 * flags -- the HUD wants the running one, the effects the frozen one -- which is why this is a seam
 * rather than one number.
 */
//? if >=1.20.5 {
class FrameDelta(private val tracker: net.minecraft.client.DeltaTracker) {
    fun partial(runsNormally: Boolean): Float = tracker.getGameTimeDeltaPartialTick(runsNormally)
}
//?} else {
/*class FrameDelta(private val partialTick: Float) {
    @Suppress("UNUSED_PARAMETER")
    fun partial(runsNormally: Boolean): Float = partialTick
}
*///?}
