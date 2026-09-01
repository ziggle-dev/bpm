package bpm.platform

import bpm.platform.ports.ContainerPort
import bpm.platform.ports.Droplets
import bpm.platform.ports.FluidVolume
import bpm.platform.ports.HandlerFluidPort
import bpm.platform.ports.HandlerPort
import bpm.platform.ports.PortFluidHandler
import bpm.platform.ports.PortHandler
import bpm.platform.ports.insertStacked
import bpm.platform.ports.toStack
import bpm.platform.ports.toVolume
import net.minecraft.world.SimpleContainer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.material.Fluids
import net.neoforged.neoforge.fluids.FluidStack
import net.neoforged.neoforge.fluids.capability.templates.FluidTank
import net.neoforged.neoforge.items.IItemHandler
import net.neoforged.neoforge.items.ItemStackHandler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The port layer, which is the seam every `bpm.nodes` verb will be retyped onto.
 *
 * The arithmetic tests matter more than they look. Droplets exist because millibuckets do not survive
 * Fabric's unit — a third of a bucket is 333.33 mB — and the whole design rests on 81 droplets being
 * exactly one millibucket. If that ever stops holding, fluid starts leaking a fraction at a time
 * through every `fluids.move`, which is the kind of bug nobody reports as a bug.
 */
class PortsTest {

    // ------------------------------------------------------------ units

    @Test
    fun `a millibucket is exactly eighty-one droplets and a bucket a thousand of those`() {
        assertEquals(81L, Droplets.PER_MB)
        assertEquals(81_000L, Droplets.PER_BUCKET)
        assertEquals(Droplets.PER_BUCKET, Droplets.ofMb(1000))
    }

    @Test
    fun `millibuckets round-trip through droplets without loss`() {
        for (mb in intArrayOf(0, 1, 7, 250, 999, 1000, 16_000, 100_000)) {
            assertEquals(mb, Droplets.toMb(Droplets.ofMb(mb)), "mB -> droplets -> mB lost $mb")
        }
    }

    @Test
    fun `a third of a bucket floors to the millibucket below rather than rounding up`() {
        // 27,000 droplets is 333.33 mB. Reporting 334 would be claiming fluid we do not have.
        val third = Droplets.PER_BUCKET / 3
        assertEquals(27_000L, third)
        assertEquals(333, Droplets.toMb(third))
    }

    @Test
    fun `a volume smaller than one millibucket reads as zero but is not empty`() {
        val dribble = FluidVolume(Fluids.WATER, 40L)
        assertEquals(0, dribble.mb)
        assertFalse(dribble.isEmpty, "40 droplets is real fluid even though it rounds to 0 mB")
    }

    @Test
    fun `emptiness follows the amount and the fluid`() {
        assertTrue(FluidVolume.EMPTY.isEmpty)
        assertTrue(FluidVolume(Fluids.WATER, 0L).isEmpty)
        assertTrue(FluidVolume(Fluids.EMPTY, 500L).isEmpty)
        assertFalse(FluidVolume.bucket(Fluids.WATER).isEmpty)
    }

    @Test
    fun `two volumes of the same fluid are the same kind whatever their amounts`() {
        val a = FluidVolume.ofMb(Fluids.WATER, 10)
        val b = FluidVolume.ofMb(Fluids.WATER, 4000)
        assertTrue(a.sameKindAs(b))
        assertFalse(a.sameKindAs(FluidVolume.ofMb(Fluids.LAVA, 10)))
    }

    // ------------------------------------------------------------ item adapters

    @Test
    fun `a NeoForge handler wrapped as a port reports and moves the same items`() {
        val handler = ItemStackHandler(3)
        handler.setStackInSlot(0, ItemStack(Items.COBBLESTONE, 16))
        val port = HandlerPort(handler)

        assertEquals(3, port.slots)
        assertEquals(16, port.stackIn(0).count)

        val leftover = port.insert(0, ItemStack(Items.COBBLESTONE, 8), simulate = false)
        assertTrue(leftover.isEmpty)
        assertEquals(24, handler.getStackInSlot(0).count)

        val taken = port.extract(0, 10, simulate = false)
        assertEquals(10, taken.count)
        assertEquals(14, handler.getStackInSlot(0).count)
    }

    @Test
    fun `simulating never changes the store`() {
        val handler = ItemStackHandler(2)
        handler.setStackInSlot(0, ItemStack(Items.COBBLESTONE, 5))
        val port = HandlerPort(handler)

        port.insert(1, ItemStack(Items.DIRT, 4), simulate = true)
        port.extract(0, 3, simulate = true)

        assertEquals(5, handler.getStackInSlot(0).count)
        assertTrue(handler.getStackInSlot(1).isEmpty)
    }

    @Test
    fun `a port wrapped back as a handler survives the round trip`() {
        val handler = ItemStackHandler(2)
        handler.setStackInSlot(1, ItemStack(Items.DIAMOND, 2))
        val round = PortHandler(HandlerPort(handler))

        assertEquals(2, round.slots)
        assertEquals(Items.DIAMOND, round.getStackInSlot(1).item)
        assertTrue(round.insertItem(0, ItemStack(Items.DIRT, 1), false).isEmpty)
        assertEquals(Items.DIRT, handler.getStackInSlot(0).item)
    }

