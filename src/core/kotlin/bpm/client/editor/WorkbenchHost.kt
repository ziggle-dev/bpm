package bpm.client.editor

import bpm.session.CommitStatus
import bpm.session.Role
import bpm.session.SessionReason
import dev.ziggle.vscript.model.GraphSource
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.runtime.EditorDoc
import java.util.UUID

/*
 * The workbench's view of the world, as small interfaces the Minecraft side implements over the network and a
 * test implements in memory. Nothing here knows about packets, players or blocks.
 */

/** A document as the library lists it. */
class DocRecord(val id: UUID, val name: String, val version: Int, val rawSize: Int, val hasErrors: Boolean, val holderName: String, val isLibrary: Boolean)

/** One validation issue as the server reported it. */
class CommitIssue(val error: Boolean, val message: String, val nodeId: Int)

/** The last word from the server about a commit. */
class CommitInfo(val status: CommitStatus, val version: Int, val message: String, val issues: List<CommitIssue>, val deployed: Int, val atMs: Long)

/** A document the workbench has open. Implementations mutate the fields as the server speaks. */
interface OpenDoc {
    val id: UUID
    val name: String
    val version: Int
    val role: Role
    val holderName: String
    val hasErrors: Boolean
    val lastReason: SessionReason

    /** Null until the server has pushed the document. */
    val editor: EditorDoc?

    /** Uncommitted local edits. The workbench sets it on edit; the host clears it on commit/push. */
    var dirty: Boolean

    /** A commit is in flight. */
    val committing: Boolean
    val lastCommit: CommitInfo?

    /** The server pushed a newer version over local edits; the workbench asks the user. */
    val hasConflict: Boolean

    val isHolder: Boolean get() = role == Role.HOLDER
    val canEdit: Boolean get() = isHolder && editor != null
}

interface DocumentStore {
    /** The importable library graphs. */
    val libraries: List<DocRecord>
    val libraryVersion: Int

    /** Resolves `import`s against the library graphs fetched so far. */
    val graphSource: GraphSource

    fun refresh()
    fun createLibrary(name: String)
    fun rename(id: UUID, name: String)
    fun delete(id: UUID)
    fun duplicate(id: UUID, name: String)

    /** Have the library graph [id] here for import resolution (fetched once per version). */
    fun ensureLibraryGraph(id: UUID)

    /** Opens a library for editing. */
    fun open(id: UUID, wantEdit: Boolean): OpenDoc

    /** The attached controller's own graph — asked for once, answered when the server has it. Null until then. */
    fun openControllerGraph(): OpenDoc?
    fun close(id: UUID)
    fun requestLease(id: UUID, steal: Boolean)
    fun releaseLease(id: UUID)

    /** True when a commit was sent. */
    fun commit(id: UUID, deploy: Boolean): Boolean

    /** Resolves a conflict with the server's version. */
    fun takeTheirs(id: UUID)

    /** Resolves a conflict by keeping the local copy (the next commit will conflict until it is saved as new). */
    fun keepMine(id: UUID)
}

/**
 * A link as the controller has it.
 *
 * @param player the uuid of the person a presence link points at, or null for a link to a block — the panel
 *   colours the row by it and shows their head instead of a block preview.
 */
class LinkView(
    val name: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val side: String?,
    val dimension: String,
    val player: String? = null,
) {
    val isPresence: Boolean get() = player != null
}

/** What the server last said about the controller the workbench is attached to. */
class ControllerInfo(
    val status: String,
    val docId: UUID?,
    val docName: String,
    val docVersion: Int,
    val runningVersion: Int,
    val enabled: Boolean,
    val debugBuild: Boolean,
    val lastError: String?,
    val fibers: Int,
    val jobs: Int,
    val transfers: Long,
    /** The controller's own buffer (`self`): item id and count per slot, empty id for an empty slot. */
    val buffer: List<Pair<String, Int>> = emptyList(),
    /** The controller's tanks, in order; an empty fluid id is an empty tank. */
    val tanks: List<TankView> = emptyList(),
    val energy: Int = 0,
    val energyCapacity: Int = 0,
    /** What the core tier allows — the denominators in the links panel's `12/16 · 1/2`. */
    val maxLinks: Int = 0,
    val maxPresence: Int = 0,
)

/** One of the controller's tanks. */
class TankView(val fluid: String, val amount: Int, val capacity: Int)

/** How the host paints and names fluids in the buffer panel. */
interface FluidLooks {
    /** The fluid's colour as ARGB, or null when the host does not know it. */
    fun colour(fluidId: String): Int?
    fun labelOf(fluidId: String): String?

    /** A second line for the tooltip — "160 experience points" — when the host has one. */
    fun describe(fluidId: String, amountMb: Int): String?
}

/** The controller a workbench was opened from, if any. */
interface ControllerControl {
    /** Where it is, for the title. */
    val label: String
    val info: ControllerInfo?
    val links: List<LinkView>

    fun bind(docId: UUID?)
    fun setFlags(enabled: Boolean, debugBuild: Boolean)
    fun start()
    fun stop()
    fun restart()
    fun renameLink(name: String, newName: String)
    fun removeLink(name: String)
}

/** Pictures of the blocks links point at, rendered by the host into a texture the panels can show. */
interface BlockPreviews {
    /** Asks for the block at [link] to be rendered soon (before the next frame). */
    fun want(link: LinkView)

    /** The rendered picture, once there is one. */
    fun region(link: LinkView): dev.ziggle.vscript.editor.host.IconRegion?

    /** The block's display name, when the position is loaded. */
    fun labelOf(link: LinkView): String?

    /**
     * The face of the player a presence link points at, once there is one — their skin's head, or the default
     * skin for someone the client has never seen. Null while nothing can be drawn.
     */
    fun head(playerId: String): dev.ziggle.vscript.editor.host.IconRegion? = null
}

/** Pictures of items by registry id, for the buffer and the pickers. */
interface ItemIcons {
    fun want(itemId: String)
    fun region(itemId: String): dev.ziggle.vscript.editor.host.IconRegion?
    fun labelOf(itemId: String): String?
}

/** Everything the workbench is given. */
class WorkbenchHost(
    val catalog: NodeCatalog,
    val store: DocumentStore,
    val prefs: Prefs,
    /** Null when the workbench was opened from a command rather than a controller. */
    val controller: ControllerControl? = null,
    /** The live view of the attached controller's program; null without a controller. */
    val run: RemoteRunView? = null,
    /** Block pictures for the links panel; null when the host cannot render them. */
    val previews: BlockPreviews? = null,
    /** Item pictures for the buffer view; null when the host cannot render them. */
    val icons: ItemIcons? = null,
    /** Fluid colours and names for the buffer view; null when the host has none. */
    val fluids: FluidLooks? = null,
    /** The player's name, for "you". */
    val playerName: String = "",
    val now: () -> Long = System::currentTimeMillis,
)
