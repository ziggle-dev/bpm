package bpm.world.entity

import bpm.world.devices.TurretBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.entity.projectile.ProjectileUtil
import net.minecraft.world.level.Level
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import bpm.platform.showMessage

/**
 * The Quantum Linker's pulse, fired inside a chamber. The quick pulse (right-click, free) flies straight: a
 * turret it lands on goes dark for fifteen seconds; on the Warden it only stings — more on an open core,
 * a scratch on the closed cage. Only the *special* (sneak + click, three a recharge, a long cooldown), which
 * locks on the Warden and turns until it lands, takes it down.
 */
class LinkerPulseEntity(type: EntityType<out LinkerPulseEntity>, level: Level) : Projectile(type, level) {
    private var age = 0
    private var life = LIFE
    private var speed = SPEED
    private var targetEntity: Int = -1
    private var targetBlock: BlockPos? = null

    val tracking: Boolean get() = entityData.get(TRACKING)

    fun launch(from: Vec3, direction: Vec3) {
        setPos(from.x, from.y, from.z)
        deltaMovement = direction.normalize().scale(speed)
    }

    /** Lock onto a living target: slower, long-lived, turning hard until it lands. */
    fun seek(target: LivingEntity) {
        entityData.set(TRACKING, true)
        targetEntity = target.id
        targetBlock = null
        speed = TRACK_SPEED
        life = TRACK_LIFE
        deltaMovement = deltaMovement.normalize().scale(speed)
    }

    /** Lock onto a block — a turret. */
    fun seekBlock(pos: BlockPos) {
        entityData.set(TRACKING, true)
        targetBlock = pos
        targetEntity = -1
        speed = TRACK_SPEED
        life = TRACK_LIFE
        deltaMovement = deltaMovement.normalize().scale(speed)
    }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        builder.define(TRACKING, false)
    }

    override fun tick() {
        super.tick()
        if (!level().isClientSide && tracking) steer()
        val hit = ProjectileUtil.getHitResultOnMoveVector(this) { canHitEntity(it) }
        if (hit.type != HitResult.Type.MISS) onHit(hit)
        if (isRemoved) return
        val next = position().add(deltaMovement)
        setPos(next.x, next.y, next.z)
        (level() as? ServerLevel)?.sendParticles(if (tracking) ParticleTypes.GLOW else ParticleTypes.END_ROD, x, y, z, 1, 0.02, 0.02, 0.02, 0.0)
        if (++age > life) discard()
    }

    private fun steer() {
        val at: Vec3 = when {
            targetEntity >= 0 -> (level().getEntity(targetEntity) as? LivingEntity)?.takeIf { it.isAlive }?.let { it.position().add(0.0, it.bbHeight * 0.55, 0.0) } ?: return
            targetBlock != null -> Vec3.atCenterOf(targetBlock!!)
            else -> return
        }
        val want = at.subtract(position()).normalize()
        val have = deltaMovement.normalize()
        deltaMovement = have.scale(1.0 - TURN).add(want.scale(TURN)).normalize().scale(speed)
    }

    override fun canHitEntity(target: Entity): Boolean = super.canHitEntity(target) && target !is WardenBoltEntity && target !is LinkerPulseEntity

    private fun tell(text: String) {
        (owner as? Player)?.showMessage(Component.literal("[bpm] $text"), true)
    }

    override fun onHitEntity(result: EntityHitResult) {
        super.onHitEntity(result)
        if (!level().isClientSide) {
            val w = result.entity as? QuantumWardenEntity
            when {
                w == null -> {}
                w.disabled -> {
                    w.pulseHit(CORE_DAMAGE, getOwner())
                    tell("the pulse bites the fallen Warden")
                }
                tracking -> {
                    w.disable(WARDEN_TICKS)
                    w.pulseHit(CORE_DAMAGE, getOwner())
                    tell("the special seizes the Warden — it falls")
                }
                w.exposed -> {
                    w.pulseHit(CAGE_DAMAGE, getOwner())
                    tell("the pulse stings the open core")
                }
                else -> {
                    w.pulseHit(CAGE_DAMAGE * 0.5f, getOwner())
                    tell("the pulse breaks on the closed cage")
                }
            }
        }
        discard()
    }

    override fun onHitBlock(result: BlockHitResult) {
        super.onHitBlock(result)
        if (!level().isClientSide && !tracking) {
            val turret = level().getBlockEntity(result.blockPos) as? TurretBlockEntity
            if (turret != null) {
                if (turret.disabled) tell("the turret is already dark") else {
                    turret.disable(TURRET_TICKS)
                    tell("the turret goes dark for ${TURRET_TICKS / 20} s")
                }
            } else if (result.direction == net.minecraft.core.Direction.UP) {
                launch(result)
            }
        }
        discard()
    }

    /** A quick pulse into the floor at your feet throws you five to ten blocks up, and the landing is free. */
    private fun launch(result: BlockHitResult) {
        val p = owner as? Player ?: return
        val l = level() as? ServerLevel ?: return
        if (p.distanceToSqr(result.location) > LAUNCH_REACH * LAUNCH_REACH) return
        val v = LAUNCH_MIN + l.random.nextDouble() * (LAUNCH_MAX - LAUNCH_MIN)
        p.deltaMovement = Vec3(p.deltaMovement.x * 0.4, v, p.deltaMovement.z * 0.4)
        p.hurtMarked = true
        p.currentImpulseImpactPos = result.location
        p.setIgnoreFallDamageFromCurrentImpulse(true)
        p.resetFallDistance()
        l.sendParticles(ParticleTypes.GUST, result.location.x, result.location.y + 0.1, result.location.z, 1, 0.0, 0.0, 0.0, 0.0)
        l.sendParticles(ParticleTypes.CLOUD, result.location.x, result.location.y + 0.2, result.location.z, 16, 0.5, 0.1, 0.5, 0.05)
        l.playSound(null, result.location.x, result.location.y, result.location.z, net.minecraft.sounds.SoundEvents.WIND_CHARGE_BURST.value(), net.minecraft.sounds.SoundSource.PLAYERS, 0.8f, 1.1f)
    }

    companion object {
        const val SPEED = 1.6
        const val LIFE = 40
        const val TRACK_SPEED = 1.1
        const val TRACK_LIFE = 200
        const val TURN = 0.45
        const val TURRET_TICKS = 300
        const val WARDEN_TICKS = 300
        const val CORE_DAMAGE = 12f
        const val CAGE_DAMAGE = 6f
        const val LAUNCH_REACH = 4.5
        const val LAUNCH_MIN = 1.0
        const val LAUNCH_MAX = 1.35

        val TRACKING: EntityDataAccessor<Boolean> = SynchedEntityData.defineId(LinkerPulseEntity::class.java, EntityDataSerializers.BOOLEAN)
    }
}
