package bpm.client.fx

import kotlin.math.sin

/**
 * One quantum rift, client-side only: the state behind a world-space portal. It tears open, idles pulling
 * things in ([inward]) or pushing them out, swells on each [pulse], and [close]s — after which it is [done]
 * and dropped.
 *
 * **No model and no GeckoLib.** This used to be a `GeoAnimatable` driving `quantum_rift.geo.json` through
 * five animations. The geometry is gone: a rift is a hole, and every attempt to build one out of cuboids
 * produced a funnel that looked like plumbing from the side. What is left is a lifecycle — how open, how
 * fast, how bright — that [bpm.client.render.RiftRenderer] hands to a shader on a single billboarded quad.
 * That also retires a whole class of bug: no bone traversal, no render layers, no `isReRender` contract to
 * get wrong, and no angle the thing can be viewed from that it was not designed for.
 */
class Rift(val inward: Boolean) {

    /** How fast the throat turns — scaled by how much the transfer is actually moving. */
    var flowSpeed: Double = 1.0

    /** So two rifts opened on the same tick do not breathe in step. */
    val phase: Float = (Math.random() * Math.PI * 2).toFloat()

    var age = 0
        private set
    var closing = false
        private set
    private var closedAt = -1
    private var pulseAt = -100

    /** When it last tore itself open again after having faded shut — see [openness]. */
    private var reopenAt = -100

    // How hard the tear is working, 0..1. Each batch adds to it and every tick takes a little away, so it
    // measures the RATE rather than the last event: a stream moving something every tick sits at 1, a
    // transfer every couple of seconds barely lifts it. [last] is the previous tick's value, so the wobble
    // interpolates instead of stepping once a tick.
    private var heat = 0.0
    private var last = 0.0

    val done: Boolean get() = closing && age - closedAt >= CLOSE_TICKS

    fun tick() {
        age++
        last = heat
        heat = (heat - COOL_PER_TICK).coerceAtLeast(0.0)
    }

    fun pulse() {
        if (closing) return
        // A batch arriving after the mouth had already begun falling shut is a fresh tear, not a
        // continuation of the last one, so it gets its own way in.
        if (age - pulseAt > HOLD_TICKS) reopenAt = age
        pulseAt = age
        // Accumulating rather than resetting to 1 is what makes this a measure of how BUSY the tear is: one
        // batch nudges it, a batch every tick pins it at full within a few ticks.
        heat = (heat + HEAT_PER_PULSE).coerceAtMost(1.0)
    }

    /**
     * Whether something is actually going through it THIS INSTANT — not whether the mouth is open.
     *
     * The two are deliberately different. [openness] holds for [HOLD_TICKS] after a batch so a stream of
     * batches reads as one continuous tear rather than a stutter, and that hold is right for a hole: a hole
     * does not blink shut between the things falling through it. It is wrong for the liquid, which is only
     * there while it is moving — gating the fluid column on openness left a thin shape hanging in the tear
     * for the whole of a `wait`, because the mouth was still legitimately open.
     */
    fun running(partialTick: Float): Float {
        if (closing) return 0f
        val since = age + partialTick - pulseAt
        return (1f - since / RUN_TICKS).coerceIn(0f, 1f)
    }

    /** How hard it is working right now, interpolated across the tick. */
    fun activity(partialTick: Float): Float = (last + (heat - last) * partialTick).toFloat().coerceIn(0f, 1f)

    fun close() {
        if (closing) return
        closing = true
        closedAt = age
    }

