package bpm.client.editor

import io.osrsx.vscript.log.LogLevel
import io.osrsx.vscript.log.ScriptLog
import io.osrsx.vscript.runtime.Context
import io.osrsx.vscript.runtime.DebugSurface
import io.osrsx.vscript.runtime.Scope
import io.osrsx.vscript.runtime.StackFrame
import io.osrsx.vscript.runtime.StoppedReason
import io.osrsx.vscript.runtime.Variable
import io.osrsx.vscript.vm.Breakpoints
import io.osrsx.vscript.vm.FiberState
import io.osrsx.vscript.vm.PauseReason

/** The verbs a run view sends back to the controller's VM. */
interface RunSink {
    fun action(name: String)
    fun breakpoint(nodeId: Int, enabled: Boolean, remove: Boolean)
    fun requestScopes(contextId: Int, frameIndex: Int)
    fun setVariable(name: String, text: String)
    fun setLiteral(nodeId: Int, pin: String, text: String)
    fun subscribe(on: Boolean)
}

/**
 * A mirror of a controller's VM as the server streams it: what fired, which fibers exist, the log, and the
 * stop snapshot — and a [DebugSurface] over that mirror so vscript's drawer draws it as if it were local.
 * Verbs go back through the [sink]. Breakpoints are kept in a local [Breakpoints] the drawer and canvas
 * edit directly; the difference against what the server last confirmed is sent every frame ([syncBreakpoints]).
 */
class RemoteRunView(private val sink: RunSink) : DebugSurface {
    val activeNodes = LinkedHashSet<Int>()
    val activeLinks = LinkedHashSet<Int>()
    var phase: String = ""
        private set
    var paused: Boolean = false
        private set
    var error: String? = null
        private set
    val log = ScriptLog()
    private var contextList: List<Context> = emptyList()
    private var token = -1L
    private var reason: StoppedReason? = null
    private var pausedContext = -1
    private var stack: List<StackFrame> = emptyList()
    private val scopeCache = HashMap<Pair<Int, Int>, List<Scope>>()
    private val requestedScopes = HashSet<Pair<Int, Int>>()
    val pinValues = HashMap<Pair<Int, String>, Any?>()
    val pureValues = HashMap<Int, Any?>()
    override val breakpoints = Breakpoints()
    private var confirmed: Map<Int, Boolean> = emptyMap()
    var subscribed = false
        private set

    fun subscribe(on: Boolean) {
        if (subscribed == on) return
        subscribed = on
        sink.subscribe(on)
        if (!on) clear()
    }

    private fun clear() {
        activeNodes.clear()
        activeLinks.clear()
        contextList = emptyList()
        paused = false
        token = -1
        stack = emptyList()
        scopeCache.clear()
        requestedScopes.clear()
        pinValues.clear()
        pureValues.clear()
    }

    // ---- what the server says -------------------------------------------------------------------------------

    fun applyFrame(full: Boolean, phase: String, paused: Boolean, stopToken: Long, addNodes: IntArray, removeNodes: IntArray, addLinks: IntArray, removeLinks: IntArray, contexts: List<Context>?, error: String?) {
        if (full) {
            activeNodes.clear()
            activeLinks.clear()
        }
        removeNodes.forEach { activeNodes.remove(it) }
        removeLinks.forEach { activeLinks.remove(it) }
        addNodes.forEach { activeNodes.add(it) }
        addLinks.forEach { activeLinks.add(it) }
        this.phase = phase
        this.error = error
        if (contexts != null) contextList = contexts
        if (this.paused && !paused) {
            // Running again: the stop snapshot is history; the live variables keep streaming into frame 0.
            stack = emptyList()
            scopeCache.keys.retainAll { it.second == 0 }
            requestedScopes.clear()
            pinValues.clear()
            pureValues.clear()
            pausedContext = -1
            reason = null
        }
        this.paused = paused
        token = stopToken
    }

    fun applyLog(records: List<Triple<Int, Int, String>>, repeats: List<Int>, cleared: Boolean) {
        if (cleared) log.clear()
        // Repeats coalesce here the way they did there: the same line twice in a row is one record with a count.
        for ((level, nodeId, message) in records) log.add(LogLevel.entries.getOrElse(level) { LogLevel.INFO }, message, nodeId)
        @Suppress("UNUSED_VARIABLE") val unused = repeats
    }

