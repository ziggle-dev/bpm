package bpm.dev

import bpm.Bpm
import bpm.catalog.BpmCatalog
import bpm.client.mc.BpmEditorScreen
import bpm.client.mc.DemoBody
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.commands.CommandSource
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.Vec2
import net.minecraft.world.phys.Vec3
import net.neoforged.fml.loading.FMLPaths
import net.neoforged.neoforge.server.ServerLifecycleHooks
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** The non-Lua commands of the dev server. Each answers a JSON frame. */
object DevCommands {
    val HELP = listOf(
        "help", "state",
        "eval <lua> | evals <lua> (server thread) | evalc <lua> (client thread) — `=expr` returns a value; globals: mc, server, java",
        "eval64/evals64/evalc64 <base64 lua>",
        "cmd <minecraft command>          run as the server console, output captured",
        "catalog [filter] | catalog hash  the node catalogue both sides agree on",
        "editor open [x y z]|close|demo   the workbench (attached to a controller when given), or the ImGui demo",
        "screenshot [path]                grab the frame to a PNG (default <gamedir>/bpm-shot.png)",
        "samples                          rewrite the sample documents in <gamedir>/bpm/graphs",
        "logs [n]                         the last n lines of latest.log",
    )

    fun state(): String {
        val mc = runCatching { Minecraft.getInstance() }.getOrNull()
        val server = ServerLifecycleHooks.getCurrentServer()
        return DevJson.obj(
            "type" to "state",
            "client" to (mc != null),
            "screen" to mc?.screen?.javaClass?.simpleName,
            "level" to mc?.level?.dimension()?.location()?.toString(),
            "player" to mc?.player?.let { p -> mapOf("name" to p.gameProfile.name, "x" to p.x, "y" to p.y, "z" to p.z) },
            "server" to (server != null),
            "serverTick" to server?.tickCount,
            "levels" to server?.allLevels?.map { it.dimension().location().toString() },
            "catalogHash" to BpmCatalog.hash.take(16),
        )
    }

    /** Run a command as the console, with permission level 4, and answer what it printed. */
    fun command(text: String): String {
        val server = ServerLifecycleHooks.getCurrentServer() ?: return DevJson.obj("type" to "cmd", "error" to "no server running")
        val lines = ArrayList<String>()
        val source = object : CommandSource {
            override fun sendSystemMessage(component: Component) { lines += component.string }
            override fun acceptsSuccess() = true
            override fun acceptsFailure() = true
            override fun shouldInformAdmins() = false
        }
        server.submit<Unit> {
            val level = server.overworld()
            val stack = CommandSourceStack(source, Vec3.atCenterOf(level.sharedSpawnPos), Vec2.ZERO, level, 4, "bpm-dev", Component.literal("bpm-dev"), server, null)
            server.commands.performPrefixedCommand(stack, text)
        }.get(30, TimeUnit.SECONDS)
        return DevJson.obj("type" to "cmd", "output" to lines)
    }

    fun catalog(filter: String): String {
        if (filter.trim().equals("hash", ignoreCase = true)) return DevJson.obj("type" to "catalog", "hash" to BpmCatalog.hash, "nodes" to BpmCatalog.catalog.all.size)
        val f = filter.trim().lowercase()
        val nodes = BpmCatalog.catalog.all
            .filter { f.isEmpty() || it.type.lowercase().contains(f) || it.title.lowercase().contains(f) }
            .map { d ->
                mapOf(
                    "type" to d.type, "title" to d.title, "kind" to d.kind.name, "host" to d.hostKind.name,
                    "in" to d.dataInputs.map { "${it.name}: ${it.type}" }, "out" to d.dataOutputs.map { "${it.name}: ${it.type}" },
                )
            }
        return DevJson.obj("type" to "catalog", "count" to nodes.size, "nodes" to nodes)
    }

    fun editor(arg: String): String {
        val mc = Minecraft.getInstance()
        val words = arg.trim().lowercase().split(" ").filter { it.isNotEmpty() }
        return when (words.firstOrNull()) {
            "demo" -> { mc.execute { mc.setScreen(BpmEditorScreen(DemoBody::draw)) }; DevJson.obj("type" to "editor", "msg" to "opening the demo") }
            "open" -> {
                // `editor open` or `editor open x y z` (attached to the controller there).
                val pos = if (words.size >= 4) net.minecraft.core.BlockPos(words[1].toInt(), words[2].toInt(), words[3].toInt()) else null
                bpm.client.ClientHooks.openWorkbench(pos)
                DevJson.obj("type" to "editor", "msg" to "opening the workbench${pos?.let { " at ${it.toShortString()}" } ?: ""}")
            }
            "close" -> { mc.execute { if (mc.screen is BpmEditorScreen) mc.setScreen(null) }; DevJson.obj("type" to "editor", "msg" to "closed") }
            else -> DevJson.obj("type" to "editor", "open" to (mc.screen is BpmEditorScreen))
        }
    }

    fun screenshot(path: String): String {
        val mc = Minecraft.getInstance()
        val given = java.nio.file.Path.of(path.trim().ifBlank { "bpm-shot.png" })
        // A relative path is relative to the game dir (the dev run's `run/`), an absolute one is taken as given.
        val out = if (given.isAbsolute) given else FMLPaths.GAMEDIR.get().resolve(given)
        val image: NativeImage = mc.submit<NativeImage> { Screenshot.takeScreenshot(mc.mainRenderTarget) }.get(10, TimeUnit.SECONDS)
        return try {
            Files.createDirectories(out.toAbsolutePath().parent)
            image.writeToFile(out)
            DevJson.obj("type" to "screenshot", "png" to out.toAbsolutePath().toString(), "w" to image.width, "h" to image.height)
        } finally {
            image.close()
        }
    }

    fun logs(n: Int): String {
        val file = FMLPaths.GAMEDIR.get().resolve("logs").resolve("latest.log")
        val lines = runCatching { Files.readAllLines(file) }.getOrDefault(emptyList())
        return DevJson.obj("type" to "logs", "lines" to lines.takeLast(n.coerceIn(1, 2000)))
    }

    @Suppress("unused")
    private val logger get() = Bpm.LOGGER
}
