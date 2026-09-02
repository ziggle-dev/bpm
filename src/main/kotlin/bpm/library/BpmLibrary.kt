package bpm.library

import bpm.Bpm
import com.google.gson.JsonParser
import dev.ziggle.vscript.model.Graph
import dev.ziggle.vscript.model.GraphDoc
import dev.ziggle.vscript.model.GraphSource
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.saveddata.SavedData
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import bpm.platform.intOr
import bpm.platform.longOr
import bpm.platform.stringOr
import bpm.platform.boolOr

/** One graph document in the world's library. The bytes are the gzip of the `GraphDoc` JSON. */
class DocumentRecord(
    override val id: UUID,
    var name: String,
    override var version: Int,
    var format: Int,
    var gz: ByteArray,
    override var sha256: String,
    var rawSize: Int,
    var owner: UUID?,
    val createdAt: Long,
    var updatedAt: Long,
    override var hasErrors: Boolean,
    /** A library graph: global, importable by controller graphs, listed in the editor. Otherwise a controller's own graph. */
    var isLibrary: Boolean = false,
) : bpm.library.DocumentInfo {
    fun save(): CompoundTag = CompoundTag().also { t ->
        bpm.platform.putUuid(t, "id", id)
        t.putString("name", name)
        t.putInt("version", version)
        t.putInt("format", format)
        t.putByteArray("gz", gz)
        t.putString("sha256", sha256)
        t.putInt("rawSize", rawSize)
        owner?.let { bpm.platform.putUuid(t, "owner", it) }
        t.putLong("createdAt", createdAt)
        t.putLong("updatedAt", updatedAt)
        t.putBoolean("hasErrors", hasErrors)
        t.putBoolean("library", isLibrary)
    }

    companion object {
        fun load(t: CompoundTag): DocumentRecord? {
            val docId = bpm.platform.uuidOrNull(t, "id") ?: return null
            return DocumentRecord(
                id = docId,
                name = t.stringOr("name", ""),
                version = t.intOr("version", 0),
                format = t.intOr("format", 0),
                gz = t.getByteArray("gz"),
                sha256 = t.stringOr("sha256", ""),
                rawSize = t.intOr("rawSize", 0),
                owner = bpm.platform.uuidOrNull(t, "owner"),
                createdAt = t.longOr("createdAt", 0L),
                updatedAt = t.longOr("updatedAt", 0L),
                hasErrors = t.boolOr("hasErrors", false),
                isLibrary = t.boolOr("library", false),
            )
        }
    }
}

/** gzip + sha-256 of document text; the same code packs commits on the wire. */

/**
 * The world's graph documents — one per world, stored with the overworld's saved data, so that blueprints
 * travel with the save and never with the client.
 *
 * Documents are addressed by UUID; names are unique too because `import` resolves by name. [store] is the
 * single write path: it bumps the document's [DocumentRecord.version] and the library's [libraryVersion],
 * which is what commits are checked against. Parsed graphs are cached per version and a document written by
 * an older `GraphDoc` format is re-encoded the first time it is read ([graph]), bumping its version like any
 * other edit; a document from a *newer* format is left alone and reported unreadable.
 */
class BpmLibrary : SavedData(), bpm.library.DocumentStore {
    private val docs = LinkedHashMap<UUID, DocumentRecord>()
    private val graphs = HashMap<UUID, Pair<Int, Graph>>()

    var libraryVersion: Int = 0
        private set

    val all: List<DocumentRecord> get() = docs.values.toList()

    override operator fun get(id: UUID): DocumentRecord? = docs[id]

    fun byName(name: String): DocumentRecord? = docs.values.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** The importable, globally listed graphs. */
    val libraries: List<DocumentRecord> get() = docs.values.filter { it.isLibrary }

    /** A document named [name] (made unique with a `-2` suffix if taken) holding [json]. */
    fun create(name: String, json: String, owner: UUID?, isLibrary: Boolean = false): DocumentRecord {
        val id = UUID.randomUUID()
        val now = System.currentTimeMillis()
        val record = DocumentRecord(
            id = id, name = uniqueName(name), version = 0, format = DocumentCodec.formatOf(json),
            gz = DocumentCodec.gzip(json), sha256 = DocumentCodec.sha256(json), rawSize = json.toByteArray(Charsets.UTF_8).size,
            owner = owner, createdAt = now, updatedAt = now, hasErrors = false, isLibrary = isLibrary,
        )
        docs[id] = record
        bump(record)
        return record
    }

