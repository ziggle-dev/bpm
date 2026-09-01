package bpm.client.editor

import dev.ziggle.imgui.FuzzySearch
import dev.ziggle.vscript.editor.host.IconRef
import dev.ziggle.vscript.editor.host.ValueCatalog
import dev.ziggle.vscript.model.HostEnum

/**
 * A host enum's members as a value catalogue — what gives a `Click`, `Aim`, `Direction` or `Notify` pin its
 * picker. A host enum is a NOMINAL type (`TypeRef.named`), so the editor has no built-in widget for it; a
 * catalogue is how a host says "here are the values", the same way it does for items and links. The stored
 * value is the member's name, which is what the node bodies read.
 */
class EnumCatalog(private val enum: HostEnum) : ValueCatalog {
    override fun search(query: String, limit: Int): List<ValueCatalog.Entry> {
        val q = query.trim()
        val hits = if (q.isEmpty()) enum.members else enum.members.filter { FuzzySearch.matches(it, q) }
        // No note: the enum's one-line description is the pin's tooltip already, and repeated on every row
        // it only competes with the member names for the width of the popup.
        return hits.take(limit).map { ValueCatalog.Entry(it, it) }
    }

    override fun browse(limit: Int): List<ValueCatalog.Entry> = search("", limit)

    /** A member, spelled as declared, or null for anything that is not one — so a mistyped literal shows as unknown. */
    override fun labelOf(value: Any?): String? = value?.toString()?.let { v -> enum.members.firstOrNull { it.equals(v, ignoreCase = true) } }

    override fun icon(value: Any?): IconRef? = null
}
