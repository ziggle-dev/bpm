package bpm.chamber

import bpm.platform.store.PlayerStore
import bpm.platform.events.BpmEvents
import bpm.Bpm
import bpm.BpmConfig
import bpm.BpmConfig.orDefault
import bpm.world.ContentBlocks
import bpm.world.DeviceBlocks
import bpm.world.ModAttachments
import bpm.world.devices.PedestalBlockEntity
import bpm.world.devices.PedestalHooks
import bpm.world.devices.PhaseBlock
import bpm.world.devices.PhaseBlockEntity
import bpm.world.devices.SpikeBlockEntity
import bpm.world.devices.TrapMode
import bpm.world.devices.TurretBlockEntity
import bpm.world.devices.VentBlockEntity
import bpm.world.entity.QuantumWardenEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import bpm.platform.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Containers
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.storage.loot.LootParams
import net.minecraft.world.level.storage.loot.LootTable
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets
import net.minecraft.world.phys.AABB
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * The fight around a chamber's pedestal: waking the Warden, the room's part in it — traps on their cycles,
 * the coherence crystals that weaken the cage when broken, the turret consoles a player can stand on to
 * turn a turret on the Warden, the surges that come every so often (vent storms, spike waves, blackouts,
 * a phasing floor) — the core coming back and being claimed (with its quality roll and the pity extra),
 * and the slot's timers: a fight nobody is in goes dormant again, a claimed room resets.
 */
object ChamberFight {
    private val CORE_QUALITY: ResourceKey<LootTable> = ResourceKey.create(Registries.LOOT_TABLE, ResourceLocation.fromNamespaceAndPath(Bpm.ID, "gameplay/core_quality"))
    private val CORE_QUALITY_FAST: ResourceKey<LootTable> = ResourceKey.create(Registries.LOOT_TABLE, ResourceLocation.fromNamespaceAndPath(Bpm.ID, "gameplay/core_quality_fast"))
    private val PITY: ResourceKey<LootTable> = ResourceKey.create(Registries.LOOT_TABLE, ResourceLocation.fromNamespaceAndPath(Bpm.ID, "gameplay/warden_pity"))

    private class PendingSpawn(val level: ServerLevel, val pedestal: BlockPos, val owner: UUID, var at: Long)

    /** What a fight in progress remembers between ticks — nothing here needs saving. */
    private class FightState {
        val standing = HashMap<UUID, Pair<BlockPos, Int>>()
        val onAlloy = HashMap<UUID, Int>()
        var surgeIn = 700
        var blackoutUntil = 0L
        var phaseFloor: List<BlockPos> = emptyList()
        var phaseUntil = 0L
        val spikeWave = ArrayDeque<Pair<Long, BlockPos>>()

        /** Where each turret (by its index in the layout) stands now — they hop between perches as the fight goes. */
        val turretAt = HashMap<Int, BlockPos>()
        val hopIn = IntArray(4) { HOP_FIRST + kotlin.random.Random.nextInt(HOP_JITTER) }
    }

    enum class Surge { VENT_STORM, SPIKE_WAVE, BLACKOUT, PHASE_FLOOR }

    private val pending = ArrayList<PendingSpawn>()
    private val fights = HashMap<UUID, FightState>()

    const val HIJACK_STAND_TICKS = 40
    const val DAIS_TICKS = 80
    /** The upper tiers: within this of the pedestal (and three below it) is where the fury and the alloy timer apply. */
    const val DAIS_REACH = 3

    /** The whole island — the pyramid and its skirt — for the turrets' island rule. */
    const val ISLAND_REACH = ChamberBuilder.DAIS_BASE + 1
    const val HIJACK_TICKS = 400
    const val BLACKOUT_TICKS = 140
    const val PHASE_FLOOR_TICKS = 300
    const val CRYSTAL_CAGE_STEP = 0.15f
    const val EMPTY_TICKS = 40L
    const val HOP_FIRST = 300
    const val HOP_EVERY = 500
    const val HOP_JITTER = 300
    private const val FAST_STAGE3_TICKS = 1200L

