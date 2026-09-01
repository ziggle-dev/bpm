package bpm

import bpm.catalog.McTypes
import bpm.net.BpmNetwork
import bpm.net.ServerNet
import bpm.runtime.RuntimeManager
import bpm.world.BpmRegistries
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import java.util.function.Consumer
import net.neoforged.fml.loading.FMLEnvironment
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS

/**
 * The mod entry. The language registries, the catalogue, the payloads and the controller registries each
 * arrive with their own subsystem and hang off the buses from here.
 */
@Mod(Bpm.ID)
object Bpm {
    const val ID = "bpm"
    val LOGGER: Logger = LogManager.getLogger(ID)

    init {
        LOGGER.info("bpm loading")
        // The language's type registries are global and replace-by-name: once, both sides, before any catalogue.
        McTypes.registerAll()
        BpmConfig.register()
        // Before anything that might send: a missing backend fails at the first send, and this is the
        // one line that decides which one it is.
        bpm.platform.net.Net.install(bpm.platform.net.NeoNet)
        bpm.platform.world.Actor.install(bpm.platform.world.NeoWorldActor)
        bpm.platform.world.Fluids.install(bpm.platform.world.NeoFluidBehaviour)
        // Strictly before BpmRegistries: touching ModBlocks and friends is what creates their registrars,
        // and a registrar cannot be created before the thing that makes them exists.
        bpm.platform.registry.Registrars.install(bpm.platform.registry.NeoRegistries(MOD_BUS))
        BpmRegistries.install(MOD_BUS)
        // The bridge first: it is what turns this loader's events into the ones the subsystems below
        // listen for, so nothing after this line names a bus.
        bpm.platform.events.NeoEventBridge.install(NeoForge.EVENT_BUS)
        bpm.chamber.ChamberEvents.install()
        bpm.chamber.ChamberFight.install()
        bpm.world.CoreTiers.install()
        bpm.world.LinkerCombat.install()
        bpm.world.devices.MonitorInput.install()
        RuntimeManager.install()
        BpmNetwork.install(MOD_BUS)
        ServerNet.install()
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java, Consumer(BpmCommands::register))
        // The client class is only ever touched on the client, so a dedicated server never loads it.
        if (FMLEnvironment.dist.isClient) bpm.client.BpmClient.init(MOD_BUS)
        // The dev-only debugging endpoint lives in a source set the shipped jar does not carry; found by
        // name so that its absence is the normal case, and never in a production launch.
        if (!FMLEnvironment.production) {
            runCatching { Class.forName("bpm.dev.DevServer").getMethod("start").invoke(null) }
                .onFailure { if (it !is ClassNotFoundException) LOGGER.warn("dev server did not start: {}", it.toString()) }
        }
    }
}
