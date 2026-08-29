package bpm.catalog

import bpm.catalog.values.FilterValue
import bpm.catalog.values.ItemStackValue
import bpm.nodes.Transfer
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.neoforged.neoforge.items.ItemStackHandler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Filters against real stacks. Needs the game's registries bootstrapped, which the NeoForge unit-test
 * launch does before any test runs.
 */
class FilterValueTest {

    @Test
    fun `filters compose with any, all and not`() {
        val pick = FilterValue.of(item = "minecraft:iron_pickaxe")
        val axe = FilterValue.of(item = "minecraft:iron_axe")
        val damaged = FilterValue.of(damaged = true)
        val worn = ItemStack(Items.IRON_PICKAXE).also { it.damageValue = 5 }

        val either = FilterValue.matcher(FilterValue.anyOf(listOf(pick, axe)), null)
        assertTrue(either.matches(ItemStack(Items.IRON_PICKAXE)))
        assertTrue(either.matches(ItemStack(Items.IRON_AXE)))
        assertFalse(either.matches(ItemStack(Items.COAL)))

        val fresh = FilterValue.matcher(FilterValue.allOf(listOf(pick, FilterValue.not(damaged))), null)
        assertTrue(fresh.matches(ItemStack(Items.IRON_PICKAXE)))
        assertFalse(fresh.matches(worn), "a worn pickaxe passed allOf(pickaxe, not damaged)")
        assertFalse(fresh.matches(ItemStack(Items.IRON_AXE)))

        val notCoal = FilterValue.matcher(FilterValue.not(FilterValue.of(item = "minecraft:coal")), null)
        assertFalse(notCoal.matches(ItemStack(Items.COAL)))
        assertTrue(notCoal.matches(ItemStack(Items.IRON_AXE)))

        // Nested: (pickaxe or axe) and not damaged.
        val nested = FilterValue.matcher(FilterValue.allOf(listOf(FilterValue.anyOf(listOf(pick, axe)), FilterValue.not(damaged))), null)
        assertTrue(nested.matches(ItemStack(Items.IRON_AXE)))
        assertFalse(nested.matches(worn))
        assertFalse(nested.matches(ItemStack(Items.COAL)))
    }

    @Test
    fun `an empty filter matches anything that is not empty`() {
        val m = FilterValue.matcher(null, null)
        assertTrue(m.matches(ItemStack(Items.COAL)))
        assertFalse(m.matches(ItemStack.EMPTY))
    }

    @Test
    fun `item, tag, name and damaged narrow`() {
        val coal = ItemStack(Items.COAL, 5)
        val pick = ItemStack(Items.IRON_PICKAXE).also { it.damageValue = 10 }
        val named = ItemStack(Items.STONE).also { it.set(DataComponents.CUSTOM_NAME, Component.literal("Left Wall")) }

        assertTrue(FilterValue.matcher(FilterValue.of(item = "minecraft:coal"), null).matches(coal))
        assertFalse(FilterValue.matcher(FilterValue.of(item = "minecraft:coal"), null).matches(pick))
        // Tags are bound when a server loads its data packs, which a unit test never does; a tag filter is
        // therefore only checked for the negative here and for real in the game tests.
        assertFalse(FilterValue.matcher(FilterValue.of(tag = "minecraft:no_such_tag"), null).matches(coal))
        assertFalse(FilterValue.matcher(FilterValue.of(tag = "not a tag id at all!!"), null).matches(coal))
        assertTrue(FilterValue.matcher(FilterValue.of(name = "left"), null).matches(named))
        assertFalse(FilterValue.matcher(FilterValue.of(name = "left"), null).matches(coal))
        assertTrue(FilterValue.matcher(FilterValue.of(damaged = true), null).matches(pick))
        assertFalse(FilterValue.matcher(FilterValue.of(damaged = true), null).matches(coal))
        assertFalse(FilterValue.matcher(FilterValue.of(item = "not an id at all"), null).matches(coal), "an unknown item matches nothing")
    }

    @Test
    fun `a component filter and a vanilla predicate both read the stack's data`() {
        val named = ItemStack(Items.STONE).also { it.set(DataComponents.CUSTOM_NAME, Component.literal("Left Wall")) }
        assertTrue(FilterValue.matcher(FilterValue.of(component = "minecraft:custom_name"), null).matches(named))
        assertFalse(FilterValue.matcher(FilterValue.of(component = "minecraft:custom_name"), null).matches(ItemStack(Items.STONE)))

        // A predicate resolves registry entries, so it is compiled against a registry access — the static
        // registries are enough for `items`.
        val registries = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY)
        val json = """{"items": "minecraft:stone", "count": {"min": 1}}"""
        assertTrue(FilterValue.matcher(FilterValue.of(predicate = json), registries).matches(named))
        assertFalse(FilterValue.matcher(FilterValue.of(predicate = json), registries).matches(ItemStack(Items.COAL)))
        assertFalse(FilterValue.matcher(FilterValue.of(predicate = "{not json"), registries).matches(named), "a bad predicate matches nothing")
    }

    @Test
    fun `an item stack round-trips through its record, components included`() {
        val named = ItemStack(Items.STONE, 3).also { it.set(DataComponents.CUSTOM_NAME, Component.literal("Left Wall")) }
        val record = ItemStackValue.record(named)!!
        assertEquals("minecraft:stone", record.get("item"))
        assertEquals(3, record.get("count"))
        val back = ItemStackValue.stack(record)
        assertEquals(3, back.count)
        assertEquals("Left Wall", back.hoverName.string)
        assertNull(ItemStackValue.record(ItemStack.EMPTY))
    }

    @Test
    fun `move takes what fits and answers how many`() {
        val from = ItemStackHandler(2).also { it.setStackInSlot(0, ItemStack(Items.COAL, 20)); it.setStackInSlot(1, ItemStack(Items.STONE, 4)) }
        val to = ItemStackHandler(1)
        val moved = Transfer.items(from, to, FilterValue.matcher(FilterValue.of(item = "minecraft:coal"), null), 12)
        assertEquals(12, moved)
        assertEquals(8, from.getStackInSlot(0).count)
        assertEquals(12, to.getStackInSlot(0).count)
        // The one slot is coal now, so stone cannot follow.
        assertEquals(0, Transfer.items(from, to, FilterValue.matcher(FilterValue.of(item = "minecraft:stone"), null), 64))
    }
}
