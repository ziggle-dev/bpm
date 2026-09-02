package bpm.chamber

import bpm.platform.store.PlayerStore
import bpm.platform.events.BlockBreak
import bpm.platform.events.BpmEvents
import bpm.platform.events.Drops
import bpm.platform.events.Respawn
import bpm.Bpm
import bpm.BpmConfig
import bpm.BpmConfig.orDefault
import bpm.world.ModAttachments
import bpm.world.devices.GateBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer

/**
 * The chamber's house rules: its blocks hold while a Warden stands in the room; a player who dies in it
 * comes back at the gate they came through rather than in their bed, with what they dropped waiting in a
 * chest beside it and the gate closed behind them; a player who logs in inside a room (which will have
 * reset while they were away) is sent out the same way.
 */
object ChamberEvents {
    fun install() {
        BpmEvents.blockBreak.listen(::onBreak)
        BpmEvents.playerDeath.listen(::onDeath)
        BpmEvents.playerDrops.listen(::onDrops)
        BpmEvents.playerRespawn.listen(::onRespawn)
        BpmEvents.playerJoin.listen(::onLogin)
    }

    /** False to hold the block where it is. */
    private fun onBreak(event: BlockBreak): Boolean {
        val level = event.level
        if (!ChamberDimension.isChamber(level)) return true
        if (!BpmConfig.CHAMBER_UNBREAKABLE_WHILE_ALIVE.orDefault()) return true
        if (BuiltInRegistries.BLOCK.getKey(event.state.block).namespace != Bpm.ID) return true
        val slot = Chambers.get(level.server).slotAt(event.pos) ?: return true
        if (ChamberFight.isCrystal(slot, event.pos)) {
            if (slot.state == SlotState.FIGHTING) ChamberFight.onCrystalBroken(level, slot, event.pos)
            return true
        }
        if (slot.locked && !event.player.isCreative) {
            event.player.displayClientMessage(Component.literal("[bpm] the chamber holds while the Warden stands"), true)
            return false
        }
        return true
    }

    private fun onDeath(player: ServerPlayer) {
        if (!ChamberDimension.isChamber(player.level())) return
        PlayerStore.set(player, ModAttachments.RESPAWN_AT_GATE, true)
        if (!BpmConfig.CHAMBER_RESET_ON_LEAVE.orDefault()) return
        val slot = Chambers.get(bpm.platform.serverOf(player)).slotAt(player.blockPosition()) ?: return
        if (Chambers.closeGate(bpm.platform.serverOf(player), slot)) player.sendSystemMessage(Component.literal("[bpm] the gate closes behind you — it takes another lens to open"))
    }

    /** What a player drops dying in a chamber goes into a chest beside the spot outside the gate they return to. */
    private fun onDrops(event: Drops): Boolean {
        val player = event.player
        if (!ChamberDimension.isChamber(player.level())) return true
        val stacks = event.stacks
        val (level, point) = Chambers.returnPoint(bpm.platform.serverOf(player), player)
        val at = BlockPos.containing(point)
        val slot = Chambers.get(bpm.platform.serverOf(player)).slotAt(player.blockPosition())
        val gate = slot?.gatePos?.takeIf { slot.gateDim == level.dimension() }?.let { level.getBlockEntity(it) as? GateBlockEntity }
        val avoid = gate?.volume()?.inflate(1.0, 0.0, 1.0)
        val chest = Chambers.stash(level, at, avoid, Component.literal("${player.name.string}'s remains"), stacks)
        player.sendSystemMessage(Component.literal(if (chest != null) "[bpm] what you carried waits in a chest outside the gate" else "[bpm] what you carried lies outside the gate"))
        // Refused: these stacks are in the chest now, and letting them scatter as well would duplicate them.
        return false
    }

    /** A room resets once its players are gone, so someone logging back in inside one is sent out the way they came. */
    private fun onLogin(player: ServerPlayer) {
        if (ChamberDimension.isChamber(player.level())) Chambers.leave(player)
    }

    private fun onRespawn(event: Respawn) {
        val player = event.player
        if (event.endConquered || !PlayerStore.get(player, ModAttachments.RESPAWN_AT_GATE)) return
        PlayerStore.set(player, ModAttachments.RESPAWN_AT_GATE, false)
        Chambers.leave(player)
    }
}
