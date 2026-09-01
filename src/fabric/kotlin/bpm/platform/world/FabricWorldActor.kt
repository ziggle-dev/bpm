package bpm.platform.world

import com.mojang.authlib.GameProfile
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.state.BlockState
import java.util.UUID

/**
 * The pseudo-player, on a loader with no fake-player API.
 *
 * NeoForge ships `FakePlayer` and a `FakePlayerFactory` that caches one per level. Fabric has neither, so
 * this keeps its own cache and builds a plain `ServerPlayer` with a profile whose UUID is derived from
 * the name — the same offline-mode scheme the game itself uses, so the actor is stable across restarts
 * and recognisable in a protection mod's logs rather than a fresh stranger every boot.
 *
 * The player is never added to the level and never ticked. `WorldJobs` positions it, puts a stack in its
 * hand and reads results back; nothing else touches it.
 */
object FabricWorldActor : WorldActor {

    /** The same name NeoForge's fake players use, so server operators see something they recognise. */
    private const val NAME = "[bpm]"

    private val profile = GameProfile(UUID.nameUUIDFromBytes("OfflinePlayer:$NAME".toByteArray()), NAME)
    private val byLevel = HashMap<ServerLevel, ServerPlayer>()

    override fun player(level: ServerLevel): ServerPlayer = byLevel.getOrPut(level) {
        ServerPlayer(level.server, level, profile, net.minecraft.server.level.ClientInformation.createDefault())
    }

    /**
     * Fabric's break event is a callback pair rather than a cancellable event: BEFORE returns false to
     * refuse. That is the same question NeoForge's `BlockEvent.BreakEvent` answers, so a claim or
     * protection mod refuses a controller exactly as it refuses a person.
     */
    override fun mayBreak(level: ServerLevel, pos: BlockPos, state: BlockState, player: ServerPlayer): Boolean =
        PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(level, player, pos, state, level.getBlockEntity(pos))

    /**
     * Through an accessor mixin, not reflection.
     *
     * `attackStrengthTicker` is private in vanilla. NeoForge widens it with an access transformer and the
     * adapter there still reaches it reflectively; here a one-method `@Accessor` does the job with the
     * mappings applied at build time, so it cannot quietly stop working on an obfuscated build the way a
     * reflective lookup does. A thousand is simply far past the point where the swing counts as full.
     */
    override fun primeAttackStrength(player: Player) {
        (player as bpm.mixin.LivingEntityAccessor).`bpm$setAttackStrengthTicker`(1000)
    }
}
