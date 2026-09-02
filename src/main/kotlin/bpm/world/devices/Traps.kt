package bpm.world.devices

import bpm.BpmConfig
import bpm.BpmConfig.orDefault
import bpm.chamber.ChamberDimension
import bpm.world.BpmDamage
import bpm.world.DeviceBlockEntities
import com.mojang.math.Axis
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.HolderLookup
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.monster.Monster
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import org.joml.Vector3f
import bpm.platform.AnimatableManager
import bpm.platform.AnimationController
import bpm.platform.RawAnimation
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.sqrt
import bpm.platform.longOr
import bpm.platform.floatOr
import bpm.platform.stringOr
import bpm.platform.boolOr
import bpm.platform.compoundOr
import bpm.platform.intsOr

/*
 * The chamber's hazards as blocks — usable anywhere, which is why they have recipes.
 *
 * Every trap runs on the SERVER's clock and triggers its animations from there (`arm`, `extend`, `charge`,
 * `erupt`, `phase_out`, `phase_in`), so the damage window and the picture agree for every viewer however
 * late they loaded the chunk. The models' self-running `cycle` animations are not used for that reason.
 */

/** A floor trap: the spike plate and the vent share a block class and differ in their entity. */
class TrapBlock(
    properties: Properties,
    private val factory: (BlockPos, BlockState) -> DeviceBlockEntity,
    /** The plate you walk over — a few pixels high, so the trap sits on the floor rather than being a block of it. */
    private val shape: VoxelShape = PLATE,
) : Block(properties), EntityBlock {
    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = factory(pos, state)
    override fun getRenderShape(state: BlockState): RenderShape = bpm.platform.ANIMATED_BLOCK_SHAPE
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, ctx: CollisionContext): VoxelShape = shape

    override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? {
        if (level.isClientSide) return null
        return BlockEntityTicker { _, _, _, be -> (be as? DeviceBlockEntity)?.serverTick() }
    }

    override fun neighborChanged(state: BlockState, level: Level, pos: BlockPos, block: Block, fromPos: bpm.platform.NeighborSource, moving: Boolean) {
        super.neighborChanged(state, level, pos, block, fromPos, moving)
        if (!level.isClientSide) (level.getBlockEntity(pos) as? TrapBlockEntity)?.onRedstone(level.hasNeighborSignal(pos))
    }

    override fun stepOn(level: Level, pos: BlockPos, state: BlockState, entity: Entity) {
        super.stepOn(level, pos, state, entity)
        if (!level.isClientSide) (level.getBlockEntity(pos) as? VentBlockEntity)?.stepped(entity)
    }

    companion object {
        /** The spike plate: 2 px plate + 1 px studs. */
        val PLATE: VoxelShape = Block.box(0.0, 0.0, 0.0, 16.0, 3.0, 16.0)
        /** The vent: 3 px grate + the 1 px ring on it. */
        val GRATE: VoxelShape = Block.box(0.0, 0.0, 0.0, 16.0, 4.0, 16.0)
    }
}

/** How a trap decides when to go off: on its own timer, or only when a link (or redstone) tells it to. */
enum class TrapMode { CYCLE, LINKED }

/**
 * A trap with a timeline: [period] ticks long, [step] called with the tick; in [TrapMode.CYCLE] it loops,
 * in [TrapMode.LINKED] one [fire] runs it once from [fireFrom]. [phase] names where it is for scripts.
 */
abstract class TrapBlockEntity(type: BlockEntityType<*>, pos: BlockPos, state: BlockState, private val period: Int, private val fireFrom: Int) : DeviceBlockEntity(type, pos, state) {
    var mode: TrapMode = TrapMode.CYCLE
        set(value) {
            field = value
            t = 0
            firing = false
        }

    /** Goes off when something living stands within 1.5 blocks (the fight's stage-3 spikes). */
    var proximity: Boolean = false

    protected var t: Int = 0
    private var firing: Boolean = false
    private var wasPowered: Boolean = false

    /** Where the trap is in its sequence, for `trap.state`. */
    abstract val phase: String

    /** One shot, now — from the arming tell onward. */
    fun fire(): Boolean {
        if (firing) return false
        firing = true
        t = fireFrom - 1
        return true
    }

    val isFiring: Boolean get() = firing

    fun onRedstone(powered: Boolean) {
        if (powered && !wasPowered && mode == TrapMode.LINKED) fire()
        wasPowered = powered
    }

    override fun serverTick() {
        when {
            mode == TrapMode.CYCLE -> {
                t = (t + 1) % period
                step(t)
            }
            firing -> {
                t++
                step(t)
                if (t >= period - 1) {
                    firing = false
                    t = 0
                }
            }
            proximity && (level?.gameTime ?: 0L) % 5L == 0L -> {
                val l = level ?: return
                val near = l.getEntitiesOfClass(LivingEntity::class.java, AABB(worldPosition).inflate(1.5, 1.0, 1.5)) { it.isAlive && it !is Player || (it is Player && !it.isCreative && !it.isSpectator) }
                if (near.isNotEmpty()) fire()
            }
        }
    }

    protected abstract fun step(t: Int)

