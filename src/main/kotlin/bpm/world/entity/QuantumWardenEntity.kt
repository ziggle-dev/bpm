package bpm.world.entity

import bpm.BpmConfig
import bpm.BpmConfig.orDefault
import bpm.chamber.ChamberFight
import bpm.world.devices.PedestalBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtUtils
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerBossEvent
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.DamageTypeTags
import net.minecraft.world.BossEvent
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageTypes
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.control.MoveControl
import net.minecraft.world.entity.monster.Monster
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import software.bernie.geckolib.animatable.GeoEntity
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache
import bpm.platform.AnimatableManager
import software.bernie.geckolib.animation.AnimationController
import bpm.platform.PlayState
import software.bernie.geckolib.animation.RawAnimation
import software.bernie.geckolib.util.GeckoLibUtil
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import bpm.platform.intOr
import bpm.platform.floatOr
import bpm.platform.boolOr

/**
 * The Quantum Warden — the containment vessel that guards the core (§7 of the mechanics design).
 *
 * It faces whoever it fights and moves without turning (its own move control steers the velocity; the body
 * is pointed at the target every tick). Above half health it flies the arena's ring and fires bolt volleys;
 * when its cage opens it fires a *seeker* that bends toward its target. From half health it is **grounded**:
 * it moves on the floor and blinks across the room instead of flying. Hits on the closed cage do a quarter
 * (more once the room's coherence crystals are broken); on the open core, everything. Stage 2 raises the
 * plates, a pool of armour that must be broken to force the cage open; stage 3 is faster, and at the end it
 * blinks to the dais and stays open. Its death returns the core to the pedestal it rose from.
 */
class QuantumWardenEntity(type: EntityType<out QuantumWardenEntity>, level: Level) : bpm.platform.BossMonster(type, level), GeoEntity {
    private val animCache: AnimatableInstanceCache = GeckoLibUtil.createInstanceCache(this)
    private val bossEvent = ServerBossEvent(displayName, BossEvent.BossBarColor.BLUE, BossEvent.BossBarOverlay.NOTCHED_6)

    /** The pedestal it rose from — the centre of its arena and where the core returns. */
    var home: BlockPos? = null
    var slotOwner: UUID? = null

    /** Tick of the last hit that came from a player — what makes a kill a player's kill. */
    var lastPlayerHurtTick: Int = -1000
        private set

    /** The per-entity Molang phase, so two Wardens never spin in step. */
    val animPhase: Double = (uuid.hashCode() and 0xffff) * 360.0 / 0x10000

    private var spawnTicks = 0
    private var attackIn = 60
    private var seekerIn = 0
    private var exposeIn = SPECIAL_EVERY
    private var exposeLeft = 0
    private var spikesIn = SPIKES_FIRST
    private var blinkTicks = 0
    private var blinkIn = 0
    private var blinkTo: Vec3? = null
    private var retreating = false
    private var retreatLeft = 0
    private var wanderTarget: Vec3? = null
    private var wanderIn = 0
    private var plateHp = 0f
    private var plateRegenAt = 0
    private val pendingBolts = ArrayList<Pair<Int, Int>>() // (tick, side −1 / +1)
    private var staggerCooldown = 0
    private var killedByPlayer = false
    private var facing: Vec3? = null
    private var groundedLoss = 0f
    private var lastHealth = -1f
    private var ambush: Player? = null

    init {
        moveControl = WardenMoveControl(this)
        setNoGravity(true)
        xpReward = 50
        setPersistenceRequired()
    }

    // ---- synced state -----------------------------------------------------------------------------------

    val stage: Int get() = entityData.get(STAGE)
    val shielded: Boolean get() = entityData.get(SHIELD)
    val exposed: Boolean get() = entityData.get(EXPOSED)

    /** Half health and below: on the floor, blinking about, no flight. */
    val grounded: Boolean get() = entityData.get(GROUNDED)

    /** Seized by the linker's pulse: down on the floor, cage open, doing nothing, for a while. */
    val disabled: Boolean get() = entityData.get(DISABLED)
    private var disabledUntil = 0

