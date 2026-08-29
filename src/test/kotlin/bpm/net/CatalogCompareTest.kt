package bpm.net

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogCompareTest {
    @Test
    fun `same hash is fine whatever the lists say`() {
        assertNull(CatalogCompare.mismatch("a", listOf("bpm@1"), "a", listOf("bpm@1")))
    }

    @Test
    fun `a missing pack is named on the side that lacks it`() {
        val m = CatalogCompare.mismatch("a", listOf("bpm@1", "extra@2"), "b", listOf("bpm@1"))!!
        assertTrue("missing on your client: extra@2" in m, m)
        val n = CatalogCompare.mismatch("a", listOf("bpm@1"), "b", listOf("bpm@1", "client-only@1"))!!
        assertTrue("not on the server: client-only@1" in n, n)
    }

    @Test
    fun `same packs but different definitions says so`() {
        val m = CatalogCompare.mismatch("a", listOf("bpm@1"), "b", listOf("bpm@1"))!!
        assertTrue("different node definitions" in m, m)
        assertTrue("bpm@1" in m)
    }
}
