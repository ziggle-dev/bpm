package bpm.net

import bpm.Bpm
import bpm.net.chunk.Chunk
import bpm.session.CommitStatus
import bpm.session.Role
import bpm.session.SessionReason
import bpm.world.ControllerStatus
import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import java.util.UUID

/*
 * Every packet of the mod. Codecs are hand-written over `FriendlyByteBuf` so one shape serves the play and
 * the configuration phase, and decode-time caps live in one place. Anything that can be large travels as
 * [ChunkPayload] pieces of a [BigMessages] message, never as a payload of its own.
 */

private fun id(name: String): ResourceLocation = ResourceLocation.fromNamespaceAndPath(Bpm.ID, name)

/** "No document" on the wire. */
val NIL_UUID: UUID = UUID(0, 0)

private inline fun <T : CustomPacketPayload> codec(
    crossinline write: (FriendlyByteBuf, T) -> Unit,
    crossinline read: (FriendlyByteBuf) -> T,
): StreamCodec<FriendlyByteBuf, T> = StreamCodec.of({ buf, v -> write(buf, v) }, { buf -> read(buf) })

private fun FriendlyByteBuf.writeStrings(list: List<String>) {
    writeVarInt(list.size)
    list.forEach { writeUtf(it) }
}

private fun FriendlyByteBuf.readStrings(max: Int = 256): List<String> {
    val n = readVarInt()
    require(n in 0..max) { "list of $n strings" }
    return List(n) { readUtf() }
}

private fun FriendlyByteBuf.writeOptUuid(id: UUID?) {
    writeBoolean(id != null)
    if (id != null) writeUUID(id)
}

private fun FriendlyByteBuf.readOptUuid(): UUID? = if (readBoolean()) readUUID() else null

private fun FriendlyByteBuf.writeOptString(s: String?) {
    writeBoolean(s != null)
    if (s != null) writeUtf(s)
}

private fun FriendlyByteBuf.readOptString(): String? = if (readBoolean()) readUtf() else null

// ---- configuration ----------------------------------------------------------------------------------------

/** Server → client during configuration: the catalogue the server runs. */
class CatalogHelloPayload(val hash: String, val packs: List<String>) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<CatalogHelloPayload>(id("catalog_hello"))
        val CODEC = codec<CatalogHelloPayload>({ b, v -> b.writeUtf(v.hash); b.writeStrings(v.packs) }, { b -> CatalogHelloPayload(b.readUtf(), b.readStrings()) })
    }
}

/** Client → server: the catalogue the client built. */
class CatalogAckPayload(val hash: String, val packs: List<String>) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<CatalogAckPayload>(id("catalog_ack"))
        val CODEC = codec<CatalogAckPayload>({ b, v -> b.writeUtf(v.hash); b.writeStrings(v.packs) }, { b -> CatalogAckPayload(b.readUtf(), b.readStrings()) })
    }
}

// ---- chunks -------------------------------------------------------------------------------------------------

/** One piece of a big message, either direction. */
class ChunkPayload(val chunk: Chunk) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ChunkPayload>(id("chunk"))
        val CODEC = codec<ChunkPayload>(
            { b, v ->
                val c = v.chunk
                b.writeVarInt(c.messageId); b.writeUtf(c.inner, 64); b.writeVarInt(c.index); b.writeVarInt(c.count)
                b.writeVarInt(c.totalBytes); b.writeBoolean(c.gz); b.writeByteArray(c.body)
            },
            { b ->
                ChunkPayload(Chunk(b.readVarInt(), b.readUtf(64), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readBoolean(), b.readByteArray(Chunk_MAX_BODY)))
            },
        )
        private const val Chunk_MAX_BODY = 24 * 1024 + 16
    }
}

// ---- library --------------------------------------------------------------------------------------------------

/** Client → server: send me the library; also subscribes to [LibraryChangedPayload]. */
class LibraryListRequestPayload : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<LibraryListRequestPayload>(id("library_list_request"))
        val CODEC = codec<LibraryListRequestPayload>({ _, _ -> }, { _ -> LibraryListRequestPayload() })
    }
}

