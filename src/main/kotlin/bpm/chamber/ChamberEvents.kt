package bpm.chamber

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
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.level.BlockEvent
import java.util.function.Consumer

/**
 * The chamber's house rules: its blocks hold while a Warden stands in the room; a player who dies in it
 * comes back at the gate they came through rather than in their bed, with what they dropped waiting in a
 * chest beside it and the gate closed behind them; a player who logs in inside a room (which will have
 * reset while they were away) is sent out the same way.
 */
object ChamberEvents {
    fun install(bus: IEventBus) {
        bus.addListener(BlockEvent.BreakEvent::class.java, Consumer(::onBreak))
        bus.addListener(LivingDeathEvent::class.java, Consumer(::onDeath))
        bus.addListener(LivingDropsEvent::class.java, Consumer(::onDrops))
        bus.addListener(PlayerEvent.PlayerRespawnEvent::class.java, Consumer(::onRespawn))
        bus.addListener(PlayerEvent.PlayerLoggedInEvent::class.java, Consumer(::onLogin))
    }

    private fun onBreak(event: BlockEvent.BreakEvent) {
        val level = event.level as? ServerLevel ?: return
        if (!ChamberDimension.isChamber(level)) return
        if (!BpmConfig.CHAMBER_UNBREAKABLE_WHILE_ALIVE.orDefault()) return
        if (BuiltInRegistries.BLOCK.getKey(event.state.block).namespace != Bpm.ID) return
        val slot = Chambers.get(level.server).slotAt(event.pos) ?: return
        if (ChamberFight.isCrystal(slot, event.pos)) {
            if (slot.state == SlotState.FIGHTING) ChamberFight.onCrystalBroken(level, slot, event.pos)
            return
        }
        if (slot.locked && !event.player.isCreative) {
            event.isCanceled = true
            event.player.displayClientMessage(Component.literal("[bpm] the chamber holds while the Warden stands"), true)
        }
    }

    private fun onDeath(event: LivingDeathEvent) {
        val player = event.entity as? ServerPlayer ?: return
        if (!ChamberDimension.isChamber(player.level())) return
        player.setData(ModAttachments.RESPAWN_AT_GATE.get(), true)
        if (!BpmConfig.CHAMBER_RESET_ON_LEAVE.orDefault()) return
        val slot = Chambers.get(player.server).slotAt(player.blockPosition()) ?: return
        if (Chambers.closeGate(player.server, slot)) player.sendSystemMessage(Component.literal("[bpm] the gate closes behind you — it takes another lens to open"))
    }

    /** What a player drops dying in a chamber goes into a chest beside the spot outside the gate they return to. */
    private fun onDrops(event: LivingDropsEvent) {
        val player = event.entity as? ServerPlayer ?: return
        if (!ChamberDimension.isChamber(player.level())) return
        val stacks = event.drops.map { it.item.copy() }.filter { !it.isEmpty }
        if (stacks.isEmpty()) return
        val (level, point) = Chambers.returnPoint(player.server, player)
        val at = BlockPos.containing(point)
        val slot = Chambers.get(player.server).slotAt(player.blockPosition())
        val gate = slot?.gatePos?.takeIf { slot.gateDim == level.dimension() }?.let { level.getBlockEntity(it) as? GateBlockEntity }
        val avoid = gate?.volume()?.inflate(1.0, 0.0, 1.0)
        val chest = Chambers.stash(level, at, avoid, Component.literal("${player.name.string}'s remains"), stacks)
        event.isCanceled = true
        player.sendSystemMessage(Component.literal(if (chest != null) "[bpm] what you carried waits in a chest outside the gate" else "[bpm] what you carried lies outside the gate"))
    }

    /** A room resets once its players are gone, so someone logging back in inside one is sent out the way they came. */
    private fun onLogin(event: PlayerEvent.PlayerLoggedInEvent) {
        val player = event.entity as? ServerPlayer ?: return
        if (ChamberDimension.isChamber(player.level())) Chambers.leave(player)
    }

    private fun onRespawn(event: PlayerEvent.PlayerRespawnEvent) {
        val player = event.entity as? ServerPlayer ?: return
        if (event.isEndConquered || !player.getData(ModAttachments.RESPAWN_AT_GATE.get())) return
        player.setData(ModAttachments.RESPAWN_AT_GATE.get(), false)
        Chambers.leave(player)
    }
}