    /** Living things in the column above the plate, [height] blocks tall. */
    protected fun victims(height: Double): List<LivingEntity> {
        val l = level ?: return emptyList()
        val box = AABB(worldPosition).setMaxY(worldPosition.y + 1.0 + height)
        return l.getEntitiesOfClass(LivingEntity::class.java, box) { it.isAlive && !(it is Player && (it.isCreative || it.isSpectator)) }
    }

    override fun saveSynced(tag: CompoundTag, registries: HolderLookup.Provider) {
        tag.putString("mode", mode.name)
        tag.putBoolean("proximity", proximity)
    }

    override fun loadSynced(tag: CompoundTag, registries: HolderLookup.Provider) {
        mode = runCatching { TrapMode.valueOf(tag.stringOr("mode", "")) }.getOrDefault(TrapMode.CYCLE)
        proximity = tag.boolOr("proximity", false)
    }
}

/**
 * The spike plate: a 3 s cycle — the tell at 1.4 s, blades up at 1.8 s, damage while they hold (1.9–2.7 s),
 * down by 2.9 s. Each victim is hit once per extension.
 */
class SpikeBlockEntity(pos: BlockPos, state: BlockState) : TrapBlockEntity(DeviceBlockEntities.SPIKE.get(), pos, state, period = 60, fireFrom = 28) {
    private val hit = HashSet<UUID>()

    /** A spike plate the Warden raised on the floor: what its cell held (air), and when it becomes that again. */
    var conjured: Boolean = false
        private set
    private var original: BlockState? = null
    private var revertAt: Long = 0

    /** Become a conjured spike over [original]: it goes off at once (the arming tell first) and is floor again after [ticks]. */
    fun conjure(original: BlockState, ticks: Long) {
        val l = level ?: return
        conjured = true
        this.original = original
        revertAt = l.gameTime + ticks
        mode = TrapMode.LINKED
        fire()
        sync()
    }

    override fun serverTick() {
        super.serverTick()
        if (!conjured) return
        val l = level as? ServerLevel ?: return
        if (l.gameTime < revertAt) return
        val back = original ?: net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
        l.sendParticles(ParticleTypes.ELECTRIC_SPARK, worldPosition.x + 0.5, worldPosition.y + 0.3, worldPosition.z + 0.5, 8, 0.3, 0.2, 0.3, 0.05)
        l.setBlock(worldPosition, back, Block.UPDATE_ALL)
    }

    override fun saveSynced(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveSynced(tag, registries)
        tag.putBoolean("conjured", conjured)
        if (conjured) {
            tag.putLong("revertAt", revertAt)
            original?.let { tag.put("original", net.minecraft.nbt.NbtUtils.writeBlockState(it)) }
        }
    }

    override fun loadSynced(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadSynced(tag, registries)
        conjured = tag.boolOr("conjured", false)
        revertAt = tag.longOr("revertAt", 0L)
        original = if (tag.contains("original")) net.minecraft.nbt.NbtUtils.readBlockState(bpm.platform.blockLookup(), tag.compoundOr("original")) else null
    }

    override val phase: String
        get() = when {
            t in 28 until 36 -> "armed"
            t in 36 until 54 -> "extended"
            t in 54 until 60 && (mode == TrapMode.CYCLE || isFiring) -> "retracting"
            else -> "idle"
        }

    override fun step(t: Int) {
        when (t) {
            28 -> triggerAnim("main", "arm")
            36 -> {
                triggerAnim("main", "extend")
                hit.clear()
            }
            54 -> triggerAnim("main", "retract")
        }
        if (t in 38..54) {
            val l = level ?: return
            for (v in victims(1.0)) {
                if (!hit.add(v.uuid)) continue
                v.hurt(BpmDamage.source(l, BpmDamage.SPIKE), BpmConfig.TRAP_SPIKE_DAMAGE.orDefault().toFloat())
                v.addEffect(MobEffectInstance(bpm.platform.SLOWNESS, 8, 1))
            }
        }
    }

    override fun registerControllers(controllers: bpm.platform.ControllerRegistrar) {
        controllers.add(
            bpm.platform.animController(this, "main", 2) { s -> s.setAndContinue(IDLE) }
                .triggerableAnim("arm", ARM).triggerableAnim("extend", EXTEND).triggerableAnim("retract", RETRACT),
        )
    }

    companion object {
        const val CONJURE_TICKS = 70L

        /** Whether a spike plate can rise on the floor cell [pos]: plain chamber floor with nothing on it. */
        fun canRise(level: ServerLevel, pos: BlockPos): Boolean {
            val s = level.getBlockState(pos)
            val floor = s.`is`(bpm.world.ContentBlocks.CHAMBER_FLOOR.get()) || s.`is`(bpm.world.ContentBlocks.CHAMBER_FLOOR_CIRCUIT.get())
            return floor && level.getBlockState(pos.above()).canBeReplaced()
        }

        /**
         * Raises a spike plate on the floor cell [pos] (the plate sits on the floor, like the room's own traps) —
         * it fires at once and is gone again after [ticks]. False where it cannot rise.
         */
        fun conjure(level: ServerLevel, pos: BlockPos, ticks: Long = CONJURE_TICKS): Boolean {
            if (!canRise(level, pos)) return false
            val at = pos.above()
            val was = level.getBlockState(at)
            level.setBlock(at, bpm.world.DeviceBlocks.PHASE_SPIKE.get().defaultBlockState(), Block.UPDATE_ALL)
            val be = level.getBlockEntity(at) as? SpikeBlockEntity ?: return false
            be.conjure(was, ticks)
            return true
        }

        val IDLE: RawAnimation = RawAnimation.begin().thenLoop("animation.phase_spike.idle")
        val ARM: RawAnimation = RawAnimation.begin().thenPlayAndHold("animation.phase_spike.arm")
        val EXTEND: RawAnimation = RawAnimation.begin().thenPlayAndHold("animation.phase_spike.extend")
        val RETRACT: RawAnimation = RawAnimation.begin().thenPlay("animation.phase_spike.retract")
    }
}

