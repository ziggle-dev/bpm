package bpm.platform.events

import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.state.BlockState

/**
 * The events the mod actually listens to, named by what happens rather than by whose bus carries it.
 *
 * Each loader spells these differently and NOT all of them exist everywhere: NeoForge has a
 * `LivingDropsEvent`, Fabric has nothing of the kind and needs a mixin on `Inventory#dropAll`. Rather
 * than let that difference reach the chamber and the runtime, the mod declares the set it cares about
 * here and each loader is responsible for firing them.
 *
 * The listeners keep the shape they already had. Every subsystem still exposes an `install()`; it simply
 * no longer takes a bus, because there is no longer one bus type to take.
 */

/** Something happened. Every listener runs. */
class Hook<P> {
    private val listeners = ArrayList<(P) -> Unit>()

    fun listen(fn: (P) -> Unit) {
        listeners += fn
    }

    fun fire(payload: P) {
        for (listener in listeners) listener(payload)
    }
}

/**
 * Something is about to happen and a listener may refuse it.
 *
 * **Every listener runs even after one has said no**, and the answer is the AND of all of them. The
 * alternative — stopping at the first refusal — would make behaviour depend on registration order, and
 * these listeners do bookkeeping as well as vetoing.
 */
class Veto<P> {
    private val listeners = ArrayList<(P) -> Boolean>()

    fun listen(fn: (P) -> Boolean) {
        listeners += fn
    }

    /** True to let it happen. */
    fun fire(payload: P): Boolean {
        var allow = true
        for (listener in listeners) if (!listener(payload)) allow = false
        return allow
    }
}

data class BlockBreak(val level: ServerLevel, val pos: BlockPos, val state: BlockState, val player: Player)

/** Something was taken out of a crafting result slot. [matrix] is the grid, still full. */
data class Crafted(val player: Player, val result: ItemStack, val matrix: net.minecraft.world.Container)

/**
 * All four phases, not only the two anything currently acts on.
 *
 * `LinkerCombat` refuses the click whatever phase it is in, and narrowing this to START and HOLD would
 * quietly stop it refusing the other two.
 */
enum class ClickPhase { START, HOLD, STOP, ABORT }

data class LeftClickBlock(
    val player: Player,
    val pos: BlockPos,
    val face: net.minecraft.core.Direction?,
    val phase: ClickPhase,
)

data class AttackEntity(val player: Player, val target: net.minecraft.world.entity.Entity)

/** A player's death drops, before they are scattered. Refusing means the listener has taken them. */
data class Drops(val player: ServerPlayer, val stacks: List<ItemStack>)

data class Respawn(val player: ServerPlayer, val endConquered: Boolean)

object BpmEvents {

    // ---- server lifecycle ----

    /** The server is about to start: everything holding per-run state clears it here. */
    val serverStarting = Hook<MinecraftServer>()
    val serverTickEnd = Hook<MinecraftServer>()
    val serverStopping = Hook<MinecraftServer>()

    // ---- players ----

    val playerJoin = Hook<ServerPlayer>()
    val playerLeave = Hook<ServerPlayer>()
    val playerRespawn = Hook<Respawn>()

    /** Only players. The loader event behind this fires for every living thing; the bridge filters. */
    val playerDeath = Hook<ServerPlayer>()

    // ---- cancellable gameplay ----

    val blockBreak = Veto<BlockBreak>()
    val playerDrops = Veto<Drops>()

    val itemCrafted = Hook<Crafted>()

    /** Fires on both sides: the HOLD phase only ever happens on the client, and the monitor slider wants it. */
    val leftClickBlock = Veto<LeftClickBlock>()
    val attackEntity = Veto<AttackEntity>()
}
