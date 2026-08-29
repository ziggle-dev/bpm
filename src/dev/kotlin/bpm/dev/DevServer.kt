package bpm.dev

import bpm.Bpm
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import net.neoforged.fml.loading.FMLPaths
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The DEV-ONLY debugging endpoint: a websocket console on the loopback, for driving a running game from
 * outside — `tools/bpm_ws.py`. Never shipped: this source set is not in the jar, and `Bpm` finds this class by
 * name only in a non-production launch.
 *
 * One text command per message, one JSON frame per reply. `eval` runs Lua with full, unsandboxed Java access
 * (luajava) — the same console the OSRS client has — on the server thread (`evals`), the client thread
 * (`evalc`) or the connection thread (`eval`). The port is pinned (`-Dbpm.debug.port`, default 8778) and
 * every upgrade must carry the token written to `<gamedir>/bpm-debug.token`; anything else gets a 404.
 */
object DevServer {
    private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    private const val HOST = "127.0.0.1"
    val port: Int = System.getProperty("bpm.debug.port")?.toIntOrNull() ?: 8778

    private val clients = CopyOnWriteArrayList<Conn>()
    private val lua = LuaConsole()

    @Volatile private var server: ServerSocket? = null
    @Volatile private var token: String = ""

    @JvmStatic
    fun start() {
        if (server != null) return
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(HOST, port))
        server = ss
        token = generateToken()
        writeTokenFile()
        Thread({ acceptLoop(ss) }, "bpm-dev-accept").apply { isDaemon = true }.start()
        // Sample documents for `/bpm doc load`; the catalogue is built here for the first time, off the game threads.
        Thread({ runCatching { SampleDocs.write() }.onFailure { Bpm.LOGGER.warn("sample documents: {}", it.toString()) } }, "bpm-dev-samples").apply { isDaemon = true }.start()
        Bpm.LOGGER.info("bpm dev server listening on {}:{} (token in {})", HOST, port, tokenFile())
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        clients.forEach { it.close() }
        clients.clear()
        runCatching { tokenFile().delete() }
        lua.close()
    }

    private fun tokenFile(): java.io.File = FMLPaths.GAMEDIR.get().resolve("bpm-debug.token").toFile()

    private fun writeTokenFile() {
        runCatching { tokenFile().writeText("port=$port\ntoken=$token\n") }
            .onFailure { Bpm.LOGGER.warn("could not write the dev token file: {}", it.message) }
    }

    private fun generateToken(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val socket = runCatching { ss.accept() }.getOrNull() ?: break
            Thread({ handle(socket) }, "bpm-dev-client").apply { isDaemon = true }.start()
        }
    }

    private fun handle(socket: Socket) {
        val conn = Conn(socket)
        try {
            if (!conn.handshake(token)) {
                socket.close()
                return
            }
            clients += conn
            conn.send(DevJson.obj("type" to "hello", "msg" to "bpm dev server ready — type 'help'"))
            while (true) {
                val msg = conn.readText() ?: break
                runCatching { dispatch(conn, msg.trim()) }
                    .onFailure { conn.send(DevJson.obj("type" to "error", "msg" to (it.message ?: it.toString()))) }
            }
        } catch (_: Exception) {
            // the client went away
        } finally {
            clients.remove(conn)
            conn.close()
        }
    }

    private fun dispatch(conn: Conn, msg: String) {
        if (msg.isEmpty()) return
        val sp = msg.indexOf(' ')
        val cmd = (if (sp < 0) msg else msg.substring(0, sp)).lowercase()
        val rest = if (sp < 0) "" else msg.substring(sp + 1).trim()
        val reply: String = when (cmd) {
            "help" -> DevJson.obj("type" to "help", "commands" to DevCommands.HELP)
            "state" -> DevCommands.state()
            "eval" -> DevJson.obj("type" to "eval", "result" to lua.eval(rest, LuaConsole.Where.HERE))
            "evals" -> DevJson.obj("type" to "eval", "result" to lua.eval(rest, LuaConsole.Where.SERVER))
            "evalc" -> DevJson.obj("type" to "eval", "result" to lua.eval(rest, LuaConsole.Where.CLIENT))
            "eval64" -> DevJson.obj("type" to "eval", "result" to lua.eval(decode64(rest), LuaConsole.Where.HERE))
            "evals64" -> DevJson.obj("type" to "eval", "result" to lua.eval(decode64(rest), LuaConsole.Where.SERVER))
            "evalc64" -> DevJson.obj("type" to "eval", "result" to lua.eval(decode64(rest), LuaConsole.Where.CLIENT))
            "cmd" -> DevCommands.command(rest)
            "catalog" -> DevCommands.catalog(rest)
            "editor" -> DevCommands.editor(rest)
            "screenshot" -> DevCommands.screenshot(rest)
            "logs" -> DevCommands.logs(rest.toIntOrNull() ?: 40)
            "samples" -> DevJson.obj("type" to "samples", "written" to SampleDocs.write(force = true))
            else -> DevJson.obj("type" to "error", "msg" to "unknown command '$cmd' — try 'help'")
        }
        conn.send(reply)
    }

    private fun decode64(s: String): String = String(Base64.getDecoder().decode(s.trim()), StandardCharsets.UTF_8)

    /** One websocket connection: the handshake, the framing, nothing else. Ported from the OSRS client. */
    private class Conn(private val socket: Socket) {
        private val input: InputStream = socket.getInputStream()
        private val output: OutputStream = socket.getOutputStream()
        private val writeLock = Any()

        fun handshake(expectedToken: String): Boolean {
            val raw = StringBuilder()
            var prev = -1
            while (true) {
                val b = input.read()
                if (b == -1) return false
                raw.append(b.toChar())
                if (prev == '\n'.code && b == '\n'.code) break
                if (b == '\r'.code) continue
                prev = b
            }
            val lines = raw.toString().split("\n").map { it.trimEnd('\r') }
            fun header(name: String): String? = lines.asSequence().drop(1)
                .firstOrNull { it.substringBefore(':', "").trim().equals(name, ignoreCase = true) }
                ?.substringAfter(':')?.trim()

            val key = header("Sec-WebSocket-Key") ?: return reject()
            val target = lines.firstOrNull()?.split(' ')?.getOrNull(1).orEmpty()
            val queryToken = target.substringAfter("token=", "").substringBefore('&').takeIf { it.isNotEmpty() }
            val given = header("X-Auth-Token") ?: queryToken
            val host = header("Host").orEmpty()
            val hostOk = host.startsWith("127.0.0.1") || host.startsWith("localhost")
            if (!hostOk || given == null || !MessageDigest.isEqual(given.toByteArray(), expectedToken.toByteArray())) return reject()

            val accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).toByteArray(StandardCharsets.UTF_8)),
            )
            val resp = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n"
            output.write(resp.toByteArray(StandardCharsets.US_ASCII))
            output.flush()
            return true
        }

        private fun reject(): Boolean {
            val body = "Not Found"
            val resp = "HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
            runCatching { output.write(resp.toByteArray(StandardCharsets.US_ASCII)); output.flush() }
            return false
        }

        fun readText(): String? {
            val message = java.io.ByteArrayOutputStream()
            while (true) {
                val b0 = input.read(); if (b0 == -1) return null
                val b1 = input.read(); if (b1 == -1) return null
                val fin = b0 and 0x80 != 0
                val opcode = b0 and 0x0F
                val masked = b1 and 0x80 != 0
                var len = (b1 and 0x7F).toLong()
                if (len == 126L) len = (readN(2) ?: return null).fold(0L) { a, x -> (a shl 8) or (x.toLong() and 0xFF) }
                else if (len == 127L) len = (readN(8) ?: return null).fold(0L) { a, x -> (a shl 8) or (x.toLong() and 0xFF) }
                val mask = if (masked) readN(4) ?: return null else null
                val payload = readN(len.toInt()) ?: return null
                if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                when (opcode) {
                    0x8 -> return null
                    0x9 -> sendFrame(0xA, payload)
                    0xA -> Unit
                    else -> {
                        message.write(payload)
                        if (fin) return message.toString(StandardCharsets.UTF_8)
                    }
                }
            }
        }

        fun send(text: String) = sendFrame(0x1, text.toByteArray(StandardCharsets.UTF_8))

        private fun sendFrame(opcode: Int, payload: ByteArray) {
            synchronized(writeLock) {
                output.write(0x80 or opcode)
                when {
                    payload.size <= 125 -> output.write(payload.size)
                    payload.size <= 0xFFFF -> { output.write(126); output.write(payload.size ushr 8); output.write(payload.size and 0xFF) }
                    else -> {
                        output.write(127)
                        for (s in 7 downTo 0) output.write((payload.size.toLong() ushr (s * 8)).toInt() and 0xFF)
                    }
                }
                output.write(payload)
                output.flush()
            }
        }

        private fun readN(n: Int): ByteArray? {
            if (n < 0) return null
            val buf = ByteArray(n)
            var read = 0
            while (read < n) {
                val r = input.read(buf, read, n - read)
                if (r == -1) return null
                read += r
            }
            return buf
        }

        fun close() {
            runCatching { socket.close() }
        }
    }
}

/** A JSON frame from pairs; gson is Minecraft's. */
object DevJson {
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    fun obj(vararg pairs: Pair<String, Any?>): String = gson.toJson(linkedMapOf(*pairs))
}
