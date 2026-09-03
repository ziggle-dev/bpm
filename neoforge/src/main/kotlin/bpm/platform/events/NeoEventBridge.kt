package bpm.platform.events

import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
/*
 * The SAME events under two package names.
 *
 * 1.20.1 is NeoForge's fork point and still ships Forge's packages -- `net.minecraftforge` throughout,
 * with the event bus at `net.minecraftforge.eventbus.api`. The rename to `net.neoforged` came with
 * 1.20.2. Almost every event kept its simple name across it, so swapping the IMPORTS is enough and the
 * body below reads the same on both sides; imports also carry nested classes, which a typealias would
 * not -- `PlayerEvent.PlayerLoggedInEvent` and `PlayerInteractEvent.LeftClickBlock.Action.START` are
 * both reached that way.
 *
 * The exception is the tick event, which is not a rename: see where it is registered.
 */
//? if >=1.20.2 {
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import net.neoforged.neoforge.event.level.BlockEvent
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
//?} else {
/*import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.event.RegisterCommandsEvent
import net.minecraftforge.event.entity.player.AttackEntityEvent
import net.minecraftforge.event.entity.living.LivingDeathEvent
import net.minecraftforge.event.entity.living.LivingDropsEvent
import net.minecraftforge.event.entity.player.PlayerEvent
import net.minecraftforge.event.entity.player.PlayerInteractEvent
import net.minecraftforge.event.level.BlockEvent
import net.minecraftforge.event.server.ServerAboutToStartEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.event.TickEvent
*///?}
import java.util.function.Consumer

/**
 * NeoForge's game bus, wired to [BpmEvents].
 *
 * One place that knows this loader's event names, so that nothing else has to. The filtering that every
 * handler used to repeat — "is this entity actually a player" — happens here once, which is why
 * [BpmEvents.playerDeath] can be typed as a player rather than as a living thing.
 */
/**
 * Add a listener for [type].
 *
 * The two buses disagree about how a listener names its event. NeoForge's takes the class and the
 * consumer; MinecraftForge's takes a bare consumer and reads the event type off the lambda's generic
 * signature -- which a Kotlin lambda does not carry -- so on that era the four-argument overload, the
 * one that is handed the class, is the only one that works.
 */
//? if >=1.20.2 {
private fun <T : net.neoforged.bus.api.Event> IEventBus.on(type: Class<T>, handler: Consumer<T>) = addListener(type, handler)
//?} else {
/*private fun <T : net.minecraftforge.eventbus.api.Event> IEventBus.on(type: Class<T>, handler: Consumer<T>) =
    addListener(net.minecraftforge.eventbus.api.EventPriority.NORMAL, false, type, handler)
*///?}

object NeoEventBridge {

    fun install(bus: IEventBus) {
        /*
         * `Item.onItemUseFirst` is a method NeoForge adds so a held item can answer a click on a block
         * before the block does. Vanilla has no such method, so the shared tree reaches it as an event
         * and this is where that event comes from on this loader. RightClickBlock fires just before the
         * interaction, and cancelling it with a result is what "first" means.
         */
        bus.on(PlayerInteractEvent.RightClickBlock::class.java, Consumer { e ->
            val payload = UseOnBlock(e.level, e.entity, e.hand, e.pos, e.face ?: net.minecraft.core.Direction.UP)
            BpmEvents.useOnBlock.fire(payload)
            payload.result?.let { r ->
                e.isCanceled = true
                e.cancellationResult = r
            }
        })

        bus.on(ServerAboutToStartEvent::class.java, Consumer { BpmEvents.serverStarting.fire(it.server) })
        /*
         * Not a rename. From 1.20.2 the tick events are their own classes with `Pre` and `Post` nested
         * inside; on 1.20.1 there is ONE `TickEvent.ServerTickEvent` carrying a phase, and subscribing
         * without checking it fires this twice per tick -- once at the start and once at the end.
         */
        //? if >=1.20.2 {
        bus.on(ServerTickEvent.Post::class.java, Consumer { BpmEvents.serverTickEnd.fire(it.server) })
        //?} else {
        /*bus.on(TickEvent.ServerTickEvent::class.java, Consumer { e ->
            if (e.phase == TickEvent.Phase.END) BpmEvents.serverTickEnd.fire(e.server)
        })
        *///?}
        bus.on(ServerStoppingEvent::class.java, Consumer { BpmEvents.serverStopping.fire(it.server) })

        bus.on(PlayerEvent.PlayerLoggedInEvent::class.java, Consumer { e ->
            (e.entity as? ServerPlayer)?.let(BpmEvents.playerJoin::fire)
        })
        bus.on(PlayerEvent.PlayerLoggedOutEvent::class.java, Consumer { e ->
            (e.entity as? ServerPlayer)?.let(BpmEvents.playerLeave::fire)
        })
        bus.on(PlayerEvent.PlayerRespawnEvent::class.java, Consumer { e ->
            (e.entity as? ServerPlayer)?.let { BpmEvents.playerRespawn.fire(Respawn(it, e.isEndConquered)) }
        })
        bus.on(LivingDeathEvent::class.java, Consumer { e ->
            (e.entity as? ServerPlayer)?.let(BpmEvents.playerDeath::fire)
        })

        /*
         * `BlockEvent.BreakEvent` became a top-level `BreakBlockEvent` in `event.level.block` at 26.1.
         * Everything it carries is unchanged -- level, pos and state off the `BlockEvent` base, the
         * player on the subclass, cancellation through `ICancellableEvent` -- so only the name it is
         * listened for differs, and the two bodies below are identical apart from that name.
         */
        //? if >=26.1 {
        /*bus.on(net.neoforged.neoforge.event.level.block.BreakBlockEvent::class.java, Consumer { e ->
            val level = e.level as? ServerLevel ?: return@Consumer
            if (!BpmEvents.blockBreak.fire(BlockBreak(level, e.pos, e.state, e.player))) e.isCanceled = true
        })
        *///?} else {
        bus.on(BlockEvent.BreakEvent::class.java, Consumer { e ->
            val level = e.level as? ServerLevel ?: return@Consumer
            if (!BpmEvents.blockBreak.fire(BlockBreak(level, e.pos, e.state, e.player))) e.isCanceled = true
        })
        //?}

        bus.on(RegisterCommandsEvent::class.java, Consumer { e ->
            BpmEvents.registerCommands.fire(CommandRegistration(e.dispatcher, e.buildContext))
        })

        bus.on(PlayerEvent.ItemCraftedEvent::class.java, Consumer { e ->
            BpmEvents.itemCrafted.fire(Crafted(e.entity, e.crafting, e.inventory))
        })

        bus.on(PlayerInteractEvent.LeftClickBlock::class.java, Consumer { e ->
            val phase = when (e.action) {
                PlayerInteractEvent.LeftClickBlock.Action.START -> ClickPhase.START
                PlayerInteractEvent.LeftClickBlock.Action.CLIENT_HOLD -> ClickPhase.HOLD
                PlayerInteractEvent.LeftClickBlock.Action.STOP -> ClickPhase.STOP
                else -> ClickPhase.ABORT
            }
            if (!BpmEvents.leftClickBlock.fire(LeftClickBlock(e.entity, e.pos, e.face, phase))) e.isCanceled = true
        })

        bus.on(AttackEntityEvent::class.java, Consumer { e ->
            if (!BpmEvents.attackEntity.fire(AttackEntity(e.entity, e.target))) e.isCanceled = true
        })

        bus.on(LivingDropsEvent::class.java, Consumer { e ->
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