/**
 * The vent: a 4 s cycle — the puddle rises at 2.0 s, the column erupts at 2.6 s and hurts and lifts whatever
 * stands in it (one point every half second, levitation) until 3.9 s; items in the column are unmade.
 */
class VentBlockEntity(pos: BlockPos, state: BlockState) : TrapBlockEntity(DeviceBlockEntities.VENT.get(), pos, state, period = 80, fireFrom = 40) {
    /** The other vents this one throws to; empty for a vent on its own. */
    val peers = ArrayList<BlockPos>()

    /** Something stepped on the grate: if this vent has peers, it goes to one of them. */
    fun stepped(entity: Entity) {
        if (peers.isEmpty() || entity.isOnPortalCooldown || entity is bpm.world.entity.QuantumWardenEntity || entity !is LivingEntity) return
        if (entity is Player && (entity.isSpectator || entity.isCreative && entity.isShiftKeyDown)) return
        val l = level as? ServerLevel ?: return
        val to = peers[l.random.nextInt(peers.size)]
        val from = entity.position()
        l.sendParticles(ParticleTypes.PORTAL, from.x, from.y + 1.0, from.z, 30, 0.3, 0.6, 0.3, 0.3)
        entity.teleportTo(to.x + 0.5, to.y + 1.0, to.z + 0.5)
        entity.setPortalCooldown(ARRIVAL_COOLDOWN)
        entity.resetFallDistance()
        l.sendParticles(ParticleTypes.PORTAL, to.x + 0.5, to.y + 2.0, to.z + 0.5, 30, 0.3, 0.6, 0.3, 0.3)
        l.playSound(null, to, net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT, net.minecraft.sounds.SoundSource.BLOCKS, 0.7f, 1.6f)
        triggerAnim("main", "charge")
        (l.getBlockEntity(to) as? VentBlockEntity)?.triggerAnim("main", "charge")
        if (entity is Player) entity.displayClientMessage(net.minecraft.network.chat.Component.literal("[bpm] the vent throws you across the room"), true)
    }

    override val phase: String
        get() = when {
            t in 40 until 52 -> "charging"
            t in 52 until 79 -> "erupting"
            else -> "idle"
        }

    override fun step(t: Int) {
        when (t) {
            40 -> triggerAnim("main", "charge")
            52 -> triggerAnim("main", "erupt")
        }
        if (t in 55..78) {
            val l = level ?: return
            if ((t - 55) % 10 == 0) {
                for (v in victims(3.0)) {
                    v.hurt(BpmDamage.source(l, BpmDamage.VENT), 1f)
                    v.addEffect(MobEffectInstance(MobEffects.LEVITATION, 15, 1))
                }
            }
            val box = AABB(worldPosition).setMaxY(worldPosition.y + 4.0)
            for (item in l.getEntitiesOfClass(ItemEntity::class.java, box)) item.discard()
        }
    }

    override fun saveSynced(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveSynced(tag, registries)
        tag.putIntArray("peers", peers.flatMap { listOf(it.x, it.y, it.z) }.toIntArray())
    }

    override fun loadSynced(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadSynced(tag, registries)
        peers.clear()
        val a = tag.intsOr("peers")
        for (i in 0 until a.size / 3) peers += BlockPos(a[i * 3], a[i * 3 + 1], a[i * 3 + 2])
    }

    override fun registerControllers(controllers: bpm.platform.ControllerRegistrar) {
        controllers.add(
            bpm.platform.animController(this, "main", 2) { s -> s.setAndContinue(IDLE) }
                .triggerableAnim("charge", CHARGE).triggerableAnim("erupt", ERUPT),
        )
    }

    companion object {
        const val ARRIVAL_COOLDOWN = 40
        val IDLE: RawAnimation = RawAnimation.begin().thenLoop("animation.decoherence_vent.idle")
        val CHARGE: RawAnimation = RawAnimation.begin().thenPlayAndHold("animation.decoherence_vent.charge")
        val ERUPT: RawAnimation = RawAnimation.begin().thenPlay("animation.decoherence_vent.erupt")
    }
}

