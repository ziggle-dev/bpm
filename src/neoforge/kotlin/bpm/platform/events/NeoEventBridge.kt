package bpm.platform.events

import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.level.BlockEvent
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.util.function.Consumer

/**
 * NeoForge's game bus, wired to [BpmEvents].
 *
 * One place that knows this loader's event names, so that nothing else has to. The filtering that every
 * handler used to repeat — "is this entity actually a player" — happens here once, which is why
 * [BpmEvents.playerDeath] can be typed as a player rather than as a living thing.
 */
object NeoEventBridge {

    fun install(bus: IEventBus) {
        /*
         * `Item.onItemUseFirst` is a method NeoForge adds so a held item can answer a click on a block
         * before the block does. Vanilla has no such method, so the shared tree reaches it as an event
         * and this is where that event comes from on this loader. RightClickBlock fires just before the
         * interaction, and cancelling it with a result is what "first" means.
         */
        bus.addListener(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock::class.java, Consumer { e ->
            val payload = UseOnBlock(e.level, e.entity, e.hand, e.pos, e.face ?: net.minecraft.core.Direction.UP)
            BpmEvents.useOnBlock.fire(payload)
            payload.result?.let { r ->
                e.isCanceled = true
                e.cancellationResult = r
            }
        })

        bus.addListener(ServerAboutToStartEvent::class.java, Consumer { BpmEvents.serverStarting.fire(it.server) })
        bus.addListener(ServerTickEvent.Post::class.java, Consumer { BpmEvents.serverTickEnd.fire(it.server) })
        bus.addListener(ServerStoppingEvent::class.java, Consumer { BpmEvents.serverStopping.fire(it.server) })

        bus.addListener(PlayerEvent.PlayerLoggedInEvent::class.java, Consumer { e ->
            (e.entity as? ServerPlayer)?.let(BpmEvents.playerJoin::fire)
        })
        bus.addListener(PlayerEvent.PlayerLoggedOutEvent::class.java, Consumer { e ->
            (e.entity as? ServerPlayer)?.let(BpmEvents.playerLeave::fire)
        })
        bus.addListener(PlayerEvent.PlayerRespawnEvent::class.java, Consumer { e ->
            (e.entity as? ServerPlayer)?.let { BpmEvents.playerRespawn.fire(Respawn(it, e.isEndConquered)) }
        })
        bus.addListener(LivingDeathEvent::class.java, Consumer { e ->
            (e.entity as? ServerPlayer)?.let(BpmEvents.playerDeath::fire)
        })

        bus.addListener(BlockEvent.BreakEvent::class.java, Consumer { e ->
            val level = e.level as? ServerLevel ?: return@Consumer
            if (!BpmEvents.blockBreak.fire(BlockBreak(level, e.pos, e.state, e.player))) e.isCanceled = true
        })

        bus.addListener(net.neoforged.neoforge.event.RegisterCommandsEvent::class.java, Consumer { e ->
            BpmEvents.registerCommands.fire(CommandRegistration(e.dispatcher, e.buildContext))
        })

        bus.addListener(PlayerEvent.ItemCraftedEvent::class.java, Consumer { e ->
            BpmEvents.itemCrafted.fire(Crafted(e.entity, e.crafting, e.inventory))
        })

        bus.addListener(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock::class.java, Consumer { e ->
            val phase = when (e.action) {
                net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock.Action.START -> ClickPhase.START
                net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock.Action.CLIENT_HOLD -> ClickPhase.HOLD
                net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock.Action.STOP -> ClickPhase.STOP
                else -> ClickPhase.ABORT
            }
            if (!BpmEvents.leftClickBlock.fire(LeftClickBlock(e.entity, e.pos, e.face, phase))) e.isCanceled = true
        })

        bus.addListener(net.neoforged.neoforge.event.entity.player.AttackEntityEvent::class.java, Consumer { e ->
            if (!BpmEvents.attackEntity.fire(AttackEntity(e.entity, e.target))) e.isCanceled = true
        })

        bus.addListener(LivingDropsEvent::class.java, Consumer { e ->
            val player = e.entity as? ServerPlayer ?: return@Consumer
            // The stacks are copied here rather than in the listener: cancelling the event is what stops
            // the originals being spawned, and a listener that stashed the live ItemStacks would be
            // holding objects the event still owns.
            val stacks = e.drops.map { it.item.copy() }.filter { !it.isEmpty }
            if (stacks.isEmpty()) return@Consumer
            if (!BpmEvents.playerDrops.fire(Drops(player, stacks))) e.isCanceled = true
        })
    }
}
