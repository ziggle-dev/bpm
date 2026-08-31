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
        assertEquals("chest", a?.name)
        assertEquals("chest-2", b?.name)
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

    @Test
    fun `a full table refuses new links, keeps the ones it has, and says which are past the cap`() {
        var cap = 2
        val t = LinkTable { LinkCaps(cap, 0) }
        assertNotNull(t.add(link("a", 1)))
        assertNotNull(t.add(link("b", 2)))
        assertTrue(t.full)
        assertNull(t.add(link("c", 3)), "a full table took a third link")
        assertEquals(setOf("a", "b"), t.names)

        // Every add is a new entry — a taken name is numbered — so a full table refuses those too.
        assertNull(t.add(link("a", 9)), "a full table numbered a duplicate name into a new slot")
        assertEquals(BlockPos(1, 64, 0), t["a"]?.pos)

        // Lowering the cap never deletes: the tail goes quiet instead.
        cap = 1
        assertEquals(listOf("b"), t.overCapacity)
        assertTrue(t.isOverCapacity("b"))
        assertTrue(!t.isOverCapacity("a"))
        assertEquals(setOf("a", "b"), t.names, "lowering the cap dropped a link")
    }

    @Test
    fun `blocks and people are capped separately, and neither crowds the other out`() {
        val t = LinkTable { LinkCaps(2, 1) }
        val alice = java.util.UUID.randomUUID()
        val bob = java.util.UUID.randomUUID()

        assertNotNull(t.add(link("chest", 1)))
        assertNotNull(t.add(link("chest", 2)))
        assertTrue(t.full)
        // A full block table still has room for a person.
        assertNotNull(t.add(link("alice", 3, side = null).copy(player = alice)), "a full block table refused a presence link")
        assertTrue(t.presenceFull)
        assertNull(t.add(link("bob", 4, side = null).copy(player = bob)), "a full presence table took a second person")
        assertNull(t.add(link("barrel", 5)), "a full block table took a third block")

        assertEquals(listOf("chest", "chest-2"), t.blocks.map { it.name })
        assertEquals(listOf("alice"), t.presence.map { it.name })
        assertEquals("alice", t.byPlayer(alice)?.name)
        assertNull(t.byPlayer(bob))
    }

    @Test
    fun `each kind counts its own capacity, so a person is never crowded out by chests`() {
        val t = LinkTable { LinkCaps(1, 1) }
        val alice = java.util.UUID.randomUUID()
        t.add(link("chest", 1))
        t.add(link("alice", 2, side = null).copy(player = alice))
        // The presence link is the second entry in the table but the first of its kind: it must still resolve.
        assertTrue(!t.isOverCapacity("alice"), "a presence link was capped by the block links ahead of it")
        assertTrue(!t.isOverCapacity("chest"))
        assertEquals(emptyList(), t.overCapacity)
    }

    @Test
    fun `a presence link survives a save and a load, and forgetting a player removes it`() {
        val t = LinkTable()
        val alice = java.util.UUID.randomUUID()
        t.add(link("chest", 1))
        t.add(link("alice", 2, side = null).copy(player = alice))

        val loaded = LinkTable()
        loaded.load(t.save())
        assertEquals(setOf("chest", "alice"), loaded.names)
        assertEquals(alice, loaded["alice"]?.player)
        assertTrue(loaded["alice"]!!.isPresence)
        assertNull(loaded["chest"]?.player, "a block link came back carrying a player")
        assertNull(loaded["chest"]?.let { if (it.isPresence) it else null })

        assertTrue(loaded.removePlayer(alice))
        assertEquals(setOf("chest"), loaded.names)
        assertTrue(!loaded.removePlayer(alice), "forgetting a player twice reported success")
    }

    @Test
    fun `a presence name is the player's, lowercased and numbered when taken`() {
        val t = LinkTable()
        assertEquals("steve", t.presenceName("Steve"))
        t.add(link("steve", 1, side = null).copy(player = java.util.UUID.randomUUID()))
        assertEquals("steve-2", t.presenceName("Steve"))
    }
}
