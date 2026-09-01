package bpm.client.mc

import bpm.catalog.McTypes
import bpm.client.net.ClientNet
import imgui.ImGui
import dev.ziggle.imgui.FuzzySearch
import dev.ziggle.vscript.editor.host.Editor
import dev.ziggle.vscript.editor.host.IconRef
import dev.ziggle.vscript.editor.host.TypeStyle
import dev.ziggle.vscript.editor.host.TypeStyles
import dev.ziggle.vscript.editor.host.ValueCatalog
import dev.ziggle.vscript.editor.host.ValueCatalogs
import dev.ziggle.vscript.model.TypeRef
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block

/**
 * The value pickers for the mod's types: items, blocks and fluids from the registries (searched by display
 * name and id), item tags, and the links of the controller the workbench is attached to. Installed as
 * `EditorHost.values`; vscript then gives every pin of those types a searchable catalogue editor.
 */
object BpmCatalogs : ValueCatalogs {
    private val items = RegistryCatalog(BuiltInRegistries.ITEM) { (it as Item).description.string }
    private val blocks = RegistryCatalog(BuiltInRegistries.BLOCK) { (it as Block).name.string }
    private val fluids = RegistryCatalog(BuiltInRegistries.FLUID) { null }
    private val tags = TagCatalog()
    private val links = LinkCatalog()
    private val keys = KeyCatalog()

    private val enums = HashMap<String, bpm.client.editor.EnumCatalog>()

    /** The registry pickers by name; any host enum gets a picker over its members (`Click`, `Aim`, `Direction`, `Notify`). */
    override fun catalogFor(type: TypeRef): ValueCatalog? = when (val name = type.required().name) {
        McTypes.ITEM.type.name -> items
        McTypes.BLOCK.type.name -> blocks
        McTypes.FLUID.type.name -> fluids
        McTypes.TAG.type.name -> tags
        McTypes.LINK.name -> links
        McTypes.KEY.type.name -> keys
        else -> dev.ziggle.vscript.model.HostEnums.of(name)?.let { e -> enums.getOrPut(e.name) { bpm.client.editor.EnumCatalog(e) } }
    }

    /** Drops cached registry snapshots (resource reload, new world). */
    fun invalidate() {
        items.invalidate()
        blocks.invalidate()
        fluids.invalidate()
        tags.invalidate()
    }
}

/** An icon that names a registry entry; the atlas (phase 7 polish) turns it into a texture region. */
class RegistryIcon(val id: String) : IconRef

private class Entry(val id: String, val label: String)

/** Searchable, sorted snapshot of a registry; ids are the stored values. */
private class RegistryCatalog(private val registry: Registry<*>, private val display: (Any) -> String?) : ValueCatalog {
    private var entries: List<Entry>? = null

    private fun entries(): List<Entry> = entries ?: registry.entrySet()
        .map { (key, value) -> Entry(key.location().toString(), runCatching { display(value as Any) }.getOrNull() ?: key.location().path) }
        .sortedBy { it.label.lowercase() }
        .also { entries = it }

    fun invalidate() {
        entries = null
    }

    override fun search(query: String, limit: Int): List<ValueCatalog.Entry> {
        val q = query.trim()
        if (q.isEmpty()) return browse(limit)
        return entries().asSequence()
            .mapNotNull { e -> (FuzzySearch.score(e.label, q) ?: FuzzySearch.score(e.id, q)?.let { it * 0.8 })?.let { it to e } }
            .sortedByDescending { it.first }
            .take(limit)
            .map { (_, e) -> toEntry(e) }
            .toList()
    }

    override fun browse(limit: Int): List<ValueCatalog.Entry> = entries().take(limit).map(::toEntry)

    override fun labelOf(value: Any?): String? {
        val id = value?.toString() ?: return null
        return entries().firstOrNull { it.id == id }?.label
    }

    override fun icon(value: Any?): IconRef? = value?.toString()?.takeIf { it.isNotEmpty() }?.let { RegistryIcon(it) }

    private fun toEntry(e: Entry) = ValueCatalog.Entry(e.id, e.label, e.id, RegistryIcon(e.id))
}

/**
 * Every key on the keyboard, so a `Key` pin gets a searchable list instead of a text field nobody can guess
 * the spelling for.
 *
 * Enumerated by asking `InputConstants` for each GLFW code rather than from a hand-written table: the names
 * are then exactly the ones the client will match a keypress against, and a name that does not exist on this
 * build simply never appears. Stored values are the canonical bare names [KeyNames] uses (`g`, `left_shift`),
 * shown as the game shows them.
 */
private class KeyCatalog : ValueCatalog {
    private var entries: List<Entry>? = null

    private fun entries(): List<Entry> = entries ?: buildList {
        for (code in 32..GLFW_LAST) {
            val key = runCatching { InputConstants.Type.KEYSYM.getOrCreate(code) }.getOrNull() ?: continue
            if (key == InputConstants.UNKNOWN) continue
            val name = bpm.world.KeyNames.normalise(key.name)
            if (name.isEmpty() || name == "unknown") continue
            add(Entry(name, bpm.world.KeyNames.label(name)))
        }
    }.distinctBy { it.id }.sortedBy { it.label.lowercase() }.also { entries = it }

