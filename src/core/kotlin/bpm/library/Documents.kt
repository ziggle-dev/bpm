package bpm.library

import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.model.Graph
import dev.ziggle.vscript.model.GraphDoc
import dev.ziggle.vscript.model.Node
import java.util.UUID

/** New documents, the same on both sides. */
object Documents {
    /** A controller's graph starts with one `on tick`, because that is where a controller's program lives. */
    fun blankController(name: String): String =
        GraphDoc.toJson(Graph(UUID.randomUUID().toString(), name, listOf(Node(1, BuiltinNodes.ENTRY_TICK, 80f, 120f))))

    /** A library starts empty: it is a bag of exported functions, not a program. */
    fun blankLibrary(name: String): String = GraphDoc.toJson(Graph(UUID.randomUUID().toString(), name))

    fun controllerName(x: Int, y: Int, z: Int): String = "controller $x,$y,$z"
}
