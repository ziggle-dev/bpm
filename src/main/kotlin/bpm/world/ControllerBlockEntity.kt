package bpm.world

import bpm.Bpm
import bpm.library.BpmLibrary
import bpm.runtime.ControllerRuntime
import bpm.runtime.RuntimeManager
import dev.ziggle.vscript.runtime.EditorDoc
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import bpm.platform.ports.Droplets
import bpm.platform.ports.EnergyCell
import bpm.platform.ports.MultiTank
import bpm.platform.ports.SlotStore
import software.bernie.geckolib.animatable.GeoBlockEntity
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache
import software.bernie.geckolib.animation.AnimatableManager
import software.bernie.geckolib.animation.AnimationController
import software.bernie.geckolib.animation.PlayState
import software.bernie.geckolib.animation.RawAnimation
import software.bernie.geckolib.util.GeckoLibUtil
import java.util.UUID

/**
 * A controller's saved state and its running program.
 *
 * Saved: the bound document ([docId]) and the version of it that last ran, the [links] table, the script's
 * files ([scriptData], see `NbtFileStore`), the redstone it emits, a nine-slot buffer (`items.*` know it as
 * the link `"self"`; `breakBlock` drops land here) and the flags. Not saved: the [runtime], which is rebuilt
 * from the library whenever the block loads with a document and [enabled].
 *
 * There is no ticker: the entity registers with the [RuntimeManager] in [onLoad] and the manager ticks every
 * controller from one place with one budget. Status is a block-state property ([ControllerBlock.STATUS]) so
 * the client never needs entity data for the model or the light.
 */
class ControllerBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(ModBlockEntities.CONTROLLER.get(), pos, state), GeoBlockEntity, bpm.session.Deployable {

    var docId: UUID? = null
        private set
    var docVersion: Int = 0
    var runningVersion: Int = 0
        private set
    override var enabled: Boolean = true
        private set
    var debugBuild: Boolean = true
    var lastError: String? = null
        private set

    /** The core it was built around: how far it links, and how many links it may hold. */
    var coreTier: CoreTier = CoreTier.STABLE

    /** How far this controller's links may reach, in blocks — the core decides; infinite for a Coherent one. */
    val linkRange: Double get() = coreTier.linkRange

    /** How many links it may hold, and how many of those may be people. */
    val maxLinks: Int get() = coreTier.maxLinks
    val maxPlayerLinks: Int get() = coreTier.maxPlayerLinks

    val links = LinkTable { LinkCaps(coreTier.maxLinks, coreTier.maxPlayerLinks) }
    var scriptData: CompoundTag = CompoundTag()
        private set
    private val signals = IntArray(6)

    /** Breakpoints by node id (armed or not); applied to every run of the program. */
    val breakpoints = LinkedHashMap<Int, Boolean>()

    val inventory = SlotStore(ControllerStores.ITEM_SLOTS) { setChanged() }
    val tanks = MultiTank(ControllerStores.TANKS, Droplets.ofMb(ControllerStores.TANK_MB)) { setChanged() }
    val energy = EnergyCell(ControllerStores.ENERGY_FE.toLong(), ControllerStores.ENERGY_RATE.toLong()) { setChanged() }

    var runtime: ControllerRuntime? = null
        private set
    private var faulted = false
    private var overBudgetTicks = 0

    /** The last fiber error already acted on, so a sticky one is not re-judged every tick. */
    private var handledError: String? = null

    private val animCache: AnimatableInstanceCache = GeckoLibUtil.createInstanceCache(this)

    /** A per-block Molang phase so neighbouring controllers never spin in step. */
    val animPhase: Double = ((pos.hashCode() and 0xffff) * 360.0 / 0x10000)

    // ---- redstone ---------------------------------------------------------------------------------------

    fun signal(side: Direction): Int = signals[side.get3DDataValue()]

    fun setSignal(side: Direction, strength: Int) {
        val i = side.get3DDataValue()
        val v = strength.coerceIn(0, 15)
        if (signals[i] == v) return
        signals[i] = v
        setChanged()
        level?.let { l ->
            l.updateNeighborsAt(worldPosition, blockState.block)
            l.updateNeighborsAt(worldPosition.relative(side), blockState.block)
        }
    }

    private fun clearSignals() {
        if (signals.all { it == 0 }) return
        signals.fill(0)
        setChanged()
        level?.let { l ->
            l.updateNeighborsAt(worldPosition, blockState.block)
            for (d in Direction.entries) l.updateNeighborsAt(worldPosition.relative(d), blockState.block)
        }
    }

