package bpm.catalog

import bpm.catalog.values.FilterValue
import bpm.nodes.DetachedHost
import io.osrsx.vscript.model.BuiltinNodes
import io.osrsx.vscript.model.Graph
import io.osrsx.vscript.model.GraphVariable
import io.osrsx.vscript.model.Link
import io.osrsx.vscript.model.Node
import io.osrsx.vscript.model.TypeRef
import io.osrsx.vscript.nodes.BuiltinHosts
import io.osrsx.vscript.runtime.EditorDoc
import io.osrsx.vscript.runtime.ScriptRuntime
import io.osrsx.vscript.vm.StructValue
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The `items.filter` construct node must hand the verbs a record the matcher understands. */
class FilterConstructTest {
    @Test
    fun `a constructed filter carries its fields and narrows a move`() {
        val library = BpmCatalog.library(DetachedHost)
        val hosts = library.install(BuiltinHosts.registry(), McValueOut)
        val runtime = ScriptRuntime(BpmCatalog.catalog, hosts)
        // on start: f = items.filter(item = diamond)
        val graph = Graph(
            "g", "g",
            nodes = listOf(
                Node(1, BuiltinNodes.ENTRY, 40f, 40f),
                Node(2, "items.filter", 300f, 200f, literals = linkedMapOf("item" to "minecraft:diamond")),
                Node(3, BuiltinNodes.VAR_SET, 300f, 40f).also { it.variable = "f" },
            ),
            links = listOf(Link(1, 1, "Exec", 3, "Exec"), Link(2, 2, "Filter", 3, "Value")),
            variables = listOf(GraphVariable("f", TypeRef.named(FilterValue.TYPE).orNull(), null)),
        )
        val issues = runtime.validate(EditorDoc(graph))
        assertTrue(issues.none { it.severity == io.osrsx.vscript.compile.Severity.ERROR }, "issues: ${issues.map { it.message }}")
        val error = runtime.run(EditorDoc(graph), debug = true)
        assertNull(error, "run: $error")
        repeat(5) { runtime.tick() }
        val f = runtime.variable("f")
        val fibers = runtime.fibers.joinToString { "${it.id}:${it.state}:${it.pauseReason}" }
        assertNotNull(f, "the variable was never set; error=${runtime.lastError} fibers=[$fibers] log=${runtime.log.records.map { it.message }} running=${runtime.isRunning} phase=${runtime.phase} pure=${runtime.pureValues} globalsVar=${runtime.variable("f")}")
        assertTrue(f is StructValue, "a ${f.javaClass.simpleName}, not a record")
        assertEquals(FilterValue.TYPE, f.type)
        assertEquals("minecraft:diamond", f.get("item"))
        val record = FilterValue.record(f)
        assertNotNull(record, "FilterValue.record refused the constructed value")
        val matcher = FilterValue.matcher(record, null)
        assertTrue(matcher.matches(ItemStack(Items.DIAMOND)))
        assertFalse(matcher.matches(ItemStack(Items.DIAMOND_ORE)), "the filter let diamond ore through")
        runtime.stop()
    }
}
