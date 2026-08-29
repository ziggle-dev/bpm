package bpm.runtime

import bpm.net.EffectKind
import bpm.net.EffectOp
import bpm.net.EffectPayload
import net.minecraft.core.BlockPos

/**
 * One controller's in-world effects, as the clients see them.
 *
 * **Transfers** are coalesced into streams: the first item moved between two spots (of one kind) opens a
 * stream with `BEGIN`, everything else moved during a tick is one `PULSE` with the sum, and a stream that
 * has carried nothing for [IDLE_TICKS] closes with `END`. A loop moving a stack a tick costs one packet a
 * tick per pair, not one per stack. **Actions** (mining, striking, using) are one-shot: the job that does
 * them sends `BEGIN`, a `PULSE` per swing and `END` itself, under an id from [newId].
 *
 * Positions come from [endpoint] (a link name → where it is, `self` being the controller) and packets go
 * out through [send], so the sender itself knows nothing of levels or players and can be tested dry.
 */
class EffectSender(
    private val controller: () -> BlockPos,
    private val endpoint: (String) -> Endpoint?,
    private val send: (EffectPayload) -> Unit,
) {
    /** Where an effect hangs: a block, and the face that was linked (−1 for none, or for the controller itself). */
    class Endpoint(val pos: BlockPos, val face: Int)

    private class Stream(val id: Int, val kind: EffectKind, val from: Endpoint, val to: Endpoint) {
        var item = ""
        var pending = 0
        var idle = 0
        var began = false
    }

    private val streams = LinkedHashMap<String, Stream>()
    private var nextId = 1

    /** An id for an action's BEGIN / PULSE / END. */
    fun newId(): Int = nextId++

    val liveStreams: Int get() = streams.size

    /** [amount] of [kind] went from link [from] to link [to]; [item] is a registry id to show, or empty. */
    fun transfer(from: String, to: String, amount: Int, kind: EffectKind, item: String) {
        if (amount <= 0) return
        val key = "$kind|$from|$to"
        val stream = streams[key] ?: run {
            if (streams.size >= MAX_STREAMS) return
            val a = endpoint(from) ?: return
            val b = endpoint(to) ?: return
            Stream(nextId++, kind, a, b).also { streams[key] = it }
        }
        if (item.isNotEmpty()) stream.item = item
        stream.pending += amount
        stream.idle = 0
    }

    /** One step of an action at [at] (face [face]), holding [item]. */
    fun action(id: Int, op: EffectOp, kind: EffectKind, at: BlockPos, face: Int, item: String) {
        send(EffectPayload(controller(), id, op, kind, at, face, at, face, 0, item))
    }

    /** Once a server tick, after the script ran: flush what moved, close what went quiet. */
    fun tick() {
        val it = streams.entries.iterator()
        while (it.hasNext()) {
            val s = it.next().value
            if (s.pending > 0) {
                send(payload(s, if (s.began) EffectOp.PULSE else EffectOp.BEGIN, s.pending))
                s.began = true
                s.pending = 0
            } else if (++s.idle >= IDLE_TICKS) {
                if (s.began) send(payload(s, EffectOp.END, 0))
                it.remove()
            }
        }
    }

    /** The controller stopped: every open stream closes now. */
    fun endAll() {
        for (s in streams.values) if (s.began) send(payload(s, EffectOp.END, 0))
        streams.clear()
    }

    private fun payload(s: Stream, op: EffectOp, amount: Int) =
        EffectPayload(controller(), s.id, op, s.kind, s.from.pos, s.from.face, s.to.pos, s.to.face, amount, s.item)

    companion object {
        const val IDLE_TICKS = 20
        const val MAX_STREAMS = 32
    }
}