/** Server → client: the library moved; [docId] names the document that changed (null = several). */
class LibraryChangedPayload(val libraryVersion: Int, val docId: UUID?, val deleted: Boolean) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<LibraryChangedPayload>(id("library_changed"))
        val CODEC = codec<LibraryChangedPayload>(
            { b, v -> b.writeVarInt(v.libraryVersion); b.writeOptUuid(v.docId); b.writeBoolean(v.deleted) },
            { b -> LibraryChangedPayload(b.readVarInt(), b.readOptUuid(), b.readBoolean()) },
        )
    }
}

class DocRenamePayload(val docId: UUID, val name: String) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<DocRenamePayload>(id("doc_rename"))
        val CODEC = codec<DocRenamePayload>({ b, v -> b.writeUUID(v.docId); b.writeUtf(v.name, 64) }, { b -> DocRenamePayload(b.readUUID(), b.readUtf(64)) })
    }
}

class DocDeletePayload(val docId: UUID) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<DocDeletePayload>(id("doc_delete"))
        val CODEC = codec<DocDeletePayload>({ b, v -> b.writeUUID(v.docId) }, { b -> DocDeletePayload(b.readUUID()) })
    }
}

class DocDuplicatePayload(val docId: UUID, val name: String) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<DocDuplicatePayload>(id("doc_duplicate"))
        val CODEC = codec<DocDuplicatePayload>({ b, v -> b.writeUUID(v.docId); b.writeUtf(v.name, 64) }, { b -> DocDuplicatePayload(b.readUUID(), b.readUtf(64)) })
    }
}

/** Client → server: send me this document (as a viewer unless I hold its lease). */
class DocFetchPayload(val docId: UUID) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<DocFetchPayload>(id("doc_fetch"))
        val CODEC = codec<DocFetchPayload>({ b, v -> b.writeUUID(v.docId) }, { b -> DocFetchPayload(b.readUUID()) })
    }
}

// ---- sessions -------------------------------------------------------------------------------------------------

/**
 * Client → server: open a document in the editor, optionally wanting the lease and watching a controller.
 * With [docId] = NIL and a [controller], opens that controller's own graph, creating it on first use.
 */
class EditorOpenPayload(val docId: UUID, val wantEdit: Boolean, val controller: BlockPos?) : CustomPacketPayload {
    override fun type() = TYPE

    val isControllerGraph: Boolean get() = docId == NIL_UUID && controller != null

    companion object {
        val TYPE = CustomPacketPayload.Type<EditorOpenPayload>(id("editor_open"))
        val CODEC = codec<EditorOpenPayload>(
            { b, v -> b.writeUUID(v.docId); b.writeBoolean(v.wantEdit); b.writeBoolean(v.controller != null); v.controller?.let { b.writeBlockPos(it) } },
            { b -> EditorOpenPayload(b.readUUID(), b.readBoolean(), if (b.readBoolean()) b.readBlockPos() else null) },
        )
    }
}

class EditorClosePayload(val docId: UUID) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<EditorClosePayload>(id("editor_close"))
        val CODEC = codec<EditorClosePayload>({ b, v -> b.writeUUID(v.docId) }, { b -> EditorClosePayload(b.readUUID()) })
    }
}

class SessionHeartbeatPayload(val docId: UUID) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<SessionHeartbeatPayload>(id("session_heartbeat"))
        val CODEC = codec<SessionHeartbeatPayload>({ b, v -> b.writeUUID(v.docId) }, { b -> SessionHeartbeatPayload(b.readUUID()) })
    }
}

class LeaseRequestPayload(val docId: UUID, val steal: Boolean) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<LeaseRequestPayload>(id("lease_request"))
        val CODEC = codec<LeaseRequestPayload>({ b, v -> b.writeUUID(v.docId); b.writeBoolean(v.steal) }, { b -> LeaseRequestPayload(b.readUUID(), b.readBoolean()) })
    }
}

class LeaseReleasePayload(val docId: UUID) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<LeaseReleasePayload>(id("lease_release"))
        val CODEC = codec<LeaseReleasePayload>({ b, v -> b.writeUUID(v.docId) }, { b -> LeaseReleasePayload(b.readUUID()) })
    }
}

