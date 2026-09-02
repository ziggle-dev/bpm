package bpm.net

import bpm.platform.idOf

import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import bpm.platform.net.BpmPayload
import bpm.platform.net.PayloadCodec
import bpm.platform.net.PayloadType
import bpm.platform.net.payloadCodec
import bpm.platform.net.payloadType
import bpm.platform.ResourceLocation

/*
 * The run view: what a controller's VM is doing, streamed to the players watching it in the editor. Deltas
 * every tick while something changes, a snapshot when a fiber pauses, logs as they happen; every verb of the
 * debugger goes the other way as a `RunControlPayload` action.
 */

private fun rid(name: String): ResourceLocation = idOf(bpm.Bpm.ID, name)

private fun <T : BpmPayload> rcodec(
    write: (FriendlyByteBuf, T) -> Unit,
    read: (FriendlyByteBuf) -> T,
): PayloadCodec<T> = payloadCodec(write, read)

private fun FriendlyByteBuf.writeInts(ids: IntArray) {
    writeVarInt(ids.size)
    ids.forEach { writeVarInt(it) }
}

private fun FriendlyByteBuf.readInts(max: Int = 4096): IntArray {
    val n = readVarInt()
    require(n in 0..max) { "$n ids" }
    return IntArray(n) { readVarInt() }
}

private fun FriendlyByteBuf.writeOpt(s: String?, max: Int = 512) {
    writeBoolean(s != null)
    if (s != null) writeUtf(s, max)
}

private fun FriendlyByteBuf.readOpt(max: Int = 512): String? = if (readBoolean()) readUtf(max) else null

/** Client → server: stream this controller's run view to me — or stop. */
class RunSubscribePayload(val pos: BlockPos, val on: Boolean) : BpmPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = payloadType<RunSubscribePayload>(rid("run_subscribe"))
        val CODEC = rcodec<RunSubscribePayload>({ b, v -> b.writeBlockPos(v.pos); b.writeBoolean(v.on) }, { b -> RunSubscribePayload(b.readBlockPos(), b.readBoolean()) })
    }
}

/** One fiber as the debugger shows it. */
class ContextDto(val id: Int, val name: String, val entryNodeId: Int, val state: Int, val pauseReason: Int, val nodeId: Int, val error: String?, val sleepingForMs: Long) {
    fun write(b: FriendlyByteBuf) {
        b.writeVarInt(id); b.writeUtf(name, 64); b.writeVarInt(entryNodeId); b.writeByte(state); b.writeByte(pauseReason); b.writeVarInt(nodeId); b.writeOpt(error); b.writeVarLong(sleepingForMs)
    }

    companion object {
        fun read(b: FriendlyByteBuf) = ContextDto(b.readVarInt(), b.readUtf(64), b.readVarInt(), b.readByte().toInt(), b.readByte().toInt(), b.readVarInt(), b.readOpt(), b.readVarLong())
        const val MAX = 256
    }
}

/**
 * Server → client, per tick while something changed: which nodes and links fired since the last frame
 * (as adds and removes, or the whole set when [full]), the phase, the fibers when they changed.
 */
class RunFramePayload(
    val pos: BlockPos,
    val full: Boolean,
    val phase: String,
    val paused: Boolean,
    val stopToken: Long,
    val addNodes: IntArray,
    val removeNodes: IntArray,
    val addLinks: IntArray,
    val removeLinks: IntArray,
    val contexts: List<ContextDto>?,
    val error: String?,
) : BpmPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = payloadType<RunFramePayload>(rid("run_frame"))
        val CODEC = rcodec<RunFramePayload>(
            { b, v ->
                b.writeBlockPos(v.pos); b.writeBoolean(v.full); b.writeUtf(v.phase, 32); b.writeBoolean(v.paused); b.writeVarLong(v.stopToken)
                b.writeInts(v.addNodes); b.writeInts(v.removeNodes); b.writeInts(v.addLinks); b.writeInts(v.removeLinks)
                b.writeBoolean(v.contexts != null)
                v.contexts?.let { list -> b.writeVarInt(list.size); list.forEach { it.write(b) } }
                b.writeOpt(v.error)
            },
            { b ->
                val pos = b.readBlockPos(); val full = b.readBoolean(); val phase = b.readUtf(32); val paused = b.readBoolean(); val token = b.readVarLong()
                val an = b.readInts(); val rn = b.readInts(); val al = b.readInts(); val rl = b.readInts()
                val contexts = if (b.readBoolean()) { val n = b.readVarInt(); require(n in 0..ContextDto.MAX); List(n) { ContextDto.read(b) } } else null
                RunFramePayload(pos, full, phase, paused, token, an, rn, al, rl, contexts, b.readOpt())
            },
        )
    }
}

