package bpm.client.mc

import bpm.Bpm
import imgui.ImGui
import imgui.type.ImString
import net.minecraft.client.Minecraft
import java.nio.file.Files

/**
 * `gradlew runClient -Pframes=N`: open the editor screen on the title screen, draw N frames, write a marker,
 * quit.
 *
 * A window is not testable by a person watching it, and a client that has to be closed by hand cannot be
 * run by a script. With a frame budget the same code path loads the natives, builds the context and the GL
 * backend, draws real frames through Minecraft's own render loop, and exits with a file that says so — or
 * without one, which is the failure.
 */
object SmokeRun {
    val frames: Int = System.getProperty("bpm.editor.frames")?.toIntOrNull() ?: 0

    @Volatile
    private var started = false

    /** True exactly once, the first time a title screen shows with a frame budget set. */
    @Synchronized
    fun claim(): Boolean {
        if (frames <= 0 || started) return false
        started = true
        return true
    }

    fun start() {
        Bpm.LOGGER.info("bpm smoke run: {} frames", frames)
        Minecraft.getInstance().setScreen(
            BpmEditorScreen(DemoBody::draw, onFrame = { n -> if (n >= frames) finish(n) }),
        )
    }

    private fun finish(drawn: Int) {
        val marker = bpm.platform.Platform.gameDir.resolve("bpm-smoke.ok")
        runCatching { Files.writeString(marker, "frames=$drawn\n") }
            .onFailure { Bpm.LOGGER.error("could not write {}", marker, it) }
        Bpm.LOGGER.info("bpm smoke run: drew {} frames, wrote {}", drawn, marker)
        Minecraft.getInstance().stop()
    }
}

/** The first pixel: enough widgets to prove drawing, clicking, typing and pasting all reach ImGui. */
object DemoBody {
    private val text = ImString(256)
    private var clicks = 0

    fun draw() {
        ImGui.text("bpm — Dear ImGui inside Minecraft")
        ImGui.separator()
        if (ImGui.button("click me")) clicks++
        ImGui.sameLine()
        ImGui.text("clicks: $clicks")
        ImGui.inputText("type here", text)
        ImGui.text("typed: ${text.get()}")
        ImGui.textDisabled("Esc closes the screen")
    }
}
