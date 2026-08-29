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
        BpmRegistries.install(MOD_BUS)
        bpm.chamber.ChamberEvents.install(NeoForge.EVENT_BUS)
        bpm.chamber.ChamberFight.install(NeoForge.EVENT_BUS)
        bpm.world.CoreTiers.install(NeoForge.EVENT_BUS)
        bpm.world.LinkerCombat.install(NeoForge.EVENT_BUS)
        RuntimeManager.install(NeoForge.EVENT_BUS)
        BpmNetwork.install(MOD_BUS)
        ServerNet.install(NeoForge.EVENT_BUS)
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
