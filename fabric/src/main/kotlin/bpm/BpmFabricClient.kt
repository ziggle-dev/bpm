package bpm

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment

/**
 * The Fabric client entry point.
 *
 * The counterpart of `BpmClient` on the other loader, and the same shape: install the client seams,
 * then let the registries hand over what they collected. The ordering rule is the one this loader keeps
 * insisting on — everything that names a registered object runs after registration, and the main entry
 * point has already run by the time a `ClientModInitializer` does.
 */
@Environment(EnvType.CLIENT)
object BpmFabricClient : ClientModInitializer {

    override fun onInitializeClient() {
        bpm.platform.client.ClientRenderers.install(
            bpm.platform.client.FabricItemRenderers,
            bpm.platform.client.FabricRendererRegistry,
        )
        bpm.platform.client.FluidVisuals.install(bpm.platform.client.FabricFluidAppearance)
        bpm.platform.client.Hud.install(bpm.platform.client.FabricHudRegistry)
        bpm.platform.client.ClientKeys.install(bpm.platform.client.FabricKeyRegistry)

        bpm.platform.events.FabricClientEventBridge.install()

        // The client subsystems declare what they want; the four registries above collected it.
        bpm.client.render.BpmItemRenderers.install()
        bpm.client.render.GeoRenderers.registerRenderers()
        bpm.client.render.GeoRenderers.installMolang()
        bpm.client.Keys.register()
        bpm.client.render.LinkerHud.install()
        bpm.platform.client.Hud.onTop(HUD_PANELS, bpm.client.mc.HudOverlay::render)

        // Now hand it all over. Fabric's registries take these immediately, so this must come last.
        bpm.platform.client.FabricItemRenderers.register()
        bpm.platform.client.FabricRendererRegistry.register()
        bpm.platform.client.FabricKeyRegistry.register()
        bpm.platform.client.FabricHudRegistry.register()

        bpm.platform.events.BpmEvents.clientSetup.fire(Unit)
    }

    /** The panel readout, drawn last so it sits over everything else on the HUD. */
    private val HUD_PANELS = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(Bpm.ID, "panels")
}
