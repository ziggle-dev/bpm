package bpm.dev

import net.minecraft.client.Minecraft
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.neoforge.server.ServerLifecycleHooks
import party.iroiro.luajava.JFunction
import party.iroiro.luajava.Lua
import party.iroiro.luajava.lua54.Lua54
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * The dev server's Lua console — one warm Lua 5.4 state with full, unsandboxed Java access through luajava's
 * `java` library (`java.import("net.minecraft.core.BlockPos")`, `java.new(...)`, method calls with `:`).
 *
 * Globals refreshed before every chunk: `mc` (the `Minecraft` instance, client only), `server` (the running
 * `MinecraftServer`, or nil), `level` (the overworld, or nil), `player` (the local player, or nil). `print`
 * output is collected and returned with the chunk's return values; `=expr` is shorthand for `return (expr)`.
 *
 * Where a chunk runs matters in Minecraft: [Where.SERVER] executes it on the server thread (the world may be
 * touched), [Where.CLIENT] on the render thread (screens, GL), [Where.HERE] on the connection's own thread.
 */
class LuaConsole {
    enum class Where { HERE, SERVER, CLIENT }

    private var lua: Lua54? = null
    private val out = StringBuilder()
    private val lock = ReentrantLock()

    private fun ensure(): Lua54 = lua ?: Lua54().also { l ->
        l.openLibraries()
        l.push(JFunction { L ->
            out.append(L.toString(1) ?: "").append('\n')
            0
        })
        l.setGlobal("__bpm_print")
        l.run(
            """
            print = function(...)
                local parts = {}
                for i = 1, select('#', ...) do parts[i] = tostring(select(i, ...)) end
                __bpm_print(table.concat(parts, '\t'))
            end
            """.trimIndent(),
        )
        lua = l
    }

    fun eval(code: String, where: Where): String {
        if (code.isBlank()) return "usage: eval <lua>  (`=expr` returns the value; globals: mc, server, level, player, java)"
        if (!lock.tryLock(30, TimeUnit.SECONDS)) return "error: the console is busy with an earlier chunk"
        try {
            val src = if (code.startsWith("=")) "return (\n${code.substring(1)}\n)" else code
            return try {
                when (where) {
                    Where.HERE -> runChunk(src)
                    Where.SERVER -> {
                        val server = ServerLifecycleHooks.getCurrentServer() ?: return "error: no server is running"
                        server.submit<String> { runChunk(src) }.get(60, TimeUnit.SECONDS)
                    }
                    Where.CLIENT -> {
                        if (!FMLEnvironment.dist.isClient) return "error: not a client"
                        Minecraft.getInstance().submit<String> { runChunk(src) }.get(60, TimeUnit.SECONDS)
                    }
                }
            } catch (e: java.util.concurrent.ExecutionException) {
                "error: ${e.cause?.message ?: e.message}"
            } catch (e: Throwable) {
                "error: ${e.message ?: e.toString()}"
            }
        } finally {
            lock.unlock()
        }
    }

    /** Run one chunk on the current thread and render what it printed and returned. */
    private fun runChunk(src: String): String {
        val l = ensure()
        out.setLength(0)
        refreshGlobals(l)
        val base = l.top
        return try {
            l.load(src)
            l.pCall(0, -1)
            val returned = (base + 1..l.top).joinToString("\t") { i -> l.toString(i) ?: l.toJavaObject(i)?.toString() ?: "nil" }
            listOf(out.toString(), returned).filter { it.isNotBlank() }.joinToString("\n").ifBlank { "ok" }
        } catch (e: Throwable) {
            "error: ${e.message ?: e.toString()}${if (out.isNotEmpty()) "\n$out" else ""}"
        } finally {
            l.setTop(base)
        }
    }

    private fun refreshGlobals(l: Lua) {
        val server = ServerLifecycleHooks.getCurrentServer()
        pushGlobal(l, "server", server)
        pushGlobal(l, "level", server?.overworld())
        if (FMLEnvironment.dist.isClient) {
            val mc = runCatching { Minecraft.getInstance() }.getOrNull()
            pushGlobal(l, "mc", mc)
            pushGlobal(l, "player", mc?.player)
        }
    }

    private fun pushGlobal(l: Lua, name: String, value: Any?) {
        if (value == null) l.pushNil() else l.pushJavaObject(value)
        l.setGlobal(name)
    }

    fun close() {
        lock.lock()
        try {
            runCatching { lua?.close() }
            lua = null
        } finally {
            lock.unlock()
        }
    }
}
