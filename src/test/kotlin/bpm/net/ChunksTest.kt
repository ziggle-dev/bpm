package bpm.net

import bpm.net.chunk.Chunk
import bpm.net.chunk.ChunkAssembler
import bpm.net.chunk.Chunker
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChunksTest {
    private var now = 0L
    private fun assembler(maxInFlight: Int = 8, maxTotal: Int = 1 shl 20, maxRaw: Int = 1 shl 20) =
        ChunkAssembler({ now }, maxInFlight = maxInFlight, timeoutMs = 10_000, maxTotalBytes = maxTotal, maxRawBytes = maxRaw)

    private fun bytes(n: Int) = ByteArray(n) { (it * 31 + 7).toByte() }

    private fun roundTrip(n: Int, gzip: Boolean, chunkBytes: Int = 1000) {
        val data = bytes(n)
        val chunks = Chunker.split("t", data, gzip = gzip, chunkBytes = chunkBytes)
        val a = assembler()
        var done: ChunkAssembler.Result.Complete? = null
        for (c in chunks) {
            when (val r = a.accept(c)) {
                is ChunkAssembler.Result.Complete -> done = r
                is ChunkAssembler.Result.Rejected -> throw AssertionError("rejected: ${r.reason}")
                ChunkAssembler.Result.Pending -> {}
            }
        }
        assertContentEquals(data, done!!.bytes, "n=$n gzip=$gzip")
        assertEquals("t", done.inner)
        assertEquals(0, a.inFlightCount)
    }

    @Test
    fun `one byte an exact multiple one over empty - plain and gzipped`() {
        for (gz in listOf(false, true)) {
            roundTrip(1, gz)
            roundTrip(0, gz)
            roundTrip(3000, gz)
            roundTrip(3001, gz)
            roundTrip(200_000, gz, chunkBytes = Chunker.CHUNK_BYTES)
        }
        assertEquals(4, Chunker.split("t", bytes(3001), gzip = false, chunkBytes = 1000).size)
    }

    @Test
    fun `out of order arrival still assembles`() {
        val data = bytes(5000)
        val chunks = Chunker.split("t", data, gzip = false, chunkBytes = 1000).reversed()
        val a = assembler()
        val last = chunks.map { a.accept(it) }.last()
        assertTrue(last is ChunkAssembler.Result.Complete)
        assertContentEquals(data, (last as ChunkAssembler.Result.Complete).bytes)
    }

    @Test
    fun `duplicates header changes and overruns are rejected and drop the message`() {
        val chunks = Chunker.split("t", bytes(3000), gzip = false, chunkBytes = 1000)
        val a = assembler()
        a.accept(chunks[0])
        assertTrue(a.accept(chunks[0]) is ChunkAssembler.Result.Rejected, "duplicate")
        assertEquals(0, a.inFlightCount)

        a.accept(chunks[0])
        val forged = Chunk(chunks[1].messageId, "other", 1, 3, 3000, false, chunks[1].body)
        assertTrue(a.accept(forged) is ChunkAssembler.Result.Rejected, "header change")
        assertEquals(0, a.inFlightCount)

        val lying = Chunk(99, "t", 0, 2, 10, false, ByteArray(500))
        assertTrue(a.accept(lying) is ChunkAssembler.Result.Rejected, "more bytes than declared")
        val short = listOf(Chunk(98, "t", 0, 2, 3000, false, ByteArray(10)), Chunk(98, "t", 1, 2, 3000, false, ByteArray(10)))
        a.accept(short[0])
        assertTrue(a.accept(short[1]) is ChunkAssembler.Result.Rejected, "fewer bytes than declared")
    }

    @Test
    fun `caps hold - declared size inflated size chunk count in flight`() {
        val a = assembler(maxInFlight = 2, maxTotal = 4000, maxRaw = 5000)
        assertTrue(a.accept(Chunk(1, "t", 0, 1, 5000, false, ByteArray(10))) is ChunkAssembler.Result.Rejected, "declared too big")
        val bomb = Chunker.split("t", ByteArray(50_000), gzip = true, chunkBytes = 1000)
        assertTrue(bomb.sumOf { it.body.size } < 4000, "gzip made it small enough to pass the declared cap")
        val last = bomb.map { a.accept(it) }.last()
        assertTrue(last is ChunkAssembler.Result.Rejected && "inflates" in last.reason, "inflation cap: $last")
        assertTrue(a.accept(Chunk(2, "t", 0, ChunkAssembler.MAX_CHUNKS + 1, 100, false, ByteArray(1))) is ChunkAssembler.Result.Rejected, "too many chunks")

        a.accept(Chunk(10, "t", 0, 2, 20, false, ByteArray(10)))
        a.accept(Chunk(11, "t", 0, 2, 20, false, ByteArray(10)))
        assertTrue(a.accept(Chunk(12, "t", 0, 2, 20, false, ByteArray(10))) is ChunkAssembler.Result.Rejected, "third in flight")
        assertEquals(2, a.inFlightCount)
    }

    @Test
    fun `stale messages expire`() {
        val a = assembler()
        now = 1000
        a.accept(Chunk(1, "t", 0, 2, 20, false, ByteArray(10)))
        now = 5000
        a.accept(Chunk(2, "t", 0, 2, 20, false, ByteArray(10)))
        now = 11_500
        assertEquals(1, a.expire())
        assertEquals(1, a.inFlightCount)
        // The late second half of the expired message starts a new, incomplete message rather than completing anything.
        assertTrue(a.accept(Chunk(1, "t", 1, 2, 20, false, ByteArray(10))) is ChunkAssembler.Result.Pending)
    }
}