    fun install() {
        BpmEvents.serverTickEnd.listen(::tick)
        PedestalHooks.onUse = ::onPedestalUse
        PedestalHooks.awaken = ::awaken
        PedestalHooks.claim = ::claim
    }

    private fun say(player: Player?, text: String) = player?.displayClientMessage(Component.literal("[bpm] $text"), true)

    /** Tells everyone in the room. */
    private fun announce(level: ServerLevel, slot: ChamberSlot, text: String) {
        for (p in level.getEntitiesOfClass(ServerPlayer::class.java, slotBox(slot))) say(p, text)
    }

    private fun slotOf(be: PedestalBlockEntity): ChamberSlot? {
        val owner = be.slotOwner ?: return null
        val server = be.level?.server ?: return null
        return Chambers.get(server).slots[owner]
    }

    private fun slotOf(w: QuantumWardenEntity): ChamberSlot? {
        val owner = w.slotOwner ?: return null
        val server = w.level().server ?: return null
        return Chambers.get(server).slots[owner]
    }

    private fun wardenOf(level: ServerLevel, slot: ChamberSlot): QuantumWardenEntity? =
        level.getEntitiesOfClass(QuantumWardenEntity::class.java, slotBox(slot)).firstOrNull { it.isAlive }

    // ---- the pedestal ---------------------------------------------------------------------------------------

    private fun onPedestalUse(be: PedestalBlockEntity, player: Player): Boolean {
        val slot = slotOf(be) ?: return false
        when (slot.state) {
            SlotState.DORMANT -> if (awaken(be)) say(player, "the core rises — the Warden wakes") else say(player, "the socket is empty")
            SlotState.FIGHTING -> say(player, "the Warden is awake")
            SlotState.CLEARED -> {
                val core = claim(be, player)
                if (core == null) say(player, "nothing to claim") else say(player, "claimed: ${core.hoverName.string}")
            }
        }
        return true
    }

    /** Wakes the Warden: the pedestal's animation first, the entity at its 1.5 s mark. */
    fun awaken(be: PedestalBlockEntity): Boolean {
        val slot = slotOf(be) ?: return false
        val level = be.level as? ServerLevel ?: return false
        if (slot.state != SlotState.DORMANT || !be.hasCore) return false
        slot.state = SlotState.FIGHTING
        slot.stage3StartedAt = 0
        Chambers.get(level.server).setDirty()
        be.triggerAnim("main", "awaken")
        pending += PendingSpawn(level, be.blockPos, slot.owner, level.gameTime + 30)
        fights[slot.owner] = FightState()
        return true
    }

    private fun spawn(p: PendingSpawn) {
        val be = p.level.getBlockEntity(p.pedestal) as? PedestalBlockEntity ?: return
        be.setHasCore(false)
        val w = QuantumWardenEntity.spawnAt(p.level, p.pedestal, p.owner)
        onStage(w, 1)
    }

    // ---- what the room does per stage -----------------------------------------------------------------------

    /** The traps by stage: spikes, vents and turrets run from the start; stage 3 adds proximity spikes and cycling bridges. */
    fun onStage(w: QuantumWardenEntity, stage: Int) {
        val level = w.level() as? ServerLevel ?: return
        val slot = slotOf(w) ?: return
        if (stage == 3) {
            slot.stage3StartedAt = level.gameTime
            Chambers.get(level.server).setDirty()
        }
        forEachDevice(level, slot) { be ->
            when (be) {
                is SpikeBlockEntity -> {
                    be.mode = if (stage == 3) TrapMode.LINKED else TrapMode.CYCLE
                    be.proximity = stage == 3
                    be.sync()
                }
                is VentBlockEntity -> {
                    be.mode = TrapMode.CYCLE
                    be.sync()
                }
                is TurretBlockEntity -> {
                    be.active = true
                    be.sync()
                }
                is PhaseBlockEntity -> if (isBridge(slot, be.blockPos)) {
                    be.mode = if (stage == 3) TrapMode.CYCLE else TrapMode.LINKED
                    if (stage != 3) be.requestSolid(true)
                    be.sync()
                }
            }
        }
        if (stage >= 2) announce(level, slot, if (stage == 2) "the Warden raises its plates" else "the Warden falters — the room turns on you")
    }

