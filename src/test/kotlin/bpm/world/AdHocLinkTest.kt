package bpm.world

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.Level
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Links made from coordinates round-trip through their name and refuse anything malformed. */
class AdHocLinkTest {
    @Test
    fun `a position and a face round-trip through the name`() {
        val name = AdHocLink.name(BlockPos(12, -3, 7), Direction.UP)
        assertEquals("@12,-3,7:up", name)
        val link = AdHocLink.parse(name, Level.OVERWORLD)!!
        assertEquals(BlockPos(12, -3, 7), link.pos)
        assertEquals(Direction.UP, link.side)
        assertEquals(Level.OVERWORLD, link.dimension)
        assertEquals(name, link.name)
    }

    @Test
    fun `no face is fine, nonsense is not`() {
        assertNull(AdHocLink.parse("@1,2,3", Level.OVERWORLD)!!.side)
        assertNull(AdHocLink.parse("chest", Level.OVERWORLD))
        assertNull(AdHocLink.parse("@1,2", Level.OVERWORLD))
        assertNull(AdHocLink.parse("@a,b,c", Level.OVERWORLD))
        assertNull(AdHocLink.parse("@1,2,3:sideways", Level.OVERWORLD))
    }
}
