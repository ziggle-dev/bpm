package bpm.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DocumentCodecTest {
    @Test
    fun `gzip round-trips and is smaller for real documents`() {
        val text = """{"format":10,"id":"x","name":"n","nodes":[${(1..200).joinToString(",") { "{\"id\":$it,\"type\":\"math.add\"}" }}]}"""
        val gz = DocumentCodec.gzip(text)
        assertTrue(gz.size < text.length / 4)
        assertEquals(text, DocumentCodec.gunzip(gz))
        assertEquals(10, DocumentCodec.formatOf(text))
        assertEquals(0, DocumentCodec.formatOf("not json"))
    }

    @Test
    fun `inflation is capped`() {
        val bomb = DocumentCodec.gzip("0".repeat(200_000))
        expect<IllegalArgumentException> { DocumentCodec.gunzip(bomb, maxRaw = 100_000) }
        assertEquals(200_000, DocumentCodec.gunzip(bomb).length)
    }

    @Test
    fun `sha is stable and content-sensitive`() {
        assertEquals(DocumentCodec.sha256("abc"), DocumentCodec.sha256("abc"))
        assertNotEquals(DocumentCodec.sha256("abc"), DocumentCodec.sha256("abd"))
        assertEquals(64, DocumentCodec.sha256("").length)
    }
}

/** `assertFailsWith` goes through kotlin-reflect, which the test classpath does not carry at the stdlib's version. */
private inline fun <reified T : Throwable> expect(block: () -> Unit) {
    val thrown = runCatching(block).exceptionOrNull()
    if (thrown !is T) throw AssertionError("expected ${T::class.java.simpleName}, got $thrown")
}
