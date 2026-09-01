package bpm.net.chunk

import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * One piece of a message too big for a single packet. The same shape travels both ways: a document push to
 * the client, a commit to the server. [inner] names the message the pieces make up (see `BigMessages`).
 */
class Chunk(
    val messageId: Int,
    val inner: String,
    val index: Int,
    val count: Int,
    val totalBytes: Int,
    val gz: Boolean,
    val body: ByteArray,
)

/** Splits a message into [Chunk]s of at most [CHUNK_BYTES], gzipped when that is worth it. */
object Chunker {
    const val CHUNK_BYTES = 24 * 1024
    private val nextId = AtomicInteger()

    fun split(inner: String, bytes: ByteArray, gzip: Boolean = bytes.size > 512, chunkBytes: Int = CHUNK_BYTES): List<Chunk> {
        require(inner.isNotEmpty() && inner.length <= 64) { "bad inner message name '$inner'" }
        val payload = if (gzip) gzip(bytes) else bytes
        val id = nextId.incrementAndGet()
        val count = maxOf(1, (payload.size + chunkBytes - 1) / chunkBytes)
        return (0 until count).map { i ->
            val from = i * chunkBytes
            val to = minOf(payload.size, from + chunkBytes)
            Chunk(id, inner, i, count, payload.size, gzip, payload.copyOfRange(from, to))
        }
    }

    fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size / 2 + 64)
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    /** Inflates at most [maxRaw] bytes. */
    fun gunzip(bytes: ByteArray, maxRaw: Int): ByteArray {
        GZIPInputStream(bytes.inputStream()).use { input ->
            val out = ByteArrayOutputStream(minOf(maxRaw, bytes.size * 4 + 64))
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (out.size() + n > maxRaw) throw IllegalArgumentException("inflates past $maxRaw bytes")
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }
}

/**
 * Reassembles [Chunk]s for one peer.
 *
 * Every check a hostile or confused peer could trip is here, on decode-time facts only: a bounded number of
 * messages in flight, declared sizes within [maxTotalBytes] and inflated sizes within [maxRawBytes],
 * consistent headers across a message's chunks, no duplicates, no overrun, and a [timeoutMs] after which a
 * half-received message is dropped. A [Result.Rejected] is the caller's cue to disconnect (server) or to log
 * (client); nothing here touches the game.
 */
class ChunkAssembler(
    private val now: () -> Long,
    private val maxInFlight: Int = 8,
    private val timeoutMs: Long = 10_000,
    private val maxTotalBytes: Int,
    private val maxRawBytes: Int,
) {
    sealed interface Result {
        class Complete(val inner: String, val bytes: ByteArray) : Result
        data object Pending : Result
        class Rejected(val reason: String) : Result
    }

    private class InFlight(val inner: String, val count: Int, val totalBytes: Int, val gz: Boolean, val startedAt: Long) {
        val parts = arrayOfNulls<ByteArray>(count)
        var received = 0
        var bytes = 0
    }

    private val inFlight = LinkedHashMap<Int, InFlight>()

    val inFlightCount: Int get() = inFlight.size

    fun accept(chunk: Chunk): Result {
        if (chunk.inner.isEmpty() || chunk.inner.length > 64) return reject(chunk, "bad inner name")
        if (chunk.count < 1 || chunk.count > MAX_CHUNKS) return reject(chunk, "bad chunk count ${chunk.count}")
        if (chunk.index < 0 || chunk.index >= chunk.count) return reject(chunk, "chunk index out of range")
        if (chunk.totalBytes < 0 || chunk.totalBytes > maxTotalBytes) return reject(chunk, "message of ${chunk.totalBytes} bytes exceeds $maxTotalBytes")
        if (chunk.body.size > Chunker.CHUNK_BYTES) return reject(chunk, "chunk body of ${chunk.body.size} bytes")

        val m = inFlight[chunk.messageId] ?: run {
            if (inFlight.size >= maxInFlight) return Result.Rejected("more than $maxInFlight messages in flight")
            InFlight(chunk.inner, chunk.count, chunk.totalBytes, chunk.gz, now()).also { inFlight[chunk.messageId] = it }
        }
        if (m.inner != chunk.inner || m.count != chunk.count || m.totalBytes != chunk.totalBytes || m.gz != chunk.gz) return reject(chunk, "chunk header changed mid-message")
        if (m.parts[chunk.index] != null) return reject(chunk, "duplicate chunk ${chunk.index}")
        m.parts[chunk.index] = chunk.body
        m.received++
        m.bytes += chunk.body.size
        if (m.bytes > m.totalBytes) return reject(chunk, "more bytes than declared")
        if (m.received < m.count) return Result.Pending

        inFlight.remove(chunk.messageId)
        if (m.bytes != m.totalBytes) return Result.Rejected("received ${m.bytes} of ${m.totalBytes} declared bytes")
        val joined = ByteArray(m.totalBytes)
        var at = 0
        for (p in m.parts) {
            p!!.copyInto(joined, at)
            at += p.size
        }
        val bytes = if (m.gz) {
            try {
                Chunker.gunzip(joined, maxRawBytes)
            } catch (e: Exception) {
                return Result.Rejected("bad gzip: ${e.message}")
            }
        } else {
            if (joined.size > maxRawBytes) return Result.Rejected("message of ${joined.size} bytes exceeds $maxRawBytes")
            joined
        }
        return Result.Complete(m.inner, bytes)
    }

    /** Drops messages that have waited longer than the timeout; returns how many. */
    fun expire(): Int {
        val t = now()
        val it = inFlight.values.iterator()
        var n = 0
        while (it.hasNext()) {
            if (t - it.next().startedAt > timeoutMs) {
                it.remove()
                n++
            }
        }
        return n
    }

    private fun reject(chunk: Chunk, reason: String): Result {
        inFlight.remove(chunk.messageId)
        return Result.Rejected(reason)
    }

    companion object {
        const val MAX_CHUNKS = 64
    }
}