/** Server → client: your role on a document and who holds it now. */
class SessionStatePayload(val docId: UUID, val role: Role, val holderName: String, val version: Int, val reason: SessionReason) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<SessionStatePayload>(id("session_state"))
        val CODEC = codec<SessionStatePayload>(
            { b, v -> b.writeUUID(v.docId); b.writeEnum(v.role); b.writeUtf(v.holderName, 64); b.writeVarInt(v.version); b.writeEnum(v.reason) },
            { b -> SessionStatePayload(b.readUUID(), b.readEnum(Role::class.java), b.readUtf(64), b.readVarInt(), b.readEnum(SessionReason::class.java)) },
        )
    }
}

// ---- commits ----------------------------------------------------------------------------------------------------

class IssueDto(val error: Boolean, val message: String, val nodeId: Int) {
    fun write(b: FriendlyByteBuf) {
        b.writeBoolean(error); b.writeUtf(message, 512); b.writeVarInt(nodeId)
    }

    companion object {
        fun read(b: FriendlyByteBuf) = IssueDto(b.readBoolean(), b.readUtf(512), b.readVarInt())
        const val MAX = 64
    }
}

/** Server → the committer: what happened to the commit named [commitId]. */
class DocCommitResultPayload(
    val docId: UUID,
    val commitId: Int,
    val status: CommitStatus,
    val version: Int,
    val sha256: String,
    val message: String,
    val issues: List<IssueDto>,
    val deployed: Int,
) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<DocCommitResultPayload>(id("doc_commit_result"))
        val CODEC = codec<DocCommitResultPayload>(
            { b, v ->
                b.writeUUID(v.docId); b.writeVarInt(v.commitId); b.writeEnum(v.status); b.writeVarInt(v.version); b.writeUtf(v.sha256, 64)
                b.writeUtf(v.message, 512); b.writeVarInt(v.issues.size); v.issues.forEach { it.write(b) }; b.writeVarInt(v.deployed)
            },
            { b ->
                val docId = b.readUUID(); val commitId = b.readVarInt(); val status = b.readEnum(CommitStatus::class.java)
                val version = b.readVarInt(); val sha = b.readUtf(64); val message = b.readUtf(512)
                val n = b.readVarInt(); require(n in 0..IssueDto.MAX) { "$n issues" }
                val issues = List(n) { IssueDto.read(b) }
                DocCommitResultPayload(docId, commitId, status, version, sha, message, issues, b.readVarInt())
            },
        )
    }
}

// ---- controllers -------------------------------------------------------------------------------------------------

enum class RunAction { START, STOP, RESTART, PAUSE, RESUME, STEP_OVER, STEP_INTO, STEP_OUT, STEP_DATA, SLEEP, WAKE }

enum class LinkOp { RENAME, REMOVE }

class ControllerBindPayload(val pos: BlockPos, val docId: UUID?) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ControllerBindPayload>(id("controller_bind"))
        val CODEC = codec<ControllerBindPayload>({ b, v -> b.writeBlockPos(v.pos); b.writeOptUuid(v.docId) }, { b -> ControllerBindPayload(b.readBlockPos(), b.readOptUuid()) })
    }
}

class ControllerFlagsPayload(val pos: BlockPos, val enabled: Boolean, val debugBuild: Boolean) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ControllerFlagsPayload>(id("controller_flags"))
        val CODEC = codec<ControllerFlagsPayload>(
            { b, v -> b.writeBlockPos(v.pos); b.writeBoolean(v.enabled); b.writeBoolean(v.debugBuild) },
            { b -> ControllerFlagsPayload(b.readBlockPos(), b.readBoolean(), b.readBoolean()) },
        )
    }
}

class RunControlPayload(val pos: BlockPos, val action: RunAction) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<RunControlPayload>(id("run_control"))
        val CODEC = codec<RunControlPayload>({ b, v -> b.writeBlockPos(v.pos); b.writeEnum(v.action) }, { b -> RunControlPayload(b.readBlockPos(), b.readEnum(RunAction::class.java)) })
    }
}

