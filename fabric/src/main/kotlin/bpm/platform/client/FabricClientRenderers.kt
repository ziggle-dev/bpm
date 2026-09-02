package bpm.platform.client

import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant
import net.fabricmc.fabric.api.transfer.v1.client.fluid.FluidVariantRendering
import net.minecraft.client.KeyMapping
import net.minecraft.resources.ResourceLocation
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
                renderer: (net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider.Context) -> net.minecraft.client.renderer.blockentity.BlockEntityRenderer<T>,
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
        for (block in pending) block { mapping -> KeyBindingHelper.registerKeyBinding(mapping) }
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

    private val layers = ArrayList<HudLayer>()

    override fun aboveCrosshair(id: ResourceLocation, layer: HudLayer) {
        layers += layer
    }

    override fun onTop(id: ResourceLocation, layer: HudLayer) {
        layers += layer
    }

    fun register() {
        HudRenderCallback.EVENT.register { graphics, delta ->
            for (layer in layers) layer.draw(graphics, delta)
        }
    }
}

/**
 * A fluid's textures and tint, from the transfer API's rendering half.
 *
 * `getSprites` gives the still and flowing sprites in that order — the same pair NeoForge hands back
 * from `IClientFluidTypeExtensions` — and the seam wants their names rather than the sprites, which the
 * contents carry.
 */
@Environment(EnvType.CLIENT)
object FabricFluidAppearance : FluidAppearance {
    override fun of(fluid: Fluid): FluidLook {
        val variant = FluidVariant.of(fluid)
        val sprites = FluidVariantRendering.getSprites(variant)
        val still = sprites?.getOrNull(0)?.contents()?.name() ?: WATER_STILL
        val flowing = sprites?.getOrNull(1)?.contents()?.name() ?: still
        // Fabric returns the tint without an alpha channel; the seam and its callers expect ARGB.
        return FluidLook(still, flowing, FluidVariantRendering.getColor(variant) or (0xFF shl 24))
    }

    private val WATER_STILL = ResourceLocation.withDefaultNamespace("block/water_still")
}
