package bpm.client.editor

import bpm.catalog.BpmCatalog
import dev.ziggle.vscript.model.Graph
import dev.ziggle.vscript.model.GraphVariable
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.runtime.EditorDoc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PickerRoutingTest {
    @Test
    fun `a variable retyped to a host enum starts at a member, never at nothing`() {
        BpmCatalog.catalog // registers the enums
        val doc = EditorDoc(Graph("t", "t", variables = listOf(GraphVariable("v", TypeRef(PinType.INT), 0), GraphVariable("w", TypeRef(PinType.STRING), "right"))))
        PickerRouting.commit(doc, "vt|v", TypeRef.named("Aim"))
        assertEquals("Auto", doc.variable("v")!!.default, "the first member")
        PickerRouting.commit(doc, "vt|w", TypeRef.named("Click"))
        assertEquals("Right", doc.variable("w")!!.default, "the member the old default spelled")
        PickerRouting.commit(doc, "vt|v", TypeRef.named("Aim").orNull())
        assertNull(doc.variable("v")!!.default, "an optional enum may start as nothing")
    }
}