    /** The pulse took the open core: everything stops, gravity takes it, the cage stays open for [ticks]. */
    fun disable(ticks: Int) {
        if (level().isClientSide || isDeadOrDying) return
        entityData.set(DISABLED, true)
        disabledUntil = tickCount + ticks
        pendingBolts.clear()
        blinkTicks = 0
        wanderTarget = null
        setNoGravity(false)
        deltaMovement = Vec3(0.0, deltaMovement.y.coerceAtMost(0.0), 0.0)
        setExposed(true, ticks)
        exposeLeft = ticks
        triggerAnim("overlay", "stagger")
    }

    private fun recover() {
        entityData.set(DISABLED, false)
        setNoGravity(!grounded)
        setExposed(false)
        exposeIn = 240
        attackIn = 40
    }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        builder.define(STAGE, 1)
        builder.define(SHIELD, false)
        builder.define(EXPOSED, false)
        builder.define(GROUNDED, false)
        builder.define(DISABLED, false)
    }

    fun setExposed(value: Boolean, ticks: Int = 0) {
        if (exposed == value) {
            if (value && ticks > 0) exposeLeft = ticks
            return
        }
        entityData.set(EXPOSED, value)
        exposeLeft = if (value) ticks else 0
        if (value) seekerIn = 8
        triggerAnim("core", if (value) "expose" else "retract")
    }

    /** The walkable floor under the arena: the dais's height below the pedestal. */
    private val floorY: Double get() = (home?.y ?: blockPosition().y) - bpm.chamber.ChamberBuilder.DAIS_HEIGHT.toDouble()

    // ---- the fight --------------------------------------------------------------------------------------

    override fun fightTick(level: ServerLevel) {
        bossEvent.progress = (health / maxHealth).coerceIn(0f, 1f)
        if (spawnTicks < SPAWN_TICKS) {
            spawnTicks++
            return
        }
        if (staggerCooldown > 0) staggerCooldown--
        updateStage()
        updateGrounded()
        plates()
        // Someone on the dais — recharging at the pedestal — gets everything: the fury.
        val intruder = ChamberFight.daisIntruder(this)
        val target = intruder ?: level.getNearestPlayer(this, 48.0)?.takeIf { !it.isCreative && !it.isSpectator }
        val fury = intruder != null
        facing = target?.getEyePosition(1f)
        if (disabled) {
            deltaMovement = Vec3(0.0, deltaMovement.y, 0.0)
            if (tickCount >= disabledUntil) recover()
            lastHealth = health
            face()
            return
        }
        // On the floor, every tenth of its health lost buys an ambush: behind the nearest player, then the special.
        if (lastHealth >= 0f && health < lastHealth && grounded) groundedLoss += lastHealth - health
        lastHealth = health
        if (grounded && target != null && blinkTicks == 0 && !retreating && groundedLoss >= maxHealth * AMBUSH_FRACTION) {
            groundedLoss -= maxHealth * AMBUSH_FRACTION
            ambush = target
            beginBlink(behind(level, target))
        }
        bolts(level)
        if (blinkTicks > 0) {
            blink(level)
            face()
            return
        }
        when {
            retreating -> retreat(level)
            fury -> closeIn(level)
            grounded -> walk(level)
            else -> hover(level)
        }
        if (!retreating && stage == 3 && health <= maxHealth * RETREAT_FRACTION && retreatLeft == 0) {
            retreating = true
            retreatLeft = RETREAT_TICKS
            val h = home ?: blockPosition()
            beginBlink(Vec3(h.x + 0.5, h.y + 1.0, h.z + 0.5))
            setExposed(true, RETREAT_TICKS)
        }
        if (target != null && --attackIn <= 0) {
            beamAttack()
            attackIn = if (fury) FURY_ATTACK_EVERY else when (stage) { 1 -> 80; 2 -> 70; else -> 50 }
        }
        if ((exposed || fury) && target != null && --seekerIn <= 0) {
            seeker(level, target)
            seekerIn = if (fury) FURY_SEEKER_EVERY else SEEKER_EVERY
        }
        if (exposeLeft > 0) {
            if (--exposeLeft == 0 && !retreating) setExposed(false)
        } else if (!retreating && --exposeIn <= 0) {
            setExposed(true, (BpmConfig.exposeSeconds(stage) * 20).toInt())
            exposeIn = SPECIAL_EVERY + random.nextInt(SPECIAL_JITTER)
        }
        // Now and then, spikes out of the floor under whoever it is watching.
        if (target != null && --spikesIn <= 0) {
            spikeAttack(level, target)
            spikesIn = SPIKES_EVERY + random.nextInt(SPIKES_JITTER)
        }
        face()
    }

    /**
     * Spikes under [target]: plates rise on the floor cell under their feet and its four neighbours at once
     * (the arming tell gives half a second to step off) and are gone again a few seconds later. Answers how
     * many rose — none where there is no chamber floor within three blocks below them.
     */
    fun spikeAttack(l: ServerLevel, target: LivingEntity): Int {
        val feet = BlockPos.containing(target.x, target.y - 0.2, target.z)
        val floor = (0..3).map { feet.below(it) }.firstOrNull { bpm.world.devices.SpikeBlockEntity.canRise(l, it) } ?: return 0
        var n = 0
        for ((dx, dz) in listOf(0 to 0, 1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
            if (bpm.world.devices.SpikeBlockEntity.conjure(l, floor.offset(dx, 0, dz))) n++
        }
        if (n > 0) {
            l.sendParticles(net.minecraft.core.particles.ParticleTypes.ELECTRIC_SPARK, floor.x + 0.5, floor.y + 1.1, floor.z + 0.5, 30, 1.0, 0.2, 1.0, 0.1)
            l.playSound(null, floor, net.minecraft.sounds.SoundEvents.SCULK_SHRIEKER_SHRIEK, net.minecraft.sounds.SoundSource.HOSTILE, 0.8f, 1.6f)
        }
        return n
    }

    private fun updateStage() {
        val frac = health / maxHealth
        val next = when {
            frac > 2f / 3f -> 1
            frac > 1f / 3f -> 2
            else -> 3
        }
        if (next == stage) return
        entityData.set(STAGE, next)
        when (next) {
            2 -> {
                entityData.set(SHIELD, true)
                plateHp = BpmConfig.WARDEN_PLATE_HEALTH.orDefault().toFloat() * PLATES
            }
            3 -> {
                entityData.set(SHIELD, false)
                plateHp = 0f
            }
        }
        ChamberFight.onStage(this, next)
    }

    private fun updateGrounded() {
        val g = health <= maxHealth * GROUNDED_AT
        if (g == grounded) return
        entityData.set(GROUNDED, g)
        setNoGravity(!g)
        wanderTarget = null
        if (g) blinkIn = 30
    }

    private fun plates() {
        if (stage != 2) return
        if (plateHp <= 0f && plateRegenAt > 0 && tickCount >= plateRegenAt && ChamberFight.platesRegenerate(this)) {
            plateHp = BpmConfig.WARDEN_PLATE_HEALTH.orDefault().toFloat() * PLATES
            plateRegenAt = 0
            entityData.set(SHIELD, true)
        }
    }

    /** A ring point around home, a few blocks up, changed every few seconds. */
    private fun hover(l: ServerLevel) {
        val h = home ?: blockPosition()
        if (--wanderIn <= 0 || wanderTarget == null) {
            val a = random.nextDouble() * Math.PI * 2
            val r = 8.0 + random.nextDouble() * 6.0
            wanderTarget = Vec3(h.x + 0.5 + cos(a) * r, floorY + 3.0 + random.nextDouble() * 2.5, h.z + 0.5 + sin(a) * r)
            wanderIn = 60 + random.nextInt(60)
        }
        val t = wanderTarget ?: return
        moveControl.setWantedPosition(t.x, t.y, t.z, if (stage == 3) 1.3 else 1.0)
    }

    /** On the floor: drift toward a ring point on the outer floor, and blink somewhere else every few seconds. */
    private fun walk(l: ServerLevel) {
        val h = home ?: blockPosition()
        if (tickCount % 2 == 0) trail(l)
        if (y < floorY - 0.6 && blinkTicks == 0) {
            beginBlink(floorPoint(l, h))
            return
        }
        if (--wanderIn <= 0 || wanderTarget == null) {
            wanderTarget = floorPoint(l, h)
            wanderIn = 80 + random.nextInt(60)
        }
        val t = wanderTarget ?: return
        moveControl.setWantedPosition(t.x, t.y, t.z, if (stage == 3) 1.2 else 0.9)
        if (--blinkIn <= 0) {
            beginBlink(floorPoint(l, h))
            blinkIn = BLINK_EVERY + random.nextInt(60)
        }
    }

    /** The floor under its footprint decoheres behind it: a 3 x 3 of trail tiles that bite anyone else. */
    private fun trail(l: ServerLevel) {
        val under = BlockPos.containing(x, y - 0.1, z)
        for (dx in -1..1) for (dz in -1..1) {
            bpm.world.devices.PhaseBlockEntity.decohere(l, under.offset(dx, 0, dz))
        }
    }

    /** A standing spot on the outer floor ring (outside the trench), at whatever height the floor is there. */
    private fun floorPoint(l: ServerLevel, h: BlockPos): Vec3 {
        val a = random.nextDouble() * Math.PI * 2
        val r = 10.5 + random.nextDouble() * 5.0
        val x = h.x + 0.5 + cos(a) * r
        val z = h.z + 0.5 + sin(a) * r
        var y = h.y + 2
        val floor = BlockPos.MutableBlockPos(x.toInt(), y, z.toInt())
        repeat(10) {
            floor.setY(y - 1)
            if (!l.getBlockState(floor).getCollisionShape(l, floor).isEmpty) return Vec3(x, y.toDouble(), z)
            y--
        }
        return Vec3(x, floorY + 1.0, z)
    }

    /** The fury's movement: straight at the dais, close, whatever the stage. */
    private fun closeIn(l: ServerLevel) {
        val h = home ?: blockPosition()
        val a = (tickCount / 40.0)
        val r = 4.5
        val y = if (grounded) floorY + 1.0 else h.y + 2.5
        moveControl.setWantedPosition(h.x + 0.5 + cos(a) * r, y, h.z + 0.5 + sin(a) * r, 1.4)
        wanderTarget = null
    }

    private fun retreat(l: ServerLevel) {
        val h = home ?: blockPosition()
        moveControl.setWantedPosition(h.x + 0.5, h.y + 1.0, h.z + 0.5, 1.0)
        if (--retreatLeft <= 0) {
            retreating = false
            setExposed(false)
            heal(RETREAT_HEAL)
            retreatLeft = -1
        }
    }

    /** The volley: the animation carries the bolts; the hit boxes leave the claws at 0.45 s and 0.75 s. */
    private fun beamAttack() {
        triggerAnim("overlay", "attack_beam")
        pendingBolts += (tickCount + 9) to -1
        pendingBolts += (tickCount + 15) to +1
        pendingBolts += (tickCount + 21) to -1
        pendingBolts += (tickCount + 27) to +1
    }

    private fun bolts(l: ServerLevel) {
        if (pendingBolts.isEmpty()) return
        val it = pendingBolts.iterator()
        while (it.hasNext()) {
            val (at, side) = it.next()
            if (tickCount < at) continue
            it.remove()
            val target = l.getNearestPlayer(this, 48.0)?.takeIf { !it.isCreative && !it.isSpectator } ?: continue
            val bolt = WardenBoltEntity(ModEntities.BOLT.get(), l)
            bolt.owner = this
            bolt.damage = BpmConfig.WARDEN_BEAM_DAMAGE.orDefault().toFloat()
            bolt.launch(claw(side), lead(target, WardenBoltEntity.SPEED))
            l.addFreshEntity(bolt)
        }
    }

    /** The open cage's special: one seeker from the chest, which turns toward its mark as it flies. */
    private fun seeker(l: ServerLevel, target: Player) {
        val bolt = WardenBoltEntity(ModEntities.BOLT.get(), l)
        bolt.owner = this
        bolt.damage = BpmConfig.WARDEN_BEAM_DAMAGE.orDefault().toFloat() + 2f
        val yaw = Math.toRadians(yRot.toDouble())
        val from = position().add(-sin(yaw) * 0.9, 1.6, cos(yaw) * 0.9)
        bolt.launch(from, target.getEyePosition(1f))
        bolt.seek(target)
        l.addFreshEntity(bolt)
    }

    /** Where a claw is: 1.2 out to the side, 0.4 forward, 1.8 up, in the body's frame. */
    private fun claw(side: Int): Vec3 {
        val yaw = Math.toRadians(yRot.toDouble())
        return position().add(-sin(yaw) * 0.4 + cos(yaw) * side * 1.2, 1.8, cos(yaw) * 0.4 + sin(yaw) * side * 1.2)
    }

    /** Where the target's eyes will be when a bolt at [speed] gets there — so a moving player is led, not chased. */
    private fun lead(target: LivingEntity, speed: Double): Vec3 {
        val eye = target.getEyePosition(1f)
        val ticks = eye.distanceTo(position()) / speed
        return eye.add(target.deltaMovement.scale(ticks * 0.8))
    }

    // ---- blink ----------------------------------------------------------------------------------------------

    private fun beginBlink(to: Vec3) {
        blinkTo = to
        blinkTicks = 34
        wanderTarget = null
    }

    /** Out (0.35 s, held), across the room, in (0.45 s) — and, for an ambush, the special as it lands. */
    private fun blink(l: ServerLevel) {
        blinkTicks--
        deltaMovement = Vec3.ZERO
        when (blinkTicks) {
            33 -> triggerAnim("overlay", "blink_out")
            24 -> {
                val to = blinkTo ?: position()
                teleportTo(to.x, to.y, to.z)
                triggerAnim("overlay", "blink_in")
            }
            12 -> ambush?.let { mark ->
                ambush = null
                if (mark.isAlive) {
                    facing = mark.getEyePosition(1f)
                    face()
                    seeker(l, mark)
                    beamAttack()
                    attackIn = 40
                }
            }
        }
    }

    /** A standing spot three blocks behind [p], on whatever floor is there; beside them if the room is in the way. */
    private fun behind(l: ServerLevel, p: Player): Vec3 {
        val look = p.lookAngle.let { Vec3(it.x, 0.0, it.z) }.let { if (it.lengthSqr() < 1e-4) Vec3(0.0, 0.0, 1.0) else it.normalize() }
        val candidates = listOf(p.position().subtract(look.scale(3.0)), p.position().add(look.yRot(Math.PI.toFloat() / 2).scale(3.0)), p.position().add(look.yRot(-Math.PI.toFloat() / 2).scale(3.0)))
        for (c in candidates) {
            var y = p.blockY + 2
            val feet = BlockPos.MutableBlockPos(c.x.toInt(), y, c.z.toInt())
            var ok = false
            repeat(6) {
                if (!ok) {
                    feet.setY(y - 1)
                    if (!l.getBlockState(feet).getCollisionShape(l, feet).isEmpty) ok = true else y--
                }
            }
            feet.setY(y)
            val room = l.getBlockState(feet).getCollisionShape(l, feet).isEmpty && l.getBlockState(feet.above()).getCollisionShape(l, feet.above()).isEmpty
            if (ok && room) return Vec3(c.x, y.toDouble(), c.z)
        }
        return floorPoint(l, home ?: blockPosition())
    }

    // ---- facing -------------------------------------------------------------------------------------------

    /** Point body and head at the target (or the way it is going); the model never spins to move. */
    private fun face() {
        val to = facing ?: wanderTarget ?: return
        val d = to.subtract(position())
        if (d.horizontalDistanceSqr() < 1e-4) return
        val yaw = Math.toDegrees(atan2(-d.x, d.z)).toFloat()
        yRot = yaw
        yBodyRot = yaw
        yHeadRot = yaw
        yRotO = yaw
        xRot = (-Math.toDegrees(atan2(d.y, sqrt(d.x * d.x + d.z * d.z)))).toFloat().coerceIn(-25f, 25f)
    }

    /** The body follows the yaw exactly — vanilla would turn it toward the movement, which is the spin. */
    override fun tickHeadTurn(yBodyRot: Float, animStep: Float): Float {
        this.yBodyRot = yRot
        this.yHeadRot = yRot
        return animStep
    }

    // ---- damage -----------------------------------------------------------------------------------------

    override fun takeDamage(level: ServerLevel, source: DamageSource, amount: Float): Boolean {
        if (invulnerable(level, source) || isDeadOrDying) return applyDamage(level, source, amount)
        val byPlayer = source.entity is Player || (source.directEntity as? Projectile)?.owner is Player
        if (byPlayer) lastPlayerHurtTick = tickCount
        var dealt = amount
        if (shielded && source.`is`(DamageTypeTags.IS_PROJECTILE)) dealt *= 0.5f
        if (exposed) {
            if (staggerCooldown == 0 && !retreating) {
                triggerAnim("overlay", "stagger")
                staggerCooldown = 20
            }
            return applyDamage(level, source, dealt)
        }
        if (shielded && plateHp > 0f) {
            plateHp -= dealt
            if (plateHp <= 0f) {
                plateHp = 0f
                plateRegenAt = tickCount + PLATE_REGEN_TICKS
                entityData.set(SHIELD, false)
                triggerAnim("overlay", "stagger")
                setExposed(true, (BpmConfig.exposeSeconds(2) * 20).toInt())
            }
        }
        return applyDamage(level, source, dealt * ChamberFight.cageMultiplier(this))
    }

    /** The linker's pulse: damage the cage cannot quarter, counted as the player's hit. */
    fun pulseHit(amount: Float, by: Entity?) {
        val level = level() as? ServerLevel ?: return
        if (isDeadOrDying) return
        if (by is Player) lastPlayerHurtTick = tickCount
        applyDamage(level, bpm.world.BpmDamage.source(level, bpm.world.BpmDamage.LINKER_PULSE, by), amount)
        stagger()
    }

    /** A hit reaction the room can ask for (a crystal shattering). */
    fun stagger() {
        if (staggerCooldown == 0) {
            triggerAnim("overlay", "stagger")
            staggerCooldown = 20
        }
    }

    override fun immuneTo(source: DamageSource): Boolean =
        source.`is`(DamageTypeTags.IS_FALL) || source.`is`(DamageTypeTags.IS_DROWNING) ||
            source.`is`(DamageTypes.IN_WALL) || source.`is`(DamageTypes.CRAMMING) || source.`is`(DamageTypeTags.IS_FIRE)

    override fun isPushable(): Boolean = false
    override fun removeWhenFarAway(distance: Double): Boolean = false
    override fun canBeLeashed(): Boolean = false

    /** A boss, not a spawn: Peaceful must not unmake it the tick it rises. */
    public override fun shouldDespawnInPeaceful(): Boolean = false

    // ---- death: the core goes home -------------------------------------------------------------------------

    override fun die(source: DamageSource) {
        killedByPlayer = tickCount - lastPlayerHurtTick <= PLAYER_KILL_WINDOW
        super.die(source)
        if (!level().isClientSide) ChamberFight.onWardenDeath(this, killedByPlayer)
    }

    override fun tickDeath() {
        deathTime++
        deltaMovement = Vec3(0.0, deltaMovement.y.coerceAtMost(0.0), 0.0)
        if (level().isClientSide) return
        if (deathTime == CORE_RETURN_TICK) returnCore()
        if (deathTime >= DEATH_TICKS) remove(Entity.RemovalReason.KILLED)
    }

    /** The core lands on the pedestal it rose from. */
    fun returnCore() {
        val h = home ?: return
        (level().getBlockEntity(h) as? PedestalBlockEntity)?.setHasCore(true)
    }

    override fun remove(reason: Entity.RemovalReason) {
        // Unmade without dying (a command, a despawn): the fight did not happen — the core goes back.
        val vanished = reason == Entity.RemovalReason.DISCARDED && !isDeadOrDying && !level().isClientSide
        super.remove(reason)
        bossEvent.removeAllPlayers()
        if (vanished) ChamberFight.onWardenVanished(this)
    }

    override fun startSeenByPlayer(player: ServerPlayer) {
        super.startSeenByPlayer(player)
        bossEvent.addPlayer(player)
    }

    override fun stopSeenByPlayer(player: ServerPlayer) {
        super.stopSeenByPlayer(player)
        bossEvent.removePlayer(player)
    }

    // ---- save ---------------------------------------------------------------------------------------------

    override fun saveExtra(tag: CompoundTag) {
        home?.let { bpm.platform.putBlockPos(tag, "home", it) }
        slotOwner?.let { bpm.platform.putUuid(tag, "slotOwner", it) }
        tag.putInt("stage", stage)
        tag.putBoolean("shield", shielded)
        tag.putBoolean("grounded", grounded)
        tag.putFloat("plateHp", plateHp)
        tag.putInt("spawnTicks", spawnTicks)
    }

    override fun loadExtra(tag: CompoundTag) {
        home = bpm.platform.blockPosOrNull(tag, "home")
        slotOwner = bpm.platform.uuidOrNull(tag, "slotOwner")
        entityData.set(STAGE, tag.intOr("stage", 0).coerceIn(1, 3))
        entityData.set(SHIELD, tag.boolOr("shield", false))
        entityData.set(GROUNDED, tag.boolOr("grounded", false))
        setNoGravity(!tag.boolOr("grounded", false))
        plateHp = tag.floatOr("plateHp", 0f)
        spawnTicks = tag.intOr("spawnTicks", 0)
    }

    // ---- geckolib -----------------------------------------------------------------------------------------

    override fun registerControllers(controllers: bpm.platform.ControllerRegistrar) {
        controllers.add(
            bpm.platform.animController(this, "main", 4) { state ->
                when {
                    isDeadOrDying -> state.setAndContinue(DEATH)
                    tickCount < SPAWN_TICKS -> state.setAndContinue(SPAWN)
                    deltaMovement.horizontalDistanceSqr() > 0.004 -> state.setAndContinue(MOVE)
                    else -> state.setAndContinue(IDLE)
                }
            },
        )
        controllers.add(
            bpm.platform.animController(this, "overlay", 0) { PlayState.STOP }
                .triggerableAnim("attack_beam", ATTACK).triggerableAnim("stagger", STAGGER)
                .triggerableAnim("blink_out", BLINK_OUT).triggerableAnim("blink_in", BLINK_IN),
        )
        controllers.add(
            bpm.platform.animController(this, "core", 0) { PlayState.STOP }
                .triggerableAnim("expose", EXPOSE).triggerableAnim("retract", RETRACT),
        )
    }

    override fun getAnimatableInstanceCache(): AnimatableInstanceCache = animCache

    companion object {
        const val PLATES = 4
        const val SPAWN_TICKS = 40
        const val DEATH_TICKS = 60
        const val CORE_RETURN_TICK = 40
        const val PLATE_REGEN_TICKS = 400
        const val RETREAT_FRACTION = 0.1f
        const val RETREAT_TICKS = 400
        const val RETREAT_HEAL = 20f
        const val PLAYER_KILL_WINDOW = 200
        const val GROUNDED_AT = 0.5f
        const val BLINK_EVERY = 120
        const val SEEKER_EVERY = 40
        const val SPECIAL_EVERY = 140
        const val SPECIAL_JITTER = 40
        const val SPIKES_FIRST = 100
        const val SPIKES_EVERY = 200
        const val SPIKES_JITTER = 60
        const val AMBUSH_FRACTION = 0.1f
        const val FURY_ATTACK_EVERY = 28
        const val FURY_SEEKER_EVERY = 45

        val STAGE: EntityDataAccessor<Int> = SynchedEntityData.defineId(QuantumWardenEntity::class.java, EntityDataSerializers.INT)
        val SHIELD: EntityDataAccessor<Boolean> = SynchedEntityData.defineId(QuantumWardenEntity::class.java, EntityDataSerializers.BOOLEAN)
        val EXPOSED: EntityDataAccessor<Boolean> = SynchedEntityData.defineId(QuantumWardenEntity::class.java, EntityDataSerializers.BOOLEAN)
        val GROUNDED: EntityDataAccessor<Boolean> = SynchedEntityData.defineId(QuantumWardenEntity::class.java, EntityDataSerializers.BOOLEAN)
        val DISABLED: EntityDataAccessor<Boolean> = SynchedEntityData.defineId(QuantumWardenEntity::class.java, EntityDataSerializers.BOOLEAN)

        val IDLE: RawAnimation = RawAnimation.begin().thenLoop("animation.quantum_warden.idle")
        val MOVE: RawAnimation = RawAnimation.begin().thenLoop("animation.quantum_warden.move")
        val SPAWN: RawAnimation = RawAnimation.begin().thenPlay("animation.quantum_warden.spawn").thenLoop("animation.quantum_warden.idle")
        val DEATH: RawAnimation = RawAnimation.begin().thenPlayAndHold("animation.quantum_warden.death")
        val ATTACK: RawAnimation = RawAnimation.begin().thenPlay("animation.quantum_warden.attack_beam")
        val STAGGER: RawAnimation = RawAnimation.begin().thenPlay("animation.quantum_warden.stagger")
        val BLINK_OUT: RawAnimation = RawAnimation.begin().thenPlayAndHold("animation.quantum_warden.blink_out")
        val BLINK_IN: RawAnimation = RawAnimation.begin().thenPlay("animation.quantum_warden.blink_in")
        val EXPOSE: RawAnimation = RawAnimation.begin().thenPlayAndHold("animation.quantum_warden.expose")
        val RETRACT: RawAnimation = RawAnimation.begin().thenPlay("animation.quantum_warden.retract")

        fun attributes(): AttributeSupplier.Builder = createMonsterAttributes()
            .add(Attributes.MAX_HEALTH, 500.0)
            .add(Attributes.ARMOR, 8.0)
            .add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
            .add(Attributes.FLYING_SPEED, 0.6)
            .add(Attributes.MOVEMENT_SPEED, 0.3)
            .add(Attributes.FOLLOW_RANGE, 48.0)
            .add(Attributes.ATTACK_DAMAGE, 6.0)

        /** A Warden rising from the pedestal at [pedestal], bound to [owner]'s slot (null outside a chamber). */
        fun spawnAt(level: ServerLevel, pedestal: BlockPos, owner: UUID?): QuantumWardenEntity {
            val w = QuantumWardenEntity(ModEntities.WARDEN.get(), level)
            w.home = pedestal
            w.slotOwner = owner
            w.getAttribute(Attributes.MAX_HEALTH)?.baseValue = BpmConfig.WARDEN_HEALTH.orDefault()
            w.health = w.maxHealth
            w.setPos(pedestal.x + 0.5, pedestal.y + 1.5, pedestal.z + 0.5)
            level.addFreshEntity(w)
            return w
        }
    }
}

