package bpm.session

import bpm.catalog.McTypes
import dev.ziggle.vscript.model.Node
import dev.ziggle.vscript.model.NodeCatalog

/**
 * A link renamed on a controller is renamed in its graph as well: every literal typed into a `Link`-typed
 * input pin that names the old link comes to name the new one. The catalogue says which pins are links, so a
 * string that merely happens to equal the name (a chat message, say) is left alone.
 */
object LinkRenames {
    /** The (node id, pin) pairs whose literal names [old]. */
    fun references(nodes: List<Node>, catalog: NodeCatalog, old: String): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        for (node in nodes) {
            val desc = catalog[node.type] ?: continue
            for (pin in desc.inputs) {
                if (pin.type.name != McTypes.LINK.name) continue
                if (node.literals[pin.name] == old) out += node.id to pin.name
            }
        }
        return out
    }

    /** Rewrites the literals in place; answers how many changed. */
    fun rewrite(nodes: List<Node>, catalog: NodeCatalog, old: String, new: String): Int {
        val refs = references(nodes, catalog, old)
        for ((id, pin) in refs) nodes.first { it.id == id }.literals[pin] = new
        return refs.size
    }
}
