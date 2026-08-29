package bpm.nodes

import bpm.catalog.McTypes
import bpm.catalog.McVs
import bpm.runtime.PredicateJob
import io.osrsx.vscript.nodes.Contribution
import io.osrsx.vscript.nodes.library

/** `redstone.*` — reading signals at links, and driving the controller's own faces. */
object RedstoneNodes {
    fun contribution(host: ControllerHost): Contribution = library("redstone", "Redstone") {
        func("level") {
            title("Redstone Level")
            doc("The strongest signal reaching the linked block, 0 to 15. 0 when the link is missing or unloaded.")
            val link = param("Link", McVs.link, "which block")
            result("Level", McVs.int)
            query { host.link(link())?.takeIf { it.loaded }?.let { host.level.getBestNeighborSignal(it.link.pos).toLong() } ?: 0L }
        }
        func("powered") {
            title("Is Powered")
            doc("Whether any signal reaches the linked block.")
            val link = param("Link", McVs.link, "which block")
            result("Powered", McVs.bool)
            query { host.link(link())?.takeIf { it.loaded }?.let { host.level.hasNeighborSignal(it.link.pos) } ?: false }
        }
        func("emitAt") {
            title("Emit At Link")
            doc(
                """
                Put a redstone signal out of a linked **Signal Emitter** block, 0 to 15, until changed — so the
                controller powers things wherever a link reaches, not only beside itself. The emitter powers
                every block around it, strongly. Answers false when the link is not an emitter, or unloaded.
                """,
            )
            val link = param("Link", McVs.link, "which emitter")
            val level = param("Level", McVs.int, "0 to 15", default = 15L)
            result("Ok", McVs.bool)
            command {
                val target = host.link(link())?.takeIf { it.loaded } ?: return@command false
                bpm.world.SignalEmitterBlock.emit(host.level, target.link.pos, level().toInt())
            }
        }
        func("emittedAt") {
            title("Emitted At Link")
            doc("The signal a linked Signal Emitter is putting out, 0 to 15. 0 when the link is not an emitter or unloaded.")
            val link = param("Link", McVs.link, "which emitter")
            result("Level", McVs.int)
            query { host.link(link())?.takeIf { it.loaded }?.let { bpm.world.SignalEmitterBlock.levelAt(host.level, it.link.pos) }?.toLong() ?: 0L }
        }
        func("emitted") {
            title("Emitted Level")
            doc("The signal this controller is putting out of one of its faces.")
            val side = param("Side", McVs.direction, "which face")
            result("Level", McVs.int)
            query { McTypes.direction(side())?.let { host.emitted(it).toLong() } ?: 0L }
        }
        func("emit") {
            title("Emit Redstone")
            doc("Put a signal out of one of this controller's faces, 0 to 15, until changed.")
            val side = param("Side", McVs.direction, "which face")
            val level = param("Level", McVs.int, "0 to 15", default = 15L)
            command {
                McTypes.direction(side())?.let { host.emitSignal(it, level().toInt().coerceIn(0, 15)) }
                null
            }
        }
        func("waitFor") {
            title("Wait For Redstone")
            doc("Park until the linked block is powered (or not), or the timeout passes. Ok says which.")
            val link = param("Link", McVs.link, "which block")
            val powered = param("Powered", McVs.bool, "wait for power, or for none", default = true)
            val timeout = param("Timeout Ticks", McVs.int, "give up after this many ticks; 0 = never", default = 0L)
            result("Ok", McVs.bool)
            action {
                val name = link(); val want = powered()
                host.jobs.start(PredicateJob("redstone.waitFor", timeout().toInt()) {
                    host.link(name)?.takeIf { it.loaded }?.let { host.level.hasNeighborSignal(it.link.pos) == want } ?: false
                })
            }
        }
    }
}
