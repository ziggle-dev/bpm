package bpm.platform.world

import bpm.Bpm
import com.mojang.authlib.GameProfile
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.state.BlockState
import net.neoforged.neoforge.common.CommonHooks
import net.neoforged.neoforge.common.util.FakePlayerFactory
import java.util.UUID

/**
 * NeoForge's fake player.
 *
 * Note where the reflection now lives. It used to sit in `WorldJobs`, in code meant to be shared, where
 * it worked only by accident: NeoForge runs Mojang mappings at runtime, so `attackStrengthTicker` resolves
 * there and nowhere else. On a production Fabric jar (intermediary names) the lookup fails, and it failed
 * into a `runCatching { }.onFailure { warn }`, so the only symptom was that every controller's swing
 * landed at half strength with nothing in the log to connect it to.
 *
 * It is still reflection here, and that is fine, because here the mapping assumption is true and stated.
 * Fabric will implement the same method with a mixin accessor, which is mapping-safe by construction.
 */
object NeoWorldActor : WorldActor {

    /** One profile for every controller on the server: the name that shows in other mods' logs. */
    val PROFILE: GameProfile = GameProfile(UUID.nameUUIDFromBytes("bpm-controller".toByteArray()), "[bpm]")

    override fun player(level: ServerLevel): ServerPlayer = FakePlayerFactory.get(level, PROFILE)

    override fun mayBreak(level: ServerLevel, pos: BlockPos, state: BlockState, player: ServerPlayer): Boolean =
        !CommonHooks.fireBlockBreak(level, GameType.SURVIVAL, player, pos, state).isCanceled

    override fun primeAttackStrength(player: Player) {
        ticker?.setInt(player, 1000)
    }

    private val ticker: java.lang.reflect.Field? by lazy {
        runCatching { LivingEntity::class.java.getDeclaredField("attackStrengthTicker").also { it.isAccessible = true } }
            .onFailure { Bpm.LOGGER.warn("bpm: attackStrengthTicker is out of reach ({}); a controller's swings will be weak", it.toString()) }
            .getOrNull()
    }

    /**
     * The real amount, which this loader can ask for: `BlockState.getExpDrop` consults the tool and its
     * enchantments, and `popExperience` is widened so the orbs can actually be spawned.
     */
    override fun dropExperience(
        level: net.minecraft.server.level.ServerLevel,
        pos: net.minecraft.core.BlockPos,
        state: net.minecraft.world.level.block.state.BlockState,
        blockEntity: net.minecraft.world.level.block.entity.BlockEntity?,
        player: net.minecraft.server.level.ServerPlayer,
        tool: net.minecraft.world.item.ItemStack,
    ) {
        val xp = state.getExpDrop(level, pos, blockEntity, player, tool)
        if (xp > 0) state.block.popExperience(level, pos, xp)
    }
}
