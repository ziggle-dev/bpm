package bpm.runtime

import io.osrsx.vscript.host.FileStore
import io.osrsx.vscript.vm.VmError
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag

/**
 * A script's files, kept inside its controller's NBT so that they travel with the world save.
 *
 * vscript's `readJson`/`writeJson`/`listFiles` nodes see a small sandboxed file system. Here it is a flat
 * compound of `path → text` under the controller: no real files, nothing outside the block, and quotas that
 * keep one script from bloating a chunk ([MAX_FILES], [MAX_FILE_BYTES], [MAX_TOTAL_BYTES]). A path is a
 * `/`-separated list of plain segments — `..`, `.` and empty segments are refused, so there is nothing to
 * escape from. Over-quota writes raise a [VmError], which stops the fiber on the node that wrote.
 */
class NbtFileStore(private val root: CompoundTag, private val onChange: () -> Unit) : FileStore {

    private val files: CompoundTag
        get() {
            if (!root.contains(KEY, Tag.TAG_COMPOUND.toInt())) root.put(KEY, CompoundTag())
            return root.getCompound(KEY)
        }

    override fun read(path: String): String? {
        val key = key(path)
        return if (files.contains(key, Tag.TAG_STRING.toInt())) files.getString(key) else null
    }

    override fun write(path: String, text: String) {
        val key = key(path)
        val bytes = text.toByteArray(Charsets.UTF_8).size
        if (bytes > MAX_FILE_BYTES) throw VmError("file '$path' is $bytes bytes; the limit is $MAX_FILE_BYTES")
        val f = files
        val isNew = !f.contains(key)
        if (isNew && f.allKeys.size >= MAX_FILES) throw VmError("this controller already holds $MAX_FILES files")
        val total = f.allKeys.sumOf { k -> if (k == key) 0 else f.getString(k).toByteArray(Charsets.UTF_8).size } + bytes
        if (total > MAX_TOTAL_BYTES) throw VmError("writing '$path' would take this controller's files to $total bytes; the limit is $MAX_TOTAL_BYTES")
        f.putString(key, text)
        onChange()
    }

    override fun exists(path: String): Boolean = files.contains(key(path), Tag.TAG_STRING.toInt())

    override fun delete(path: String): Boolean {
        val key = key(path)
        if (!files.contains(key)) return false
        files.remove(key)
        onChange()
        return true
    }

    override fun list(dir: String): List<String> {
        val prefix = dirPrefix(dir)
        return files.allKeys.filter { it.startsWith(prefix) && '/' !in it.substring(prefix.length) }
            .map { it.substring(prefix.length) }.sorted()
    }

    override fun folders(dir: String): List<String> {
        val prefix = dirPrefix(dir)
        return files.allKeys.filter { it.startsWith(prefix) && '/' in it.substring(prefix.length) }
            .map { it.substring(prefix.length).substringBefore('/') }.distinct().sorted()
    }

    /** Every stored path, for tooling. */
    val paths: List<String> get() = files.allKeys.sorted()

    private fun dirPrefix(dir: String): String {
        val trimmed = dir.trim('/')
        return if (trimmed.isEmpty()) "" else key(trimmed) + "/"
    }

    private fun key(path: String): String {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) throw VmError("empty file path")
        for (p in parts) {
            if (p == "." || p == "..") throw VmError("file path '$path' may not contain '.' or '..' segments")
            if (p.length > 64) throw VmError("file path segment '${p.take(16)}…' is too long")
        }
        return parts.joinToString("/")
    }

    companion object {
        const val KEY = "files"
        const val MAX_FILES = 64
        const val MAX_FILE_BYTES = 64 * 1024
        const val MAX_TOTAL_BYTES = 256 * 1024
    }
}
