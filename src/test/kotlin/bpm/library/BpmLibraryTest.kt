package bpm.library

import dev.ziggle.vscript.model.GraphDoc
import dev.ziggle.vscript.model.GraphImport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BpmLibraryTest {
    private val lib = BpmLibrary()
    private fun doc(name: String) = GraphDoc.toJson(dev.ziggle.vscript.model.Graph("id-$name", name))

    @Test
    fun `create, store, rename and delete move the right versions`() {
        val a = lib.create("alpha", doc("alpha"), null)
        assertEquals(1, a.version)
        assertEquals(1, lib.libraryVersion)
        val b = lib.create("alpha", doc("beta"), null)
        assertEquals("alpha-2", b.name, "names are unique")

        lib.store(a.id, doc("alpha2"))
        assertEquals(2, a.version)
        assertTrue(lib.rename(a.id, "gamma"))
        assertEquals(2, a.version, "a rename is not an edit of the document")
        assertEquals(4, lib.libraryVersion, "but the library moved")
        assertNotNull(lib.byName("GAMMA"), "names are case-insensitive")

        assertTrue(lib.delete(b.id))
        assertFalse(lib.delete(b.id))
        assertNull(lib[b.id])
        assertEquals(1, lib.all.size)
    }

    @Test
    fun `graphs are parsed once per version and imports resolve by id or name`() {
        val a = lib.create("lib", doc("lib"), null)
        val g1 = lib.graph(a.id)
        assertNotNull(g1)
        assertTrue(g1 === lib.graph(a.id), "cached")
        lib.store(a.id, doc("lib-v2"))
        assertFalse(g1 === lib.graph(a.id), "a new version is parsed again")
        val src = lib.graphSource()
        assertEquals("lib-v2", src.load(GraphImport("x", "lib"))?.name)
        assertEquals("lib-v2", src.load(GraphImport("x", "whatever", a.id.toString()))?.name)
        assertNull(src.load(GraphImport("x", "missing")))
        assertNull(lib.graph(java.util.UUID.randomUUID()))
    }

    @Test
    fun `nbt round trip`() {
        val a = lib.create("alpha", doc("alpha"), java.util.UUID.randomUUID())
        lib.store(a.id, doc("alpha2"), hasErrors = true)
        val tag = net.minecraft.nbt.CompoundTag().also(lib::writeTo)
        val back = BpmLibrary.load(tag)
        val r = back[a.id]!!
        assertEquals("alpha", r.name)
        assertEquals(2, r.version)
        assertTrue(r.hasErrors)
        assertEquals(a.owner, r.owner)
        assertEquals(lib.text(a.id), back.text(a.id))
        assertEquals(lib.libraryVersion, back.libraryVersion)
    }
}
