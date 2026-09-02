package bpm.platform

import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.level.Level

/**
 * A projectile that declares synched fields.
 *
 * The counterpart of [BossMonster]'s handling and for the same reason: `defineSynchedData` changed arity
 * at 1.20.5, a subclass cannot declare both, and `LinkerPulseEntity` extends `Projectile` directly rather
 * than through any base of ours. This is that base.
 */
abstract class SynchedProjectile(type: EntityType<out Projectile>, level: Level) : Projectile(type, level) {

    /** Declare this projectile's synched fields; see [SynchedSink]. */
    protected abstract fun defineSynched(sink: SynchedSink)

    //? if >=1.20.5 {
    final override fun defineSynchedData(builder: net.minecraft.network.syncher.SynchedEntityData.Builder) {
        defineSynched(SynchedSink(builder))
    }
    //?} else {
    /*final override fun defineSynchedData() {
        defineSynched(SynchedSink(entityData))
    }
    *///?}
}
