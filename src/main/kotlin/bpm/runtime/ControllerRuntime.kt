package bpm.runtime

import bpm.catalog.BpmCatalog
import bpm.catalog.McValueOut
import bpm.catalog.values.EntityHandle
import bpm.nodes.ControllerHost
import bpm.world.ControllerBlockEntity
import bpm.world.LinkTable
import bpm.world.ResolvedLink
import io.osrsx.vscript.host.RunPhase
import io.osrsx.vscript.log.LogLevel
import io.osrsx.vscript.nodes.BuiltinHosts
import io.osrsx.vscript.runtime.EditorDoc
import io.osrsx.vscript.runtime.ScriptRuntime
import io.osrsx.vscript.runtime.TickMode
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.neoforged.neoforge.energy.IEnergyStorage
import net.neoforged.neoforge.fluids.capability.IFluidHandler
import net.neoforged.neoforge.items.IItemHandler

/**
 * One controller's running program: the [ControllerHost] its nodes call into, the vscript [ScriptRuntime]
 * that runs them, and the [TickJobs] that carry actions across ticks.
 *
 * Built fresh for every start — a `NodeLibrary` is installed against a `HostRegistry` that belongs to this
 * runtime alone (vscript registers `vscript.log` on whatever registry it is handed), and node bodies close
 * over this host. The catalogue the program was validated against is the shared, detached one; descriptors
 * are identical, only the bodies differ.
 *
 * Everything here runs on the server thread. [tickOnce] advances the jobs *before* the VM so a job that
 * completes this tick resumes its fiber this tick.
 */
class ControllerRuntime(private val be: ControllerBlockEntity, private val manager: RuntimeManager) : ControllerHost {

    override val level: ServerLevel get() = be.level as ServerLevel
    override val pos: BlockPos get() = be.blockPos
    override val links: LinkTable get() = be.links
    override val jobs = TickJobs()
    override val tickCount: Long get() = manager.clock.ticks
    override val selfInventory: IItemHandler get() = be.inventory
    override val selfTanks: IFluidHandler get() = be.tanks
    override val selfEnergy: IEnergyStorage get() = be.energy