class LogDto(val level: Int, val nodeId: Int, val message: String, val repeats: Int) {
    fun write(b: FriendlyByteBuf) {
        b.writeByte(level); b.writeVarInt(nodeId); b.writeUtf(message, 1024); b.writeVarInt(repeats)
    }

    companion object {
        fun read(b: FriendlyByteBuf) = LogDto(b.readByte().toInt(), b.readVarInt(), b.readUtf(1024), b.readVarInt())
        const val MAX = 64
    }
}

/** Server → client: log records since the last batch (at most [LogDto.MAX] per tick). */
class RunLogPayload(val pos: BlockPos, val records: List<LogDto>, val cleared: Boolean) : BpmPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = payloadType<RunLogPayload>(rid("run_log"))
        val CODEC = rcodec<RunLogPayload>(
            { b, v -> b.writeBlockPos(v.pos); b.writeBoolean(v.cleared); b.writeVarInt(v.records.size); v.records.forEach { it.write(b) } },
            { b -> val pos = b.readBlockPos(); val cleared = b.readBoolean(); val n = b.readVarInt(); require(n in 0..LogDto.MAX); RunLogPayload(pos, List(n) { LogDto.read(b) }, cleared) },
        )
    }
}

class FrameDto(val index: Int, val chunkName: String, val pc: Int, val nodeId: Int, val activation: Int) {
    fun write(b: FriendlyByteBuf) {
        b.writeVarInt(index); b.writeUtf(chunkName, 128); b.writeVarInt(pc); b.writeVarInt(nodeId); b.writeVarInt(activation)
    }

    companion object {
        fun read(b: FriendlyByteBuf) = FrameDto(b.readVarInt(), b.readUtf(128), b.readVarInt(), b.readVarInt(), b.readVarInt())
    }
}

class VarDto(val name: String, val display: String, val typeName: String, val nodeId: Int) {
    fun write(b: FriendlyByteBuf) {
        b.writeUtf(name, 128); b.writeUtf(display, 512); b.writeUtf(typeName, 64); b.writeVarInt(nodeId)
    }

    companion object {
        fun read(b: FriendlyByteBuf) = VarDto(b.readUtf(128), b.readUtf(512), b.readUtf(64), b.readVarInt())
    }
}

class ScopeDto(val name: String, val vars: List<VarDto>) {
    fun write(b: FriendlyByteBuf) {
        b.writeUtf(name, 64); b.writeVarInt(vars.size); vars.forEach { it.write(b) }
    }

    companion object {
        fun read(b: FriendlyByteBuf): ScopeDto {
            val name = b.readUtf(64)
            val n = b.readVarInt(); require(n in 0..512)
            return ScopeDto(name, List(n) { VarDto.read(b) })
        }
    }
}

class PinValueDto(val nodeId: Int, val pin: String, val display: String) {
    fun write(b: FriendlyByteBuf) {
        b.writeVarInt(nodeId); b.writeUtf(pin, 64); b.writeUtf(display, 256)
    }

    companion object {
        fun read(b: FriendlyByteBuf) = PinValueDto(b.readVarInt(), b.readUtf(64), b.readUtf(256))
    }
}

/** Big message (chunked): everything the drawer shows when a fiber stops — built once per stop. */
class RunPauseMsg(
    val pos: BlockPos,
    val contextId: Int,
    val stopToken: Long,
    val reason: Int,
    val stack: List<FrameDto>,
    val scopes: List<ScopeDto>,
    val pinValues: List<PinValueDto>,
    val pureValues: List<PinValueDto>,
) {
    fun write(b: FriendlyByteBuf) {
        b.writeBlockPos(pos); b.writeVarInt(contextId); b.writeVarLong(stopToken); b.writeByte(reason)
        b.writeVarInt(stack.size); stack.forEach { it.write(b) }
        b.writeVarInt(scopes.size); scopes.forEach { it.write(b) }
        b.writeVarInt(pinValues.size); pinValues.forEach { it.write(b) }
        b.writeVarInt(pureValues.size); pureValues.forEach { it.write(b) }
    }

    companion object {
        const val NAME = "run_pause"
        fun read(b: FriendlyByteBuf): RunPauseMsg {
            val pos = b.readBlockPos(); val ctx = b.readVarInt(); val token = b.readVarLong(); val reason = b.readByte().toInt()
            val ns = b.readVarInt(); require(ns in 0..64); val stack = List(ns) { FrameDto.read(b) }
            val nsc = b.readVarInt(); require(nsc in 0..32); val scopes = List(nsc) { ScopeDto.read(b) }
            val np = b.readVarInt(); require(np in 0..512); val pins = List(np) { PinValueDto.read(b) }
            val nq = b.readVarInt(); require(nq in 0..512); val pure = List(nq) { PinValueDto.read(b) }
            return RunPauseMsg(pos, ctx, token, reason, stack, scopes, pins, pure)
        }
    }
}

