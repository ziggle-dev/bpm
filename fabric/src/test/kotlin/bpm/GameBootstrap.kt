package bpm

import org.junit.platform.launcher.LauncherSession
import org.junit.platform.launcher.LauncherSessionListener

/**
 * Stands Minecraft up once, before any Fabric test runs.
 *
 * ModDevGradle does this for the NeoForge branch through `unitTest { }`. Loom has no equivalent, so
 * without it a Fabric test that so much as mentions `ItemStack` or `Level` dies in the class
 * INITIALISER -- "NoClassDefFoundError: Could not initialize class net.minecraft.world.item.ItemStack" --
 * because those statics reach for registries that were never populated.
 *
 * Doing it here rather than excluding the tests that trip it, because the set that trips it is
 * band-specific: 1.21.1 fell over on `ItemStack` and 1.21.11 on `Level`, in entirely different test
 * classes, purely because the static-init chains differ between versions. An exclusion list would have to
 * be maintained per node and would rot the first time a version shifted an initialiser.
 *
 * A `LauncherSessionListener` runs once per suite, before the first class is loaded, which is the only
 * point early enough to matter. It is registered through `META-INF/services`, and both calls have been
 * unchanged from 1.20.1 to 26.2.
 */
class GameBootstrap : LauncherSessionListener {

    override fun launcherSessionOpened(session: LauncherSession) {
        if (started) return
        started = true
        // Order matters: the version has to be known before the registries are filled.
        net.minecraft.SharedConstants.tryDetectVersion()
        net.minecraft.server.Bootstrap.bootStrap()
    }

    private companion object {
        /** Guarded because a listener may be opened more than once in a forked run. */
        @Volatile
        var started = false
    }
}
