package bpm.world

import bpm.chamber.ChamberDimension
import net.minecraft.server.level.ServerPlayer
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import java.util.function.Consumer

/**
 * Sneak + attack with the linker inside a chamber fires its tracking pulse. A left click on a block or an
 * entity reaches the server as an event; a left click on air only exists on the client, which sends
 * `LinkerTrackPayload` instead (see `bpm.client.BpmClient`).
 */
object LinkerCombat {
    fun install(bus: IEventBus) {
        bus.addListener(PlayerInteractEvent.LeftClickBlock::class.java, Consumer(::onLeftClickBlock))
        bus.addListener(AttackEntityEvent::class.java, Consumer(::onAttack))
    }

    private fun wants(player: net.minecraft.world.entity.player.Player): Boolean =
        player.isShiftKeyDown && ChamberDimension.isChamber(player.level()) && LinkerItem.handWith(player) != null

    private fun onLeftClickBlock(event: PlayerInteractEvent.LeftClickBlock) {
        val player = event.entity as? ServerPlayer ?: return
        if (!wants(player)) return
        event.isCanceled = true
        if (event.action == PlayerInteractEvent.LeftClickBlock.Action.START) {
            LinkerItem.handWith(player)?.let { (player.mainHandItem.item as? LinkerItem ?: player.offhandItem.item as LinkerItem).trackingPulse(player, it) }
        }
    }

    private fun onAttack(event: AttackEntityEvent) {
        val player = event.entity as? ServerPlayer ?: return
        if (!wants(player) || player.mainHandItem.item !is LinkerItem) return
        event.isCanceled = true
        (player.mainHandItem.item as LinkerItem).trackingPulse(player, net.minecraft.world.InteractionHand.MAIN_HAND)
    }

    /** From the client's left click on air. */
    fun onTrackPayload(player: ServerPlayer, hand: net.minecraft.world.InteractionHand) {
        if (!wants(player)) return
        (player.getItemInHand(hand).item as? LinkerItem)?.trackingPulse(player, hand)
    }
}
