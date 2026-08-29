package bpm.client.mc

import bpm.Bpm
import bpm.client.editor.Prefs
import net.neoforged.fml.loading.FMLPaths
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/** The workbench's preferences in `<gamedir>/bpm/editor.properties`, written a moment after they change. */
object EditorPrefs : Prefs {
    private val file: Path = FMLPaths.GAMEDIR.get().resolve("bpm").resolve("editor.properties")
    private val props = Properties()
    private var loaded = false
    private var dirtyAt = 0L

    private fun load() {
        if (loaded) return
        loaded = true
        if (Files.isRegularFile(file)) {
            runCatching { Files.newBufferedReader(file).use { props.load(it) } }
                .onFailure { Bpm.LOGGER.warn("editor preferences unreadable: {}", it.toString()) }
        }
    }

    override fun getString(key: String, default: String): String {
        load()
        return props.getProperty(key) ?: default
    }

    override fun putString(key: String, value: String) {
        load()
        if (props.getProperty(key) == value) return
        props.setProperty(key, value)
        dirtyAt = System.currentTimeMillis()
    }

    /** Call once per frame; writes the file when something changed a second ago. */
    fun flush(force: Boolean = false) {
        if (dirtyAt == 0L) return
        if (!force && System.currentTimeMillis() - dirtyAt < 1000) return
        dirtyAt = 0
        runCatching {
            Files.createDirectories(file.parent)
            Files.newBufferedWriter(file).use { props.store(it, "bpm editor preferences") }
        }.onFailure { Bpm.LOGGER.warn("editor preferences not saved: {}", it.toString()) }
    }
}
