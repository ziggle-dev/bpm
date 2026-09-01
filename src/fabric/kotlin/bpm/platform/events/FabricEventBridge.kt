package bpm.platform.events

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.fabricmc.fabric.api.event.player.AttackEntityCallback
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult

/**
 * Fabric's callbacks, wired to [BpmEvents].
 *
 * The same job as `NeoEventBridge` and a good deal shorter, because Fabric's events are already
 * one-callback-per-thing rather than a class hierarchy to filter. Where the two loaders genuinely differ
 * is in how a listener says no: NeoForge cancels an event object, Fabric returns a value. Both land on
 * the same `Veto`, which is why the subsystems above never learn the difference.
 *
 * Three hooks are NOT wired here, and each is a mixin rather than an oversight — Fabric has no callback
 * for them at all:
 *
 * - `playerDrops`: needs `Inventory#dropAll`.
 * - `itemCrafted`: needs `ResultSlot#onTake`.
 * - `leftClickBlock` in its HOLD phase: `AttackBlockCallback` fires on START only, and the monitor
 *   slider's drag depends on HOLD. START is wired below; the rest wants
 *   `MultiPlayerGameMode#continueDestroyBlock`, which is client-side.
 *
 * They are listed together so the next person sees the whole gap at once rather than finding it three
 * times.
 */
object FabricEventBridge {

    fun install() {
        ServerLifecycleEvents.SERVER_STARTING.register { server -> BpmEvents.serverStarting.fire(server) }
        ServerLifecycleEvents.SERVER_STOPPING.register { server -> BpmEvents.serverStopping.fire(server) }
        ServerTickEvents.END_SERVER_TICK.register { server -> BpmEvents.serverTickEnd.fire(server) }

        ServerPlayConnectionEvents.JOIN.register { handler, _, _ -> BpmEvents.playerJoin.fire(handler.player) }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ -> BpmEvents.playerLeave.fire(handler.player) }

        ServerPlayerEvents.AFTER_RESPAWN.register { _, newPlayer, alive ->
            // `alive` is Fabric's word for "came back through the end portal rather than from a death".
            BpmEvents.playerRespawn.fire(Respawn(newPlayer, alive))
        }
        ServerLivingEntityEvents.AFTER_DEATH.register { entity, _ ->
            (entity as? ServerPlayer)?.let(BpmEvents.playerDeath::fire)
        }

        /*
         * Returning false refuses the break, which is the same answer NeoForge gets by cancelling its
         * BreakEvent. Note this is also what FabricWorldActor asks when a controller breaks a block, so a
         * protection mod sees one consistent question whoever is swinging.
         */
        PlayerBlockBreakEvents.BEFORE.register { level, player, pos, state, _ ->
            level !is ServerLevel || BpmEvents.blockBreak.fire(BlockBreak(level, pos, state, player))
        }

        AttackBlockCallback.EVENT.register { player, level, _, pos, direction ->
            val allowed = BpmEvents.leftClickBlock.fire(LeftClickBlock(player, pos, direction, ClickPhase.START))
            if (allowed) InteractionResult.PASS else InteractionResult.FAIL
        }

        AttackEntityCallback.EVENT.register { player, _, _, target, _ ->
            val allowed = BpmEvents.attackEntity.fire(AttackEntity(player, target))
            if (allowed) InteractionResult.PASS else InteractionResult.FAIL
        }

        /*
         * The counterpart of NeoForge's `Item.onItemUseFirst`. A listener claims the click by setting a
         * result; anything else falls through to the block, which is what keeps "a plain use on a chest
         * still opens the chest" true.
         */
        UseBlockCallback.EVENT.register { player, level, hand, hit ->
            val payload = UseOnBlock(level, player, hand, hit.blockPos, hit.direction)
            BpmEvents.useOnBlock.fire(payload)
            payload.result ?: InteractionResult.PASS
        }

        CommandRegistrationCallback.EVENT.register { dispatcher, registry, _ ->
            BpmEvents.registerCommands.fire(CommandRegistration(dispatcher, registry))
        }
    }
}