    /**
     * 0 → 1 as it tears open, 1 while it runs, back to 0 as it closes; plus a short swell on each pulse.
     *
     * Drives both the quad's size and its alpha, so a rift grows into existence and shrinks out of it
     * rather than popping. Values above 1 during a pulse are wanted — the shader clamps the brightness but
     * the size does not, so a big transfer visibly flexes the mouth.
     */
    fun openness(partialTick: Float): Float {
        val t = age + partialTick
        val opened = (t / OPEN_TICKS).coerceIn(0f, 1f)
        val shut = if (!closing) 1f else 1f - ((t - closedAt) / CLOSE_TICKS).coerceIn(0f, 1f)
        val since = t - pulseAt

        // **A rift is only as open as the traffic through it.**
        //
        // Openness used to depend on the Transfer object existing, and a Transfer lives until the server
        // says END — so a graph looping `move; wait 20` held one tear open across every gap, because from
        // the client's side the stream never ended. Short waits hid it: the batches came faster than the
        // eye could separate, so a permanently-open tear read as a busy one. At `wait 20` the gap became
        // visible and the tear was plainly just sitting there.
        //
        // Tying it to the last batch instead makes the behaviour fall out for any interval, with nothing to
        // tune per graph: hold for a beat, then fall shut. A `wait 10` never leaves the hold and stays open
        // continuously; a `wait 20` closes in the gap and tears itself open again on the next batch.
        // In and out BOTH take time. The fade-out was already gradual but the way back in was a step: the
        // instant a batch landed, `since` reset to zero and this term jumped straight to 1, so a tear that
        // had gently shut over eight ticks flicked back open in one. A hole opening is worth watching as
        // much as one closing, and on a `wait` loop you watch it happen over and over.
        val fadeOut = 1f - ((since - HOLD_TICKS) / FADE_TICKS).coerceIn(0f, 1f)
        val fadeIn = ((t - reopenAt) / REOPEN_TICKS).coerceIn(0f, 1f)
        val alive = ease(fadeIn) * ease(fadeOut)

        val swell = if (since in 0f..PULSE_TICKS) 0.22f * (1f - since / PULSE_TICKS) else 0f
        return ease(opened) * ease(shut) * alive + swell * alive
    }

    /**
     * How the tear moves while it works: a slow wobble in place, never a shove.
     *
     * It used to RECOIL — a damped ring thrown along the travel vector, so the mouth lurched bodily after
     * whatever went through it. Two problems: a hole in space that slides about reads as an object rather
     * than an opening, and at one batch a tick the ring never got past its own first instant, which is a
     * buzz rather than a kick. A hole should not move. It should sit where it is and be unstable, and how
     * unstable says how much is going through it.
     *
     * Yaw and tilt separately, on slow periods that do not divide into each other, so the two never line up
     * into an obvious cycle.
     */
    fun yawWobble(partialTick: Float): Float {
        val t = age + partialTick
        return (sin(t * 0.055f) * 7f + sin(t * 0.021f + 1.3f) * 3f) * activity(partialTick)
    }

    fun tiltWobble(partialTick: Float): Float {
        val t = age + partialTick
        return sin(t * 0.043f + 1.7f) * 5f * activity(partialTick)
    }

    /** A gentle breathe in size, the last of the old kick worth keeping. */
    fun breathe(partialTick: Float): Float {
        val t = age + partialTick
        return 1f + sin(t * 0.07f) * 0.05f * activity(partialTick)
    }

    /** Smoothstep, so the tear does not start and stop with a corner. */
    private fun ease(x: Float): Float = x * x * (3f - 2f * x)

    companion object {
        const val OPEN_TICKS = 5f
        const val CLOSE_TICKS = 8
        private const val PULSE_TICKS = 6f

        /** How long after a batch the tear stays fully open, before it starts falling shut. */
        private const val HOLD_TICKS = 11f

        /** …and how long it takes to shut once it starts. Together these set the interval that reads as continuous. */
        private const val FADE_TICKS = 8f

        /** How long it takes to tear itself open again after a lapse. A little quicker than it shuts. */
        private const val REOPEN_TICKS = 6f

        /** How long after a batch liquid is still visibly running — see [running]. */
        private const val RUN_TICKS = 5f

        /** What one batch adds to [activity], and what each quiet tick takes off it. */
        private const val HEAT_PER_PULSE = 0.34
        // Slow enough that anything keeping the tear continuously open also keeps it visibly working: the
        // mouth holds for ~19 ticks after a batch, so cooling that zeroed out in 7 left a steadily-open
        // tear sitting perfectly still between batches.
        private const val COOL_PER_TICK = 0.03
    }
}
