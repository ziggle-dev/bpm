package bpm.world.entity

import bpm.world.BpmDamage
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.entity.projectile.ProjectileUtil
import net.minecraft.world.level.Level
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import bpm.platform.floatOr

/**
 * The Warden's bolt: a fast energy spear drawn by [bpm.client.render.WardenBoltRenderer] — teal for the
 * volley, orchid and slower for the *seeker* the open cage fires, which bends toward its target a little
 * every tick and can be outrun or ducked behind cover. Breaks on whatever it meets first.
 */
class WardenBoltEntity(type: EntityType<out WardenBoltEntity>, level: Level) : bpm.platform.SavingProjectile(type, level) {
    var damage: Float = 6f
    var speed: Double = SPEED
    var life: Int = LIFE
    private var age = 0
    private var targetId: Int = -1
    private var lastTargetPos: Vec3? = null

    val homing: Boolean get() = entityData.get(HOMING)

    fun launch(from: Vec3, toward: Vec3) {
        setPos(from.x, from.y, from.z)
        deltaMovement = toward.subtract(from).normalize().scale(speed)
    }

    /** A seeker: slower, longer-lived, turning toward [target] by [TURN] of the way each tick. */
    fun seek(target: LivingEntity) {
        entityData.set(HOMING, true)
        targetId = target.id
        speed = SEEK_SPEED
        life = SEEK_LIFE
        deltaMovement = deltaMovement.normalize().scale(speed)
    }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        builder.define(HOMING, false)
    }

    override fun tick() {
        super.tick()
        if (!level().isClientSide && homing) steer()
        val hit = ProjectileUtil.getHitResultOnMoveVector(this) { canHitEntity(it) }
        if (hit.type != HitResult.Type.MISS) onHit(hit)
        if (isRemoved) return
        val next = position().add(deltaMovement)
        setPos(next.x, next.y, next.z)
        (level() as? ServerLevel)?.let { l ->
            if (homing) l.sendParticles(ParticleTypes.WITCH, x, y, z, 2, 0.05, 0.05, 0.05, 0.0)
            else if (age % 2 == 0) l.sendParticles(ParticleTypes.ELECTRIC_SPARK, x, y, z, 1, 0.02, 0.02, 0.02, 0.0)
        }
        if (++age > life) discard()
    }

    private fun steer() {
        val target = level().getEntity(targetId) as? LivingEntity ?: return
        if (!target.isAlive) return
        // A mark that jumps — a vent, a blink — is lost: the seeker keeps its last heading.
        val at = target.position()
        val was = lastTargetPos
        lastTargetPos = at
        if (was != null && was.distanceToSqr(at) > LOSE_LOCK_DISTANCE * LOSE_LOCK_DISTANCE) {
            targetId = -1
            lastTargetPos = null
            return
        }
        val want = target.getEyePosition(1f).subtract(position()).normalize()
        val have = deltaMovement.normalize()
        val dir = have.scale(1.0 - TURN).add(want.scale(TURN)).normalize()
        deltaMovement = dir.scale(speed)
    }

    override fun canHitEntity(target: Entity): Boolean = super.canHitEntity(target) && target !is QuantumWardenEntity && target !is WardenBoltEntity

    override fun onHitEntity(result: EntityHitResult) {
        super.onHitEntity(result)
        if (!level().isClientSide) result.entity.hurt(BpmDamage.source(level(), BpmDamage.WARDEN_BEAM, getOwner()), damage)
        discard()
    }

    override fun onHitBlock(result: BlockHitResult) {
        super.onHitBlock(result)
        discard()
    }

    override fun loadExtra(tag: CompoundTag, registries: net.minecraft.core.HolderLookup.Provider) {
        damage = tag.floatOr("damage", 0f)
    }

    override fun saveExtra(tag: CompoundTag, registries: net.minecraft.core.HolderLookup.Provider) {
        tag.putFloat("damage", damage)
    }

    companion object {
        const val SPEED = 2.0
        const val LIFE = 24
        const val SEEK_SPEED = 0.85
        const val SEEK_LIFE = 100
        const val TURN = 0.3
        const val LOSE_LOCK_DISTANCE = 5.0

        val HOMING: EntityDataAccessor<Boolean> = SynchedEntityData.defineId(WardenBoltEntity::class.java, EntityDataSerializers.BOOLEAN)
    }
}
