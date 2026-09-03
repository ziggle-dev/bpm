package bpm.platform

//? if >=1.20.2 {
import net.neoforged.fml.ModList
import net.neoforged.fml.loading.FMLPaths
//?} else {
/*import net.minecraftforge.fml.ModList
import net.minecraftforge.fml.loading.FMLPaths
*///?}
import java.nio.file.Path

object NeoPlatform : PlatformInfo {
    override val gameDir: Path get() = FMLPaths.GAMEDIR.get()
    override val configDir: Path get() = FMLPaths.CONFIGDIR.get()
    //? if >=1.21.9 {
    /*override val isClient: Boolean get() = net.neoforged.fml.loading.FMLEnvironment.getDist().isClient
    override val isProduction: Boolean get() = net.neoforged.fml.loading.FMLEnvironment.isProduction()
    *///?} elif >=1.20.2 {
    override val isClient: Boolean get() = net.neoforged.fml.loading.FMLEnvironment.dist.isClient
    override val isProduction: Boolean get() = net.neoforged.fml.loading.FMLEnvironment.production
    //?} else {
    /*override val isClient: Boolean get() = net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient
    override val isProduction: Boolean get() = net.minecraftforge.fml.loading.FMLEnvironment.production
    *///?}
    override fun isModLoaded(modId: String): Boolean = ModList.get().isLoaded(modId)
}
