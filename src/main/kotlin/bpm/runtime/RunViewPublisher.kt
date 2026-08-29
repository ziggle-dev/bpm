package bpm.runtime

import io.osrsx.vscript.log.LogRecord
import io.osrsx.vscript.runtime.DebugSession
import io.osrsx.vscript.runtime.ScriptRuntime
import io.osrsx.vscript.runtime.StoppedReason
import io.osrsx.vscript.runtime.Variable

/**
 * Turns one runtime's state into the deltas the run view sends: which nodes and links fired since last
 * time, the phase and fibers when they changed, new log records, and a snapshot when a fiber stops.
 *
 * Pure: it reads a [ScriptRuntime] and a [DebugSession] and answers plain data; the network side wraps the
 * answers in payloads. One per controller, shared by every watcher — a newcomer gets [snapshot] and joins
 * the stream. Caps: a delta above [FULL_ABOVE] ids is sent as a full set; logs at most [LOG_BATCH] per call;
 * a pause carries at most [MAX_VALUES] pin and pure values, [MAX_STACK] frames.
 */
class RunViewPublisher(private val runtime: ScriptRuntime, private val debug: DebugSession, private val activeWindowNanos: Long = 400_000_000L) {

    class Frame(
        val full: Boolean,
        val phase: String,
        val paused: Boolean,
        val stopToken: Long,
        val addNodes: IntArray,
        val removeNodes: IntArray,
        val addLinks: IntArray,
        val removeLinks: IntArray,
        val contexts: List<io.osrsx.vscript.runtime.Context>?,
        val error: String?,
    )

    class Pause(
        val contextId: Int,
        val stopToken: Long,
        val reason: StoppedReason,
        val stack: List<io.osrsx.vscript.runtime.StackFrame>,
        val scopes: List<io.osrsx.vscript.runtime.Scope>,
        val pinValues: List<Triple<Int, String, String>>,
        val pureValues: List<Pair<Int, String>>,
    )

    private var lastNodes: Set<Int> = emptySet()
    private var lastLinks: Set<Int> = emptySet()
    private var lastPhase = ""
    private var lastPaused = false
    private var lastToken = -1L
    private var lastContexts = ""
    private var lastError: String? = null
    private var lastLogSeq = -1L
    private var lastRunId = -1
    private var pausedToken = -1L

    /** The whole state, for a watcher that just arrived. */
    fun snapshot(): Frame {
        val nodes = runtime.activeNodes(activeWindowNanos)
        val links = runtime.activeLinks(activeWindowNanos)
        lastNodes = nodes
        lastLinks = links
        lastPhase = runtime.phase.name
        lastPaused = debug.isPaused
        lastToken = debug.stopToken()
        val contexts = debug.contexts()
        lastContexts = signature(contexts)
        lastError = runtime.lastError
        return Frame(true, lastPhase, lastPaused, lastToken, nodes.toIntArray(), IntArray(0), links.toIntArray(), IntArray(0), contexts, lastError)
    }

    /** What changed since the last call; null when nothing did. */
    fun frame(): Frame? {
        val nodes = runtime.activeNodes(activeWindowNanos)
        val links = runtime.activeLinks(activeWindowNanos)
        val phase = runtime.phase.name
        val paused = debug.isPaused
        val token = debug.stopToken()
        val contexts = debug.contexts()
        val sig = signature(contexts)
        val error = runtime.lastError
        val same = nodes == lastNodes && links == lastLinks && phase == lastPhase && paused == lastPaused && token == lastToken && sig == lastContexts && error == lastError
        if (same) return null
        val addN = nodes - lastNodes
        val remN = lastNodes - nodes
        val addL = links - lastLinks
        val remL = lastLinks - links
        val full = addN.size + remN.size + addL.size + remL.size > FULL_ABOVE
        val ctx = if (sig != lastContexts) contexts else null
        lastNodes = nodes
        lastLinks = links
        lastPhase = phase
        lastPaused = paused
        lastToken = token
        lastContexts = sig
        lastError = error
        return if (full) {
            Frame(true, phase, paused, token, nodes.toIntArray(), IntArray(0), links.toIntArray(), IntArray(0), ctx, error)
        } else {
            Frame(false, phase, paused, token, addN.toIntArray(), remN.toIntArray(), addL.toIntArray(), remL.toIntArray(), ctx, error)
        }
    }

