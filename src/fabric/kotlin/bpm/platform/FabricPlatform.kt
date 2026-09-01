package bpm.platform

import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Path

/**
 * Fabric answers all five of these from one object, `FabricLoader`, where NeoForge spreads them across
 * `FMLPaths`, `FMLEnvironment` and `ModList`.
 *
 * `isDevelopmentEnvironment` is the inverse of NeoForge's `production`, which is worth stating plainly
 * because getting it backwards would silently start the dev websocket console on a player's server.
 */
object FabricPlatform : PlatformInfo {
    override val gameDir: Path get() = FabricLoader.getInstance().gameDir
    override val configDir: Path get() = FabricLoader.getInstance().configDir
    override val isClient: Boolean
        get() = FabricLoader.getInstance().environmentType == net.fabricmc.api.EnvType.CLIENT
    override val isProduction: Boolean get() = !FabricLoader.getInstance().isDevelopmentEnvironment
    override fun isModLoaded(modId: String): Boolean = FabricLoader.getInstance().isModLoaded(modId)
}
