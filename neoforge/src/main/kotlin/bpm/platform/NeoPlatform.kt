package bpm.platform

import net.neoforged.fml.ModList
import net.neoforged.fml.loading.FMLPaths
import java.nio.file.Path

object NeoPlatform : PlatformInfo {
    override val gameDir: Path get() = FMLPaths.GAMEDIR.get()
    override val configDir: Path get() = FMLPaths.CONFIGDIR.get()
    //? if >=1.21.9 {
    /*override val isClient: Boolean get() = net.neoforged.fml.loading.FMLLoader.getDist().isClient
    override val isProduction: Boolean get() = net.neoforged.fml.loading.FMLLoader.isProduction()
    *///?} else {
    override val isClient: Boolean get() = net.neoforged.fml.loading.FMLEnvironment.dist.isClient
    override val isProduction: Boolean get() = net.neoforged.fml.loading.FMLEnvironment.production
    //?}
    override fun isModLoaded(modId: String): Boolean = ModList.get().isLoaded(modId)
}
