package bpm.platform.world

import bpm.platform.ports.FluidVolume
import bpm.platform.ports.toStack
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.level.material.Fluid
import net.neoforged.neoforge.common.SoundActions

/**
 * NeoForge's `FluidType`, which is where this loader keeps a fluid's manners.
 *
 * Worth having rather than falling back on the vanilla defaults, because other mods register their fluid
 * types here: a modded coolant that is supposed to flash off in the Nether does so through
 * `isVaporizedOnPlacement`, and its bucket has whatever sound its author chose.
 */
object NeoFluidBehaviour : FluidBehaviour {

    override fun vaporizesIn(level: ServerLevel, pos: BlockPos, volume: FluidVolume): Boolean =
        bpm.platform.waterEvaporatesIn(level, pos) && volume.fluid.fluidType.isVaporizedOnPlacement(level, pos, volume.toStack())

    override fun vaporize(level: ServerLevel, pos: BlockPos, volume: FluidVolume) {
        volume.fluid.fluidType.onVaporize(null, level, pos, volume.toStack())
    }

    override fun emptySound(fluid: Fluid): SoundEvent =
        fluid.fluidType.getSound(SoundActions.BUCKET_EMPTY) ?: SoundEvents.BUCKET_EMPTY

    override fun fillSound(fluid: Fluid): SoundEvent =
        fluid.fluidType.getSound(SoundActions.BUCKET_FILL) ?: SoundEvents.BUCKET_FILL

    /**
     * The real localized name, which on this loader every fluid has: `FluidType.description`. The seam's
     * default derives a key from the registry id instead, which is right for our own fluid and a
     * reasonable guess for anyone else's — but here we can simply ask.
     */
    override fun displayName(fluid: net.minecraft.world.level.material.Fluid): net.minecraft.network.chat.Component =
        fluid.fluidType.description
}
