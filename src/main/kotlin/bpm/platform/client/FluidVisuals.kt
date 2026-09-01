package bpm.platform.client

import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.material.Fluid

/**
 * What a fluid looks like: its still texture and its tint.
 *
 * Vanilla has no answer for this — water and lava are special-cased in the renderer — so every loader
 * invented one. NeoForge hangs it off `IClientFluidTypeExtensions`, Fabric off
 * `FluidVariantRendering`/`FluidRenderHandlerRegistry`. The mod needs exactly two facts, for the buffer
 * panel's gauges and for a monitor's fluid widget.
 *
 * Named `FluidVisuals` rather than `FluidLooks` because vscript already has a `FluidLooks` seam of its
 * own — that one is the editor asking its host for a colour, this one is the host asking the loader.
 */
data class FluidLook(val still: ResourceLocation, val tint: Int)

interface FluidAppearance {
    fun of(fluid: Fluid): FluidLook
}

object FluidVisuals {
    private lateinit var backend: FluidAppearance

    fun install(impl: FluidAppearance) {
        backend = impl
    }

    fun of(fluid: Fluid): FluidLook = backend.of(fluid)
}
