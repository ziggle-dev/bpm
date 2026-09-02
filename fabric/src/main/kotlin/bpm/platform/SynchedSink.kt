package bpm.platform

import net.minecraft.network.syncher.EntityDataAccessor

/**
 * Where an entity declares its synched fields.
 *
 * 1.20.5 changed both halves of this. The override became `defineSynchedData(builder)` where it had been
 * `defineSynchedData()`, and the fields are declared on the BUILDER rather than on the entity's own
 * `entityData`. A subclass cannot declare both arities, so the override lives in the per-entity base and
 * hands the shared subclass one of these.
 */
//? if >=1.20.5 {
class SynchedSink(private val builder: net.minecraft.network.syncher.SynchedEntityData.Builder) {
    fun <T> define(key: EntityDataAccessor<T>, value: T) {
        builder.define(key, value)
    }
}
//?} else {
/*class SynchedSink(private val data: net.minecraft.network.syncher.SynchedEntityData) {
    fun <T> define(key: EntityDataAccessor<T>, value: T) {
        data.define(key, value)
    }
}
*///?}