    private fun standDown(level: ServerLevel, slot: ChamberSlot) {
        forEachDevice(level, slot) { be ->
            when (be) {
                is SpikeBlockEntity -> { be.mode = TrapMode.CYCLE; be.proximity = false; be.sync() }
                is VentBlockEntity -> { be.mode = TrapMode.CYCLE; be.sync() }
                is TurretBlockEntity -> { be.active = true; be.hijack(null, 0); be.sync() }
                is PhaseBlockEntity -> if (isBridge(slot, be.blockPos)) { be.mode = TrapMode.LINKED; be.requestSolid(true); be.sync() }
            }
        }
        fights.remove(slot.owner)?.let { restoreRoom(level, slot, it) }
    }

    private fun isBridge(slot: ChamberSlot, pos: BlockPos): Boolean {
        val rel = pos.subtract(slot.origin)
        val dx = rel.x - ChamberBuilder.CX
        val dz = rel.z - ChamberBuilder.CZ
        val d = sqrt((dx * dx + dz * dz).toDouble())
        return rel.y == 0 && d >= 7.5 && d < 9.5
    }

    private fun forEachDevice(level: ServerLevel, slot: ChamberSlot, body: (net.minecraft.world.level.block.entity.BlockEntity) -> Unit) {
        val o = slot.origin
        for (x in 0..ChamberBuilder.SIZE) for (z in 0..ChamberBuilder.SIZE) for (y in -1..8) {
            val be = level.getBlockEntity(o.offset(x, y, z)) ?: continue
            body(be)
        }
    }

    // ---- the crystals ----------------------------------------------------------------------------------------

    /** The player standing on the dais steps or beside the pedestal — the one the Warden turns on. */
    fun daisIntruder(w: QuantumWardenEntity): Player? {
        val level = w.level() as? ServerLevel ?: return null
        val slot = slotOf(w) ?: return null
        val p = slot.pedestal
        val box = AABB(p.x - DAIS_REACH - 0.5, p.y - 3.5, p.z - DAIS_REACH - 0.5, p.x + DAIS_REACH + 1.5, p.y + 2.5, p.z + DAIS_REACH + 1.5)
        return level.getEntitiesOfClass(ServerPlayer::class.java, box) { !it.isCreative && !it.isSpectator }.minByOrNull { it.distanceToSqr(w) }
    }

    /** Whether [pos] is on the centre island — anywhere on the dais pyramid, skirt to summit — of the room it is in. */
    fun onIsland(level: ServerLevel, pos: BlockPos): Boolean {
        val slot = Chambers.get(level.server).slotAt(pos) ?: return false
        val p = slot.pedestal
        return kotlin.math.abs(pos.x - p.x) <= ISLAND_REACH && kotlin.math.abs(pos.z - p.z) <= ISLAND_REACH && pos.y in (p.y - ChamberBuilder.DAIS_HEIGHT)..(p.y + 2)
    }

    /** Alloy underfoot, in any of its shapes — the block, its stairs, its slab. */
    private fun isAlloy(state: net.minecraft.world.level.block.state.BlockState): Boolean =
        state.`is`(ContentBlocks.QUANTUM_ALLOY_BLOCK.get()) || state.`is`(ContentBlocks.QUANTUM_ALLOY_STAIRS.get()) || state.`is`(ContentBlocks.QUANTUM_ALLOY_SLAB.get())

