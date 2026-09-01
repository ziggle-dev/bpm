package bpm.platform

import java.nio.file.Path

/**
 * The handful of questions about the running game that every loader answers differently and no loader
 * answers interestingly: where the game directory is, which side this is, whether another mod is present.
 *
 * Small, but it is what stops five unrelated files each importing `FMLPaths` to find out where to put a
 * settings file.
 */
interface PlatformInfo {
    val gameDir: Path
    val configDir: Path

    /** False on a dedicated server. */
    val isClient: Boolean

    /** False in a development run — what gates the dev console. */
    val isProduction: Boolean

    fun isModLoaded(modId: String): Boolean
}

object Platform {
    private lateinit var backend: PlatformInfo

    fun install(impl: PlatformInfo) {
        backend = impl
    }

    val gameDir: Path get() = backend.gameDir
    val configDir: Path get() = backend.configDir
    val isClient: Boolean get() = backend.isClient
    val isProduction: Boolean get() = backend.isProduction

    fun isModLoaded(modId: String): Boolean = backend.isModLoaded(modId)
}
