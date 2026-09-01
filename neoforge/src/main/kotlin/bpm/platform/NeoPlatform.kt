package bpm.platform

import net.neoforged.fml.ModList
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.fml.loading.FMLPaths
import java.nio.file.Path

object NeoPlatform : PlatformInfo {
    override val gameDir: Path get() = FMLPaths.GAMEDIR.get()
    override val configDir: Path get() = FMLPaths.CONFIGDIR.get()
    override val isClient: Boolean get() = FMLEnvironment.dist.isClient
    override val isProduction: Boolean get() = FMLEnvironment.production
    override fun isModLoaded(modId: String): Boolean = ModList.get().isLoaded(modId)
}