/** The turret mounts on any face; FACING is the way it points away from that face (floor → up). */
class TurretBlock(properties: Properties) : Block(properties), EntityBlock {
    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.UP))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING)
    }

    override fun getStateForPlacement(ctx: BlockPlaceContext): BlockState = defaultBlockState().setValue(FACING, ctx.clickedFace)
    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = TurretBlockEntity(pos, state)
    override fun getRenderShape(state: BlockState): RenderShape = bpm.platform.ANIMATED_BLOCK_SHAPE

    override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? =
        DeviceBlockEntity.ticker(level, type, DeviceBlockEntities.TURRET.get())

    companion object {
        val FACING: net.minecraft.world.level.block.state.properties.EnumProperty<net.minecraft.core.Direction> = BlockStateProperties.FACING
    }
}

/**
 * The observer turret: finds the nearest thing it may shoot within range and in sight, follows it, and fires
 * two hitscan bolts every three seconds. In a chamber it shoots players; outside, hostiles (players too when
 * the config says so). A script can point it somewhere and make it fire.
 */
class TurretBlockEntity(pos: BlockPos, state: BlockState) : DeviceBlockEntity(DeviceBlockEntities.TURRET.get(), pos, state) {
    /** Where the eye looks, in degrees, for the `track` animation — synced so the client's model follows. */
    var targetYaw: Float = 0f
    var targetPitch: Float = 0f
    var tracking: Boolean = false

    /** Whether it hunts on its own; a script can still aim and fire it. */
    var active: Boolean = true

    private var hijackTarget: java.util.UUID? = null
    private var hijackedUntil: Long = 0
    private var disabledUntil: Long = 0

