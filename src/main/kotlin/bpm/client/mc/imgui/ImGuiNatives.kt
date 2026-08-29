package bpm.client.mc.imgui

import bpm.Bpm
import imgui.ImGui
import net.neoforged.fml.loading.FMLPaths
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Loads the Dear ImGui JNI library exactly once, from a folder this mod owns.
 *
 * `imgui.ImGui`'s static initialiser finds its native three ways: `-Dimgui.library.path`, `System.loadLibrary`,
 * or by extracting `io/imgui/java/native-bin/<lib>` from the classpath into `java.io.tmpdir`. The third is
 * what would happen by default and it is the one to avoid in a game: the temp dir is shared, a stale copy
 * from another launcher or version wins silently, and a JNI library binds to the first classloader that
 * loads it. So the native is copied out of our own jar into `<gamedir>/bpm/natives/imgui-<version>/` and the
 * property is set before the class is touched. The natives jar is jarJar'd into this mod, so there is one
 * `imgui.binding` module in the GAME layer and therefore one loader that ever asks for it.
 *
 * [boot] is safe to call from anywhere on the render thread; the first call does the work and the rest
 * answer whether it succeeded. A failure is logged once and reported as false so the editor can say "no
 * ImGui on this platform" instead of crashing the client on every open.
 */
object ImGuiNatives {
    const val VERSION = "1.90.0"

    private val booted = AtomicBoolean(false)

    @Volatile
    private var ok = false

    @Volatile
    var failure: Throwable? = null
        private set

    fun boot(): Boolean {
        if (booted.getAndSet(true)) return ok
        try {
            val lib = libraryFileName()
            val dir: Path = FMLPaths.GAMEDIR.get().resolve("bpm").resolve("natives").resolve("imgui-$VERSION")
            val resource = ImGuiNatives::class.java.classLoader.getResourceAsStream("io/imgui/java/native-bin/$lib")
            if (resource != null) {
                resource.use { input ->
                    val bytes = input.readAllBytes()
                    Files.createDirectories(dir)
                    val out = dir.resolve(lib)
                    // A library that is already loaded in this process cannot be overwritten on Windows,
                    // and does not need to be: same size, same version folder, same file.
                    if (!Files.exists(out) || Files.size(out) != bytes.size.toLong()) Files.write(out, bytes)
                    System.setProperty("imgui.library.path", dir.toAbsolutePath().toString())
                }
            } else {
                // Nested (jar-in-jar) modules do not expose their resources through the mod's loader in a dev
                // run; the binding's own loader still finds the native inside its own jar and extracts it.
                Bpm.LOGGER.info("imgui native '{}' not reachable from the mod's loader; the binding extracts its own", lib)
            }
            ImGui.init()
            ok = true
            Bpm.LOGGER.info("Dear ImGui {} loaded from {}", VERSION, System.getProperty("imgui.library.path") ?: "the binding's default path")
        } catch (t: Throwable) {
            failure = t
            ok = false
            Bpm.LOGGER.error("Dear ImGui could not be loaded; the graph editor will not open", t)
        }
        return ok
    }

    private fun libraryFileName(): String {
        val os = System.getProperty("os.name").lowercase()
        return when {
            "win" in os -> "imgui-java64.dll"
            "mac" in os -> "libimgui-java64.dylib"
            else -> "libimgui-java64.so"
        }
    }
}
