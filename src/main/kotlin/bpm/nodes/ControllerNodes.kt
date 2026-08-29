package bpm.nodes

import bpm.catalog.McVs
import bpm.catalog.values.BlockPosValue
import bpm.runtime.CountdownJob
import io.osrsx.vscript.nodes.Contribution
import io.osrsx.vscript.nodes.library

/** `controller.*` — the block the script runs in. */
object ControllerNodes {
    fun contribution(host: ControllerHost): Contribution = library("controller", "Controller") {
        func("pos") {
            title("Controller Position")
            doc("Where this controller stands.")
            result("Pos", McVs.blockPos)
            query { BlockPosValue.of(host.pos) }
        }
        func("links") {
            title("Links")
            doc("Every link this controller has, by name — the ones the wand made, plus `self`, its own buffer.")
            result("Links", McVs.link.list())
            query { listOf(ControllerHost.SELF) + host.links.names.toList() }
        }
        func("link") {
            title("Link By Name")
            doc("A link, by the name it was given. Nothing when there is no link called that.")
            val name = param("Name", McVs.string, "the link's name")
            result("Link", McVs.link.orNull())
            query { val n = name(); if (n == ControllerHost.SELF || host.links[n] != null) n else null }
        }
        func("linkInfo") {
            title("Link Info")
            doc(
                """
                What a link points at: the block's position, the face that was linked (nothing when none
                was), whether a link by that name exists, and whether its chunk is loaded right now. `self`
                answers the controller's own position. The way from a Link to a Pos for Area, Distance,
                Offset and Link At.
                """,
            )
            val link = param("Link", McVs.link, "which link")
            val pos = result("Pos", McVs.blockPos)
            val side = result("Side", McVs.direction.orNull())
            val exists = result("Exists", McVs.bool)
            val loaded = result("Loaded", McVs.bool)
            query {
                pos set BlockPosValue.of(host.pos)
                side set null
                exists set false
                loaded set false
                val n = link()
                if (n == ControllerHost.SELF) {
                    exists set true
                    loaded set true
                    return@query null
                }
                val r = host.link(n) ?: return@query null
                pos set BlockPosValue.of(r.link.pos)
                side set r.link.side?.let { bpm.catalog.McTypes.directionName(it) }
                exists set true
                loaded set r.loaded
                null
            }
        }
        func("linkAt") {
            title("Link At")
            doc(
                """
                A link made on the spot from a position — and a face, when one matters — for working an
                area or anything the wand did not name. Goes wherever a Link goes; the position must be in
                the controller's dimension and within the linker's range of it.
                """,
            )
            val at = param("Pos", McVs.blockPos, "which block")
            val side = param("Side", McVs.direction.orNull(), "which face, when a face matters")
            result("Link", McVs.link)
            query {
                val p = bpm.catalog.values.BlockPosValue.toBlockPos(at()) ?: return@query ""
                bpm.world.AdHocLink.name(p, bpm.catalog.McTypes.direction(side()))
            }
        }
        func("tickCount") {
            title("Tick Count")
            doc("How many ticks the server has run — the clock `delay` and `wait` count on.")
            result("Ticks", McVs.int)
            query { host.tickCount }
        }
        func("dimension") {
            title("Dimension")
            doc("Which dimension this controller is in, as its id.")
            result("Id", McVs.string)
            query { host.level.dimension().location().toString() }
        }
        func("wait") {
            title("Wait Ticks")
            doc(
                """
                Park for a number of ticks. In `on tick`, the pass stretches over them and the next pass
                begins when the wait is over; one tick is 50 milliseconds when the server keeps up.
                """,
            )
            val ticks = param("Ticks", McVs.int, "how many ticks", default = 1L)
            action { host.jobs.start(CountdownJob(ticks().toInt().coerceAtLeast(1))) }
        }
        func("sleep") {
            title("Sleep")
            doc("Ask this script to finish the pass it is in and go to sleep; `on sleep` runs after.")
            val reason = param("Reason", McVs.string, "what to say in the log", default = "")
            command { host.requestSleep(reason()); null }
        }
    }
}
