package bpm.platform

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.monster.Monster
import net.minecraft.world.level.Level

/**
 * A monster whose fight logic and damage handling are written once.
 *
 * 1.21.2 made explicit something that was always true: the three hooks a boss needs -- its AI step, its
 * damage handler and its invulnerability test -- only ever run on the server, so each of them now takes
 * the `ServerLevel` rather than leaving the callee to cast for it. `Entity.hurt` became FINAL and split
 * into `hurtServer` and `hurtClient`, which is the reason this cannot be an alias: there is no longer
 * one method with two spellings, there are two methods where there was one.
 *
 * The subclass writes the fight; this decides which of those it is called through, and hands it the
 * level it was going to cast for anyway. [applyDamage] is what `super.hurt` used to mean, and exists
 * because a subclass adjusting damage still has to be able to pass the result on to vanilla.
 */
abstract class BossMonster(type: EntityType<out Monster>, level: Level) : Monster(type, level) {

    /** One server tick of the fight. */
    protected abstract fun fightTick(level: ServerLevel)

    /** Take [amount] from [source]; answer whether it landed. Call [applyDamage] to let vanilla have it. */
    protected abstract fun takeDamage(level: ServerLevel, source: DamageSource, amount: Float): Boolean

    /** Damage kinds this boss ignores outright, over and above whatever vanilla already refuses. */
    protected abstract fun immuneTo(source: DamageSource): Boolean

    /**
     * Hold the body and head to the entity's own yaw, instead of letting vanilla turn them toward the
     * movement. False leaves vanilla's turning alone.
     *
     * `tickHeadTurn` used to take the body rotation AND an animation step and hand the step back; at
     * 1.21.9 it takes the rotation and returns nothing. Nothing this mod does with it ever touched the
     * step -- it was passed straight through -- so the seam is the question rather than the signature.
     */
    protected open fun holdHeadToYaw(): Boolean = false

    //? if >=1.21.9 {
    /*override fun tickHeadTurn(yBodyRot: Float) {
        if (holdHeadToYaw()) {
            this.yBodyRot = yRot
            this.yHeadRot = yRot
            return
        }
        super.tickHeadTurn(yBodyRot)
    }

    /*
     * There is no `shouldDespawnInPeaceful` on this band. The question moved to registration:
     * `EntityType.isAllowedInPeaceful`, which is true unless the builder says `notInPeaceful()`. A boss
     * registered without that call already survives Peaceful, so the override that used to be needed is
     * simply absent -- the behaviour is unchanged and it is now decided in the one place it belongs.
     */
    *///?} else {
    override fun tickHeadTurn(yBodyRot: Float, animStep: Float): Float {
        if (!holdHeadToYaw()) return super.tickHeadTurn(yBodyRot, animStep)
        this.yBodyRot = yRot
        this.yHeadRot = yRot
        return animStep
    }

    /** A boss, not a spawn: Peaceful must not unmake it the tick it rises. */
    override fun shouldDespawnInPeaceful(): Boolean = false
    //?}

    /**
     * Write and read this boss's own fields, as a compound.
     *
     * The same bridge [SavingProjectile] carries, spelled out again because a monster cannot extend a
     * projectile. See [bpm.platform.SavingBlockEntity] for why the mod keeps CompoundTag as its own
     * vocabulary rather than following vanilla's writer into the shared tree.
     */
    protected open fun saveExtra(tag: net.minecraft.nbt.CompoundTag) {}

    protected open fun loadExtra(tag: net.minecraft.nbt.CompoundTag) {}

    //? if >=1.21.9 {
    /*override fun addAdditionalSaveData(output: net.minecraft.world.level.storage.ValueOutput) {
        super.addAdditionalSaveData(output)
        val tag = net.minecraft.nbt.CompoundTag()
        saveExtra(tag)
        pushTag(output, tag)
    }

    override fun readAdditionalSaveData(input: net.minecraft.world.level.storage.ValueInput) {
        super.readAdditionalSaveData(input)
        loadExtra(pullTag(input))
    }
    *///?} else {
    override fun addAdditionalSaveData(tag: net.minecraft.nbt.CompoundTag) {
        super.addAdditionalSaveData(tag)
        saveExtra(tag)
    }

    override fun readAdditionalSaveData(tag: net.minecraft.nbt.CompoundTag) {
        super.readAdditionalSaveData(tag)
        loadExtra(tag)
    }
    //?}

    //? if >=1.21.2 {
    /*override fun customServerAiStep(level: ServerLevel) = fightTick(level)

    override fun hurtServer(level: ServerLevel, source: DamageSource, amount: Float): Boolean =
        takeDamage(level, source, amount)

    override fun isInvulnerableTo(level: ServerLevel, source: DamageSource): Boolean =
        super.isInvulnerableTo(level, source) || immuneTo(source)

    /** Vanilla's own damage handling -- what `super.hurt` meant before the split. */
    protected fun applyDamage(level: ServerLevel, source: DamageSource, amount: Float): Boolean =
        super.hurtServer(level, source, amount)

    /** The full invulnerability answer, vanilla's included. */
    protected fun invulnerable(level: ServerLevel, source: DamageSource): Boolean = isInvulnerableTo(level, source)
    *///?} else {
    override fun customServerAiStep() {
        (level() as? ServerLevel)?.let(::fightTick)
    }

    override fun hurt(source: DamageSource, amount: Float): Boolean {
        val server = level() as? ServerLevel ?: return super.hurt(source, amount)
        return takeDamage(server, source, amount)
    }

    override fun isInvulnerableTo(source: DamageSource): Boolean = super.isInvulnerableTo(source) || immuneTo(source)

    protected fun applyDamage(level: ServerLevel, source: DamageSource, amount: Float): Boolean =
        super.hurt(source, amount)

    protected fun invulnerable(level: ServerLevel, source: DamageSource): Boolean = isInvulnerableTo(source)
    //?}
}
