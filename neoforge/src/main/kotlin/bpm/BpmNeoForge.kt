package bpm

import bpm.catalog.McTypes
import bpm.net.BpmNetwork
import bpm.net.ServerNet
import bpm.runtime.RuntimeManager
import bpm.world.BpmRegistries
import java.util.function.Consumer
//? if >=1.20.2 {
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS
//?} else {
/*import thedarkcolour.kotlinforforge.forge.MOD_BUS
*///?}

/*
 * The same names under two packages, Kotlin for Forge included: this band takes KFF's FORGE artifact,
 * because NeoForge 47 is still Forge underneath and its bus is net.minecraftforge's. See the note
 * beside the dependency in neoforge/build.gradle.kts.
 */
//? if >=1.20.2 {
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
//?} else {
/*import net.minecraftforge.fml.common.Mod
import net.minecraftforge.common.MinecraftForge as NeoForge
import net.minecraftforge.event.RegisterCommandsEvent
*///?}

/**
 * The NeoForge entry point. The language registries, the catalogue, the payloads and the controller
 * registries each arrive with their own subsystem and hang off the buses from here.
 *
 * The mod's id and logger are NOT here — they are on [Bpm], in the shared tree, because a hundred call
 * sites want them and none of those care about the loader. This object is only the wiring that is
 * specific to NeoForge, and the corresponding Fabric entry point does the same job with the same seams.
 */
@Mod(Bpm.ID)
object BpmNeoForge {
    private val LOGGER = Bpm.LOGGER

    init {
        LOGGER.info("bpm loading")
        // Every seam is wired first, before anything that might use one. A missing backend then fails at
        // its first use with the name of the thing that was not installed, rather than somewhere further in.
        bpm.platform.Platform.install(bpm.platform.NeoPlatform)
        bpm.platform.net.Net.install(bpm.platform.net.NeoNet)
        bpm.platform.ports.Ports.install(bpm.platform.ports.NeoPorts, bpm.platform.ports.NeoPortProviders)
        bpm.platform.registry.FluidRegistry.install(bpm.platform.registry.NeoFluidRegistrar)
        bpm.platform.world.Actor.install(bpm.platform.world.NeoWorldActor)
        bpm.platform.world.Fluids.install(bpm.platform.world.NeoFluidBehaviour)

        // The language's type registries are global and replace-by-name: once, both sides, before any catalogue.
        McTypes.registerAll()
        BpmConfig.register()
        // Strictly before BpmRegistries: touching ModBlocks and friends is what creates their registrars,
        // and a registrar cannot be created before the thing that makes them exists.
        bpm.platform.registry.Registrars.install(bpm.platform.registry.NeoRegistries(MOD_BUS))
        BpmRegistries.install()
        //? if >=1.20.2 {
        MOD_BUS.addListener(
            net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent::class.java,
            java.util.function.Consumer(bpm.platform.ports.NeoPortProviders::onRegisterCapabilities),
        )
        //?} else {
        /*// No per-type registration on this band: a provider is attached to each block entity as it is
        // built, which is a listener on the GAME bus rather than a one-shot event on the mod bus.
        bpm.platform.ports.NeoPortProviders.install(NeoForge.EVENT_BUS)
        *///?}
        // The bridge first: it is what turns this loader's events into the ones the subsystems below
        // listen for, so nothing after this line names a bus.
        bpm.platform.events.NeoEventBridge.install(NeoForge.EVENT_BUS)
        //? if >=1.20.2 {
        MOD_BUS.addListener(net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent::class.java, Consumer(bpm.platform.net.NeoNet::onRegisterPayloads))
        //?} else {
        /*// There is no registration event: the channel is built once and every message goes through it.
        bpm.platform.net.NeoNet.registerChannel()
        *///?}
        bpm.chamber.ChamberEvents.install()
        bpm.chamber.ChamberFight.install()
        bpm.world.CoreTiers.install()
        bpm.world.LinkerCombat.install()
        bpm.world.devices.MonitorInput.install()
        RuntimeManager.install()
        BpmNetwork.install()
        ServerNet.install()
        bpm.platform.events.BpmEvents.registerCommands.listen(BpmCommands::register)
        // The client class is only ever touched on the client, so a dedicated server never loads it.
        if (bpm.platform.Platform.isClient) bpm.client.BpmClient.init(MOD_BUS)
        // The dev-only debugging endpoint lives in a source set the shipped jar does not carry; found by
        // name so that its absence is the normal case, and never in a production launch.
        if (!bpm.platform.Platform.isProduction) {
            runCatching { Class.forName("bpm.dev.DevServer").getMethod("start").invoke(null) }
                .onFailure { if (it !is ClassNotFoundException) LOGGER.warn("dev server did not start: {}", it.toString()) }
        }
    }
}