    // ---- nbt --------------------------------------------------------------------------------------------

    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        docId?.let { tag.putUUID("doc", it) }
        tag.putInt("docVersion", docVersion)
        tag.putInt("runningVersion", runningVersion)
        tag.putBoolean("enabled", enabled)
        tag.putBoolean("debug", debugBuild)
        tag.putString("coreTier", coreTier.key)
        tag.put("links", links.save())
        tag.put("data", scriptData.copy())
        tag.putIntArray("signals", signals.copyOf())
        tag.put("inventory", inventory.save(registries))
        tag.put("tanks", tanks.save())
        tag.put("energy", energy.save())
        lastError?.let { tag.putString("lastError", it) }
        tag.put("breakpoints", net.minecraft.nbt.ListTag().also { list -> breakpoints.forEach { (id, on) -> list.add(CompoundTag().also { it.putInt("node", id); it.putBoolean("on", on) }) } })
    }

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        docId = if (tag.hasUUID("doc")) tag.getUUID("doc") else null
        docVersion = tag.getInt("docVersion")
        runningVersion = tag.getInt("runningVersion")
        enabled = !tag.contains("enabled") || tag.getBoolean("enabled")
        debugBuild = !tag.contains("debug") || tag.getBoolean("debug")
        coreTier = CoreTier.byKey(tag.getString("coreTier"))
        links.load(tag.getList("links", Tag.TAG_COMPOUND.toInt()))
        scriptData = tag.getCompound("data")
        val s = tag.getIntArray("signals")
        if (s.size == 6) s.copyInto(signals) else signals.fill(0)
        if (tag.contains("inventory")) inventory.load(registries, tag.getList("inventory", Tag.TAG_COMPOUND.toInt()))
        if (tag.contains("tanks")) tanks.load(tag.getList("tanks", Tag.TAG_COMPOUND.toInt()))
        tag.get("energy")?.let { energy.load(it) }
        lastError = if (tag.contains("lastError")) tag.getString("lastError") else null
        breakpoints.clear()
        val bps = tag.getList("breakpoints", Tag.TAG_COMPOUND.toInt())
        for (i in 0 until bps.size) bps.getCompound(i).let { breakpoints[it.getInt("node")] = it.getBoolean("on") }
    }

    /** Arms, disarms or removes a breakpoint, now and for every later run. */
    fun setBreakpoint(nodeId: Int, enabled: Boolean, remove: Boolean) {
        if (remove) breakpoints.remove(nodeId) else breakpoints[nodeId] = enabled
        runtime?.runtime?.breakpoints?.let { if (remove) it.remove(nodeId) else it.add(nodeId, enabled) }
        setChanged()
    }

    /** What the client needs: the link table (wand HUD, editor) and the binding. */
    override fun applyImplicitComponents(input: DataComponentInput) {
        super.applyImplicitComponents(input)
        coreTier = CoreTier.byKey(input.get(ModComponents.CORE_TIER.get()))
    }

    override fun collectImplicitComponents(builder: net.minecraft.core.component.DataComponentMap.Builder) {
        super.collectImplicitComponents(builder)
        builder.set(ModComponents.CORE_TIER.get(), coreTier.key)
    }

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag = CompoundTag().also { tag ->
        tag.putString("coreTier", coreTier.key)
        docId?.let { tag.putUUID("doc", it) }
        tag.put("links", links.save())
        tag.putBoolean("enabled", enabled)
        lastError?.let { tag.putString("lastError", it) }
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> = ClientboundBlockEntityDataPacket.create(this)

    private fun sync() {
        val l = level ?: return
        if (!l.isClientSide) l.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_CLIENTS)
    }

    // ---- lifecycle --------------------------------------------------------------------------------------

    /**
     * Vanilla's `setLevel`, not NeoForge's `onLoad`.
     *
     * The two fire at the same moment for our purposes, and only one of them exists on both loaders.
     * The ordering that matters is that this runs AFTER the NBT: a chunk restores a block entity with
     * `loadStatic`, which reads the tag, and only then hands it to the chunk, which is what calls
     * `setLevel`. So `enabled` and `docId` are already the saved ones by the time they are read here —
     * which they would not be if this had been hung on the constructor.
     */
    override fun setLevel(level: Level) {
        super.setLevel(level)
        if (level is ServerLevel) {
            RuntimeManager.register(this)
            if (enabled && docId != null) RuntimeManager.queueRestart(this)
        }
    }

    override fun setRemoved() {
        shutdown()
        super.setRemoved()
    }

    /*
     * There is no `onChunkUnloaded` override any more, and none is needed. NeoForge added that method to
     * tell "the chunk went away" apart from "the block was broken", but this class did the same thing in
     * both cases, and vanilla already calls `setRemoved` on every block entity in a chunk it unloads
     * (`LevelChunk.clearAllBlockEntities`). So the override above covers both, on both loaders.
     */

    private fun shutdown() {
        if (level !is ServerLevel) return
        runtime?.let { rt ->
            runCatching { rt.stop() }.onFailure { Bpm.LOGGER.warn("controller {}: stop failed: {}", worldPosition.toShortString(), it.toString()) }
        }
        runtime = null
        RuntimeManager.unregister(this)
    }

    /** Builds the runtime from the library and starts it; false (with [lastError] set) when it could not. */
    fun startRuntime(): Boolean {
        val l = level as? ServerLevel ?: return false
        stopRuntime()
        val id = docId ?: return false
        val lib = BpmLibrary.get(l.server)
        val record = lib[id] ?: return fault("the bound document no longer exists")
        val graph = lib.graph(id) ?: return fault("document '${record.name}' could not be read")
        val rt = ControllerRuntime(this, RuntimeManager)
        val error = runCatching { rt.start(EditorDoc(graph), debugBuild, resume = true) }
            .getOrElse { "could not start: $it" }
        if (error != null) {
            runCatching { rt.stop() }
            return fault(error)
        }
        for ((id, on) in breakpoints) rt.runtime.breakpoints.add(id, on)
        runtime = rt
        runningVersion = record.version
        docVersion = record.version
        lastError = null
        faulted = false
        overBudgetTicks = 0
        handledError = null
        setChanged()
        updateStatus()
        return true
    }

    fun stopRuntime() {
        runtime?.let { rt ->
            // A graph that is not running has no business still being on someone's screen.
            runCatching { rt.clearAllPanels() }.onFailure { Bpm.LOGGER.warn("controller {}: panels not cleared: {}", worldPosition.toShortString(), it.toString()) }
            runCatching { rt.clearKeyWatch() }.onFailure { Bpm.LOGGER.warn("controller {}: key watch not cleared: {}", worldPosition.toShortString(), it.toString()) }
            runCatching { rt.stop() }.onFailure { Bpm.LOGGER.warn("controller {}: stop failed: {}", worldPosition.toShortString(), it.toString()) }
            runtime = null
            clearSignals()
            setChanged()
        }
        updateStatus()
    }

    /** The server is going down: ask for sleep and run a bounded drain so `on sleep` gets to write its state. */
    fun drainForShutdown() {
        val rt = runtime ?: return
        runCatching {
            rt.requestSleep("server stopping", 0)
            var passes = 0
            while (passes++ < DRAIN_PASSES && rt.isRunning && !rt.isAsleep) {
                RuntimeManager.clock.ticks++
                rt.tickOnce(DRAIN_BUDGET_NANOS)
            }
        }.onFailure { Bpm.LOGGER.warn("controller {}: drain failed: {}", worldPosition.toShortString(), it.toString()) }
        runCatching { rt.stop() }
        runtime = null
        setChanged()
    }

    /** One tick of the program, from the [RuntimeManager]. */
    fun tickRuntime(budgetNanos: Long, hardLimitNanos: Long) {
        val rt = runtime ?: return
        try {
            rt.tickOnce(budgetNanos)
        } catch (t: Throwable) {
            fault("crashed: $t")
            Bpm.LOGGER.error("controller {} crashed", worldPosition.toShortString(), t)
            return
        }
        rt.lastError?.takeIf { it != handledError }?.let { err ->
            // Handle each error ONCE. `ScriptRuntime.lastError` is sticky for the life of a run — it is
            // cleared only when a run starts, and its setter is private — so reading it every tick meant one
            // error, ever, faulted the controller on that tick and on every tick after it. That is why a
            // graph that hiccuped once stayed dead until the world was reloaded, which is the only thing
            // that starts a fresh run.
            handledError = err
            // And fault only when the program actually cannot go on. A fiber that died is not the same as a
            // program that stopped: `on start` failing, or one branch throwing, leaves the tick loop running,
            // and killing the whole controller for it loses every other thing the graph was doing.
            if (!rt.isRunning && !rt.isAsleep) {
                fault(err)
                return
            }
            // Still running: say so where the player will see it, and let it carry on.
            lastError = err
            Bpm.LOGGER.warn("controller {}: {} (still running)", worldPosition.toShortString(), err)
            sync()
        }
        if (rt.lastTickNanos > hardLimitNanos) {
            if (++overBudgetTicks >= 3) {
                fault("over the ${hardLimitNanos / 1_000_000} ms limit for three ticks in a row")
                return
            }
        } else {
            overBudgetTicks = 0
        }
        if (!rt.isRunning && !rt.isAsleep) {
            // Nothing left to run: no tick handlers and every start fiber finished. Done, not failed — but
            // say so. A controller that simply goes quiet, with its monitors frozen on whatever they last
            // showed, is indistinguishable from a broken one until someone reads a log.
            Bpm.LOGGER.info("controller {}: nothing left to run, stopping", worldPosition.toShortString())
            runCatching { rt.stop() }
            runtime = null
            setChanged()
        }
        updateStatus()
    }

    private fun fault(message: String): Boolean {
        lastError = message
        faulted = true
        runtime?.let { runCatching { it.stop() } }
        runtime = null
        clearSignals()
        setChanged()
        updateStatus()
        sync()
        Bpm.LOGGER.warn("controller {}: {}", worldPosition.toShortString(), message)
        return false
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        faulted = false
        if (value) {
            if (docId != null) RuntimeManager.queueRestart(this)
        } else {
            stopRuntime()
        }
        setChanged()
        updateStatus()
        sync()
    }

    /** Binds (or with null unbinds) a document; a bound, enabled controller starts on the next tick. */
    fun bind(id: UUID?) {
        docId = id
        faulted = false
        lastError = null
        if (id != null && enabled) RuntimeManager.queueRestart(this) else stopRuntime()
        setChanged()
        updateStatus()
        sync()
    }

    /** This controller's own graph, created on first use: one document per controller, never shared. */
    fun ensureGraph(owner: UUID?): UUID {
        val l = level as ServerLevel
        val lib = BpmLibrary.get(l.server)
        docId?.let { if (lib[it] != null) return it }
        val name = bpm.library.Documents.controllerName(worldPosition.x, worldPosition.y, worldPosition.z)
        val record = lib.create(name, bpm.library.Documents.blankController(name), owner, isLibrary = false)
        bind(record.id)
        return record.id
    }

    override fun requestRestart() {
        faulted = false
        lastError = null
        if (docId != null && enabled) RuntimeManager.queueRestart(this)
    }

    val status: ControllerStatus
        get() {
            val rt = runtime
            return when {
                faulted -> ControllerStatus.ERROR
                rt == null -> ControllerStatus.IDLE
                rt.isAsleep -> ControllerStatus.ASLEEP
                else -> ControllerStatus.RUNNING
            }
        }

    private fun updateStatus() {
        val l = level as? ServerLevel ?: return
        val s = status
        val state = blockState
        if (state.hasProperty(ControllerBlock.STATUS) && state.getValue(ControllerBlock.STATUS) != s) {
            l.setBlock(worldPosition, state.setValue(ControllerBlock.STATUS, s), Block.UPDATE_CLIENTS)
        }
    }

    fun describe(): String {
        val l = level as? ServerLevel
        val doc = docId?.let { id -> l?.let { BpmLibrary.get(it.server)[id]?.name } ?: id.toString() } ?: "no document"
        val rt = runtime
        val run = when {
            rt != null -> "${rt.phaseName.lowercase()}, ${rt.runtime.fibers.size} fibers, ${rt.jobs.size} jobs, ${rt.transfers} items moved"
            faulted -> "error: $lastError"
            else -> "stopped"
        }
        return "$doc · ${if (enabled) "enabled" else "disabled"} · $run · ${links.all.size} links"
    }

    // ---- geckolib ---------------------------------------------------------------------------------------

    override fun registerControllers(controllers: AnimatableManager.ControllerRegistrar) {
        controllers.add(
            AnimationController(this, "status", 5) { state ->
                val on = blockState.takeIf { it.hasProperty(ControllerBlock.STATUS) }?.getValue(ControllerBlock.STATUS)?.isOn ?: false
                state.setAndContinue(if (on) IDLE else OFF)
                PlayState.CONTINUE
            }.triggerableAnim("powerup", POWERUP).triggerableAnim("powerdown", POWERDOWN),
        )
        controllers.add(
            AnimationController(this, "overlay", 0) { PlayState.STOP }
                .triggerableAnim("open", OPEN).triggerableAnim("close", CLOSE),
        )
    }

    override fun getAnimatableInstanceCache(): AnimatableInstanceCache = animCache

    companion object {
        private const val DRAIN_PASSES = 40
        private const val DRAIN_BUDGET_NANOS = 3_000_000L

        val IDLE: RawAnimation = RawAnimation.begin().thenLoop("animation.quantum_controller.idle")
        val OFF: RawAnimation = RawAnimation.begin().thenLoop("animation.quantum_controller.off")
        val POWERUP: RawAnimation = RawAnimation.begin().thenPlay("animation.quantum_controller.powerup")
        val POWERDOWN: RawAnimation = RawAnimation.begin().thenPlayAndHold("animation.quantum_controller.powerdown")
        val OPEN: RawAnimation = RawAnimation.begin().thenPlay("animation.quantum_controller.open")
        val CLOSE: RawAnimation = RawAnimation.begin().thenPlay("animation.quantum_controller.close")
    }
}
