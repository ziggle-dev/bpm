package bpm.runtime

import bpm.catalog.BpmCatalog
import bpm.catalog.McValueOut
import bpm.catalog.values.EntityHandle
import bpm.nodes.ControllerHost
import bpm.world.ControllerBlockEntity
import bpm.world.LinkTable
import bpm.world.ResolvedLink
import dev.ziggle.vscript.host.RunPhase
import dev.ziggle.vscript.log.LogLevel
import dev.ziggle.vscript.nodes.BuiltinHosts
import dev.ziggle.vscript.runtime.EditorDoc
import dev.ziggle.vscript.runtime.ScriptRuntime
import dev.ziggle.vscript.runtime.TickMode
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import bpm.platform.ports.EnergyPort
import bpm.platform.ports.HandlerFluidPort
import bpm.platform.ports.HandlerPort
import bpm.platform.ports.StoragePort
import bpm.platform.ports.FluidPort
import bpm.platform.ports.ItemPort

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
    private val selfInventoryPort: ItemPort by lazy { HandlerPort(be.inventory) }
    override val selfInventory: ItemPort get() = selfInventoryPort
    private val selfTanksPort: FluidPort by lazy { HandlerFluidPort(be.tanks) }
    override val selfTanks: FluidPort get() = selfTanksPort

    /**
     * The stores wrapped once rather than per access. The block entity's own buffer, tanks and cell stay
     * NeoForge handlers and stay published as capabilities — that is what other mods' pipes and cables
     * look for — and only the face the nodes see changes.
     */
    private val selfEnergyPort: EnergyPort by lazy { StoragePort(be.energy) }
    override val selfEnergy: EnergyPort get() = selfEnergyPort

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

    lateinit var debug: dev.ziggle.vscript.runtime.DebugSession
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
        debug = dev.ziggle.vscript.runtime.DebugSession(runtime)
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
        // A link past the core's capacity is kept but goes quiet: it resolves, and answers nothing.
        val capped = links.isOverCapacity(name)
        if (capped && warned.add("$name/capacity")) {
            log(LogLevel.WARN, "'$name' is past this controller's ${links.capacity} links — a bigger core holds more")
        }
        val r = if (link.isPresence) bpm.world.PresenceLink(link, level, be) else ResolvedLink(link, level, capped)
        return r.also { resolved[name] = it }
    }

    override fun items(name: String): ItemPort? =
        if (name == ControllerHost.SELF) selfInventory else link(name)?.let { r -> r.items().also { if (it == null) unavailable(name, r, "items") } }

    override fun fluids(name: String): FluidPort? =
        if (name == ControllerHost.SELF) selfTanks else link(name)?.let { r -> r.fluids().also { if (it == null) unavailable(name, r, "fluids") } }

    override fun energy(name: String): EnergyPort? =
        if (name == ControllerHost.SELF) selfEnergy else link(name)?.let { r -> r.energy().also { if (it == null) unavailable(name, r, "energy") } }

    private fun adHoc(name: String): ResolvedLink? {
        adHocLinks[name]?.let { return it }
        val link = bpm.world.AdHocLink.parse(name, level.dimension()) ?: return null
        if (!link.pos.closerThan(pos, be.linkRange)) {
            if (warned.add("$name/range")) log(LogLevel.WARN, "$name is farther than ${bpm.world.CoreTier.rangeText(be.linkRange)} blocks from the controller")
            return null
        }
        return ResolvedLink(link, level).also { adHocLinks[name] = it }
    }

    private fun unavailable(name: String, r: ResolvedLink, what: String) {
        if (!warned.add("$name/$what")) return
        if (r is bpm.world.PresenceLink) {
            // A person's link says who and why, not where: "at 12, 64, -8" is no help when the answer is
            // that they put the tether in a chest.
            val grant = when (what) { "items" -> bpm.world.Grant.READ; else -> null }
            val why = r.reason(grant) ?: "they are carrying no inventory bpm can reach"
            log(LogLevel.WARN, "'$name': $why")
            return
        }
        val why = when {
            r.capped -> "it is past this controller's ${links.capacity} links"
            !r.loaded -> "its chunk is not loaded"
            else -> "the block there has no $what"
        }
        log(LogLevel.WARN, "link '$name' at ${r.link.pos.toShortString()}: $why")
    }

    override fun entity(handle: Any?): Entity? {
        val h = handle as? EntityHandle ?: return null
        return level.server.getLevel(h.dimension)?.getEntity(h.uuid)
    }

    /**
     * Keys: which are held, which presses have not been read yet, and what this graph asked of each.
     *
     * An edge is shared, not per-node: the first node to ask for `(player, key)` takes it. Two nodes racing
     * for the same key is a graph asking one question twice, and duplicating the press would fire both
     * branches off one keystroke — which is the surprising answer, not the useful one.
     *
     * A watch lasts for the life of the run once named: a graph that asks about a key in one branch will ask
     * again the next time that branch runs, and un-watching it in between would drop the very press it waits
     * for.
     */
    class KeyWish(val requireModifier: Boolean, val consume: Boolean)

    private val held = HashMap<java.util.UUID, MutableSet<String>>()

    /**
     * Presses waiting to be read, COUNTED rather than flagged.
     *
     * A set collapsed two quick presses into one, so a toggle tapped twice before its node next ran flipped
     * once and looked like a dropped keystroke. Capped, because a graph that never reads a key it asked to
     * watch must not accumulate presses forever.
     */
    private val pressed = HashMap<Pair<java.util.UUID, String>, Int>()
    private val wishes = HashMap<java.util.UUID, MutableMap<String, KeyWish>>()

    /** The panels this controller is drawing on people's screens — see `docs/DESIGN_PLAYER_LINK.md` §10. */
    val hud = HudPanels { be.blockPos }

    override fun showPanel(
        player: net.minecraft.server.level.ServerPlayer,
        widgets: List<bpm.world.devices.Widget>,
        anchor: String,
        offsetX: Int,
        offsetY: Int,
        width: Int,
        scale: Double,
        timeoutTicks: Int,
    ) {
        val panel = hud.show(player.uuid, widgets, anchor, offsetX, offsetY, width, scale, timeoutTicks, manager.clock.ticks) ?: return
        sendPanel(player, panel)
    }

    override fun clearPanel(player: java.util.UUID): Boolean {
        if (!hud.clear(player)) return false
        (level.server.playerList.getPlayer(player))?.let { p ->
            bpm.platform.net.Net.sendToPlayer(
                p,
                bpm.net.HudPanelPayload(be.blockPos, "TopRight", 0, 0, 0, 1f, net.minecraft.nbt.ListTag()),
            )
        }
        return true
    }

    override fun panelVisible(player: java.util.UUID): Boolean = hud[player]?.visible == true

    /** Take every panel this controller put up back down — what stopping does. */
    fun clearAllPanels() {
        for (uuid in hud.players.toList()) clearPanel(uuid)
        hud.clearAll()
    }

    override fun takePanelPress(player: java.util.UUID, id: String): Boolean = hud.takePress(player, id)

    override fun panelValue(player: java.util.UUID, id: String): Double = hud.valueOf(player, id)

    override fun panelText(player: java.util.UUID, id: String): String = hud.textOf(player, id)

    private fun sendPanel(player: net.minecraft.server.level.ServerPlayer, panel: bpm.runtime.HudPanels.Panel) {
        val tags = bpm.world.devices.Widget.saveAll(panel.widgets, level.registryAccess())
        bpm.platform.net.Net.sendToPlayer(
            player,
            bpm.net.HudPanelPayload(be.blockPos, panel.anchor, panel.offsetX, panel.offsetY, panel.width, panel.scale.toFloat(), tags),
        )
    }

    /**
     * A key moved, from the network. Server thread.
     *
     * A graph that asked for the modifier does not see a bare press, so `W` and `alt+W` are different keys to
     * two different graphs even though the client reports one edge for both.
     */
    fun key(player: java.util.UUID, key: String, down: Boolean, modifier: Boolean) {
        val wish = wishes[player]?.get(key) ?: return
        if (wish.requireModifier && !modifier) return
        val set = held.getOrPut(player) { HashSet() }
        if (down) {
            set.add(key)
            val at = player to key
            pressed[at] = (pressed[at] ?: 0).coerceAtMost(MAX_PENDING - 1) + 1
        } else {
            set.remove(key)
        }
    }

    /** What [player]'s client is asked to report on this controller's behalf. */
    fun keyWishes(player: java.util.UUID): Map<String, KeyWish> = wishes[player].orEmpty()

    override fun watchKey(player: java.util.UUID, key: String, requireModifier: Boolean, consume: Boolean) {
        val map = wishes.getOrPut(player) { LinkedHashMap() }
        val had = map[key]
        if (had != null && had.requireModifier == requireModifier && had.consume == consume) return
        if (had == null && map.size >= bpm.world.KeyNames.MAX_WATCHED) return
        map[key] = KeyWish(requireModifier, consume)
        level.server.playerList.getPlayer(player)?.let { KeyWatch.refresh(it) }
    }

    /** Was it pressed since anything last asked — and if so, take the edge. */
    override fun takeKey(player: java.util.UUID, key: String): Boolean {
        val at = player to key
        val n = pressed[at] ?: return false
        if (n <= 1) pressed.remove(at) else pressed[at] = n - 1
        return true
    }

    override fun keyHeld(player: java.util.UUID, key: String): Boolean = held[player]?.contains(key) == true

    /** Stop watching everything this run named — the run is over. */
    fun clearKeyWatch() {
        val players = wishes.keys.toList()
        wishes.clear()
        held.clear()
        pressed.clear()
        for (uuid in players) {
            KeyWatch.forget(uuid)
            level.server.playerList.getPlayer(uuid)?.let { KeyWatch.refresh(it) }
        }
    }

    override fun presence(player: java.util.UUID): bpm.world.PresenceLink? =
        links.byPlayer(player)?.let { link(it.name) as? bpm.world.PresenceLink }

    override fun warnOnce(key: String, message: String) {
        if (warned.add(key)) log(LogLevel.WARN, message)
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
        val resolved = link(name) ?: return null
        val l = resolved.link
        // A person carries their own end about with them; everything else stays where it was put.
        val entity = (resolved as? bpm.world.PresenceLink)?.player?.id ?: 0
        return EffectSender.Endpoint(l.pos, l.side?.get3DDataValue() ?: -1, entity)
    }

    /** To everyone who can see either end, or the controller, once each. */
    private fun sendEffect(p: bpm.net.EffectPayload) {
        val l = be.level as? ServerLevel ?: return
        val players = LinkedHashSet<net.minecraft.server.level.ServerPlayer>()
        for (pos in listOf(p.controller, p.origin, p.target)) players += l.chunkSource.chunkMap.getPlayers(net.minecraft.world.level.ChunkPos(pos), false)
        for (player in players) bpm.platform.net.Net.sendToPlayer(player, p)
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
        // Panels nothing refreshed this second come down, the way a monitor's screen does.
        for (uuid in hud.expired(manager.clock.ticks)) clearPanel(uuid)
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
    /**
     * The last fiber error, if any.
     *
     * `ScriptRuntime.lastError` is sticky for the life of a run and its setter is private, so a caller that
     * acts on it must remember which error it has already handled — reading it every tick turns one past
     * error into a permanent verdict. See `ControllerBlockEntity.tickRuntime`.
     */
    val lastError: String? get() = runtime.lastError

    val phaseName: String get() = runtime.phase.name

    private companion object {
        /** Unread presses kept per key — enough for a fast double-tap, not a queue that grows unwatched. */
        const val MAX_PENDING = 8
    }
}