    @Test
    fun `a handler that cannot be written wholesale says so instead of swallowing the stack`() {
        // The bug this replaces: WorldJobs cast to IItemHandlerModifiable and did nothing when the cast
        // failed, so a stack handed to a read-only handler vanished silently.
        val readOnly = object : IItemHandler {
            override fun getSlots() = 1
            override fun getStackInSlot(slot: Int): ItemStack = ItemStack.EMPTY
            override fun insertItem(slot: Int, stack: ItemStack, simulate: Boolean): ItemStack = stack
            override fun extractItem(slot: Int, amount: Int, simulate: Boolean): ItemStack = ItemStack.EMPTY
            override fun getSlotLimit(slot: Int) = 64
            override fun isItemValid(slot: Int, stack: ItemStack) = true
        }
        assertFalse(HandlerPort(readOnly).setStackIn(0, ItemStack(Items.DIRT, 1)))
        assertTrue(HandlerPort(ItemStackHandler(1)).setStackIn(0, ItemStack(Items.DIRT, 1)))
    }

    // ------------------------------------------------------------ insertStacked

    @Test
    fun `insertStacked tops up a partial stack before it opens any empty slot`() {
        val handler = ItemStackHandler(3)
        handler.setStackInSlot(1, ItemStack(Items.COBBLESTONE, 60))
        val port = HandlerPort(handler)

        val left = port.insertStacked(ItemStack(Items.COBBLESTONE, 8), simulate = false)

        // The ordering is the point, and it is two passes rather than one: every partial stack is
        // topped up first, wherever it sits, and only then does the leftover take the lowest empty
        // slot. So slot 1 fills before slot 0 is touched, even though slot 0 comes first.
        assertTrue(left.isEmpty, "8 cobble fits: 4 onto the partial stack, 4 into an empty slot")
        assertEquals(64, handler.getStackInSlot(1).count, "the partial stack is filled first")
        assertEquals(4, handler.getStackInSlot(0).count, "the remainder takes the lowest empty slot")
        assertTrue(handler.getStackInSlot(2).isEmpty, "and stops as soon as it has somewhere to go")
    }

    @Test
    fun `insertStacked hands back what did not fit`() {
        val handler = ItemStackHandler(1)
        handler.setStackInSlot(0, ItemStack(Items.COBBLESTONE, 60))
        val left = HandlerPort(handler).insertStacked(ItemStack(Items.COBBLESTONE, 10), simulate = false)
        assertEquals(6, left.count)
    }

    // ------------------------------------------------------------ ContainerPort

    @Test
    fun `a vanilla container is an item port with no seam at all`() {
        val container = SimpleContainer(4)
        container.setItem(0, ItemStack(Items.COBBLESTONE, 10))
        val port = ContainerPort(container)

        assertEquals(4, port.slots)
        assertEquals(10, port.stackIn(0).count)
        assertTrue(port.insert(0, ItemStack(Items.COBBLESTONE, 5), simulate = false).isEmpty)
        assertEquals(15, container.getItem(0).count)
        assertEquals(5, port.extract(0, 5, simulate = false).count)
        assertEquals(10, container.getItem(0).count)
        assertTrue(port.setStackIn(3, ItemStack(Items.DIRT, 1)))
        assertEquals(Items.DIRT, container.getItem(3).item)
    }

    @Test
    fun `a container port refuses to merge unlike items into one slot`() {
        val container = SimpleContainer(1)
        container.setItem(0, ItemStack(Items.COBBLESTONE, 1))
        val left = ContainerPort(container).insert(0, ItemStack(Items.DIRT, 1), simulate = false)
        assertEquals(1, left.count, "dirt does not stack onto cobblestone")
        assertEquals(Items.COBBLESTONE, container.getItem(0).item)
    }

    // ------------------------------------------------------------ fluid adapters

    @Test
    fun `a NeoForge tank wrapped as a port reports droplets and fills in millibuckets`() {
        val tank = FluidTank(16_000)
        val port = HandlerFluidPort(tank)

        assertEquals(1, port.tanks)
        assertEquals(Droplets.ofMb(16_000), port.tankCapacity(0))

        val filled = port.fill(FluidVolume.ofMb(Fluids.WATER, 1000), simulate = false)
        assertEquals(Droplets.ofMb(1000), filled)
        assertEquals(1000, tank.fluidAmount)
        assertEquals(1000, port.inTank(0).mb)
    }

    @Test
    fun `draining by amount comes back as the fluid that was held`() {
        val tank = FluidTank(16_000)
        tank.fill(FluidStack(Fluids.WATER, 500), net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE)
        val port = HandlerFluidPort(tank)

        val out = port.drain(Droplets.ofMb(200), simulate = false)
        assertEquals(Fluids.WATER, out.fluid)
        assertEquals(200, out.mb)
        assertEquals(300, tank.fluidAmount)
    }

    @Test
    fun `a fluid port wrapped back as a handler survives the round trip`() {
        val tank = FluidTank(16_000)
        val round = PortFluidHandler(HandlerFluidPort(tank))

        assertEquals(16_000, round.getTankCapacity(0))
        val moved = round.fill(FluidStack(Fluids.LAVA, 250), net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE)
        assertEquals(250, moved)
        assertEquals(Fluids.LAVA, round.getFluidInTank(0).fluid)
        assertEquals(250, round.getFluidInTank(0).amount)
    }

    @Test
    fun `FluidStack and FluidVolume convert both ways without drift`() {
        val stack = FluidStack(Fluids.WATER, 1234)
        val volume = stack.toVolume()
        assertEquals(Droplets.ofMb(1234), volume.droplets)
        assertEquals(1234, volume.toStack().amount)
        assertEquals(Fluids.WATER, volume.toStack().fluid)
        assertTrue(FluidStack.EMPTY.toVolume().isEmpty)
        assertTrue(FluidVolume.EMPTY.toStack().isEmpty)
    }
}