    /** Pulsed: a burst, then dark — no hunting, no firing — for [ticks]. */
    fun disable(ticks: Int) {
        val l = level as? ServerLevel ?: return
        disabledUntil = l.gameTime + ticks
        target = null
        forced = null
        tracking = false
        off = true
        val c = eye()
        l.sendParticles(ParticleTypes.EXPLOSION, c.x, c.y, c.z, 2, 0.2, 0.2, 0.2, 0.0)
        l.sendParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y, c.z, 40, 0.4, 0.4, 0.4, 0.3)
        l.sendParticles(ParticleTypes.LARGE_SMOKE, c.x, c.y, c.z, 12, 0.3, 0.3, 0.3, 0.02)
        l.playSound(null, worldPosition, net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE.value(), net.minecraft.sounds.SoundSource.BLOCKS, 0.6f, 1.4f)
        triggerAnim("main", "powerdown")
        sync()
    }

    val disabled: Boolean get() = disabledUntil > (level?.gameTime ?: 0L)

    /** Dark, as the client sees it — the base of the `off` animation. */
    var off: Boolean = false

    /** Whole seconds until it wakes, for the sign the client hangs over it; 0 when lit. */
    fun secondsDark(): Int {
        val now = level?.gameTime ?: return 0
        return ((disabledUntil - now + 19) / 20).toInt().coerceAtLeast(0)
    }

    /** The client's smoothed aim, for the renderer. */
    var shownYaw: Float = 0f
    var shownPitch: Float = 0f

    /** Turn the turret on [target] for [ticks] (null clears): it hunts nothing else meanwhile. */
    fun hijack(target: LivingEntity?, ticks: Int) {
        hijackTarget = target?.uuid
        hijackedUntil = if (target == null) 0 else (level?.gameTime ?: 0L) + ticks
        this.target = null
        forced = null
    }

    val hijacked: Boolean get() = hijackTarget != null

    private var target: LivingEntity? = null
    private var forced: Vec3? = null

    /** The current target is the Warden, and the bolts mend it rather than hurt it. */
    private var healing = false
    private var lostTicks = 0
    private var cooldown = 0

    /** Whom it follows with its eye when it has nothing to shoot — the nearest player, else the Warden. */
    private var watching: LivingEntity? = null
    private var shotAt = -1
    private var syncIn = 0

    val facing: Direction get() = blockState.takeIf { it.hasProperty(TurretBlock.FACING) }?.getValue(TurretBlock.FACING) ?: Direction.UP

    /** The eye: just outside the block along the mount's normal, so its own block never blocks its sight. */
    fun eye(): Vec3 = Vec3.atCenterOf(worldPosition).add(Vec3.atLowerCornerOf(bpm.platform.unitVector(facing)).scale(0.56))

    /** Point the eye at [pos] (null lets it hunt again). */
    fun aimAt(pos: Vec3?) {
        forced = pos
        if (pos != null) target = null
    }

    val hasTarget: Boolean get() = target != null || forced != null

    override fun serverTick() {
        val l = level as? ServerLevel ?: return
        if (cooldown > 0) cooldown--
        if (disabled) {
            watching = null
            if (tracking) {
                tracking = false
                sync()
            }
            if (l.gameTime % 5 == 0L) l.sendParticles(ParticleTypes.SMOKE, worldPosition.x + 0.5, worldPosition.y + 0.9, worldPosition.z + 0.5, 2, 0.15, 0.1, 0.15, 0.01)
            return
        }
        if (off) {
            off = false
            triggerAnim("main", "powerup")
            sync()
        }
        if (shotAt >= 0) {
            shotAt++
            if (shotAt == 2 || shotAt == 12) bolt(l)
            if (shotAt > 12) shotAt = -1
        }
        if (hijackTarget != null && l.gameTime >= hijackedUntil) hijack(null, 0)
        val forcedAt = forced
        if (forcedAt != null) {
            look(forcedAt)
        } else {
            val prey = hijackTarget
            if (prey != null) {
                healing = false
                if (l.gameTime % 4 == 0L) target = (l.getEntity(prey) as? LivingEntity)?.takeIf { it.isAlive && canSee(l, it) }
            } else if (active && l.gameTime % 4 == 0L) {
                hunt(l)
                if (target == null) mend(l) else healing = false
            } else if (!active) target = null
            // A patient that got back on its feet is no patient.
            if (healing) (target as? bpm.world.entity.QuantumWardenEntity)?.let { if (!patient(it)) { target = null; healing = false } }
            val tgt = target
            if (tgt != null) {
                if (!tgt.isAlive || !canSee(l, tgt)) {
                    if (++lostTicks > 40) {
                        target = null
                        lostTicks = 0
                    }
                } else {
                    lostTicks = 0
                    look(tgt.getEyePosition(1f))
                    if (cooldown == 0) fire()
                }
            }
            // Nothing to shoot: the eye still follows whoever is nearest — a player, else the Warden — without firing.
            if (target == null) {
                if (l.gameTime % 4 == 0L || watching?.isAlive != true) watching = if (active) nearestToWatch(l) else null
                watching?.let { look(it.getEyePosition(1f)) }
            } else {
                watching = null
            }
        }
        val nowTracking = target != null || forced != null || watching != null
        if (nowTracking != tracking) {
            tracking = nowTracking
            sync()
        } else if (tracking && --syncIn <= 0) {
            syncIn = 2
            sync()
        }
    }

    /** Fires the two-bolt volley (the animation carries the bolts; the damage lands at 0.1 s and 0.6 s). */
    fun fire(): Boolean {
        if (shotAt >= 0) return false
        shotAt = 0
        cooldown = 60
        triggerAnim("main", "fire")
        return true
    }

    /** Whom to face with nothing to shoot: the nearest player in reach (any but a spectator), else the nearest Warden. */
    private fun nearestToWatch(l: ServerLevel): LivingEntity? {
        val eye = eye()
        val box = AABB(worldPosition).inflate(reach(l))
        return l.getEntitiesOfClass(Player::class.java, box) { it.isAlive && !it.isSpectator }.minByOrNull { it.distanceToSqr(eye) }
            ?: l.getEntitiesOfClass(bpm.world.entity.QuantumWardenEntity::class.java, box) { it.isAlive }.minByOrNull { it.distanceToSqr(eye) }
    }

    /** How far it looks: the whole room in a chamber, the config's range outside. */
    private fun reach(l: ServerLevel): Double = if (ChamberDimension.isChamber(l)) CHAMBER_REACH else BpmConfig.TRAP_TURRET_RANGE.orDefault().toDouble()

    private fun hunt(l: ServerLevel) {
        val range = reach(l)
        val eye = eye()
        val box = AABB(worldPosition).inflate(range)
        val inChamber = ChamberDimension.isChamber(l)
        val playersToo = inChamber || BpmConfig.TRAP_TURRET_TARGETS_PLAYERS_OVERWORLD.orDefault()
        // In a chamber the turrets are the Warden's: whenever it is on the floor — below half, or knocked down by
        // a special — they shoot anyone they can see; while it flies, only whoever is on the centre island.
        val openSeason = !inChamber || l.getEntitiesOfClass(bpm.world.entity.QuantumWardenEntity::class.java, AABB(worldPosition).inflate(64.0)) { it.isAlive }
            .any { it.health < it.maxHealth * 0.5f || it.disabled || it.grounded }
        val candidates = l.getEntitiesOfClass(LivingEntity::class.java, box) { e ->
            e.isAlive && when (e) {
                is Player -> playersToo && !e.isCreative && !e.isSpectator && (openSeason || bpm.chamber.ChamberFight.onIsland(l, e.blockPosition()))
                is bpm.world.entity.QuantumWardenEntity -> false
                is Monster -> !inChamber
                else -> false
            }
        }
        target = candidates.filter { it.getEyePosition(1f).distanceToSqr(eye) <= range * range && canSee(l, it) }.minByOrNull { it.distanceToSqr(eye) }
    }

    /**
     * A Warden worth mending: alive, hurt, not knocked down — and either on its feet on the floor (below half)
     * or flying past within [FLYING_MEND_REACH] blocks of this turret.
     */
    private fun patient(w: bpm.world.entity.QuantumWardenEntity): Boolean {
        if (!w.isAlive || w.disabled || w.health >= w.maxHealth) return false
        return if (w.grounded) w.health < w.maxHealth * 0.5f
        else w.position().distanceToSqr(Vec3.atCenterOf(worldPosition)) <= FLYING_MEND_REACH * FLYING_MEND_REACH
    }

    /** No player in sight: a Warden worth mending becomes the target, and the bolts heal it. */
    private fun mend(l: ServerLevel) {
        val range = reach(l)
        val eye = eye()
        val w = l.getEntitiesOfClass(bpm.world.entity.QuantumWardenEntity::class.java, AABB(worldPosition).inflate(range)) { patient(it) && canSee(l, it) }
            .minByOrNull { it.distanceToSqr(eye) }
        target = w
        healing = w != null
    }

    /**
     * Line of sight from the eye — the ray starts a block and a half out along its own direction, so the
     * turret's own block (which the eye sits on, and which any steep ray would otherwise enter) never counts.
     */
    private fun canSee(l: ServerLevel, e: LivingEntity): Boolean {
        val from = eye()
        val to = e.getEyePosition(1f)
        if (to.distanceToSqr(from) <= RAY_SKIP * RAY_SKIP) return true
        val start = from.add(to.subtract(from).normalize().scale(RAY_SKIP))
        val hit = l.clip(ClipContext(start, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, e))
        return hit.type == HitResult.Type.MISS || hit.location.distanceToSqr(start) >= to.distanceToSqr(start) - 0.5
    }

    /**
     * Yaw and pitch of [at] in the mount's frame, as rotations of the model's `yaw` (about +Y) and `pitch`
     * (about +X) bones from its rest pose facing −Z: the renderer sets those bones directly.
     */
    private fun look(at: Vec3) {
        val d = at.subtract(eye())
        val v = Vector3f(d.x.toFloat(), d.y.toFloat(), d.z.toFloat())
        when (facing) {
            Direction.DOWN -> v.rotate(Axis.XP.rotationDegrees(180f))
            Direction.SOUTH -> v.rotate(Axis.XP.rotationDegrees(-90f))
            Direction.NORTH -> v.rotate(Axis.XP.rotationDegrees(90f))
            Direction.EAST -> v.rotate(Axis.ZP.rotationDegrees(90f))
            Direction.WEST -> v.rotate(Axis.ZP.rotationDegrees(-90f))
            else -> {}
        }
        val horizontal = sqrt(v.x * v.x + v.z * v.z)
        targetYaw = Math.toDegrees(atan2(-v.x.toDouble(), -v.z.toDouble())).toFloat()
        targetPitch = Math.toDegrees(atan2(v.y.toDouble(), horizontal.toDouble())).toFloat()
    }

    /** One hitscan bolt: the first living thing along the eye's line within range takes the hit; sparks mark the path. */
    private fun bolt(l: ServerLevel) {
        val from = eye()
        val range = reach(l)
        val aim = forced ?: target?.getEyePosition(1f) ?: return
        val dir = aim.subtract(from).normalize()
        val to = from.add(dir.scale(range))
        val start = from.add(dir.scale(RAY_SKIP))
        val blockHit = l.clip(ClipContext(start, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()))
        val end = if (blockHit.type == HitResult.Type.MISS) to else blockHit.location
        var victim: LivingEntity? = null
        var best = Double.MAX_VALUE
        for (e in l.getEntitiesOfClass(LivingEntity::class.java, AABB(from, end).inflate(1.0)) { it.isAlive && !(it is Player && (it.isCreative || it.isSpectator)) }) {
            val clipped = e.boundingBox.inflate(0.3).clip(from, end).orElse(null) ?: continue
            val dist = clipped.distanceToSqr(from)
            if (dist < best) {
                best = dist
                victim = e
            }
        }
        val stop = victim?.getEyePosition(1f) ?: end
        val steps = (stop.distanceTo(from) * 2).toInt().coerceIn(2, 40)
        val mending = healing && victim is bpm.world.entity.QuantumWardenEntity
        for (i in 0..steps) {
            val p = from.lerp(stop, i.toDouble() / steps)
            l.sendParticles(if (mending) ParticleTypes.HAPPY_VILLAGER else ParticleTypes.ELECTRIC_SPARK, p.x, p.y, p.z, 1, 0.0, 0.0, 0.0, 0.0)
        }
        if (mending) (victim as LivingEntity).heal(BpmConfig.TRAP_TURRET_HEAL.orDefault().toFloat())
        else victim?.hurt(BpmDamage.source(l, BpmDamage.TURRET), BpmConfig.TRAP_TURRET_DAMAGE.orDefault().toFloat())
    }

    override fun saveSynced(tag: CompoundTag, registries: HolderLookup.Provider) {
        tag.putFloat("yaw", targetYaw)
        tag.putFloat("pitch", targetPitch)
        tag.putBoolean("tracking", tracking)
        tag.putBoolean("active", active)
        tag.putBoolean("off", off)
        tag.putLong("darkUntil", disabledUntil)
    }

    override fun loadSynced(tag: CompoundTag, registries: HolderLookup.Provider) {
        targetYaw = tag.floatOr("yaw", 0f)
        targetPitch = tag.floatOr("pitch", 0f)
        tracking = tag.boolOr("tracking", false)
        active = !tag.contains("active") || tag.boolOr("active", false)
        off = tag.boolOr("off", false)
        disabledUntil = tag.longOr("darkUntil", 0L)
    }

    override fun registerControllers(controllers: bpm.platform.ControllerRegistrar) {
        controllers.add(
            bpm.platform.animController(this, "main", 4) { s -> s.setAndContinue(if (off) OFF else if (tracking) TRACK else IDLE) }
                .triggerableAnim("fire", FIRE).triggerableAnim("powerdown", POWERDOWN).triggerableAnim("powerup", POWERUP),
        )
    }

    companion object {
        const val CHAMBER_REACH = 64.0
        const val RAY_SKIP = 1.4
        const val FLYING_MEND_REACH = 8.0

        /**
         * Moves the turret at [from] to [to] — a clear cell with something solid under it — with a flash at both
         * ends; it keeps its facing and whether it hunts. Answers the turret at its new perch, or null when it could not go.
         */
        fun hop(level: ServerLevel, from: BlockPos, to: BlockPos): TurretBlockEntity? {
            val old = level.getBlockEntity(from) as? TurretBlockEntity ?: return null
            if (!level.getBlockState(to).canBeReplaced() || !level.getBlockState(to.below()).isFaceSturdy(level, to.below(), Direction.UP)) return null
            val state = level.getBlockState(from)
            val active = old.active
            val a = Vec3.atCenterOf(from)
            val b = Vec3.atCenterOf(to)
            level.sendParticles(ParticleTypes.REVERSE_PORTAL, a.x, a.y + 0.3, a.z, 40, 0.3, 0.4, 0.3, 0.05)
            level.setBlock(from, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL)
            level.setBlock(to, state, Block.UPDATE_ALL)
            val be = level.getBlockEntity(to) as? TurretBlockEntity ?: return null
            be.active = active
            be.triggerAnim("main", "powerup")
            be.sync()
            level.sendParticles(ParticleTypes.PORTAL, b.x, b.y + 0.3, b.z, 40, 0.3, 0.4, 0.3, 0.05)
            level.playSound(null, to, net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT, net.minecraft.sounds.SoundSource.BLOCKS, 0.8f, 0.7f)
            return be
        }
        val IDLE: RawAnimation = RawAnimation.begin().thenLoop("animation.observer_turret.idle")
        val TRACK: RawAnimation = RawAnimation.begin().thenLoop("animation.observer_turret.track")
        val FIRE: RawAnimation = RawAnimation.begin().thenPlay("animation.observer_turret.fire")
        val OFF: RawAnimation = RawAnimation.begin().thenLoop("animation.observer_turret.off")
        val POWERDOWN: RawAnimation = RawAnimation.begin().thenPlayAndHold("animation.observer_turret.powerdown")
        val POWERUP: RawAnimation = RawAnimation.begin().thenPlay("animation.observer_turret.powerup")
    }
}