    /** Whether [pos] is one of a room's coherence crystals — breakable even while the room is locked. */
    fun isCrystal(slot: ChamberSlot, pos: BlockPos): Boolean {
        val layout = slot.layout ?: return false
        val rel = pos.subtract(slot.origin)
        return layout.crystals.any { it == rel }
    }

    /** One fewer crystal: the cage takes more, the Warden reels, the nearest vents erupt. */
    fun onCrystalBroken(level: ServerLevel, slot: ChamberSlot, pos: BlockPos) {
        slot.crystalsBroken = (slot.crystalsBroken + 1).coerceAtMost(4)
        Chambers.get(level.server).setDirty()
        wardenOf(level, slot)?.stagger()
        val vents = slot.layout?.vents?.map { slot.origin.offset(it.x, it.y, it.z) }?.sortedBy { it.distSqr(pos) }?.take(2) ?: emptyList()
        for (v in vents) (level.getBlockEntity(v) as? VentBlockEntity)?.fire()
        announce(level, slot, "a coherence crystal shatters — the cage weakens (${slot.crystalsBroken} of 4)")
    }

    /** Damage the closed cage lets through: the configured quarter, plus a step per broken crystal. */
    fun cageMultiplier(w: QuantumWardenEntity): Float {
        val base = BpmConfig.WARDEN_CAGE_MULTIPLIER.orDefault().toFloat()
        val slot = slotOf(w) ?: return base
        return (base + CRYSTAL_CAGE_STEP * slot.crystalsBroken).coerceAtMost(0.85f)
    }

    /** The plates only grow back while a crystal still stands. */
    fun platesRegenerate(w: QuantumWardenEntity): Boolean = (slotOf(w)?.crystalsBroken ?: 0) < 4

    // ---- the consoles and the surges -------------------------------------------------------------------------

    private fun tickFight(level: ServerLevel, slot: ChamberSlot, state: FightState, now: Long) {
        val players = level.getEntitiesOfClass(ServerPlayer::class.java, slotBox(slot)) { !it.isSpectator }
        val layout = slot.layout
        if (layout != null && now % 5 == 0L) consoles(level, slot, state, players, layout)
        if (layout != null) dais(level, slot, state, players, layout)
        if (layout != null && players.isNotEmpty()) hops(level, slot, state, layout)

        // A surge now and then, quicker the further the fight has gone.
        if (players.isNotEmpty() && --state.surgeIn <= 0) {
            val w = wardenOf(level, slot)
            val stage = w?.stage ?: 1
            surge(level, slot, state, stage, now)
            state.surgeIn = when (stage) { 1 -> 700 + level.random.nextInt(300); 2 -> 500 + level.random.nextInt(200); else -> 350 + level.random.nextInt(150) }
        }
        // Surges winding down.
        while (state.spikeWave.isNotEmpty() && state.spikeWave.first().first <= now) {
            val (_, p) = state.spikeWave.removeFirst()
            (level.getBlockEntity(p) as? SpikeBlockEntity)?.fire()
        }
        if (state.blackoutUntil > 0 && now >= state.blackoutUntil) {
            state.blackoutUntil = 0
            lights(level, slot, on = true)
        }
        if (state.phaseUntil > 0 && now >= state.phaseUntil) {
            state.phaseUntil = 0
            val floor = ContentBlocks.CHAMBER_FLOOR.get().defaultBlockState()
            for (p in state.phaseFloor) level.setBlock(p, floor, Block.UPDATE_ALL)
            state.phaseFloor = emptyList()
        }
    }