/**
 * Moves the Warden by steering its velocity toward the wanted point — never by turning it, which is what
 * vanilla's flying control does and what made it spin. Flying: full 3-D steering; grounded: horizontal only,
 * gravity keeps it on the floor.
 */
class WardenMoveControl(private val warden: QuantumWardenEntity) : MoveControl(warden) {
    override fun tick() {
        val v = warden.deltaMovement
        if (operation != Operation.MOVE_TO) {
            if (!warden.grounded) warden.deltaMovement = v.scale(0.8)
            return
        }
        operation = Operation.WAIT
        val d = Vec3(wantedX, wantedY, wantedZ).subtract(warden.position())
        val dist = if (warden.grounded) d.horizontalDistance() else d.length()
        if (dist < 0.3) {
            warden.deltaMovement = if (warden.grounded) Vec3(v.x * 0.5, v.y, v.z * 0.5) else v.scale(0.5)
            return
        }
        val speed = speedModifier * (if (warden.grounded) GROUND_SPEED else FLY_SPEED)
        if (warden.grounded) {
            val dir = Vec3(d.x, 0.0, d.z).normalize()
            warden.deltaMovement = Vec3(lerp(v.x, dir.x * speed), v.y, lerp(v.z, dir.z * speed))
        } else {
            val dir = d.normalize()
            warden.deltaMovement = v.scale(1 - BLEND).add(dir.scale(speed * BLEND))
        }
    }

    private fun lerp(from: Double, to: Double) = from + (to - from) * BLEND

    companion object {
        const val FLY_SPEED = 0.32
        const val GROUND_SPEED = 0.24
        const val BLEND = 0.25
    }
}

/** How long the cage stays open, per stage — `warden.exposeSeconds [3, 4, 2]`. */
private fun BpmConfig.exposeSeconds(stage: Int): Double = when (stage) { 1 -> 3.0; 2 -> 4.0; else -> 2.0 }
