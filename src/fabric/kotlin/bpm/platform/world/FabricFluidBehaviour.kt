package bpm.platform.world

import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariantAttributes
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.level.material.Fluid

/**
 * Fabric keeps a fluid's sensory properties on `FluidVariantAttributes`, the transfer API's answer to
 * NeoForge's `FluidType`. Same three questions, a different registry.
 *
 * The vaporize pair is left to the seam's vanilla-accurate default: water hisses away in an ultrawarm
 * dimension and nothing else does. NeoForge can do better because other mods tell its fluid-type
 * registry about their own liquids; Fabric's attribute handlers do not carry that fact, so answering
 * from the dimension is both what vanilla does and the most this loader actually knows.
 */
object FabricFluidBehaviour : FluidBehaviour by DefaultsFrom() {

    override fun emptySound(fluid: Fluid): SoundEvent =
        FluidVariantAttributes.getEmptySound(FluidVariant.of(fluid))

    override fun fillSound(fluid: Fluid): SoundEvent =
        FluidVariantAttributes.getFillSound(FluidVariant.of(fluid))

    override fun displayName(fluid: Fluid): Component =
        FluidVariantAttributes.getName(FluidVariant.of(fluid))
}

/**
 * Only exists to inherit the interface's default methods, which Kotlin will not let an object do while
 * also overriding some of them by delegation. Everything it does not implement comes from
 * [FluidBehaviour]'s own defaults.
 */
private class DefaultsFrom : FluidBehaviour {
    override fun vaporizesIn(
        level: net.minecraft.server.level.ServerLevel,
        pos: net.minecraft.core.BlockPos,
        volume: bpm.platform.ports.FluidVolume,
    ): Boolean = level.dimensionType().ultraWarm() && volume.fluid.`is`(net.minecraft.tags.FluidTags.WATER)

    override fun vaporize(
        level: net.minecraft.server.level.ServerLevel,
        pos: net.minecraft.core.BlockPos,
        volume: bpm.platform.ports.FluidVolume,
    ) {
        level.playSound(null, pos, SoundEvents.FIRE_EXTINGUISH, net.minecraft.sounds.SoundSource.BLOCKS, 0.5f, 2.6f)
        repeat(8) {
            level.sendParticles(
                net.minecraft.core.particles.ParticleTypes.LARGE_SMOKE,
                pos.x + Math.random(), pos.y + Math.random(), pos.z + Math.random(), 1, 0.0, 0.0, 0.0, 0.0,
            )
        }
    }
}