    /** Nobody camps the upper dais: four seconds on its alloy (block, stairs or slab) and a vent takes you — the recharge must be a dash. */
    private fun dais(level: ServerLevel, slot: ChamberSlot, state: FightState, players: List<ServerPlayer>, layout: RoomLayout) {
        for (p in players) {
            if (p.isCreative) continue
            val on = p.onPos.y >= slot.pedestal.y - DAIS_REACH && isAlloy(level.getBlockState(p.onPos))
            val n = (state.onAlloy[p.uuid] ?: 0).let { if (on) it + 1 else (it - 2).coerceAtLeast(0) }
            state.onAlloy[p.uuid] = n
            if (n == DAIS_TICKS - 40) say(p, "the dais rejects you — move")
            if (n >= DAIS_TICKS && layout.vents.isNotEmpty() && !p.isOnPortalCooldown) {
                state.onAlloy[p.uuid] = 0
                val v = layout.vents[level.random.nextInt(layout.vents.size)]
                val to = ChamberBuilder.trapPos(slot.origin, v)
                level.sendParticles(net.minecraft.core.particles.ParticleTypes.PORTAL, p.x, p.y + 1.0, p.z, 30, 0.3, 0.6, 0.3, 0.3)
                p.teleportTo(to.x + 0.5, to.y + 0.25, to.z + 0.5)
                p.setPortalCooldown(VentBlockEntity.ARRIVAL_COOLDOWN)
                p.fallDistance = 0f
                level.playSound(null, to, net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT, net.minecraft.sounds.SoundSource.BLOCKS, 0.8f, 1.2f)
                say(p, "the dais throws you to a vent")
            }
        }
        state.onAlloy.keys.retainAll(players.map { it.uuid }.toSet())
    }

    /** Standing on a turret's console for two seconds turns that turret on the Warden for twenty. */
    private fun consoles(level: ServerLevel, slot: ChamberSlot, state: FightState, players: List<ServerPlayer>, layout: RoomLayout) {
        val seen = HashSet<UUID>()
        for (p in players) {
            val under = p.onPos
            val i = layout.consoles.indexOfFirst { slot.origin.offset(it.x, it.y, it.z) == under }
            if (i < 0) continue
            seen += p.uuid
            val console = slot.origin.offset(layout.consoles[i].x, layout.consoles[i].y, layout.consoles[i].z)
            val (at, ticks) = state.standing[p.uuid] ?: (console to 0)
            val n = if (at == console) ticks + 5 else 5
            state.standing[p.uuid] = console to n
            if (n >= HIJACK_STAND_TICKS) {
                state.standing[p.uuid] = console to -200
                val turret = level.getBlockEntity(turretPos(slot, state, layout, i)) as? TurretBlockEntity
                val w = wardenOf(level, slot)
                if (turret != null && w != null) {
                    turret.hijack(w, HIJACK_TICKS)
                    say(p, "turret hijacked — it turns on the Warden for ${HIJACK_TICKS / 20} s")
                }
            }
        }
        state.standing.keys.retainAll(seen)
    }

    /** Every so often a turret hops to another perch — a spare turret pillar's top, or a cover column that carries no light. */
    private fun hops(level: ServerLevel, slot: ChamberSlot, state: FightState, layout: RoomLayout) {
        for (i in layout.turrets.indices) {
            if (i >= state.hopIn.size || --state.hopIn[i] > 0) continue
            state.hopIn[i] = HOP_EVERY + level.random.nextInt(HOP_JITTER)
            val from = turretPos(slot, state, layout, i)
            val be = level.getBlockEntity(from) as? TurretBlockEntity ?: continue
            if (be.off || be.hijacked) continue
            val free = perches(slot, layout).filter { it != from && level.getBlockState(it).canBeReplaced() }
            if (free.isEmpty()) continue
            val to = free[level.random.nextInt(free.size)]
            if (TurretBlockEntity.hop(level, from, to) != null) state.turretAt[i] = to
        }
    }

    /** Where turret [i] of the layout stands now. */
    private fun turretPos(slot: ChamberSlot, state: FightState, layout: RoomLayout, i: Int): BlockPos =
        state.turretAt[i] ?: layout.turrets[i].first.let { slot.origin.offset(it.x, it.y, it.z) }

