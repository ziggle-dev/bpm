package bpm.platform.world

import bpm.platform.ports.FluidVolume
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.tags.FluidTags
import net.minecraft.world.level.material.Fluid

/**
 * What a fluid *does* when it is poured or scooped, as opposed to how much of it there is.
 *
 * NeoForge answers these through `FluidType`, a registry Fabric has no equivalent of. They are the last
 * three things `fluids.place` and `fluids.pickup` need that are not vanilla, and they are behaviour
 * rather than storage — which is why they are here and not on [bpm.platform.ports.FluidPort].
 *
 * A millibucket is a millibucket everywhere; whether lava hisses away in the Nether is not.
 */
interface FluidBehaviour {

    /** Whether pouring this out here would make it vanish in steam rather than place a block. */
    fun vaporizesIn(level: ServerLevel, pos: BlockPos, volume: FluidVolume): Boolean

    /** Do the vanishing: the hiss and the smoke. Only called when [vaporizesIn] said so. */
    fun vaporize(level: ServerLevel, pos: BlockPos, volume: FluidVolume)

    fun emptySound(fluid: Fluid): SoundEvent = SoundEvents.BUCKET_EMPTY

    fun fillSound(fluid: Fluid): SoundEvent = SoundEvents.BUCKET_FILL

    /**
     * What to call this fluid on a monitor widget or in a script's `name` field.
     *
     * Vanilla has no answer — water and lava are named by their bucket items and nothing else has a name
     * at all — so both loaders invented one: NeoForge hangs it off `FluidType.description`, Fabric off
     * `FluidVariantAttributes`. The default follows the same key convention this mod uses for its own
     * fluid, so an unported loader shows a sensible translated name rather than nothing.
     */
    fun displayName(fluid: Fluid): Component {
        val id = net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(fluid)
        return Component.translatable("fluid.${id.namespace}.${id.path}")
    }
}

/**
 * The installed behaviour.
 *
 * The default is vanilla-accurate rather than absent: water vaporizes in an ultrawarm dimension, and the
 * bucket sounds are the bucket sounds. A loader that knows more — NeoForge does, because other mods
 * register fluid types with it — overrides. That way a Fabric build without a fluid-type registry is
 * merely less informed about modded fluids, not broken.
 */
object Fluids {
    private var backend: FluidBehaviour = object : FluidBehaviour {
        override fun vaporizesIn(level: ServerLevel, pos: BlockPos, volume: FluidVolume): Boolean =
            level.dimensionType().ultraWarm() && volume.fluid.`is`(FluidTags.WATER)

        override fun vaporize(level: ServerLevel, pos: BlockPos, volume: FluidVolume) {
            level.playSound(null, pos, SoundEvents.FIRE_EXTINGUISH, net.minecraft.sounds.SoundSource.BLOCKS, 0.5f, 2.6f)
            repeat(8) { level.sendParticles(net.minecraft.core.particles.ParticleTypes.LARGE_SMOKE, pos.x + Math.random(), pos.y + Math.random(), pos.z + Math.random(), 1, 0.0, 0.0, 0.0, 0.0) }
        }
    }

    fun install(impl: FluidBehaviour) {
        backend = impl
    }

    fun vaporizesIn(level: ServerLevel, pos: BlockPos, volume: FluidVolume): Boolean = backend.vaporizesIn(level, pos, volume)

    fun vaporize(level: ServerLevel, pos: BlockPos, volume: FluidVolume) = backend.vaporize(level, pos, volume)

    fun emptySound(fluid: Fluid): SoundEvent = backend.emptySound(fluid)

    fun fillSound(fluid: Fluid): SoundEvent = backend.fillSound(fluid)

    fun displayName(fluid: Fluid): Component = backend.displayName(fluid)
}
