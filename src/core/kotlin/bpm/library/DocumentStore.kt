package bpm.library

import dev.ziggle.vscript.model.GraphSource
import java.util.UUID

/**
 * What the commit pipeline needs to know about a stored document.
 *
 * A view rather than the record itself: `DocumentRecord` carries its own NBT, which belongs to the
 * world and cannot be seen from here. These five fields are all the pipeline reads, and stating them
 * is what lets it be tested against a map instead of a save file.
 */
interface DocumentInfo {
    val id: UUID
    val version: Int
    val sha256: String
    val hasErrors: Boolean
}

/**
 * The three things the commit pipeline needs from the library.
 *
 * The library is a `SavedData` and belongs to the world; the pipeline is decision logic and does not
 * care where a document is kept.
 */
interface DocumentStore {
    operator fun get(id: UUID): DocumentInfo?
    fun store(id: UUID, json: String, hasErrors: Boolean = false): DocumentInfo?
    fun graphSource(): GraphSource
}