class LinkEditPayload(val pos: BlockPos, val op: LinkOp, val name: String, val newName: String) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<LinkEditPayload>(id("link_edit"))
        val CODEC = codec<LinkEditPayload>(
            { b, v -> b.writeBlockPos(v.pos); b.writeEnum(v.op); b.writeUtf(v.name, 64); b.writeUtf(v.newName, 64) },
            { b -> LinkEditPayload(b.readBlockPos(), b.readEnum(LinkOp::class.java), b.readUtf(64), b.readUtf(64)) },
        )
    }
}

/** Client → server: sneak + attack on air with the linker in [hand] — fire the tracking pulse. */
class LinkerTrackPayload(val hand: net.minecraft.world.InteractionHand) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<LinkerTrackPayload>(id("linker_track"))
        val CODEC = codec<LinkerTrackPayload>(
            { b, v -> b.writeEnum(v.hand) },
            { b -> LinkerTrackPayload(b.readEnum(net.minecraft.world.InteractionHand::class.java)) },
        )
    }
}

/** Client → server: keep me posted on this controller (status every second, links on change) — or stop. */
class ControllerWatchPayload(val pos: BlockPos, val watch: Boolean) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ControllerWatchPayload>(id("controller_watch"))
        val CODEC = codec<ControllerWatchPayload>({ b, v -> b.writeBlockPos(v.pos); b.writeBoolean(v.watch) }, { b -> ControllerWatchPayload(b.readBlockPos(), b.readBoolean()) })
    }
}

class ControllerStatusPayload(
    val pos: BlockPos,
    val status: ControllerStatus,
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
    /** The buffer's slots as (item id, count); an empty id is an empty slot. */
    val buffer: List<Pair<String, Int>> = emptyList(),
    /** The tanks, in order; an empty fluid id is an empty tank. */
    val tanks: List<TankDto> = emptyList(),
    val energy: Int = 0,
    val energyCapacity: Int = 0,
) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ControllerStatusPayload>(id("controller_status"))
        val CODEC = codec<ControllerStatusPayload>(
            { b, v ->
                b.writeBlockPos(v.pos); b.writeEnum(v.status); b.writeOptUuid(v.docId); b.writeUtf(v.docName, 64); b.writeVarInt(v.docVersion)
                b.writeVarInt(v.runningVersion); b.writeBoolean(v.enabled); b.writeBoolean(v.debugBuild); b.writeOptString(v.lastError)
                b.writeVarInt(v.fibers); b.writeVarInt(v.jobs); b.writeVarLong(v.transfers)
                b.writeVarInt(v.buffer.size); v.buffer.forEach { (id, n) -> b.writeUtf(id, 128); b.writeVarInt(n) }
                b.writeVarInt(v.tanks.size); v.tanks.forEach { t -> b.writeUtf(t.fluid, 128); b.writeVarInt(t.amount); b.writeVarInt(t.capacity) }
                b.writeVarInt(v.energy); b.writeVarInt(v.energyCapacity)
            },
            { b ->
                val pos = b.readBlockPos(); val status = b.readEnum(ControllerStatus::class.java); val doc = b.readOptUuid(); val name = b.readUtf(64); val dv = b.readVarInt()
                val rv = b.readVarInt(); val en = b.readBoolean(); val dbg = b.readBoolean(); val err = b.readOptString(); val fibers = b.readVarInt(); val jobs = b.readVarInt(); val tr = b.readVarLong()
                val n = b.readVarInt(); require(n in 0..64) { "$n buffer slots" }
                val buffer = List(n) { b.readUtf(128) to b.readVarInt() }
                val m = b.readVarInt(); require(m in 0..16) { "$m tanks" }
                val tanks = List(m) { TankDto(b.readUtf(128), b.readVarInt(), b.readVarInt()) }
                val energy = b.readVarInt(); val energyCap = b.readVarInt()
                ControllerStatusPayload(pos, status, doc, name, dv, rv, en, dbg, err, fibers, jobs, tr, buffer, tanks, energy, energyCap)
            },
        )
    }
}

/** One tank of a controller as the status packet carries it. */
class TankDto(val fluid: String, val amount: Int, val capacity: Int)

class LinkDto(val name: String, val pos: BlockPos, val side: Int, val dimension: String) {
    fun write(b: FriendlyByteBuf) {
        b.writeUtf(name, 64); b.writeBlockPos(pos); b.writeByte(side); b.writeUtf(dimension, 128)
    }

