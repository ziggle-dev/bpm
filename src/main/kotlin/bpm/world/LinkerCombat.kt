package bpm.world

import bpm.platform.events.BpmEvents
import bpm.platform.events.ClickPhase
import bpm.platform.events.Crafted
import bpm.platform.events.LeftClickBlock
import bpm.platform.events.AttackEntity
import bpm.chamber.ChamberDimension
import net.minecraft.server.level.ServerPlayer
import java.util.function.Consumer

/**
 * Sneak + attack with the linker inside a chamber fires its tracking pulse. A left click on a block or an
 * entity reaches the server as an event; a left click on air only exists on the client, which sends
 * `LinkerTrackPayload` instead (see `bpm.client.BpmClient`).
 */
object LinkerCombat {
    fun install() {
        BpmEvents.leftClickBlock.listen(::onLeftClickBlock)
        BpmEvents.attackEntity.listen(::onAttack)
    }

    private fun wants(player: net.minecraft.world.entity.player.Player): Boolean =
        player.isShiftKeyDown && ChamberDimension.isChamber(player.level()) && LinkerItem.handWith(player) != null

    /** False to swallow the click: a wand doing this is not also mining. */
    private fun onLeftClickBlock(event: LeftClickBlock): Boolean {
        val player = event.player as? ServerPlayer ?: return true
        if (!wants(player)) return true
        if (event.phase == ClickPhase.START) {
            LinkerItem.handWith(player)?.let { (player.mainHandItem.item as? LinkerItem ?: player.offhandItem.item as LinkerItem).trackingPulse(player, it) }
        }
        return false
    }

    private fun onAttack(event: AttackEntity): Boolean {
        val player = event.player as? ServerPlayer ?: return true
        if (!wants(player) || player.mainHandItem.item !is LinkerItem) return true
        (player.mainHandItem.item as LinkerItem).trackingPulse(player, net.minecraft.world.InteractionHand.MAIN_HAND)
        return false
    }

    /** From the client's left click on air. */
    fun onTrackPayload(player: ServerPlayer, hand: net.minecraft.world.InteractionHand) {
        if (!wants(player)) return
        (player.getItemInHand(hand).item as? LinkerItem)?.trackingPulse(player, hand)
    }
}
