package bpm.library

import io.osrsx.vscript.model.BuiltinNodes
import io.osrsx.vscript.model.Graph
import io.osrsx.vscript.model.Link
import io.osrsx.vscript.model.Node
import java.util.UUID

/** Programs the mod ships as documents: the Warden's, which the Warden's Program item puts in the library. */
object Programs {
    const val WARDEN_PROGRAM = "Warden's Program"

    private class B(val name: String) {
        val nodes = ArrayList<Node>()
        val links = ArrayList<Link>()
        fun node(type: String, vararg literals: Pair<String, Any?>, x: Float = 0f, y: Float = 0f): Int {
            val id = nodes.size + 1
            nodes += Node(id, type, x = x, y = y, literals = LinkedHashMap(literals.toMap()))
            return id
        }
        fun exec(from: Int, to: Int, fromPin: String = "Exec") { links += Link(links.size + 1, from, fromPin, to, "Exec") }
        fun build() = Graph(UUID.nameUUIDFromBytes("bpm-program-$name".toByteArray()).toString(), name, nodes, links)
    }

    /**
     * The room's trap program, as the Warden runs it: every pass — the spike goes off, the turret fires at
     * whatever it tracks, the bridge drops out for two seconds and comes back. Expects links `spike`,
     * `turret` and `bridge`.
     */
    fun wardenProgram(): Graph = B(WARDEN_PROGRAM).run {
        val tick = node(BuiltinNodes.ENTRY_TICK, x = 40f, y = 80f)
        val spike = node("trap.fire", "Link" to "spike", x = 300f, y = 80f)
        val fire = node("turret.fire", "Link" to "turret", x = 560f, y = 80f)
        val drop = node("phase.solid", "Link" to "bridge", "Solid" to false, x = 820f, y = 80f)
        val wait1 = node("controller.wait", "Ticks" to 40L, x = 1080f, y = 80f)
        val raise = node("phase.solid", "Link" to "bridge", "Solid" to true, x = 1340f, y = 80f)
        val wait2 = node("controller.wait", "Ticks" to 40L, x = 1600f, y = 80f)
        exec(tick, spike)
        exec(spike, fire)
        exec(fire, drop)
        exec(drop, wait1)
        exec(wait1, raise)
        exec(raise, wait2)
        build()
    }
}