    /** Every place a turret can stand: the turret pillars' tops and the cover columns without a light cap. */
    private fun perches(slot: ChamberSlot, layout: RoomLayout): List<BlockPos> {
        val o = slot.origin
        val out = ArrayList<BlockPos>()
        for ((p, _) in layout.turrets) out += o.offset(p.x, p.y, p.z)
        for ((i, c) in layout.cover.withIndex()) if (i % 3 != 0) out += o.offset(c.first.x, c.second + 1, c.first.z)
        return out
    }

    private fun surge(level: ServerLevel, slot: ChamberSlot, state: FightState, stage: Int, now: Long) {
        val layout = slot.layout ?: return
        val choices = Surge.entries.filter { it != Surge.PHASE_FLOOR || stage >= 2 }
        when (choices[level.random.nextInt(choices.size)]) {
            Surge.VENT_STORM -> {
                for (v in layout.vents) (level.getBlockEntity(ChamberBuilder.trapPos(slot.origin, v)) as? VentBlockEntity)?.fire()
                announce(level, slot, "the vents surge")
            }
            Surge.SPIKE_WAVE -> {
                val o = slot.origin
                val sorted = layout.spikes.sortedBy { atan2((it.z - ChamberBuilder.CZ).toDouble(), (it.x - ChamberBuilder.CX).toDouble()) }
                sorted.forEachIndexed { k, s -> state.spikeWave.addLast((now + k * 4L) to ChamberBuilder.trapPos(o, s)) }
                announce(level, slot, "the floor ripples")
            }
            Surge.BLACKOUT -> {
                state.blackoutUntil = now + BLACKOUT_TICKS
                lights(level, slot, on = false)
                announce(level, slot, "the lights fail")
            }
            Surge.PHASE_FLOOR -> {
                val o = slot.origin
                val heading = level.random.nextDouble() * 360.0
                val cells = ArrayList<BlockPos>()
                for (x in 0..ChamberBuilder.SIZE) for (z in 0..ChamberBuilder.SIZE) {
                    val dx = x - ChamberBuilder.CX
                    val dz = z - ChamberBuilder.CZ
                    val d = sqrt((dx * dx + dz * dz).toDouble())
                    if (d < 10.0 || d >= 14.5) continue
                    val deg = Math.toDegrees(atan2(dz.toDouble(), dx.toDouble())).let { (it + 360) % 360 }
                    if (RoomLayout.angleDiff(deg, heading) > 22.0) continue
                    val p = o.offset(x, 0, z)
                    if (!level.getBlockState(p).`is`(ContentBlocks.CHAMBER_FLOOR.get())) continue
                    cells += p
                }
                val ghost = DeviceBlocks.PHASE_BLOCK.get().defaultBlockState().setValue(PhaseBlock.SOLID, true)
                for (p in cells) {
                    level.setBlock(p, ghost, Block.UPDATE_ALL)
                    (level.getBlockEntity(p) as? PhaseBlockEntity)?.let { it.mode = TrapMode.CYCLE; it.sync() }
                }
                state.phaseFloor = cells
                state.phaseUntil = now + PHASE_FLOOR_TICKS
                announce(level, slot, "the floor decoheres")
            }
        }
    }

    private fun lights(level: ServerLevel, slot: ChamberSlot, on: Boolean) {
        val layout = slot.layout ?: return
        val state = if (on) ContentBlocks.CHAMBER_LIGHT.get().defaultBlockState() else ContentBlocks.CHAMBER_WALL.get().defaultBlockState()
        for (p in layout.lights) level.setBlock(slot.origin.offset(p.x, p.y, p.z), state, Block.UPDATE_ALL)
        for ((i, c) in layout.cover.withIndex()) {
            if (i % 3 != 0) continue
            level.setBlock(slot.origin.offset(c.first.x, c.second + 1, c.first.z), state, Block.UPDATE_ALL)
        }
    }

