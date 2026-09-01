package bpm.session

import bpm.catalog.BpmCatalog
import bpm.library.BpmLibrary
import bpm.library.DocumentCodec
import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.model.Graph
import dev.ziggle.vscript.model.GraphDoc
import dev.ziggle.vscript.model.Link
import dev.ziggle.vscript.model.Node
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommitPipelineTest {
    private class FakeController(override val enabled: Boolean) : Deployable {
        var restarts = 0
        override fun requestRestart() {
            restarts++
        }
    }

    private val library = BpmLibrary()
    private val controllers = listOf(FakeController(true), FakeController(false), FakeController(true))
    private val pipeline = CommitPipeline(library, BpmCatalog.catalog, { controllers })

    private fun graph(vararg nodes: Node, links: List<Link> = emptyList()) = Graph("g", "g", nodes.toList(), links)

    /** `on tick → items.move(chest, hopper)`: valid. */
    private fun mover(): String = GraphDoc.toJson(
        graph(
            Node(1, BuiltinNodes.ENTRY_TICK),
            Node(2, "items.move", literals = linkedMapOf("From" to "chest", "To" to "hopper", "Max" to 8L)),
            links = listOf(Link(1, 1, "Exec", 2, "Exec")),
        ),
    )

    /** `on tick → items.nope`: a node the catalogue does not have, which is an error. */
    private fun broken(): String = GraphDoc.toJson(
        graph(Node(1, BuiltinNodes.ENTRY_TICK), Node(2, "items.nope"), links = listOf(Link(1, 1, "Exec", 2, "Exec"))),
    )

    private fun request(id: UUID, base: Int, json: String, deploy: Boolean = false) =
        CommitRequest(id, base, DocumentCodec.sha256(json), json, deploy)

    @Test
    fun `ok unchanged conflict not holder not found`() {
        val rec = library.create("doc", "{}", null)
        val v0 = rec.version
        val ok = pipeline.commit(true, request(rec.id, v0, mover()))
        assertEquals(CommitStatus.OK, ok.status, ok.message)
        assertEquals(v0 + 1, ok.version)
        assertEquals(0, ok.errors)
        assertEquals(mover(), library.text(rec.id))

        val unchanged = pipeline.commit(true, request(rec.id, ok.version, mover()))
        assertEquals(CommitStatus.UNCHANGED, unchanged.status)
        assertEquals(ok.version, library[rec.id]!!.version, "unchanged stores nothing")

        val conflict = pipeline.commit(true, request(rec.id, v0, broken()))
        assertEquals(CommitStatus.CONFLICT, conflict.status)
        assertEquals(mover(), library.text(rec.id), "a conflicting commit changes nothing")

        assertEquals(CommitStatus.NOT_HOLDER, pipeline.commit(false, request(rec.id, ok.version, broken())).status)
        assertEquals(CommitStatus.NOT_FOUND, pipeline.commit(true, request(UUID.randomUUID(), 0, mover())).status)
    }

    @Test
    fun `bad format, rejected render entry and too large`() {
        val rec = library.create("doc", "{}", null)
        assertEquals(CommitStatus.BAD_FORMAT, pipeline.commit(true, request(rec.id, rec.version, "{not json")).status)
        val render = GraphDoc.toJson(graph(Node(1, BuiltinNodes.ENTRY_RENDER)))
        val rejected = pipeline.commit(true, request(rec.id, rec.version, render))
        assertEquals(CommitStatus.REJECTED, rejected.status)
        assertTrue("Render" in rejected.message)
        val small = CommitPipeline(library, BpmCatalog.catalog, { emptyList() }, maxRawBytes = 10)
        assertEquals(CommitStatus.TOO_LARGE, small.commit(true, request(rec.id, rec.version, mover())).status)
        assertEquals("{}", library.text(rec.id), "none of those stored anything")
    }

    @Test
    fun `errors are stored but flagged and never deployed - deploy restarts only bound and enabled`() {
        val rec = library.create("doc", "{}", null)
        val bad = pipeline.commit(true, request(rec.id, rec.version, broken(), deploy = true))
        assertEquals(CommitStatus.OK, bad.status)
        assertTrue(bad.errors > 0, "the validator saw the unknown node: ${bad.issues.map { it.message }}")
        assertTrue(library[rec.id]!!.hasErrors)
        assertEquals(broken(), library.text(rec.id), "half-finished work is kept")
        assertEquals(0, bad.deployed)
        assertTrue(controllers.all { it.restarts == 0 })

        val good = pipeline.commit(true, request(rec.id, bad.version, mover(), deploy = true))
        assertEquals(CommitStatus.OK, good.status, good.message)
        assertFalse(library[rec.id]!!.hasErrors)
        assertEquals(2, good.deployed, "the disabled controller is left alone")
        assertEquals(listOf(1, 0, 1), controllers.map { it.restarts })

        // Deploying the saved version again: nothing to store, controllers restart anyway.
        val again = pipeline.commit(true, request(rec.id, good.version, mover(), deploy = true))
        assertEquals(CommitStatus.UNCHANGED, again.status)
        assertEquals(2, again.deployed)
        assertEquals(listOf(2, 0, 2), controllers.map { it.restarts })
    }
}
