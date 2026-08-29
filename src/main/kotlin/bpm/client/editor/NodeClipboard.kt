package bpm.client.editor

import io.osrsx.vscript.model.Graph
import io.osrsx.vscript.model.GraphDoc
import io.osrsx.vscript.model.Link
import io.osrsx.vscript.runtime.EditorDoc

/**
 * Copy/paste of nodes as text: the selected nodes and the links between them, serialised through
 * `GraphDoc` so every field a node has (literals, comment, size, fold, function, callee) travels along
 * without this code knowing about it. Pasting takes fresh ids, drops the group at a point and is one undo
 * step. Pure — the workbench hands it the clipboard text.
 */
object NodeClipboard {
    const val MARKER = "bpm-nodes:"

    fun encode(doc: EditorDoc, ids: Collection<Int>): String? {
        val set = ids.toSet()
        if (set.isEmpty()) return null
        val nodes = doc.nodes.filter { it.id in set }
        val links = doc.links.filter { it.fromNode in set && it.toNode in set }
        return MARKER + GraphDoc.toJson(Graph("clip", "clip", nodes, links))
    }

    fun decode(text: String?): Graph? {
        if (text == null || !text.startsWith(MARKER)) return null
        return runCatching { GraphDoc.fromJson(text.removePrefix(MARKER)) }.getOrNull()?.takeIf { it.nodes.isNotEmpty() }
    }

    /** Pastes [clip] with its top-left corner at ([x], [y]); the new node ids, in the clip's order. */
    fun paste(doc: EditorDoc, clip: Graph, x: Float, y: Float): List<Int> {
        if (clip.nodes.isEmpty()) return emptyList()
        val minX = clip.nodes.minOf { it.x }
        val minY = clip.nodes.minOf { it.y }
        val out = ArrayList<Int>()
        doc.edit("paste") {
            // Fresh instances, so pasting twice never shares a node object with the clipboard.
            val copies = GraphDoc.fromJson(GraphDoc.toJson(clip))
            val idMap = HashMap<Int, Int>()
            for (n in copies.nodes) idMap[n.id] = doc.takeNodeId()
            for (n in copies.nodes) {
                n.id = idMap[n.id]!!
                n.x = n.x - minX + x
                n.y = n.y - minY + y
                n.group = n.group?.let { idMap[it] }
                doc.nodes.add(n)
                out += n.id
            }
            for (l in copies.links) {
                doc.links.add(Link(doc.takeLinkId(), idMap[l.fromNode]!!, l.fromPin, idMap[l.toNode]!!, l.toPin))
            }
        }
        return out
    }

    /** Copy then delete, as one undo step for the delete. */
    fun cut(doc: EditorDoc, ids: Collection<Int>): String? {
        val text = encode(doc, ids) ?: return null
        doc.edit("cut") { ids.toList().forEach { doc.removeNode(it) } }
        return text
    }
}