    private fun restoreRoom(level: ServerLevel, slot: ChamberSlot, state: FightState) {
        if (state.blackoutUntil > 0) lights(level, slot, on = true)
        if (state.phaseFloor.isNotEmpty()) {
            val floor = ContentBlocks.CHAMBER_FLOOR.get().defaultBlockState()
            for (p in state.phaseFloor) level.setBlock(p, floor, Block.UPDATE_ALL)
        }
    }

    // ---- the end of a fight -------------------------------------------------------------------------------------

    /** The Warden went without dying (a command, a despawn rule): the core is back on the pedestal, the fight forgotten. */
    fun onWardenVanished(w: QuantumWardenEntity) {
        val level = w.level() as? ServerLevel ?: return
        w.returnCore()
        val slot = slotOf(w) ?: return
        if (slot.state != SlotState.FIGHTING) return
        slot.state = SlotState.DORMANT
        slot.stage3StartedAt = 0
        Chambers.get(level.server).setDirty()
        standDown(level, slot)
    }

    /** The room is being stamped afresh: whatever fight it held, and any Warden about to appear in it, is forgotten. */
    fun onReset(slot: ChamberSlot) {
        fights.remove(slot.owner)
        pending.removeIf { it.owner == slot.owner }
    }

    /** The Warden fell: the room unlocks, the traps stand down; the core returns on its own two seconds later. */
    fun onWardenDeath(w: QuantumWardenEntity, killedByPlayer: Boolean) {
        val level = w.level() as? ServerLevel ?: return
        val slot = slotOf(w) ?: return
        slot.state = SlotState.CLEARED
        slot.lastKillByPlayer = killedByPlayer
        slot.killedAt = level.gameTime
        Chambers.get(level.server).setDirty()
        standDown(level, slot)
        forEachDevice(level, slot) { be ->
            when (be) {
                is SpikeBlockEntity -> { be.mode = TrapMode.LINKED; be.sync() }
                is VentBlockEntity -> { be.mode = TrapMode.LINKED; be.sync() }
                is TurretBlockEntity -> { be.active = false; be.sync() }
            }
        }
        announce(level, slot, "the Warden collapses — the core returns to its socket")
    }

    /**
     * Hands the core over: its quality rolled by how the fight went (a fast stage 3 doubles the good odds;
     * a kill without a player's hand is always Stable), the pity extra for players, the reset timer started.
     */
    fun claim(be: PedestalBlockEntity, player: Player?): ItemStack? {
        val slot = slotOf(be) ?: return null
        val level = be.level as? ServerLevel ?: return null
        if (slot.state != SlotState.CLEARED || !be.hasCore) return null
        val fast = slot.lastKillByPlayer && slot.stage3StartedAt > 0 && slot.killedAt - slot.stage3StartedAt < FAST_STAGE3_TICKS
        val core = if (!slot.lastKillByPlayer) ItemStack(bpm.world.ContentItems.QUANTUM_CORE.get()) else roll(level, if (fast) CORE_QUALITY_FAST else CORE_QUALITY).firstOrNull() ?: ItemStack(bpm.world.ContentItems.QUANTUM_CORE.get())
        be.setHasCore(false)
        be.triggerAnim("main", "claim")
        slot.claimedAt = level.gameTime
        Chambers.get(level.server).setDirty()
        if (player is ServerPlayer) {
            give(player, core.copy())
            if (slot.lastKillByPlayer || BpmConfig.WARDEN_AUTOMATED_KILLS_CAN_ROLL.orDefault()) pity(level, player)
        }
        return core
    }

