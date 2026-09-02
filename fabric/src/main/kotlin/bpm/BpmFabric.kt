package bpm

import bpm.catalog.McTypes
import bpm.net.BpmNetwork
import bpm.net.ServerNet
import bpm.runtime.RuntimeManager
import bpm.world.BpmRegistries
import net.fabricmc.api.ModInitializer

/**
 * The Fabric entry point.
 *
 * Deliberately the same sequence as `BpmNeoForge`, in the same order, because the order is the part that
 * is load-bearing and it is easier to see that two lists match than to reason about two arrangements.
 * The differences are all in the middle: there is no mod bus to hand around, so registration and payload
 * wiring happen inline rather than as listeners waiting for an event.
 *
 * Two Fabric-only steps, both consequences of registries that resolve eagerly:
 *
 * `FabricPortProviders.register()` and `FabricFluidRegistrar.installAttributes()` run AFTER
 * `BpmRegistries.install()`. Both name block entity types and fluids, and on this loader nothing is
 * bound until `installAll` has run inside that call. On NeoForge the equivalents are event listeners and
 * the question does not arise.
 */
object BpmFabric : ModInitializer {

    override fun onInitialize() {
        Bpm.LOGGER.info("bpm loading")
        // Every seam is wired first, before anything that might use one. A missing backend then fails at
        // its first use with the name of the thing that was not installed, rather than somewhere further in.
        bpm.platform.Platform.install(bpm.platform.FabricPlatform)
        bpm.platform.net.Net.install(bpm.platform.net.FabricNet)
        bpm.platform.ports.Ports.install(bpm.platform.ports.FabricPorts, bpm.platform.ports.FabricPortProviders)
        bpm.platform.registry.FluidRegistry.install(bpm.platform.registry.FabricFluidRegistrar)
        bpm.platform.world.Actor.install(bpm.platform.world.FabricWorldActor)
        bpm.platform.world.Fluids.install(bpm.platform.world.FabricFluidBehaviour)

        // The language's type registries are global and replace-by-name: once, both sides, before any catalogue.
        McTypes.registerAll()
        BpmConfig.register()
        bpm.platform.registry.Registrars.install(bpm.platform.registry.FabricRegistries())
        BpmRegistries.install()
        // Only now do block entity types and fluids exist to be named.
        bpm.platform.ports.FabricPortProviders.register()
        bpm.platform.registry.FabricFluidRegistrar.installAttributes()

        bpm.platform.events.FabricEventBridge.install()
        bpm.chamber.ChamberEvents.install()
        bpm.chamber.ChamberFight.install()
        bpm.world.CoreTiers.install()
        bpm.world.LinkerCombat.install()
        bpm.world.devices.MonitorInput.install()
        RuntimeManager.install()
        BpmNetwork.install()
        ServerNet.install()
        bpm.platform.events.BpmEvents.registerCommands.listen(bpm.BpmCommands::register)

        // The dev-only debugging endpoint lives in a source set the shipped jar does not carry; found by
        // name so that its absence is the normal case, and never in a production launch.
        if (!bpm.platform.Platform.isProduction) {
            runCatching { Class.forName("bpm.dev.DevServer").getMethod("start").invoke(null) }
                .onFailure { if (it !is ClassNotFoundException) Bpm.LOGGER.warn("dev server did not start: {}", it.toString()) }
        }
    }
}
