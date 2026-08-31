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
    /**
     * Where an effect hangs: a block, and the face that was linked (−1 for none, or for the controller itself).
     *
     * [entity] is set when the end is a person rather than a place. The position still travels — a client that
     * cannot see the entity has to put the rift somewhere, and it decides who is close enough to be told at
     * all — but a client that can see them follows the entity instead.
     */
    class Endpoint(val pos: BlockPos, val face: Int, val entity: Int = 0)

    /**
     * [fromName] and [toName] are kept, not just the [from] and [to] they resolved to, because a presence
     * link moves: an endpoint resolved once when the stream opened left the rift hanging where the player
     * happened to be standing for the first item, for as long as the stream ran.
     */
    private class Stream(
        val id: Int,
        val kind: EffectKind,
        val fromName: String,
        val toName: String,
        var from: Endpoint,
        var to: Endpoint,
    ) {
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
            Stream(nextId++, kind, from, to, a, b).also { streams[key] = it }
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

    private fun payload(s: Stream, op: EffectOp, amount: Int): EffectPayload {
        // Where the ends are NOW. A link that has stopped resolving keeps its last known spot: the stream is
        // about to be closed anyway, and a rift that pops to the origin on its final packet looks like a bug.
        endpoint(s.fromName)?.let { s.from = it }
        endpoint(s.toName)?.let { s.to = it }
        return EffectPayload(
            controller(), s.id, op, s.kind, s.from.pos, s.from.face, s.to.pos, s.to.face, amount, s.item,
            s.from.entity, s.to.entity,
        )
    }

    companion object {
        const val IDLE_TICKS = 20
        const val MAX_STREAMS = 32
    }
}
