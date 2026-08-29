package bpm.world.entity

import bpm.Bpm
import net.minecraft.core.registries.Registries
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister

object ModEntities {
    val REG: DeferredRegister<EntityType<*>> = DeferredRegister.create(Registries.ENTITY_TYPE, Bpm.ID)

    val WARDEN: DeferredHolder<EntityType<*>, EntityType<QuantumWardenEntity>> = REG.register("quantum_warden") { ->
        EntityType.Builder.of(::QuantumWardenEntity, MobCategory.MONSTER).sized(2.4f, 3.2f).fireImmune().clientTrackingRange(12).build("quantum_warden")
    }

    val BOLT: DeferredHolder<EntityType<*>, EntityType<WardenBoltEntity>> = REG.register("warden_bolt") { ->
        EntityType.Builder.of(::WardenBoltEntity, MobCategory.MISC).sized(0.3f, 0.3f).noSave().noSummon().clientTrackingRange(8).updateInterval(2).build("warden_bolt")
    }

    val PULSE: DeferredHolder<EntityType<*>, EntityType<LinkerPulseEntity>> = REG.register("linker_pulse") { ->
        EntityType.Builder.of(::LinkerPulseEntity, MobCategory.MISC).sized(0.25f, 0.25f).noSave().noSummon().clientTrackingRange(8).updateInterval(2).build("linker_pulse")
    }

    fun attributes(event: EntityAttributeCreationEvent) {
        event.put(WARDEN.get(), QuantumWardenEntity.attributes().build())
    }
}