    companion object {
        fun read(b: FriendlyByteBuf) = LinkDto(b.readUtf(64), b.readBlockPos(), b.readByte().toInt(), b.readUtf(128))
        const val MAX = 512
    }
}

/** Server → client: a controller's whole link table. */
class LinkTableSyncPayload(val pos: BlockPos, val links: List<LinkDto>) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<LinkTableSyncPayload>(id("link_table"))
        val CODEC = codec<LinkTableSyncPayload>(
            { b, v -> b.writeBlockPos(v.pos); b.writeVarInt(v.links.size); v.links.forEach { it.write(b) } },
            { b ->
                val pos = b.readBlockPos()
                val n = b.readVarInt(); require(n in 0..LinkDto.MAX) { "$n links" }
                LinkTableSyncPayload(pos, List(n) { LinkDto.read(b) })
            },
        )
    }
}

/**
 * Server → client, to a document's holder: a link was renamed and the stored document rewritten to match —
 * make the same edit to the open copy and rebase it on [version] (so uncommitted work survives).
 */
class LinkRenamedPayload(val docId: UUID, val oldName: String, val newName: String, val version: Int, val sha256: String) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<LinkRenamedPayload>(id("link_renamed"))
        val CODEC = codec<LinkRenamedPayload>(
            { b, v -> b.writeUUID(v.docId); b.writeUtf(v.oldName, 64); b.writeUtf(v.newName, 64); b.writeVarInt(v.version); b.writeUtf(v.sha256, 128) },
            { b -> LinkRenamedPayload(b.readUUID(), b.readUtf(64), b.readUtf(64), b.readVarInt(), b.readUtf(128)) },
        )
    }
}

/** What an in-world effect packet says: a stream opens, carries something, closes. */
enum class EffectOp { BEGIN, PULSE, END }

/** What an effect shows: things moving (a stream between two rifts), or a job at a block (one rift with the tool at work). */
/**
 * [DROP] is [ITEMS] with the arrival left off: the client draws the outgoing leg only, because the thing
 * that appears at the far end is a real `ItemEntity` the server spawns out of the target rift. Appended
 * rather than inserted — the payload writes an enum by ordinal.
 */
enum class EffectKind { ITEMS, FLUID, ENERGY, XP, MINE, USE, STRIKE, DROP }

/**
 * Server → client: one step of an in-world effect. For a transfer, [origin] / [target] are the two ends
 * (a face −1 means "the controller itself" when the position is the controller's, else "no face");
 * [amount] is what moved since the last step and [item] a registry id to draw (an item, a fluid). For an
 * action both ends are the block worked on and [item] is the tool.
 */
class EffectPayload(
    val controller: BlockPos,
    val stream: Int,
    val op: EffectOp,
    val kind: EffectKind,
    val origin: BlockPos,
    val originFace: Int,
    val target: BlockPos,
    val targetFace: Int,
    val amount: Int,
    val item: String,
) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<EffectPayload>(id("effect"))
        val CODEC = codec<EffectPayload>(
            { b, v ->
                b.writeBlockPos(v.controller); b.writeVarInt(v.stream); b.writeEnum(v.op); b.writeEnum(v.kind)
                b.writeBlockPos(v.origin); b.writeByte(v.originFace); b.writeBlockPos(v.target); b.writeByte(v.targetFace)
                b.writeVarInt(v.amount); b.writeUtf(v.item, 128)
            },
            { b ->
                EffectPayload(
                    b.readBlockPos(), b.readVarInt(), b.readEnum(EffectOp::class.java), b.readEnum(EffectKind::class.java),
                    b.readBlockPos(), b.readByte().toInt(), b.readBlockPos(), b.readByte().toInt(),
                    b.readVarInt(), b.readUtf(128),
                )
            },
        )
    }
}

// ---- big messages (travel as chunks) ----------------------------------------------------------------------------------

