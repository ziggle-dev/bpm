package bpm.world

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.Level
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkTableTest {
    private fun link(name: String, x: Int = 0, side: Direction? = Direction.UP) = Link(name, BlockPos(x, 64, 0), side, Level.OVERWORLD)

    @Test
    fun `a taken name is numbered, and a link is found by name or by face`() {
        val t = LinkTable()
        val a = t.add(link("chest", 1))
        val b = t.add(link("chest", 2))
        assertEquals("chest", a.name)
        assertEquals("chest-2", b.name)
        assertEquals(setOf("chest", "chest-2"), t.names)
        assertNotNull(t.at(BlockPos(2, 64, 0), Direction.UP))
        assertNull(t.at(BlockPos(2, 64, 0), Direction.DOWN))
    }

    @Test
    fun `a ray finds the first link cell it enters, and stops where it is blocked`() {
        val t = LinkTable()
        t.add(link("gone", 5))
        t.add(link("far", 9))
        val eye = net.minecraft.world.phys.Vec3(0.5, 64.5, 0.5)
        val east = net.minecraft.world.phys.Vec3(1.0, 0.0, 0.0)
        assertEquals("gone", t.firstAlong(eye, east, 12.0)?.name)
        assertTrue(t.remove("gone"))
        assertEquals("far", t.firstAlong(eye, east, 12.0)?.name, "the next link along the ray was not found")
        assertNull(t.firstAlong(eye, east, 3.0), "a link beyond the reach was found")
        assertNull(t.firstAlong(eye, net.minecraft.world.phys.Vec3(-1.0, 0.0, 0.0), 12.0), "a link behind was found")
        t.add(link("wall", 5))
        assertNull(t.firstAlong(eye, east, 12.0) { it.x == 3 }, "the ray went through a blocking cell")
    }

    @Test
    fun `rename keeps the link and refuses a taken or empty name`() {
        val t = LinkTable()
        t.add(link("chest")); t.add(link("hopper", 3))
        assertTrue(t.rename("chest", "input"))
        assertNull(t["chest"]); assertNotNull(t["input"])
        assertTrue(!t.rename("input", "hopper"))
        assertTrue(!t.rename("input", " "))
        assertTrue(t.rename("input", "input"), "renaming to itself is fine")
    }

    @Test
    fun `save and load round-trip every field and bump the version`() {
        val t = LinkTable()
        t.add(link("chest", 1)); t.add(link("tank", 2, side = null))
        val v = t.version
        val copy = LinkTable().also { it.load(t.save()) }
        assertEquals(t.all, copy.all)
        assertNull(copy["tank"]!!.side)
        assertTrue(copy.version > 0)
        t.remove("chest")
        assertTrue(t.version > v)
    }

    @Test
    fun `an auto name is the block's path`() {
        assertEquals("chest", LinkTable.autoName(net.minecraft.resources.ResourceLocation.parse("minecraft:chest")))
        assertEquals("tank", LinkTable.autoName(net.minecraft.resources.ResourceLocation.parse("mekanism:machines/tank")))
    }
}
