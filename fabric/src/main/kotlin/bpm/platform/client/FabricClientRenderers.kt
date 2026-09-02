package bpm.platform.client

import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant
import net.fabricmc.fabric.api.transfer.v1.client.fluid.FluidVariantRendering
import net.minecraft.client.KeyMapping
import bpm.platform.ResourceLocation
import net.minecraft.world.level.material.Fluid

/*
 * Item renderers used to be bridged here, onto `BuiltinItemRendererRegistry`. They are not any more:
 * GeckoLib registers its own items on both loaders, from `GeoRenderProvider`. See
 * `bpm.platform.client.ClientRenderers`.
 */

/** Block-entity and entity renderers. Two registries here, one event on NeoForge; same declarations. */
@Environment(EnvType.CLIENT)
object FabricRendererRegistry : RendererRegistry {

    private val pending = ArrayList<(RendererSink) -> Unit>()

    override fun renderers(block: (RendererSink) -> Unit) {
        pending += block
    }

    fun register() {
        val sink = object : RendererSink {
            override fun <T : net.minecraft.world.level.block.entity.BlockEntity> blockEntity(
                type: net.minecraft.world.level.block.entity.BlockEntityType<out T>,
                renderer: (net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider.Context) -> BlockEntityRendererOf<T>,
            ) {
                // The seam types the provider on T while the registry infers it from the `out T` type;
                // the two are the same class at runtime and Kotlin will not say so on its own.
                @Suppress("UNCHECKED_CAST")
                BlockEntityRendererRegistry.register(
                    type as net.minecraft.world.level.block.entity.BlockEntityType<T>,
                ) { ctx -> renderer(ctx) }
            }

            override fun <T : net.minecraft.world.entity.Entity> entity(
                type: net.minecraft.world.entity.EntityType<out T>,
                renderer: (net.minecraft.client.renderer.entity.EntityRendererProvider.Context) -> EntityRendererOf<T>,
            ) {
                EntityRendererRegistry.register(type) { ctx -> renderer(ctx) }
            }
        }
        for (block in pending) block(sink)
        pending.clear()
    }
}

/**
 * Key bindings. Fabric has one helper and no conflict context, which the seam already anticipated:
 * the binding this mod owns refuses to fire while a screen is open, which is what the context bought.
 */
@Environment(EnvType.CLIENT)
object FabricKeyRegistry : KeyRegistry {

    private val pending = ArrayList<((KeyMapping) -> Unit) -> Unit>()

    override fun keys(block: ((KeyMapping) -> Unit) -> Unit) {
        pending += block
    }

    fun register() {
        // 26.1 renamed the module, the class and the method together -- `fabric-key-binding-api-v1`'s
        // `KeyBindingHelper.registerKeyBinding` is `fabric-key-mapping-api-v1`'s
        // `KeyMappingHelper.registerKeyMapping` -- catching up with the game, which has called these
        // `KeyMapping` since long before. Same argument, same return.
        //? if >=26.1 {
        /*for (block in pending) block { mapping ->
            net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper.registerKeyMapping(mapping)
        }
        *///?} else {
        for (block in pending) block { mapping ->
            net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.registerKeyBinding(mapping)
        }
        //?}
        pending.clear()
    }
}

/**
 * The HUD.
 *
 * **Both positions land in the same place here, and that is a real if small difference.** NeoForge's
 * `RegisterGuiLayersEvent` can insert a layer immediately above the crosshair; Fabric API's
 * `HudRenderCallback` only appends after the whole HUD. So the linker overlay draws on top rather than
 * under the hotbar's later layers. Nothing overlaps it in practice, and the alternative was a mixin on
 * `Gui` for a cosmetic ordering — noted rather than hidden, and cheap to revisit if it ever shows.
 */
@Environment(EnvType.CLIENT)
object FabricHudRegistry : HudRegistry {

    // Kept with their ids, and kept apart, because from 26.1 both are meaningful: the HUD is a list of
    // named elements a mod can splice into, so `aboveCrosshair` really can go above the crosshair.
    private val aboveCrosshairLayers = ArrayList<Pair<ResourceLocation, HudLayer>>()
    private val topLayers = ArrayList<Pair<ResourceLocation, HudLayer>>()

    override fun aboveCrosshair(id: ResourceLocation, layer: HudLayer) {
        aboveCrosshairLayers += id to layer
    }

    override fun onTop(id: ResourceLocation, layer: HudLayer) {
        topLayers += id to layer
    }

    fun register() {
        //? if >=26.1 {
        /*// `HudRenderCallback` is gone, replaced by a registry of named elements. Each layer becomes one,
        // and `attachElementAfter` puts the linker overlay exactly where NeoForge's
        // `RegisterGuiLayersEvent` puts it -- so the ordering caveat in the note above does not apply on
        // this band. `HudElement.extractRenderState` takes the same pair the old callback did, which is
        // why `HudLayer.draw` is unchanged.
        for ((id, layer) in aboveCrosshairLayers) {
            net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.attachElementAfter(
                net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.CROSSHAIR,
                id,
                { graphics, delta -> layer.draw(graphics, delta) },
            )
        }
        for ((id, layer) in topLayers) {
            net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                id,
                { graphics, delta -> layer.draw(graphics, delta) },
            )
        }
        *///?} else {
        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register { graphics, delta ->
            for ((_, layer) in aboveCrosshairLayers) layer.draw(graphics, delta)
            for ((_, layer) in topLayers) layer.draw(graphics, delta)
        }
        //?}
    }
}

/**
 * A fluid's textures and tint, from the transfer API's rendering half.
 *
 * Below 26.1 `getSprites` gives the still and flowing sprites in that order — the same pair NeoForge
 * hands back from `IClientFluidTypeExtensions` — and the seam wants their names rather than the sprites,
 * which the contents carry. From 26.1 the appearance is baked game data instead; see the body.
 */
@Environment(EnvType.CLIENT)
object FabricFluidAppearance : FluidAppearance {
    override fun of(fluid: Fluid): FluidLook {
        val variant = FluidVariant.of(fluid)
        // From 26.1 a fluid's appearance is the game's own baked data rather than something the transfer
        // API answers: `FluidVariantRenderHandler` lost `getSprites` entirely, and the still, flowing and
        // overlay materials live on `FluidModel`, reached through the model manager. This is the same
        // move the NeoForge branch made when `IClientFluidTypeExtensions` lost its texture methods -- and
        // it means both loaders now read the appearance from one place. The tint is still Fabric's, since
        // `FluidVariantRendering.getColor` survived.
        //? if >=26.1 {
        /*val model = net.minecraft.client.Minecraft.getInstance().modelManager
            .getFluidStateModelSet()
            .get(fluid.defaultFluidState())
        val still = model.stillMaterial().sprite().contents().name()
        val flowing = model.flowingMaterial().sprite().contents().name()
        *///?} else {
        val sprites = FluidVariantRendering.getSprites(variant)
        val still = sprites?.getOrNull(0)?.contents()?.name() ?: WATER_STILL
        val flowing = sprites?.getOrNull(1)?.contents()?.name() ?: still
        //?}
        // Fabric returns the tint without an alpha channel; the seam and its callers expect ARGB.
        return FluidLook(still, flowing, FluidVariantRendering.getColor(variant) or (0xFF shl 24))
    }

    private val WATER_STILL = ResourceLocation.withDefaultNamespace("block/water_still")
}