/** The superposition block: solid or a ghost frame, toggled by its cycle, redstone, or a link. */
class PhaseBlock(properties: Properties) : bpm.platform.SkylightAwareBlock(properties), EntityBlock {
    init {
        registerDefaultState(stateDefinition.any().setValue(SOLID, true))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(SOLID)
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = PhaseBlockEntity(pos, state)
    override fun getRenderShape(state: BlockState): RenderShape = bpm.platform.ANIMATED_BLOCK_SHAPE

    /** A ghost is nothing to walk on — except for the Warden, which walks its own decohered floor. */
    override fun getCollisionShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape {
        if (state.getValue(SOLID)) return Shapes.block()
        val e = (context as? net.minecraft.world.phys.shapes.EntityCollisionContext)?.entity
        return if (e is bpm.world.entity.QuantumWardenEntity) Shapes.block() else Shapes.empty()
    }

    /** Inside a decohered trail tile, anything living but the Warden is hurt — the reason not to follow it. */
    override fun insideBlock(state: BlockState, level: net.minecraft.server.level.ServerLevel, pos: BlockPos, entity: Entity) {
        if (state.getValue(SOLID)) return
        if (entity !is LivingEntity || entity is bpm.world.entity.QuantumWardenEntity) return
        if (entity is Player && (entity.isCreative || entity.isSpectator)) return
        val be = level.getBlockEntity(pos) as? PhaseBlockEntity ?: return
        if (!be.trail) return
        entity.hurt(BpmDamage.source(level, BpmDamage.DECOHERENCE), BpmConfig.WARDEN_TRAIL_DAMAGE.orDefault().toFloat())
    }

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = Shapes.block()

    override fun propagatesSkylight(state: BlockState): Boolean = !state.getValue(SOLID)

    override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? =
        DeviceBlockEntity.ticker(level, type, DeviceBlockEntities.PHASE.get())

    override fun neighborChanged(state: BlockState, level: Level, pos: BlockPos, block: Block, fromPos: bpm.platform.NeighborSource, moving: Boolean) {
        super.neighborChanged(state, level, pos, block, fromPos, moving)
        if (!level.isClientSide) (level.getBlockEntity(pos) as? PhaseBlockEntity)?.onRedstone(level.hasNeighborSignal(pos))
    }

    companion object {
        val SOLID: BooleanProperty = BooleanProperty.create("solid")
    }
}

/**
 * The superposition block's timing: a phase takes half a second, during which the picture changes and then
 * the collision follows. In [TrapMode.CYCLE] it is solid for 2.5 s and a ghost for 2.5 s; otherwise it holds
 * whatever it was last told (a link, or redstone: powered = ghost).
 */
class PhaseBlockEntity(pos: BlockPos, state: BlockState) : DeviceBlockEntity(DeviceBlockEntities.PHASE.get(), pos, state) {
    var mode: TrapMode = TrapMode.LINKED
        set(value) {
            field = value
            t = 0
        }

