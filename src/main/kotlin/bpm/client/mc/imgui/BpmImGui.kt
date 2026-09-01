package bpm.client.mc.imgui

import bpm.Bpm
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferUploader
import imgui.ImGui
import imgui.ImGuiIO
import imgui.flag.ImGuiConfigFlags
import imgui.flag.ImGuiWindowFlags
import imgui.gl3.ImGuiImplGl3
import imgui.internal.ImGuiContext
import dev.ziggle.imgui.FontLoader
import dev.ziggle.imgui.FontSet
import dev.ziggle.imgui.Fonts
import dev.ziggle.imgui.ThemeStyle
import dev.ziggle.vscript.editor.graph.EditorSettings
import dev.ziggle.vscript.editor.host.EditorHost
import dev.ziggle.vscript.runtime.EditorDoc
import dev.ziggle.vscript.runtime.EditorLog
import org.apache.logging.log4j.Level
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GLCapabilities

/**
 * The one Dear ImGui context this client has, drawn inside Minecraft's own GL context and frame.
 *
 * The recipe is vscript's `EditorContext` with the window taken out: Minecraft owns the window, the swap and
 * the input, so what is left is a context, a font atlas, a GL3 backend and a frame. Three things it does that
 * are worth knowing:
 *
 *  - **The GL backend follows the GL context.** `ImGuiImplGl3` owns a shader, a VAO and the font texture,
 *    all of which die with the context they were made in. `GL.getCapabilities()` is a different object per
 *    context, so its identity is the cheap "has the world been recreated" check the OSRS client uses too.
 *  - **`ImGui.render()` runs whatever the body did.** A frame that began and never rendered leaves ImGui
 *    mid-frame, and the next `newFrame` asserts inside native code. The body is caught, the frame is ended.
 *  - **Minecraft's state cache is left truthful.** The GL3 backend saves and restores every piece of raw GL
 *    state it touches, so `GlStateManager`'s shadow of that state stays right; the two things it cannot know
 *    about — the immediate-mode VAO `BufferUploader` remembers, and the model-view uniforms — are reset here.
 */
object BpmImGui {
    private const val ROOT_FLAGS = ImGuiWindowFlags.NoTitleBar or ImGuiWindowFlags.NoResize or
        ImGuiWindowFlags.NoMove or ImGuiWindowFlags.NoCollapse or ImGuiWindowFlags.NoBringToFrontOnFocus

    private var ctx: ImGuiContext? = null
    private var gl: ImGuiImplGl3? = null
    private var caps: GLCapabilities? = null
    private var lastNanos = System.nanoTime()

    /** The fonts the atlas holds; bound around every body so the widget kit's globals point at them. */
    var fonts: FontSet? = null
        private set

    /** What was typed since the editor last asked — installed as `EditorHost.typed`. */
    val typed = ScreenTypedText()

    val ready: Boolean get() = ctx != null && gl != null

    /**
     * Make the context and the backend if they do not exist, and remake the backend if the GL context has
     * changed underneath it. Render thread only. False when ImGui cannot be loaded on this machine.
     */
    fun ensure(): Boolean {
        RenderSystem.assertOnRenderThread()
        if (ctx == null) {
            if (!ImGuiNatives.boot()) return false
            val made = ImGui.createContext()
            ImGui.setCurrentContext(made)
            val io = ImGui.getIO()
            io.iniFilename = null
            // Minecraft owns the cursor; ImGui asking GLFW for a resize arrow would fight it.
            io.addConfigFlags(ImGuiConfigFlags.NoMouseCursorChange)
            McClipboard.install(io)
            ThemeStyle.apply(ImGui.getStyle())
            fonts = FontLoader.load(io)
            EditorHost.typed = typed
            EditorHost.values = bpm.client.mc.BpmCatalogs
            EditorHost.icons = bpm.client.mc.BlockPreviewRenderer
            EditorHost.styles = bpm.client.mc.BpmTypeStyles
            // vscript's own settings and workspace live under the game dir, not the user's home.
            val home = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve("bpm").toFile()
            EditorSettings.home = home
            EditorDoc.workspace = java.io.File(home, "graphs")
            EditorLog.sink = EditorLog.Sink { level, tag, message, error ->
                Bpm.LOGGER.log(level.toLog4j(), "[$tag] $message", error)
            }
            ctx = made
        }
        val now = runCatching { GL.getCapabilities() }.getOrNull()
        if (gl == null || (now != null && now !== caps)) {
            // The old backend's GL objects went with the old context; nothing to free, only to forget.
            gl = ImGuiImplGl3().also { it.init("#version 150") }
            caps = now
        }
        return true
    }

    /**
     * One frame: [logicalW] x [logicalH] in window coordinates, drawn at [fbScale] pixels per unit.
     *
     * [pump] runs with the IO in hand before the frame begins — the place to sync anything not delivered as
     * an event, such as the modifier keys. [body] draws into a borderless root window covering the screen.
     */
    fun frame(logicalW: Float, logicalH: Float, fbScale: Float, pump: (ImGuiIO) -> Unit, bareRoot: Boolean = false, body: () -> Unit) {
        if (!ensure()) return
        val backend = gl ?: return
        val io = ImGui.getIO()
        io.setDisplaySize(logicalW, logicalH)
        io.setDisplayFramebufferScale(fbScale, fbScale)
        val now = System.nanoTime()
        // Real time, clamped: double-click and key repeat are measured against this, and a fixed 1/60
        // drifts from the wall clock the moment the game renders at any other rate.
        io.deltaTime = ((now - lastNanos) / 1e9f).coerceIn(1e-4f, 0.25f)
        lastNanos = now
        pump(io)

        backend.newFrame() // lazily builds the shader, the VAO and the font texture
        ImGui.newFrame()
        try {
            ImGui.setNextWindowPos(0f, 0f)
            ImGui.setNextWindowSize(logicalW, logicalH)
            ImGui.begin("##bpm-root", if (bareRoot) ROOT_FLAGS or ImGuiWindowFlags.NoBackground else ROOT_FLAGS)
            Fonts.use(fonts ?: FontSet(null, null, null, false)) {
                runCatching { body() }.onFailure { Bpm.LOGGER.error("bpm editor draw failed", it) }
            }
            ImGui.end()
        } finally {
            ImGui.render()
            backend.renderDrawData(ImGui.getDrawData())
            BufferUploader.reset()
            RenderSystem.applyModelViewMatrix()
        }
    }

    /** A screen reopened after a while must not see a huge first delta. */
    fun resetClock() {
        lastNanos = System.nanoTime()
    }

    private fun EditorLog.Level.toLog4j(): Level = when (this) {
        EditorLog.Level.DEBUG -> Level.DEBUG
        EditorLog.Level.INFO -> Level.INFO
        EditorLog.Level.WARN -> Level.WARN
        EditorLog.Level.ERROR -> Level.ERROR
    }
}
