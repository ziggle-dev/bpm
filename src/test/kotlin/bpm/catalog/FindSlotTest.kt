package bpm.catalog

import bpm.catalog.values.FilterValue
import bpm.nodes.ControllerHost
import bpm.nodes.DetachedHost
import bpm.runtime.TickJobs
import bpm.world.LinkTable
import bpm.world.ResolvedLink
import dev.ziggle.vscript.log.LogLevel
import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.model.Graph
import dev.ziggle.vscript.model.GraphDoc
import dev.ziggle.vscript.model.GraphVariable
import dev.ziggle.vscript.model.Link
import dev.ziggle.vscript.model.Node
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.nodes.BuiltinHosts
import dev.ziggle.vscript.runtime.EditorDoc
import dev.ziggle.vscript.runtime.ScriptRuntime
import dev.ziggle.vscript.vm.StructValue
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.RegistryAccess
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import bpm.platform.ports.EnergyPort
import net.neoforged.neoforge.fluids.capability.IFluidHandler
import net.neoforged.neoforge.items.IItemHandler
import net.neoforged.neoforge.items.ItemStackHandler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `items.find` over a composed filter, run through the VM from JSON exactly as a deployed graph is: the
 * slot of the first undamaged iron pickaxe in a buffer that also holds stone and a worn pickaxe.
 */
class FindSlotTest {

    /** A host with nothing but an inventory; every other member is the client's throwing one. */
    private class InventoryHost(private val inv: IItemHandler) : ControllerHost {
        override val level: ServerLevel get() = DetachedHost.level
        override val pos: BlockPos get() = BlockPos.ZERO
        override val links: LinkTable get() = DetachedHost.links
        override val jobs: TickJobs get() = DetachedHost.jobs
        override val tickCount: Long get() = 0
        override val registries: RegistryAccess get() = RegistryAccess.EMPTY
        override fun link(name: String): ResolvedLink? = null
        override fun items(name: String): IItemHandler? = if (name == ControllerHost.SELF) inv else null
        override fun fluids(name: String): IFluidHandler? = null
        override fun energy(name: String): EnergyPort? = null
        override val selfInventory: IItemHandler get() = inv
        override val selfTanks: IFluidHandler get() = DetachedHost.selfTanks
        override val selfEnergy: EnergyPort get() = DetachedHost.selfEnergy
        override fun entity(handle: Any?): Entity? = null
        override fun emitSignal(side: Direction, strength: Int) {}
        override fun emitted(side: Direction): Int = 0
        override fun requestSleep(reason: String) {}
        override fun log(level: LogLevel, message: String) {}
        override fun notify(level: String, message: String) {}
        override fun transferred(from: String, to: String, amount: Int, kind: bpm.net.EffectKind, item: String) {}
        override fun effect(id: ResourceLocation, data: CompoundTag) {}
    }

    @Test
    fun `find answers the slot of the first undamaged pickaxe`() {
        val inv = ItemStackHandler(9)
        inv.setStackInSlot(0, ItemStack(Items.STONE, 4))
        inv.setStackInSlot(1, ItemStack(Items.IRON_PICKAXE).also { it.damageValue = 5 })
        inv.setStackInSlot(2, ItemStack(Items.IRON_PICKAXE))
        val library = BpmCatalog.library(InventoryHost(inv))
        val hosts = library.install(BuiltinHosts.registry(), McValueOut)
        val runtime = ScriptRuntime(BpmCatalog.catalog, hosts)

        // on start: slot = items.find(self, allOf(filter(item = iron_pickaxe), not(filter(damaged = true)))).Slot
        val graph = Graph(
            "g", "g",
            nodes = listOf(
                Node(1, BuiltinNodes.ENTRY, 40f, 40f),
                Node(2, "items.filter", 40f, 200f, literals = linkedMapOf("item" to "minecraft:iron_pickaxe")),
                Node(3, "items.filter", 40f, 300f, literals = linkedMapOf("damaged" to true)),
                Node(4, "items.not", 300f, 300f),
                Node(5, "items.allOf", 560f, 250f),
                Node(6, "items.find", 820f, 250f, literals = linkedMapOf("Link" to "self")),
                Node(7, BuiltinNodes.VAR_SET, 1080f, 40f).also { it.variable = "slot" },
                Node(8, BuiltinNodes.VAR_SET, 1340f, 40f).also { it.variable = "f" },
            ),
            links = listOf(
                Link(1, 1, "Exec", 7, "Exec"),
                Link(2, 7, "Exec", 8, "Exec"),
                Link(3, 3, "Filter", 4, "Of"),
                Link(4, 2, "Filter", 5, "A"),
                Link(5, 4, "Filter", 5, "B"),
                Link(6, 5, "Filter", 6, "Filter"),
                Link(7, 6, "Slot", 7, "Value"),
                Link(8, 5, "Filter", 8, "Value"),
            ),
            variables = listOf(
                GraphVariable("slot", TypeRef(dev.ziggle.vscript.model.PinType.INT), -1L),
                GraphVariable("f", TypeRef.named(FilterValue.TYPE).orNull(), null),
            ),
        )
        // Through JSON, the way a deployed document reaches a controller.
        val loaded = GraphDoc.fromJson(GraphDoc.toJson(graph))
        val issues = runtime.validate(EditorDoc(loaded))
        assertTrue(issues.none { it.severity == dev.ziggle.vscript.compile.Severity.ERROR }, "issues: ${issues.map { it.message }}")
        assertNull(runtime.run(EditorDoc(loaded), debug = true))
        repeat(5) { runtime.tick() }

        val f = runtime.variable("f")
        assertNotNull(f, "the composed filter never arrived; error=${runtime.lastError} log=${runtime.log.records.map { it.message }}")
        assertTrue(f is StructValue && f.type == FilterValue.TYPE, "not a Filter: $f")
        val m = FilterValue.matcher(f, null)
        assertTrue(m.matches(ItemStack(Items.IRON_PICKAXE)), "the composed filter refused a fresh pickaxe: ${describe(f)}")
        assertTrue(!m.matches(ItemStack(Items.IRON_PICKAXE).also { it.damageValue = 5 }), "the composed filter admitted a worn pickaxe: ${describe(f)}")

        assertEquals(2L, runtime.variable("slot"), "find answered the wrong slot; filter=${describe(f)} error=${runtime.lastError}")
        runtime.stop()
    }

    private fun describe(v: Any?): String {
        if (v is StructValue) {
            val parts = ArrayList<String>()
            for (name in v.names) parts.add(name + "=" + describe(v.get(name)))
            return v.type + parts.joinToString(prefix = "{", postfix = "}")
        }
        if (v is List<*>) return v.joinToString(prefix = "[", postfix = "]") { describe(it) }
        return v.toString()
    }
}