    private var t = 0
    private var pending: Boolean? = null
    private var pendingIn = 0

    /** A tile the grounded Warden decohered: what it was, and when it becomes that again. */
    var trail: Boolean = false
        private set
    private var original: BlockState? = null
    private var revertAt: Long = 0

    /** Become a trail tile over [original] for [ticks]: phase out now, phase in and turn back when the time is up. */
    fun decohere(original: BlockState, ticks: Long) {
        val l = level ?: return
        trail = true
        this.original = original
        revertAt = l.gameTime + ticks
        mode = TrapMode.LINKED
        requestSolid(false)
        sync()
    }

    val solid: Boolean get() = blockState.getValue(PhaseBlock.SOLID)

    /** Asks for [solid] with the half-second phase first; nothing when already there or on the way. */
    fun requestSolid(solid: Boolean) {
        if (this.solid == solid && pending == null) return
        if (pending == solid) return
        pending = solid
        pendingIn = 10
        triggerAnim("main", if (solid) "phase_in" else "phase_out")
    }

    fun onRedstone(powered: Boolean) {
        if (mode == TrapMode.LINKED) requestSolid(!powered)
    }

    override fun serverTick() {
        val l = level ?: return
        if (trail) {
            val left = revertAt - l.gameTime
            if (left == 10L) requestSolid(true)
            if (left <= 0L) {
                val back = original ?: bpm.world.ContentBlocks.CHAMBER_FLOOR.get().defaultBlockState()
                l.setBlock(worldPosition, back, Block.UPDATE_ALL)
                return
            }
        }
        if (mode == TrapMode.CYCLE) {
            t = (t + 1) % 120
            if (t == 50) requestSolid(false)
            if (t == 110) requestSolid(true)
        }
        val want = pending ?: return
        if (--pendingIn > 0) return
        pending = null
        if (want == solid) return
        if (want) pushOut(l)
        l.setBlock(worldPosition, blockState.setValue(PhaseBlock.SOLID, want), 3)
        sync()
    }