    fun applyPause(contextId: Int, stopToken: Long, reason: Int, stack: List<StackFrame>, scopes: List<Scope>, pins: List<Triple<Int, String, String>>, pure: List<Pair<Int, String>>) {
        pausedContext = contextId
        token = stopToken
        paused = true
        this.reason = StoppedReason.entries.getOrElse(reason) { StoppedReason.PAUSE }
        this.stack = stack
        scopeCache.clear()
        requestedScopes.clear()
        scopeCache[contextId to 0] = scopes
        pinValues.clear()
        for ((n, pin, d) in pins) pinValues[n to pin] = d
        pureValues.clear()
        for ((n, d) in pure) pureValues[n] = d
    }

    fun applyScopes(contextId: Int, frameIndex: Int, scopes: List<Scope>) {
        scopeCache[contextId to frameIndex] = scopes
    }

    fun applyBreakpoints(entries: Map<Int, Boolean>) {
        confirmed = entries
        breakpoints.clear()
        for ((id, on) in entries) breakpoints.add(id, on)
    }

    /** Sends whatever the drawer or canvas changed in the local breakpoint table since the server's last word. */
    fun syncBreakpoints() {
        val now = breakpoints.entries().associate { it.first to it.second.enabled }
        for ((id, on) in now) if (confirmed[id] != on) sink.breakpoint(id, on, remove = false)
        for (id in confirmed.keys) if (id !in now) sink.breakpoint(id, false, remove = true)
        confirmed = now
    }

    fun toggleBreakpoint(nodeId: Int) {
        breakpoints.toggle(nodeId)
        syncBreakpoints()
    }

    // ---- DebugSurface ---------------------------------------------------------------------------------------

    override val isPaused: Boolean get() = paused
    override fun contexts(): List<Context> = contextList
    override fun focused(): Context? = contextList.firstOrNull { it.id == pausedContext } ?: contextList.firstOrNull { it.isPaused }
    override fun stoppedReason(): StoppedReason? = if (paused) reason ?: StoppedReason.PAUSE else null
    override fun stopToken(): Long = token
    override fun stackTrace(contextId: Int): List<StackFrame> = if (contextId == pausedContext) stack else emptyList()

    override fun scopes(contextId: Int, frameIndex: Int): List<Scope> {
        scopeCache[contextId to frameIndex]?.let { return it }
        if (requestedScopes.add(contextId to frameIndex)) sink.requestScopes(contextId, frameIndex)
        return emptyList()
    }

    override fun valueOf(contextId: Int, nodeId: Int, pin: String): Variable? =
        pinValues[nodeId to pin]?.let { Variable(pin, null, nodeId, shown = it.toString(), typed = "") }

    override fun resume() = sink.action("RESUME")
    override fun stop() = sink.action("STOP")
    override fun stepOver() = sink.action("STEP_OVER")
    override fun stepInto() = sink.action("STEP_INTO")
    override fun stepOut() = sink.action("STEP_OUT")
    override fun stepIntoData() = sink.action("STEP_DATA")
    fun pause() = sink.action("PAUSE")

    /** A literal changed on the canvas while the program runs: tune it in place (debug builds only). */
    fun setLiteral(nodeId: Int, pin: String, text: String) = sink.setLiteral(nodeId, pin, text)
    fun setVariable(name: String, text: String) = sink.setVariable(name, text)

    /** The node the drawer should point at while paused, or -1. */
    val pausedNode: Int get() = if (paused) (focused()?.nodeId ?: -1) else -1

    companion object {
        fun context(id: Int, name: String, entryNodeId: Int, state: Int, pauseReason: Int, nodeId: Int, error: String?, sleepingForMs: Long) = Context(
            id, name, entryNodeId,
            FiberState.entries.getOrElse(state) { FiberState.RUNNABLE },
            PauseReason.entries.getOrElse(pauseReason) { PauseReason.NONE },
            nodeId, error, emptyList(), sleepingForMs,
        )
    }
}
