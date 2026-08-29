package bpm.world

import bpm.Bpm
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageType
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level

/** The mod's damage types (data/bpm/damage_type), looked up on the level they are dealt in. */
object BpmDamage {
    val SPIKE: ResourceKey<DamageType> = key("spike")
    val VENT: ResourceKey<DamageType> = key("vent")
    val TURRET: ResourceKey<DamageType> = key("turret")
    val WARDEN_BEAM: ResourceKey<DamageType> = key("warden_beam")
    val LINKER_PULSE: ResourceKey<DamageType> = key("linker_pulse")
    val DECOHERENCE: ResourceKey<DamageType> = key("decoherence")

    private fun key(name: String) = ResourceKey.create(Registries.DAMAGE_TYPE, ResourceLocation.fromNamespaceAndPath(Bpm.ID, name))

    fun source(level: Level, type: ResourceKey<DamageType>, attacker: Entity? = null): DamageSource {
        val holder = level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(type)
        return if (attacker == null) DamageSource(holder) else DamageSource(holder, attacker)
    }
}
