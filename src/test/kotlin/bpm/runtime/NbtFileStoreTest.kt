package bpm.runtime

import dev.ziggle.vscript.vm.VmError
import net.minecraft.nbt.CompoundTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NbtFileStoreTest {
    private fun store(): Pair<NbtFileStore, IntArray> {
        val changes = IntArray(1)
        return NbtFileStore(CompoundTag()) { changes[0]++ } to changes
    }

    @Test
    fun `write, read, list and delete`() {
        val (fs, changes) = store()
        assertNull(fs.read("state.json"))
        fs.write("state.json", "{\"a\":1}")
        fs.write("logs/day1.txt", "x")
        fs.write("logs/day2.txt", "y")
        fs.write("logs/old/day0.txt", "z")
        assertEquals("{\"a\":1}", fs.read("state.json"))
        assertTrue(fs.exists("/state.json"))
        assertEquals(listOf("state.json"), fs.list(""))
        assertEquals(listOf("day1.txt", "day2.txt"), fs.list("logs"))
        assertEquals(listOf("old"), fs.folders("logs/"))
        assertEquals(listOf("logs"), fs.folders(""))
        assertTrue(fs.delete("logs/day1.txt"))
        assertFalse(fs.delete("logs/day1.txt"))
        assertEquals(listOf("day2.txt"), fs.list("logs"))
        assertEquals(5, changes[0], "every write and successful delete marks the entity changed")
    }

    @Test
    fun `paths are normalised and cannot escape`() {
        val (fs, _) = store()
        fs.write("a//b\\c.txt", "1")
        assertEquals("1", fs.read("a/b/c.txt"))
        expect<VmError> { fs.write("../x", "1") }
        expect<VmError> { fs.read("./x") }
        expect<VmError> { fs.write("", "1") }
        assertEquals(listOf("a/b/c.txt"), fs.paths)
    }

    @Test
    fun `quotas hold`() {
        val (fs, _) = store()
        expect<VmError> { fs.write("big", "x".repeat(NbtFileStore.MAX_FILE_BYTES + 1)) }
        fs.write("big", "x".repeat(NbtFileStore.MAX_FILE_BYTES))
        // Total: three more maximal files fit exactly (4 × 64 KB = 256 KB); a fifth byte does not.
        fs.write("b2", "x".repeat(NbtFileStore.MAX_FILE_BYTES))
        fs.write("b3", "x".repeat(NbtFileStore.MAX_FILE_BYTES))
        fs.write("b4", "x".repeat(NbtFileStore.MAX_FILE_BYTES))
        expect<VmError> { fs.write("b5", "x") }
        // Rewriting an existing file counts its new size, not old + new.
        fs.write("big", "y".repeat(NbtFileStore.MAX_FILE_BYTES))
        val (many, _) = store()
        repeat(NbtFileStore.MAX_FILES) { many.write("f$it", "") }
        expect<VmError> { many.write("one-too-many", "") }
        many.write("f0", "still allowed to rewrite")
    }
}

/** `assertFailsWith` goes through kotlin-reflect, which the test classpath does not carry at the stdlib's version. */
private inline fun <reified T : Throwable> expect(block: () -> Unit) {
    val thrown = runCatching(block).exceptionOrNull()
    if (thrown !is T) throw AssertionError("expected ${T::class.java.simpleName}, got $thrown")
}