    /** Whatever stands inside a block that is about to be solid goes to the nearest free side. */
    private fun pushOut(l: Level) {
        val box = AABB(worldPosition)
        for (e in l.getEntitiesOfClass(Entity::class.java, box)) {
            val free = listOf(Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN)
                .map { worldPosition.relative(it) }
                .firstOrNull { l.getBlockState(it).getCollisionShape(l, it).isEmpty } ?: worldPosition.above()
            e.teleportTo(free.x + 0.5, free.y.toDouble(), free.z + 0.5)
        }
    }

    override fun saveSynced(tag: CompoundTag, registries: HolderLookup.Provider) {
        tag.putString("mode", mode.name)
        tag.putBoolean("trail", trail)
        if (trail) {
            tag.putLong("revertAt", revertAt)
            original?.let { tag.put("original", net.minecraft.nbt.NbtUtils.writeBlockState(it)) }
        }
    }

    override fun loadSynced(tag: CompoundTag, registries: HolderLookup.Provider) {
        mode = runCatching { TrapMode.valueOf(tag.stringOr("mode", "")) }.getOrDefault(TrapMode.LINKED)
        trail = tag.boolOr("trail", false)
        revertAt = tag.longOr("revertAt", 0L)
        original = if (tag.contains("original")) net.minecraft.nbt.NbtUtils.readBlockState(bpm.platform.blockLookup(), tag.compoundOr("original")) else null
    }

    override fun registerControllers(controllers: bpm.platform.ControllerRegistrar) {
        controllers.add(
            bpm.platform.animController(this, "main", 2) { s -> s.setAndContinue(if (solid) SOLID_ANIM else GHOST) }
                .triggerableAnim("phase_out", PHASE_OUT).triggerableAnim("phase_in", PHASE_IN),
        )
    }

    companion object {
        const val TRAIL_TICKS = 300L

        /** Turn a floor tile at [pos] into a trail tile for [ticks]; only plain chamber floor decoheres. */
        fun decohere(level: net.minecraft.server.level.ServerLevel, pos: BlockPos, ticks: Long = TRAIL_TICKS): Boolean {
            val was = level.getBlockState(pos)
            if (!was.`is`(bpm.world.ContentBlocks.CHAMBER_FLOOR.get()) && !was.`is`(bpm.world.ContentBlocks.CHAMBER_FLOOR_CIRCUIT.get())) return false
            level.setBlock(pos, bpm.world.DeviceBlocks.PHASE_BLOCK.get().defaultBlockState().setValue(PhaseBlock.SOLID, true), Block.UPDATE_ALL)
            val be = level.getBlockEntity(pos) as? PhaseBlockEntity ?: return false
            be.decohere(was, ticks)
            return true
        }

        val SOLID_ANIM: RawAnimation = RawAnimation.begin().thenPlayAndHold("animation.phase_block.solid")
        val GHOST: RawAnimation = RawAnimation.begin().thenLoop("animation.phase_block.ghost")
        val PHASE_OUT: RawAnimation = RawAnimation.begin().thenPlayAndHold("animation.phase_block.phase_out")
        val PHASE_IN: RawAnimation = RawAnimation.begin().thenPlay("animation.phase_block.phase_in")
    }
}
