package bpm.client.fx

import software.bernie.geckolib.animatable.GeoAnimatable
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache
import software.bernie.geckolib.animation.AnimatableManager
import software.bernie.geckolib.animation.AnimationController
import software.bernie.geckolib.animation.PlayState
import software.bernie.geckolib.animation.RawAnimation
import software.bernie.geckolib.util.GeckoLibUtil
import software.bernie.geckolib.util.RenderUtil

/**
 * One quantum rift, client-side only: the GeckoLib animatable behind a world-space portal. It tears open,
 * idles pulling things in ([inward]) or pushing them out, swells on each [pulse], and [close]s — after which
 * it is [done] and dropped. Every rift is its own animatable instance, so two never share an animation state.
 */
class Rift(val inward: Boolean) : GeoAnimatable {
    private val cache: AnimatableInstanceCache = GeckoLibUtil.createInstanceCache(this)

    /** What the model's Molang reads: how fast things flow, and a per-rift spiral offset so neighbours never sync. */
    var flowSpeed: Double = 1.0
    val phase: Double = Math.random() * 360.0

    var age = 0
        private set
    var closing = false
        private set
    private var closedAt = -1

    val done: Boolean get() = closing && age - closedAt >= CLOSE_TICKS

    fun tick() {
        age++
    }

    fun pulse() {
        if (!closing) cache.getManagerForId<Rift>(0).tryTriggerAnimation("overlay", "pulse")
    }

    fun close() {
        if (closing) return
        closing = true
        closedAt = age
    }

    override fun registerControllers(controllers: AnimatableManager.ControllerRegistrar) {
        controllers.add(
            AnimationController(this, "main", 0) { state ->
                state.setAndContinue(if (closing) CLOSE else if (inward) OPEN_IN else OPEN_OUT)
            },
        )
        controllers.add(AnimationController(this, "overlay", 0) { PlayState.STOP }.triggerableAnim("pulse", PULSE))
    }

    override fun getAnimatableInstanceCache(): AnimatableInstanceCache = cache
    override fun getTick(o: Any?): Double = RenderUtil.getCurrentTick()

    companion object {
        /** The `close` animation is half a second; a little more so the last frame is seen. */
        const val CLOSE_TICKS = 12
        val OPEN_IN: RawAnimation = RawAnimation.begin().thenPlay("animation.quantum_rift.open").thenLoop("animation.quantum_rift.idle_in")
        val OPEN_OUT: RawAnimation = RawAnimation.begin().thenPlay("animation.quantum_rift.open").thenLoop("animation.quantum_rift.idle_out")
        val CLOSE: RawAnimation = RawAnimation.begin().thenPlayAndHold("animation.quantum_rift.close")
        val PULSE: RawAnimation = RawAnimation.begin().thenPlay("animation.quantum_rift.pulse")
    }
}