    /** Links made from coordinates, most recently used last; the table's version does not touch these. */
    private val adHocLinks = object : LinkedHashMap<String, ResolvedLink>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ResolvedLink>?): Boolean = size > 256
    }

    private val resolved = HashMap<String, ResolvedLink>()
    private var resolvedVersion = -1
    private val warned = HashSet<String>()

    /** Items moved through this runtime, for `/bpm status`. */
    var transfers: Long = 0
        private set

    /** Wall time the last [tickOnce] took, for the manager's watchdog. */
    var lastTickNanos: Long = 0
        private set

    val phase = RunPhase()
    val files = NbtFileStore(be.scriptData) { be.setChanged() }

    private val library = BpmCatalog.library(this)
    private val hosts = library.install(BuiltinHosts.registry(files = files, phase = phase), McValueOut)

    lateinit var debug: io.osrsx.vscript.runtime.DebugSession
        private set
    lateinit var publisher: RunViewPublisher
        private set

    val runtime = ScriptRuntime(
        catalog = BpmCatalog.catalog,
        hosts = hosts,
        clock = manager.clock,
        actuator = null,
        source = manager.library(level).graphSource(),
        runPhase = phase,
        tickMode = TickMode.LOOP,
    )

    init {
        debug = io.osrsx.vscript.runtime.DebugSession(runtime)
        publisher = RunViewPublisher(runtime, debug)
    }

    // ---- host -------------------------------------------------------------------------------------------

    override fun link(name: String): ResolvedLink? {
        if (name.startsWith(bpm.world.AdHocLink.PREFIX)) return adHoc(name)
        if (resolvedVersion != links.version) {
            resolved.clear()
            resolvedVersion = links.version
        }
        resolved[name]?.let { return it }
        val link = links[name] ?: return null
        return ResolvedLink(link, level).also { resolved[name] = it }
    }

    override fun items(name: String): IItemHandler? =
        if (name == ControllerHost.SELF) selfInventory else link(name)?.let { r -> r.items().also { if (it == null) unavailable(name, r, "items") } }

    override fun fluids(name: String): IFluidHandler? =
        if (name == ControllerHost.SELF) selfTanks else link(name)?.let { r -> r.fluids().also { if (it == null) unavailable(name, r, "fluids") } }

    override fun energy(name: String): IEnergyStorage? =
        if (name == ControllerHost.SELF) selfEnergy else link(name)?.let { r -> r.energy().also { if (it == null) unavailable(name, r, "energy") } }

    private fun adHoc(name: String): ResolvedLink? {
        adHocLinks[name]?.let { return it }
        val link = bpm.world.AdHocLink.parse(name, level.dimension()) ?: return null
        if (!link.pos.closerThan(pos, be.linkRange)) {
            if (warned.add("$name/range")) log(LogLevel.WARN, "$name is farther than ${be.linkRange.toInt()} blocks from the controller")
            return null
        }
        return ResolvedLink(link, level).also { adHocLinks[name] = it }
    }

    private fun unavailable(name: String, r: ResolvedLink, what: String) {
        if (!warned.add("$name/$what")) return
        val why = if (!r.loaded) "its chunk is not loaded" else "the block there has no $what"
        log(LogLevel.WARN, "link '$name' at ${r.link.pos.toShortString()}: $why")
    }

    override fun entity(handle: Any?): Entity? {
        val h = handle as? EntityHandle ?: return null
        return level.server.getLevel(h.dimension)?.getEntity(h.uuid)
    }

    override fun emitSignal(side: Direction, strength: Int) = be.setSignal(side, strength)

    override fun emitted(side: Direction): Int = be.signal(side)

    override fun requestSleep(reason: String) {
        runtime.requestSleep(reason)
    }

    override fun log(level: LogLevel, message: String) {
        runtime.log.add(level, message)
    }

    override fun notify(level: String, message: String) {
        // The console sees it too: a player watching the editor cannot see the action bar behind it.
        val lvl = when (level.lowercase()) { "warn", "warning" -> LogLevel.WARN; "error" -> LogLevel.ERROR; else -> LogLevel.INFO }
        log(lvl, message)
        manager.notify(be, level, message)
    }

    /** The in-world effects: streams between the links things move through, and the tool at work where a job runs. */
    val effects = EffectSender({ be.blockPos }, ::endpointOf, ::sendEffect)

    private fun endpointOf(name: String): EffectSender.Endpoint? {
        if (name == ControllerHost.SELF) return EffectSender.Endpoint(be.blockPos, -1)
        val l = link(name)?.link ?: return null
        return EffectSender.Endpoint(l.pos, l.side?.get3DDataValue() ?: -1)
    }

    /** To everyone who can see either end, or the controller, once each. */
    private fun sendEffect(p: bpm.net.EffectPayload) {
        val l = be.level as? ServerLevel ?: return
        val players = LinkedHashSet<net.minecraft.server.level.ServerPlayer>()
        for (pos in listOf(p.controller, p.origin, p.target)) players += l.chunkSource.chunkMap.getPlayers(net.minecraft.world.level.ChunkPos(pos), false)
        for (player in players) net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, p)
    }

    override fun transferred(from: String, to: String, amount: Int, kind: bpm.net.EffectKind, item: String) {
        transfers += amount
        effects.transfer(from, to, amount, kind, item)
    }

    override fun newEffectId(): Int = effects.newId()

    override fun action(stream: Int, op: bpm.net.EffectOp, kind: bpm.net.EffectKind, at: BlockPos, face: Int, item: String) =
        effects.action(stream, op, kind, at, face, item)

    override fun effect(id: ResourceLocation, data: CompoundTag) {
        // Addon effects (the plan's EffectPayload `extra`): not carried yet.
    }

    // ---- lifecycle --------------------------------------------------------------------------------------

    /** Compiles and starts [doc]; the error text when it could not, null when it runs. */
    fun start(doc: EditorDoc, debug: Boolean, resume: Boolean): String? {
        warned.clear()
        return runtime.run(doc, debug = debug, resume = resume)
    }

    fun tickOnce(budgetNanos: Long) {
        val t0 = System.nanoTime()
        jobs.advance()
        runtime.scheduler.budgetNanos = budgetNanos
        runtime.tick()
        effects.tick()
        lastTickNanos = System.nanoTime() - t0
    }

    fun requestSleep(reason: String, withinMs: Long): Boolean = runtime.requestSleep(reason, withinMs)

    fun stop() {
        jobs.cancelAll()
        effects.endAll()
        runtime.stop()
    }

    val isRunning: Boolean get() = runtime.isRunning
    val isAsleep: Boolean get() = runtime.isAsleep
    val lastError: String? get() = runtime.lastError
    val phaseName: String get() = runtime.phase.name
}
