package bpm.platform.world

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.state.BlockState

/**
 * The pseudo-player a controller acts through, and the two things about it that only a loader can answer.
 *
 * A controller's world actions are meant to be indistinguishable from a person's — the tool decides the
 * speed, harvestability, fortune and durability, and other mods see the break happen — which means acting
 * through something the game will accept as a player. NeoForge ships one; Fabric has no such API at all
 * and needs roughly a hundred lines of `ServerPlayer` subclass with a connection that swallows packets.
 *
 * Everything else `WorldJobs` does with it is vanilla `ServerPlayer`, so only these three cross.
 */
interface WorldActor {

    /**
     * The actor for this level. The same instance each call: `WorldJobs` sets its position, puts a stack in
     * its hand and juggles attribute modifiers around a swing, all of which assume continuity.
     */
    fun player(level: ServerLevel): ServerPlayer

    /**
     * Fire the break event other mods listen to, and answer whether they allowed it.
     *
     * False means refused. This is the hook that lets claim and protection mods say no to a controller
     * exactly as they would to a person.
     */
    fun mayBreak(level: ServerLevel, pos: BlockPos, state: BlockState, player: ServerPlayer): Boolean

    /**
     * Leave the actor as if it had stood still a long while, so its next swing lands at full strength.
     *
     * A fake player is never ticked, so its attack-strength counter never recovers on its own and every
     * blow would otherwise be a weak one. The counter is private in vanilla, so how this is done is a
     * property of the loader's mappings — which is exactly why it belongs behind this interface rather
     * than in shared code doing reflection and hoping.
     */
    fun primeAttackStrength(player: Player)

    /**
     * Drop whatever experience breaking this block should have dropped.
     *
     * Vanilla knows the answer and will not say: `Block.getExpDrop` and `Block.popExperience` are both
     * protected, and NeoForge widens them and adds a `BlockState.getExpDrop` that consults the tool and
     * its enchantments (silk touch drops nothing, fortune does not change ore XP). There is no vanilla
     * route to the same number.
     *
     * The default therefore drops nothing, and says so rather than pretending: a `world.mine` on a loader
     * that has not implemented this yields the block's items but no orbs. That is a visible shortfall and
     * a small one, and it is better than guessing an amount that would be wrong for every modded block.
     */
    fun dropExperience(
        level: ServerLevel,
        pos: BlockPos,
        state: BlockState,
        blockEntity: net.minecraft.world.level.block.entity.BlockEntity?,
        player: ServerPlayer,
        tool: net.minecraft.world.item.ItemStack,
    ) {
    }
}

/** The installed actor. See [bpm.platform.net.Net] for why this is a `lateinit` and not a service lookup. */
object Actor {
    private lateinit var backend: WorldActor

    fun install(impl: WorldActor) {
        backend = impl
    }

    fun player(level: ServerLevel): ServerPlayer = backend.player(level)

    fun mayBreak(level: ServerLevel, pos: BlockPos, state: BlockState, player: ServerPlayer): Boolean =
        backend.mayBreak(level, pos, state, player)

    fun primeAttackStrength(player: Player) = backend.primeAttackStrength(player)

    fun dropExperience(
        level: ServerLevel,
        pos: BlockPos,
        state: BlockState,
        blockEntity: net.minecraft.world.level.block.entity.BlockEntity?,
        player: ServerPlayer,
        tool: net.minecraft.world.item.ItemStack,
    ) = backend.dropExperience(level, pos, state, blockEntity, player, tool)
}