class LibraryRecordDto(val id: UUID, val name: String, val version: Int, val rawSize: Int, val hasErrors: Boolean, val updatedAt: Long, val holderName: String, val isLibrary: Boolean) {
    fun write(b: FriendlyByteBuf) {
        b.writeUUID(id); b.writeUtf(name, 64); b.writeVarInt(version); b.writeVarInt(rawSize); b.writeBoolean(hasErrors); b.writeVarLong(updatedAt); b.writeUtf(holderName, 64); b.writeBoolean(isLibrary)
    }

    companion object {
        fun read(b: FriendlyByteBuf) = LibraryRecordDto(b.readUUID(), b.readUtf(64), b.readVarInt(), b.readVarInt(), b.readBoolean(), b.readVarLong(), b.readUtf(64), b.readBoolean())
    }
}

class LibraryListMsg(val libraryVersion: Int, val records: List<LibraryRecordDto>) {
    fun write(b: FriendlyByteBuf) {
        b.writeVarInt(libraryVersion); b.writeVarInt(records.size); records.forEach { it.write(b) }
    }

    companion object {
        fun read(b: FriendlyByteBuf): LibraryListMsg {
            val v = b.readVarInt()
            val n = b.readVarInt(); require(n in 0..10_000) { "$n records" }
            return LibraryListMsg(v, List(n) { LibraryRecordDto.read(b) })
        }
    }
}

/** Server → client: a whole document, with the receiver's role on it. */
class DocPushMsg(val docId: UUID, val version: Int, val name: String, val sha256: String, val json: String, val role: Role, val holderName: String, val hasErrors: Boolean) {
    fun write(b: FriendlyByteBuf) {
        b.writeUUID(docId); b.writeVarInt(version); b.writeUtf(name, 64); b.writeUtf(sha256, 64)
        b.writeByteArray(json.toByteArray(Charsets.UTF_8)); b.writeEnum(role); b.writeUtf(holderName, 64); b.writeBoolean(hasErrors)
    }

    companion object {
        fun read(b: FriendlyByteBuf) = DocPushMsg(
            b.readUUID(), b.readVarInt(), b.readUtf(64), b.readUtf(64), String(b.readByteArray(), Charsets.UTF_8),
            b.readEnum(Role::class.java), b.readUtf(64), b.readBoolean(),
        )
    }
}

/** Client → server: make a document from this text — a library when [library]. */
class DocCreateMsg(val name: String, val json: String, val library: Boolean) {
    fun write(b: FriendlyByteBuf) {
        b.writeUtf(name, 64); b.writeByteArray(json.toByteArray(Charsets.UTF_8)); b.writeBoolean(library)
    }

    companion object {
        fun read(b: FriendlyByteBuf) = DocCreateMsg(b.readUtf(64), String(b.readByteArray(), Charsets.UTF_8), b.readBoolean())
    }
}

/** Client → server: the holder's whole document, to become the next version. */
class DocCommitMsg(val docId: UUID, val baseVersion: Int, val commitId: Int, val deploy: Boolean, val sha256: String, val json: String) {
    fun write(b: FriendlyByteBuf) {
        b.writeUUID(docId); b.writeVarInt(baseVersion); b.writeVarInt(commitId); b.writeBoolean(deploy); b.writeUtf(sha256, 64)
        b.writeByteArray(json.toByteArray(Charsets.UTF_8))
    }

    companion object {
        fun read(b: FriendlyByteBuf) = DocCommitMsg(b.readUUID(), b.readVarInt(), b.readVarInt(), b.readBoolean(), b.readUtf(64), String(b.readByteArray(), Charsets.UTF_8))
    }
}

/** Names and byte encoding of the messages that travel in chunks. */
object BigMessages {
    const val LIBRARY_LIST = "library_list"
    const val DOC_PUSH = "doc_push"
    const val DOC_CREATE = "doc_create"
    const val DOC_COMMIT = "doc_commit"

    fun encode(write: (FriendlyByteBuf) -> Unit): ByteArray {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        try {
            write(buf)
            return ByteBufUtil.getBytes(buf)
        } finally {
            buf.release()
        }
    }

    fun <T> decode(bytes: ByteArray, read: (FriendlyByteBuf) -> T): T {
        val buf = FriendlyByteBuf(Unpooled.wrappedBuffer(bytes))
        try {
            return read(buf)
        } finally {
            buf.release()
        }
    }
}
