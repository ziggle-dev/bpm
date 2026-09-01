package bpm.platform.client

import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.material.Fluid

/**
 * What a fluid looks like: its two textures and its tint.
 *
 * Vanilla has no answer for this — water and lava are special-cased in the renderer — so every loader
 * invented one. NeoForge hangs it off `IClientFluidTypeExtensions`, Fabric off
 * `FluidVariantRendering`/`FluidRenderHandlerRegistry`, whose `getFluidSprites` returns the same pair in
 * the same order. So both loaders can answer all three questions, and the mod asks nothing else.
 *
 * [flowing] is what the transfer stream draws with; a gauge and a monitor widget want [still]. NeoForge
 * lets `flowingTexture` be null and the callers all fell back to [still], so the fallback lives here
 * instead of at each call site.
 *
 * Named `FluidVisuals` rather than `FluidLooks` because vscript already has a `FluidLooks` seam of its
 * own — that one is the editor asking its host for a colour, this one is the host asking the loader.
 */
data class FluidLook(val still: ResourceLocation, val flowing: ResourceLocation, val tint: Int)

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