    /** Log records since the last call (a new run clears the watcher's log first — [cleared]). */
    class Logs(val records: List<LogRecord>, val cleared: Boolean)

    fun logs(): Logs? {
        val log = runtime.log
        val runId = log.runId
        val cleared = runId != lastRunId
        if (cleared) {
            lastRunId = runId
            lastLogSeq = -1
        }
        val fresh = log.records.filter { it.seq > lastLogSeq }
        if (fresh.isEmpty() && !cleared) return null
        val batch = fresh.takeLast(LOG_BATCH)
        batch.lastOrNull()?.let { lastLogSeq = it.seq }
        // Anything beyond the batch is dropped for this tick, and the cursor moves past it: a script that
        // logs faster than the wire carries loses lines rather than lagging forever.
        fresh.lastOrNull()?.let { lastLogSeq = it.seq }
        return Logs(batch, cleared)
    }

    /** The stop snapshot the first time a given stop is seen; null while running or already reported. */
    fun pause(force: Boolean = false): Pause? {
        if (!debug.isPaused) {
            pausedToken = -1
            return null
        }
        val token = debug.stopToken()
        if (token == pausedToken && !force) return null
        pausedToken = token
        val ctx = debug.focused() ?: debug.contexts().firstOrNull { it.isPaused } ?: return null
        val stack = debug.stackTrace(ctx.id).take(MAX_STACK)
        val scopes = debug.scopes(ctx.id, 0)
        val pins = debug.visibleValues(ctx.id).entries.take(MAX_VALUES).map { (k, v) -> Triple(k.first, k.second, Variable.render(v)) }
        val pure = runtime.pureValues.entries.take(MAX_VALUES).map { (id, v) -> id to Variable.render(v) }
        return Pause(ctx.id, token, debug.stoppedReason() ?: StoppedReason.PAUSE, stack, scopes, pins, pure)
    }

    fun scopes(contextId: Int, frameIndex: Int): List<io.osrsx.vscript.runtime.Scope> = debug.scopes(contextId, frameIndex)

    private val lastScopes = HashMap<Int, String>()

    /**
     * The top-frame scopes of every fiber whose values changed since the last call — what keeps the drawer's
     * Variables tab live while the program runs, the way a local session's does by reading every frame.
     */
    fun liveScopes(): List<Pair<Int, List<io.osrsx.vscript.runtime.Scope>>> {
        val out = ArrayList<Pair<Int, List<io.osrsx.vscript.runtime.Scope>>>()
        val seen = HashSet<Int>()
        for (c in debug.contexts()) {
            seen += c.id
            val scopes = debug.scopes(c.id, 0)
            val sig = scopes.joinToString("|") { s -> s.name + ":" + s.variables.joinToString(",") { v -> v.name + "=" + v.display } }
            if (lastScopes[c.id] != sig) {
                lastScopes[c.id] = sig
                out += c.id to scopes
            }
        }
        lastScopes.keys.retainAll(seen)
        return out
    }

    private fun signature(contexts: List<io.osrsx.vscript.runtime.Context>): String =
        contexts.joinToString("|") { "${it.id}:${it.state}:${it.pauseReason}:${it.nodeId}:${it.error != null}:${it.sleepingForMs}" }

    companion object {
        const val FULL_ABOVE = 1024
        const val LOG_BATCH = 64
        const val MAX_STACK = 64
        const val MAX_VALUES = 512
    }
}