    /** The pity extra: each claim without it adds two percent, capped at sixteen; a hit resets it. */
    private fun pity(level: ServerLevel, player: ServerPlayer) {
        val pity = PlayerStore.get(player, ModAttachments.WARDEN_PITY)
        if (level.random.nextDouble() < pity / 100.0) {
            for (extra in roll(level, PITY)) give(player, extra)
            PlayerStore.set(player, ModAttachments.WARDEN_PITY, 0)
            say(player, "the Warden's remains give up something more")
        } else {
            PlayerStore.set(player, ModAttachments.WARDEN_PITY, (pity + 2).coerceAtMost(16))
        }
    }

    private fun roll(level: ServerLevel, key: ResourceKey<LootTable>): List<ItemStack> {
        val table = level.server.reloadableRegistries().getLootTable(key)
        val out = ArrayList<ItemStack>()
        table.getRandomItems(LootParams.Builder(level).create(LootContextParamSets.EMPTY)) { out += it }
        return out
    }

    private fun give(player: ServerPlayer, stack: ItemStack) {
        if (!player.inventory.add(stack)) Containers.dropItemStack(player.level(), player.x, player.y, player.z, stack)
    }

    // ---- the clock ----------------------------------------------------------------------------------------------

    /**
     * Every tick: spawns due and fights in progress. Every second: a room that has emptied — its players left,
     * logged out or died — ends its run (`chamber.resetOnLeave`); otherwise the old timers: a fight nobody is in
     * goes dormant, a claimed room resets.
     */
    private fun tick(server: MinecraftServer) {
        val now = server.overworld().gameTime
        if (pending.isNotEmpty()) {
            val it = pending.iterator()
            while (it.hasNext()) {
                val p = it.next()
                if (now >= p.at) {
                    it.remove()
                    spawn(p)
                }
            }
        }
        val chamber = ChamberDimension.level(server) ?: return
        val data = Chambers.get(server)
        for (slot in data.slots.values) {
            if (slot.state == SlotState.FIGHTING) fights[slot.owner]?.let { tickFight(chamber, slot, it, now) }
        }
        if (now % 20 != 0L) return
        for (slot in data.slots.values) {
            if (!slot.built) continue
            val inside = chamber.getEntitiesOfClass(ServerPlayer::class.java, slotBox(slot)) { it.isAlive && !it.isSpectator }
            if (inside.isNotEmpty()) {
                slot.lastVisit = now
                continue
            }
            if (slot.occupied && BpmConfig.CHAMBER_RESET_ON_LEAVE.orDefault() && now - slot.lastVisit >= EMPTY_TICKS) {
                Chambers.endRun(server, slot)
                continue
            }
            when (slot.state) {
                SlotState.FIGHTING -> if (now - slot.lastVisit >= BpmConfig.WARDEN_STATE_HOLD_MINUTES.orDefault() * 60L * 20L) goDormant(chamber, slot)
                SlotState.CLEARED -> if (slot.claimedAt > 0 && now - slot.lastVisit >= BpmConfig.CHAMBER_RESET_MINUTES.orDefault() * 60L * 20L) Chambers.reset(server, slot)
                else -> {}
            }
        }
    }

    /** The Warden goes back into the core: the entity leaves (which puts the core back), the fight forgotten. */
    private fun goDormant(chamber: ServerLevel, slot: ChamberSlot) {
        for (w in chamber.getEntitiesOfClass(QuantumWardenEntity::class.java, slotBox(slot))) w.discard()
        (chamber.getBlockEntity(slot.pedestal) as? PedestalBlockEntity)?.setHasCore(true)
        slot.state = SlotState.DORMANT
        slot.stage3StartedAt = 0
        Chambers.get(chamber.server).setDirty()
        standDown(chamber, slot)
    }

    fun slotBox(slot: ChamberSlot): AABB {
        val o = slot.origin
        return AABB(o.x.toDouble(), o.y - 4.0, o.z.toDouble(), o.x + ChamberBuilder.SIZE + 1.0, o.y + ChamberBuilder.CEILING + 1.0, o.z + ChamberBuilder.SIZE + 1.0)
    }
}
