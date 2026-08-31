package bpm.world

import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.neoforged.neoforge.items.ItemStackHandler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The grant set as it is spelled, cycled and read — `docs/DESIGN_PLAYER_LINK.md` §2.2. */
class GrantsTest {

    @Test
    fun `a grant set round-trips through the text it rides on the stack as`() {
        assertEquals("state,read,give,hud", Grants.format(Grants.DELIVERY))
        assertEquals(Grants.DELIVERY, Grants.parse("state,read,give,hud"))
        assertEquals(Grants.FULL, Grants.parse(Grants.format(Grants.FULL)))
    }

    @Test
    fun `nonsense in the text is ignored rather than fatal, so a bad edit cannot brick a tether`() {
        assertEquals(setOf(Grant.GIVE, Grant.TAKE), Grants.parse("give, nonsense ,TAKE"))
        assertEquals(Grants.NONE, Grants.parse(""))
        assertEquals(Grants.NONE, Grants.parse(null))
    }

    @Test
    fun `take is never in the preset a tether is made with`() {
        assertFalse(Grant.TAKE in Grants.DELIVERY, "a fresh tether let a controller empty someone's pockets")
        assertTrue(Grant.TAKE in Grants.FULL)
    }

    @Test
    fun `the presets cycle, and an unknown set restarts the cycle`() {
        assertEquals(Grants.FULL, Grants.next(Grants.DELIVERY))
        assertEquals(Grants.WATCH, Grants.next(Grants.FULL))
        assertEquals(Grants.DELIVERY, Grants.next(Grants.WATCH))
        assertEquals(Grants.DELIVERY, Grants.next(setOf(Grant.ENDER)))
    }

    @Test
    fun `watching opens no inventory at all, which is how read stays honest`() {
        // Rather than a handler that hides its slots — which would break insertItemStacked — a tether that
        // grants only `state` hands out no handler, and every item verb reads that as "nothing there".
        assertFalse(Grants.opensInventory(Grants.WATCH))
        assertTrue(Grants.opensInventory(Grants.DELIVERY))
        assertTrue(Grants.opensInventory(setOf(Grant.TAKE)), "take alone must still open the inventory it takes from")
    }
}

/** The mask itself: what a controller may change, and what it may not touch. */
class GrantedInvTest {
    private val pack = 0
    private val armour = Inventory.INVENTORY_SIZE
    private val offhand = Inventory.INVENTORY_SIZE + 4

    private fun inv(): ItemStackHandler = ItemStackHandler(Inventory.INVENTORY_SIZE + 5).also {
        it.setStackInSlot(pack, ItemStack(Items.STONE, 16))
        it.setStackInSlot(armour, ItemStack(Items.IRON_CHESTPLATE))
        it.setStackInSlot(offhand, ItemStack(Items.SHIELD))
    }

    @Test
    fun `give may put in and not take out, take may take out and not put in`() {
        val backing = inv()
        val give = GrantedInv(Grants.DELIVERY, backing)
        assertTrue(give.insertItem(1, ItemStack(Items.TORCH, 4), false).isEmpty, "a delivery tether could not deliver")
        assertTrue(give.extractItem(pack, 8, false).isEmpty, "a delivery tether emptied someone's pockets")

        val take = GrantedInv(setOf(Grant.TAKE), backing)
        assertEquals(8, take.extractItem(pack, 8, false).count)
        assertEquals(4, take.insertItem(2, ItemStack(Items.TORCH, 4), false).count, "a take-only tether inserted")
    }

    @Test
    fun `the pack is reachable without equip, the armour and the offhand are not`() {
        val backing = inv()
        val without = GrantedInv(Grants.FULL, backing)
        assertEquals(16, without.extractItem(pack, 64, true).count)
        assertTrue(without.extractItem(armour, 1, false).isEmpty, "a tether without 'equip' stripped someone's armour")
        assertTrue(without.extractItem(offhand, 1, false).isEmpty, "a tether without 'equip' took someone's shield")

        val with = GrantedInv(Grants.FULL + Grant.EQUIP, backing)
        assertFalse(with.extractItem(armour, 1, false).isEmpty, "'equip' did not open the armour slots")
        assertFalse(with.extractItem(offhand, 1, false).isEmpty, "'equip' did not open the offhand")
    }

    @Test
    fun `reading is never masked, so stacking still works for a controller allowed to insert`() {
        // insertItemStacked reads the slots it is about to fill; a handler that lied about them would
        // scatter deliveries into empty slots instead of topping stacks up.
        val give = GrantedInv(Grants.DELIVERY, inv())
        assertEquals(16, give.getStackInSlot(pack).count)
        assertEquals(Inventory.INVENTORY_SIZE + 5, give.slots)
        assertTrue(give.isItemValid(1, ItemStack(Items.TORCH)))
        assertFalse(GrantedInv(Grants.WATCH, inv()).isItemValid(1, ItemStack(Items.TORCH)))
    }
}
