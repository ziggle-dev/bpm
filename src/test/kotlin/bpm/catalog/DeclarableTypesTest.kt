package bpm.catalog

import io.osrsx.vscript.model.Types
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The domain's wire-only types can be named — for a parameter, a field, a list, a variable — without gaining an editor. */
class DeclarableTypesTest {
    @Test
    fun `stacks, filters, entities and the enums are on the type pickers`() {
        BpmCatalog.catalog // registers the types
        val offered = Types.forVariables.map { it.name }.toSet()
        for (name in listOf("ItemStack", "FluidStack", "Filter", "Slot", "BlockState", "Entity", "Player", "Link", "BlockPos", "Direction", "Click", "Aim", "Notify")) {
            assertTrue(name in offered, "$name is not offered as a type ($offered)")
        }
        for (name in listOf("ItemStack", "FluidStack", "Filter", "Slot", "BlockState", "Entity", "Player")) {
            assertEquals(false, Types.of(name)!!.authorable, "$name has no inline editor and must not claim one")
        }
        assertTrue("ItemStack" in io.osrsx.vscript.model.BuiltinNodes.elementTypes.map { it.name }, "a list of stacks")
    }
}