/** Client → server: the scopes of a deeper frame. */
class RunScopesRequestPayload(val pos: BlockPos, val contextId: Int, val frameIndex: Int) : BpmPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = payloadType<RunScopesRequestPayload>(rid("run_scopes_request"))
        val CODEC = rcodec<RunScopesRequestPayload>(
            { b, v -> b.writeBlockPos(v.pos); b.writeVarInt(v.contextId); b.writeVarInt(v.frameIndex) },
            { b -> RunScopesRequestPayload(b.readBlockPos(), b.readVarInt(), b.readVarInt()) },
        )
    }
}

class RunScopesPayload(val pos: BlockPos, val contextId: Int, val frameIndex: Int, val scopes: List<ScopeDto>) : BpmPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = payloadType<RunScopesPayload>(rid("run_scopes"))
        val CODEC = rcodec<RunScopesPayload>(
            { b, v -> b.writeBlockPos(v.pos); b.writeVarInt(v.contextId); b.writeVarInt(v.frameIndex); b.writeVarInt(v.scopes.size); v.scopes.forEach { it.write(b) } },
            { b -> val pos = b.readBlockPos(); val c = b.readVarInt(); val f = b.readVarInt(); val n = b.readVarInt(); require(n in 0..32); RunScopesPayload(pos, c, f, List(n) { ScopeDto.read(b) }) },
        )
    }
}

/** Client → server: arm, disarm or remove a breakpoint on a node of the controller's program. */
class BreakpointSetPayload(val pos: BlockPos, val nodeId: Int, val enabled: Boolean, val remove: Boolean) : BpmPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = payloadType<BreakpointSetPayload>(rid("breakpoint_set"))
        val CODEC = rcodec<BreakpointSetPayload>(
            { b, v -> b.writeBlockPos(v.pos); b.writeVarInt(v.nodeId); b.writeBoolean(v.enabled); b.writeBoolean(v.remove) },
            { b -> BreakpointSetPayload(b.readBlockPos(), b.readVarInt(), b.readBoolean(), b.readBoolean()) },
        )
    }
}

/** Server → client: the controller's whole breakpoint table. */
class BreakpointsPayload(val pos: BlockPos, val nodeIds: IntArray, val enabled: BooleanArray) : BpmPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = payloadType<BreakpointsPayload>(rid("breakpoints"))
        val CODEC = rcodec<BreakpointsPayload>(
            { b, v -> b.writeBlockPos(v.pos); b.writeInts(v.nodeIds); v.enabled.forEach { b.writeBoolean(it) } },
            { b -> val pos = b.readBlockPos(); val ids = b.readInts(); BreakpointsPayload(pos, ids, BooleanArray(ids.size) { b.readBoolean() }) },
        )
    }
}

/** Client → server: set a variable of the running program from its text (numbers, booleans, or text). */
class SetVariablePayload(val pos: BlockPos, val name: String, val text: String) : BpmPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = payloadType<SetVariablePayload>(rid("set_variable"))
        val CODEC = rcodec<SetVariablePayload>(
            { b, v -> b.writeBlockPos(v.pos); b.writeUtf(v.name, 128); b.writeUtf(v.text, 512) },
            { b -> SetVariablePayload(b.readBlockPos(), b.readUtf(128), b.readUtf(512)) },
        )
    }
}

/** Client → server: change a literal of the running program (a live tune; the document itself is committed separately). */
class SetLiteralPayload(val pos: BlockPos, val nodeId: Int, val pin: String, val text: String) : BpmPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = payloadType<SetLiteralPayload>(rid("set_literal"))
        val CODEC = rcodec<SetLiteralPayload>(
            { b, v -> b.writeBlockPos(v.pos); b.writeVarInt(v.nodeId); b.writeUtf(v.pin, 64); b.writeUtf(v.text, 512) },
            { b -> SetLiteralPayload(b.readBlockPos(), b.readVarInt(), b.readUtf(64), b.readUtf(512)) },
        )
    }
}

/** Text typed in the debugger, as a value: whole number, decimal, boolean, or the text itself. */
fun parseDebugValue(text: String): Any? {
    val t = text.trim()
    if (t == "null") return null
    t.toLongOrNull()?.let { return it }
    t.toDoubleOrNull()?.let { return it }
    t.toBooleanStrictOrNull()?.let { return it }
    return if (t.length >= 2 && t.startsWith('"') && t.endsWith('"')) t.substring(1, t.length - 1) else t
}
