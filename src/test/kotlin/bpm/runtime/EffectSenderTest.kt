package bpm.runtime

import bpm.net.EffectKind
import bpm.net.EffectOp
import bpm.net.EffectPayload
import net.minecraft.core.BlockPos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EffectSenderTest {
    private val sent = ArrayList<EffectPayload>()
    private val sender = EffectSender(
        controller = { BlockPos(0, 64, 0) },
        endpoint = { name -> when (name) { "self" -> EffectSender.Endpoint(BlockPos(0, 64, 0), -1); "chest" -> EffectSender.Endpoint(BlockPos(5, 64, 0), 1); else -> null } },
        send = { sent += it },
    )

    private fun ops() = sent.map { it.op }

    @Test
    fun `a stream opens once, pulses with the tick's sum, and closes when it goes quiet`() {
        sender.transfer("chest", "self", 3, EffectKind.ITEMS, "minecraft:coal")
        sender.transfer("chest", "self", 4, EffectKind.ITEMS, "")
        sender.tick()
        assertEquals(listOf(EffectOp.BEGIN), ops())
        assertEquals(7, sent[0].amount)
        assertEquals("minecraft:coal", sent[0].item, "the sample survives a later transfer without one")
        assertEquals(BlockPos(5, 64, 0), sent[0].origin)
        assertEquals(1, sent[0].originFace)
        assertEquals(-1, sent[0].targetFace, "the controller itself has no face")
        sender.transfer("chest", "self", 2, EffectKind.ITEMS, "")
        sender.tick()
        assertEquals(listOf(EffectOp.BEGIN, EffectOp.PULSE), ops())
        repeat(EffectSender.IDLE_TICKS - 1) { sender.tick() }
        assertEquals(2, sent.size, "still open while quiet")
        sender.tick()
        assertEquals(listOf(EffectOp.BEGIN, EffectOp.PULSE, EffectOp.END), ops())
        assertEquals(0, sender.liveStreams)
    }

    @Test
    fun `a stream to an unknown link, or of nothing, sends nothing`() {
        sender.transfer("chest", "nowhere", 5, EffectKind.ITEMS, "")
        sender.transfer("chest", "self", 0, EffectKind.ITEMS, "")
        sender.tick()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `kinds and directions are separate streams, and endAll closes them all`() {
        sender.transfer("chest", "self", 1, EffectKind.ITEMS, "")
        sender.transfer("self", "chest", 1, EffectKind.ITEMS, "")
        sender.transfer("chest", "self", 100, EffectKind.FLUID, "minecraft:water")
        sender.tick()
        assertEquals(3, sent.count { it.op == EffectOp.BEGIN })
        assertEquals(3, sender.liveStreams)
        sender.endAll()
        assertEquals(3, sent.count { it.op == EffectOp.END })
        assertEquals(0, sender.liveStreams)
    }

    @Test
    fun `actions go straight through under their own id`() {
        val id = sender.newId()
        sender.action(id, EffectOp.BEGIN, EffectKind.MINE, BlockPos(1, 2, 3), 1, "minecraft:iron_pickaxe")
        sender.action(id, EffectOp.END, EffectKind.MINE, BlockPos(1, 2, 3), 1, "")
        assertEquals(listOf(EffectOp.BEGIN, EffectOp.END), ops())
        assertEquals(id, sent[0].stream)
        assertEquals(EffectKind.MINE, sent[0].kind)
        assertEquals(BlockPos(1, 2, 3), sent[0].origin)
    }
}