    override fun search(query: String, limit: Int): List<ValueCatalog.Entry> {
        val q = query.trim()
        if (q.isEmpty()) return browse(limit)
        return entries().asSequence()
            .mapNotNull { e -> (FuzzySearch.score(e.label, q) ?: FuzzySearch.score(e.id, q))?.let { it to e } }
            .sortedByDescending { it.first }
            .take(limit)
            .map { (_, e) -> ValueCatalog.Entry(e.id, e.label) }
            .toList()
    }

    override fun browse(limit: Int): List<ValueCatalog.Entry> = entries().take(limit).map { ValueCatalog.Entry(it.id, it.label) }

    override fun labelOf(value: Any?): String? =
        value?.toString()?.takeIf { it.isNotEmpty() }?.let { bpm.world.KeyNames.label(bpm.world.KeyNames.normalise(it)) }

    /** A key has no picture; the name is the whole of it. */
    override fun icon(value: Any?): IconRef? = null

    private companion object {
        /** GLFW's highest key code (MENU); above it there is nothing to name. */
        const val GLFW_LAST = 348
    }
}

/** Item tags known to the client's registry; ids are the stored values (`namespace:path`, no `#`). */
private class TagCatalog : ValueCatalog {
    private var ids: List<String>? = null

    private fun ids(): List<String> = ids ?: BuiltInRegistries.ITEM.tags.map { it.first.location().toString() }.toList().sorted().also { ids = it }

    fun invalidate() {
        ids = null
    }

    override fun search(query: String, limit: Int): List<ValueCatalog.Entry> {
        val q = query.trim()
        if (q.isEmpty()) return browse(limit)
        return ids().asSequence().mapNotNull { id -> FuzzySearch.score(id, q)?.let { it to id } }
            .sortedByDescending { it.first }.take(limit).map { (_, id) -> ValueCatalog.Entry(id, id) }.toList()
    }

    override fun browse(limit: Int): List<ValueCatalog.Entry> = ids().take(limit).map { ValueCatalog.Entry(it, it) }
    override fun labelOf(value: Any?): String? = value?.toString()
    override fun icon(value: Any?): IconRef? = null
}

/** The links of every controller the client is watching — in practice the one the workbench is attached to. */
private class LinkCatalog : ValueCatalog {
    private fun names(): List<String> = listOf(bpm.nodes.ControllerHost.SELF) + ClientNet.controllers.values.flatMap { v -> v.links.map { it.name } }.distinct().sorted()

    override fun search(query: String, limit: Int): List<ValueCatalog.Entry> {
        val q = query.trim()
        val all = names()
        val hits = if (q.isEmpty()) all else all.filter { FuzzySearch.matches(it, q) }
        return hits.take(limit).map { ValueCatalog.Entry(it, it, note(it)) }
    }

    override fun browse(limit: Int): List<ValueCatalog.Entry> = search("", limit)
    override fun labelOf(value: Any?): String? = value?.toString()
    override fun icon(value: Any?): IconRef? = null

    private fun note(name: String): String =
        if (name == bpm.nodes.ControllerHost.SELF) "the controller's own buffer" else
            ClientNet.controllers.values.firstNotNullOfOrNull { v -> v.links.firstOrNull { it.name == name } }
                ?.let { "${it.pos.x}, ${it.pos.y}, ${it.pos.z}" } ?: ""
}

/** Colours for the mod's types on wires and pins; editors come from the catalogues above. */
object BpmTypeStyles : TypeStyles {
    private fun rgb(r: Int, g: Int, b: Int) = ImGui.colorConvertFloat4ToU32(r / 255f, g / 255f, b / 255f, 1f)

    private val styles: Map<String, TypeStyle> by lazy {
        mapOf(
            McTypes.LINK.name to TypeStyle(colour = rgb(0x4d, 0xff, 0xd8), editor = Editor.CATALOGUE, width = 110f),
            McTypes.ITEM.type.name to TypeStyle(colour = rgb(0x4f, 0xa3, 0xff), editor = Editor.CATALOGUE, width = 130f),
            McTypes.BLOCK.type.name to TypeStyle(colour = rgb(0x1e, 0x6f, 0xd9), editor = Editor.CATALOGUE, width = 130f),
            McTypes.FLUID.type.name to TypeStyle(colour = rgb(0x19, 0xc2, 0xa6), editor = Editor.CATALOGUE, width = 120f),
            McTypes.TAG.type.name to TypeStyle(colour = rgb(0xbf, 0xe0, 0xff), editor = Editor.CATALOGUE, width = 140f),
            McTypes.ITEM_STACK.type.name to TypeStyle(colour = rgb(0x8a, 0xb4, 0xf8)),
            McTypes.FLUID_STACK.type.name to TypeStyle(colour = rgb(0x5f, 0xd6, 0xc0)),
            McTypes.BLOCK_POS.type.name to TypeStyle(colour = rgb(0xd9, 0xb3, 0x6b)),
            McTypes.FILTER.type.name to TypeStyle(colour = rgb(0xf0, 0xa3, 0xd6)),
        )
    }

    override fun styleFor(type: TypeRef): TypeStyle? = styles[type.required().name]
}
