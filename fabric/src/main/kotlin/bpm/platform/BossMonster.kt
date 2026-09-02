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
