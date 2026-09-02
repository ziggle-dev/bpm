package bpm.platform.client

import bpm.platform.registry.FluidSpec
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.minecraft.world.level.material.Fluid

/**
 * How the experience fluid looks in the world, told to this loader.
 *
 * Nothing here existed before, and that was a real hole rather than an oversight this file inherits:
 * Fabric was never told anything about the fluid's appearance, so the game fell back to whatever it does
 * for an unregistered fluid. Below 26.1 that was quiet enough to miss. From 26.1 it is not -- a fluid
 * with no registered model gets the DEFAULT model, which is the missing-texture chequer, so the liquid
 * drew purple and black in the world.
 *
 * The counterpart of [bpm.platform.registry.NeoFluids]' `models`/`looks` on the other loader, and the
 * same split at the same version, because the game changed underneath both:
 *
 *  - from 26.1 a fluid's appearance is a baked `FluidModel` registered once, and Fabric takes it through
 *    `FluidRenderingRegistry.register(still, flowing, Unbaked)` -- the same four facts NeoForge hands to
 *    `RegisterFluidModelsEvent`, in the same order.
 *  - below that it is a `FluidRenderHandler` asked for sprites while drawing, which is what
 *    `SimpleFluidRenderHandler` is.
 *
 * The chunk layer goes with it, and only on the older arm. `FluidModel.Unbaked.bake` derives the layer
 * from the sprites' own transparency from 26.1, which is why [drawFluidTranslucent] is deliberately
 * empty there and called here everywhere else.
 */
@Environment(EnvType.CLIENT)
object FabricFluidModels {

    /**
     * Register [spec]'s appearance for its [still] and [flowing] fluids.
     *
     * Called from the client entry point. Registration has to happen before models bake, which a
     * `ClientModInitializer` comfortably precedes.
     */
    fun register(spec: FluidSpec, still: Fluid, flowing: Fluid) {
        val overlay = spec.overlayTexture ?: spec.stillTexture
        //? if >=26.1 {
        /*net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderingRegistry.register(
            still,
            flowing,
            net.minecraft.client.renderer.block.FluidModel.Unbaked(
                net.minecraft.client.resources.model.sprite.Material(spec.stillTexture),
                net.minecraft.client.resources.model.sprite.Material(spec.flowingTexture),
                net.minecraft.client.resources.model.sprite.Material(overlay),
                net.minecraft.client.color.block.BlockTintSource { spec.tint },
            ),
        )
        *///?} else {
        net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandlerRegistry.INSTANCE.register(
            still,
            flowing,
            net.fabricmc.fabric.api.client.render.fluid.v1.SimpleFluidRenderHandler(
                spec.stillTexture,
                spec.flowingTexture,
                overlay,
                // `SimpleFluidRenderHandler` wants 0xRRGGBB; the spec carries the alpha the seam uses
                // elsewhere, and passing it through would read as a tint far darker than intended.
                spec.tint and 0xFFFFFF,
            ),
        )
        // Declared here rather than at the call site so the two halves of "how this fluid draws" stay in
        // one place; from 26.1 the layer rides on the model and this is a no-op.
        drawFluidTranslucent(still)
        drawFluidTranslucent(flowing)
        //?}
    }
}
