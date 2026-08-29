package bpm.client.editor

import io.osrsx.vscript.model.FunctionPin
import io.osrsx.vscript.model.Graph
import io.osrsx.vscript.model.GraphFunction
import io.osrsx.vscript.model.PinType
import io.osrsx.vscript.model.StructType
import io.osrsx.vscript.model.TypeRef
import io.osrsx.vscript.runtime.EditorDoc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The "Of" chip on a list-typed function pin or record field makes it a LIST of that element. */
class ListElementRoutingTest {
    @Test
    fun `a function's list pins take an element type, keeping name, default and optionality`() {
        val doc = EditorDoc(Graph("t", "t", functions = listOf(GraphFunction("f",
            params = listOf(FunctionPin("xs", TypeRef(PinType.LIST), 5L)),
            results = listOf(FunctionPin("ys", TypeRef(PinType.LIST).orNull())),
        ))))
        PickerRouting.commit(doc, "fe|f|in|0", TypeRef.named("BlockPos"))
        PickerRouting.commit(doc, "fe|f|out|0", TypeRef(PinType.STRING))
        val f = doc.function("f")!!
        assertEquals("xs", f.params[0].name)
        assertEquals(5L, f.params[0].default, "the default survives")
        assertTrue(f.params[0].type.isList && f.params[0].type.of?.name == "BlockPos", "${f.params[0].type}")
        assertTrue(f.results[0].type.isList && f.results[0].type.of?.builtin == PinType.STRING && f.results[0].type.optional, "${f.results[0].type}")
    }

    @Test
    fun `a record's list field takes an element type`() {
        val doc = EditorDoc(Graph("t", "t", types = listOf(StructType("Bag", listOf(FunctionPin("items", TypeRef(PinType.LIST)))))))
        PickerRouting.commit(doc, "se|Bag|0", TypeRef.named("ItemStack"))
        val t = doc.struct("Bag")!!
        assertTrue(t.fields[0].type.isList && t.fields[0].type.of?.name == "ItemStack", "${t.fields[0].type}")
    }
}