    /** Replaces the text of [id]; returns null when there is no such document. */
    override fun store(id: UUID, json: String, hasErrors: Boolean): DocumentRecord? {
        val record = docs[id] ?: return null
        record.gz = DocumentCodec.gzip(json)
        record.sha256 = DocumentCodec.sha256(json)
        record.rawSize = json.toByteArray(Charsets.UTF_8).size
        record.format = DocumentCodec.formatOf(json)
        record.hasErrors = hasErrors
        record.updatedAt = System.currentTimeMillis()
        graphs.remove(id)
        bump(record)
        return record
    }

    /** Renames without touching the document's version: an open editor's next commit must not conflict on a name. */
    fun rename(id: UUID, name: String): Boolean {
        val record = docs[id] ?: return false
        if (record.name == name) return true
        record.name = uniqueName(name)
        record.updatedAt = System.currentTimeMillis()
        libraryVersion++
        setDirty()
        return true
    }

    fun delete(id: UUID): Boolean {
        val removed = docs.remove(id) ?: return false
        graphs.remove(id)
        libraryVersion++
        setDirty()
        Bpm.LOGGER.info("bpm library: deleted '{}' ({})", removed.name, id)
        return true
    }

    /** The document's JSON text. */
    fun text(id: UUID): String? = docs[id]?.let { DocumentCodec.gunzip(it.gz) }

    /** The parsed graph, cached per version; null when the document is missing or unreadable. */
    fun graph(id: UUID): Graph? {
        val record = docs[id] ?: return null
        graphs[id]?.let { (v, g) -> if (v == record.version) return g }
        if (record.format > GraphDoc.FORMAT) {
            Bpm.LOGGER.warn("bpm library: '{}' is format {} but this build reads up to {}", record.name, record.format, GraphDoc.FORMAT)
            return null
        }
        val graph = runCatching { GraphDoc.fromJson(DocumentCodec.gunzip(record.gz)) }
            .onFailure { Bpm.LOGGER.warn("bpm library: '{}' could not be parsed: {}", record.name, it.toString()) }
            .getOrNull() ?: return null
        if (record.format < GraphDoc.FORMAT) {
            // Re-encode at the current format once, as an edit: the version moves so that open editors notice.
            Bpm.LOGGER.info("bpm library: migrating '{}' from format {} to {}", record.name, record.format, GraphDoc.FORMAT)
            store(id, GraphDoc.toJson(graph), record.hasErrors)
        }
        graphs[id] = record.version to graph
        return graph
    }

    /** Every loaded controller graph that imports [libraryId] (by id or by name). */
    fun importers(libraryId: UUID): List<UUID> {
        val lib = docs[libraryId] ?: return emptyList()
        return docs.values.filter { r -> r.id != libraryId }.filter { r ->
            graph(r.id)?.imports?.any { it.docId == libraryId.toString() || it.ref.equals(lib.name, ignoreCase = true) } == true
        }.map { it.id }
    }

    /** `import` resolution for validators and compilers: by document id first, then by name. */
    override fun graphSource(): GraphSource = GraphSource { imp ->
        val byId = imp.docId?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let(::graph)
        byId ?: byName(imp.ref)?.let { graph(it.id) }
    }

    private fun uniqueName(wanted: String): String {
        val base = wanted.trim().ifEmpty { "untitled" }
        if (byName(base) == null) return base
        var n = 2
        while (byName("$base-$n") != null) n++
        return "$base-$n"
    }

    private fun bump(record: DocumentRecord) {
        record.version++
        libraryVersion++
        setDirty()
    }

    override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag {
        tag.putInt("libraryVersion", libraryVersion)
        tag.put("docs", ListTag().also { list -> docs.values.forEach { list.add(it.save()) } })
        return tag
    }

    companion object {
        const val NAME = "bpm_library"

        fun load(tag: CompoundTag, @Suppress("UNUSED_PARAMETER") registries: HolderLookup.Provider): BpmLibrary {
            val lib = BpmLibrary()
            lib.libraryVersion = tag.intOr("libraryVersion", 0)
            val list = tag.getList("docs", Tag.TAG_COMPOUND.toInt())
            for (i in 0 until list.size) DocumentRecord.load(list.getCompound(i))?.let { lib.docs[it.id] = it }
            return lib
        }

        private val FACTORY = Factory(::BpmLibrary, ::load, null)

        /** The library of the server's world, created on first use. Server thread only. */
        fun get(server: MinecraftServer): BpmLibrary = server.overworld().dataStorage.computeIfAbsent(FACTORY, NAME)
    }
}
