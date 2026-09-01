package bpm.library

import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** How a document is compressed, hashed and version-stamped. Plain bytes and text; no game types. */
object DocumentCodec {
    const val MAX_RAW_BYTES = 1 shl 20

    fun gzip(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    /** Inflates at most [maxRaw] bytes; anything past that is a malformed or hostile document. */
    fun gunzip(bytes: ByteArray, maxRaw: Int = MAX_RAW_BYTES): String {
        GZIPInputStream(bytes.inputStream()).use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (out.size() + n > maxRaw) throw IllegalArgumentException("document exceeds $maxRaw bytes")
                out.write(buf, 0, n)
            }
            return out.toString(Charsets.UTF_8)
        }
    }

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** The `format` field of a document's JSON, or 0 when it has none. */
    fun formatOf(text: String): Int = runCatching {
        JsonParser.parseString(text).asJsonObject.get("format")?.asInt ?: 0
    }.getOrDefault(0)
}
