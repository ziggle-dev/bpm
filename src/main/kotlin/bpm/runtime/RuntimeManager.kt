package bpm.runtime

import bpm.platform.events.BpmEvents
import bpm.Bpm
import bpm.library.BpmLibrary
import bpm.world.ControllerBlockEntity
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer

/**
 * Ticks every loaded controller's program from one place, so that they share one budget fairly instead of
 * each block entity ticking itself.
 *
 * Per server tick: the [clock] moves, a few queued (re)starts compile (spreading a redeploy of a blueprint
 * used by many controllers over ticks), then each live runtime gets an equal share of [globalBudgetMs],
 * clamped to [[minShareMicros], [maxShareMs]], starting from a rotating offset so no controller is always
 * the one that hits the global deadline. A runtime that stays over [hardLimitMs] for three ticks (a host
 * call cannot be preempted) is faulted by its block entity.
 *
 * Controllers register in `onLoad` and unregister when removed or unloaded; a stopping server drains them
 * so that `on sleep` handlers get to write their state. Server thread only.
 */
object RuntimeManager {
    val clock = TickClock()

    private val controllers = LinkedHashSet<ControllerBlockEntity>()
    private val restarts = ArrayDeque<ControllerBlockEntity>()
    private var rotate = 0

    var globalBudgetMs: Double = 8.0
    var minShareMicros: Long = 250
    var maxShareMs: Double = 3.0
    var restartsPerTick: Int = 4
    var hardLimitMs: Double = 20.0

    /** Controllers skipped because the tick's global deadline had passed, since the server started. */
    var skipped: Long = 0
        private set

    /** Wall time the last tick's scripting took. */
    var lastTickNanos: Long = 0
        private set

    val size: Int get() = controllers.size
    val live: Int get() = controllers.count { it.runtime != null }

    fun install() {
        BpmEvents.serverStarting.listen { reset() }
        BpmEvents.serverTickEnd.listen { onTick() }
        BpmEvents.serverStopping.listen { onStopping() }
    }

    fun library(level: ServerLevel): BpmLibrary = BpmLibrary.get(level.server)

    fun register(be: ControllerBlockEntity) {
        controllers.add(be)
    }

    fun unregister(be: ControllerBlockEntity) {
        controllers.remove(be)
        restarts.remove(be)
    }

    /** Starts (or restarts) [be] on one of the next ticks. */
    fun queueRestart(be: ControllerBlockEntity) {
        if (be !in restarts) restarts.addLast(be)
    }

    fun all(): List<ControllerBlockEntity> = controllers.toList()

    private fun reset() {
        controllers.clear()
        restarts.clear()
        rotate = 0
        skipped = 0
        clock.ticks = 0
    }

    private fun onTick() {
        val t0 = System.nanoTime()
        clock.ticks++
        repeat(restartsPerTick) {
            val be = restarts.removeFirstOrNull() ?: return@repeat
            if (be in controllers && !be.isRemoved) be.startRuntime()
        }
        val live = controllers.filter { it.runtime != null }
        if (live.isNotEmpty()) {
            val n = live.size
            val share = (globalBudgetMs * 1_000_000.0 / n).toLong()
                .coerceIn(minShareMicros * 1_000, (maxShareMs * 1_000_000.0).toLong())
            val deadline = t0 + (globalBudgetMs * 2_000_000.0).toLong()
            rotate = (rotate + 1) % n
            for (i in 0 until n) {
                val be = live[(i + rotate) % n]
                if (System.nanoTime() > deadline) {
                    skipped++
                    continue
                }
                // Every controller gets the same share, whatever core it was built around: a tier buys reach
                // and breadth, never tick time (docs/DESIGN_TIERS_AND_FABRICATION.md §2.2).
                be.tickRuntime(share, (hardLimitMs * 1_000_000.0).toLong())
            }
        }
        lastTickNanos = System.nanoTime() - t0
    }

    private fun onStopping() {
        for (be in controllers.toList()) be.drainForShutdown()
    }

    /** A script's `chat.notify`: the players near the controller, on the action bar for info, in chat otherwise. */
    fun notify(be: ControllerBlockEntity, level: String, message: String) {
        val world = be.level as? ServerLevel ?: return
        val text = Component.literal("[bpm ${be.blockPos.toShortString()}] $message")
        val actionBar = level.equals("info", ignoreCase = true)
        val p = be.blockPos
        for (player in world.players()) {
            if (player is ServerPlayer && player.distanceToSqr(p.x + 0.5, p.y + 0.5, p.z + 0.5) <= NOTIFY_RANGE_SQ) {
                player.displayClientMessage(text, actionBar)
            }
        }
    }

    fun stats(): String {
        val running = controllers.filter { it.runtime != null }
        val fibers = running.sumOf { it.runtime?.runtime?.fibers?.size ?: 0 }
        val jobs = running.sumOf { it.runtime?.jobs?.size ?: 0 }
        return "tick ${clock.ticks}: ${controllers.size} controllers, ${running.size} running, $fibers fibers, $jobs jobs, " +
            "last tick ${"%.2f".format(lastTickNanos / 1e6)} ms, budget $globalBudgetMs ms, skipped $skipped, ${restarts.size} restarts queued"
    }

    private const val NOTIFY_RANGE_SQ = 32.0 * 32.0

    init {
        Bpm.LOGGER.debug("runtime manager ready")
    }
}
