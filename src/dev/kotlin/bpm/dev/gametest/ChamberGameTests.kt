package bpm.dev.gametest

import bpm.Bpm
import bpm.chamber.ChamberBuilder
import bpm.chamber.ChamberDimension
import bpm.chamber.ChamberSlot
import bpm.chamber.Chambers
import bpm.chamber.RoomLayout
import bpm.world.ContentBlocks
import bpm.world.DeviceBlocks
import bpm.world.devices.GateBlock
import bpm.world.devices.GateBlockEntity
import bpm.world.devices.PedestalBlock
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.util.UUID

/** The gate's frame check and the chamber's room, against a real server. */
@GameTestHolder(Bpm.ID)
@PrefixGameTestTemplate(false)
class ChamberGameTests {

    /** A 5 × 5 ring in the X plane with its centre at [centre]; the projector at the top centre. Answers the projector's position. */
    private fun ring(helper: GameTestHelper, centre: BlockPos, corners: Boolean): BlockPos {
        for (du in -2..2) for (dv in -2..2) {
            val edge = Math.abs(du) == 2 || Math.abs(dv) == 2
            if (!edge) continue
            val p = centre.offset(du, dv, 0)
            if (du == 0 && dv == 2) continue
            val corner = Math.abs(du) == 2 && Math.abs(dv) == 2
            helper.setBlock(p, if (corner && corners) ContentBlocks.GATE_FRAME_CORNER.get() else ContentBlocks.GATE_FRAME.get())
        }
        val projector = centre.above(2)
        // The plot may be rotated: the ring runs along the plot's X, which is world X or world Z. The oval faces
        // away from the plot's interior (plot south), and the 3 x 3 backplate of frames stands one block behind
        // the opening, into the plot.
        val intoPlot = helper.absolutePos(centre.south()).subtract(helper.absolutePos(centre))
        val facing = bpm.platform.nearestDirection(-intoPlot.x.toDouble(), 0.0, -intoPlot.z.toDouble())
        for (du in -1..1) for (dv in -1..1) helper.setBlock(centre.south().offset(du, dv, 0), ContentBlocks.GATE_FRAME.get())
        helper.setBlock(projector, DeviceBlocks.QUANTUM_GATE.get().defaultBlockState().setValue(GateBlock.FACING, facing))
        return projector
    }

    @GameTest(template = "empty7t", timeoutTicks = 200)
    fun gateFrameIsCheckedAndRechecked(helper: GameTestHelper) {
        val projector = ring(helper, BlockPos(3, 3, 1), corners = true)
        val be = helper.getBlockEntity<GateBlockEntity>(projector)
        helper.startSequence()
            .thenExecuteAfter(3) {
                be.revalidate()
                val ring = (-2..2).flatMap { du -> (-2..2).map { dv -> val q = bpm.world.devices.GateFrame.centre(be.blockPos).relative(bpm.world.devices.GateFrame.along(be.axis), du).above(dv); "($du,$dv)=" + helper.level.getBlockState(q).block.descriptionId.substringAfterLast('.') } }
                helper.assertTrue(be.frameOk, "a whole frame was not accepted: projector ${be.blockPos} (rel ${helper.relativePos(be.blockPos)}), problem ${be.problem()} (${be.problem()?.let { helper.level.getBlockState(it).block }}), axis ${be.axis}, centre rel ${helper.relativePos(bpm.world.devices.GateFrame.centre(be.blockPos))}; ring: ${ring.joinToString(" ")}")
                helper.assertTrue(!be.isOpen, "the gate opened by itself")
            }
            .thenExecute { helper.setBlock(BlockPos(1, 3, 1), Blocks.AIR) }
            .thenExecuteAfter(45) {
                helper.assertTrue(!be.frameOk, "a broken frame was still accepted")
            }
            .thenExecute { helper.setBlock(BlockPos(1, 3, 1), ContentBlocks.GATE_FRAME.get()); helper.setBlock(BlockPos(3, 3, 1), Blocks.STONE) }
            .thenExecuteAfter(45) {
                helper.assertTrue(!be.frameOk, "a blocked opening was accepted")
            }
            .thenExecute { helper.setBlock(BlockPos(3, 3, 1), Blocks.AIR) }
            .thenExecuteAfter(45) {
                helper.assertTrue(be.frameOk, "the mended frame was not accepted again")
                be.open(200)
                helper.assertTrue(be.isOpen, "open() did not open the gate")
            }
            .thenSucceed()
    }

    @GameTest(template = "empty7t", timeoutTicks = 100)
    fun theRunEndingClosesTheGate(helper: GameTestHelper) {
        val projector = ring(helper, BlockPos(3, 3, 1), corners = true)
        val be = helper.getBlockEntity<GateBlockEntity>(projector)
        helper.startSequence()
            .thenExecuteAfter(3) {
                be.revalidate()
                be.open(2000)
                helper.assertTrue(be.isOpen, "the gate did not open")
            }
            .thenExecute {
                val slot = ChamberSlot(UUID.randomUUID(), 999)
                slot.gateDim = helper.level.dimension()
                slot.gatePos = be.blockPos
                helper.assertTrue(Chambers.closeGate(helper.level.server, slot), "closeGate found no open gate")
                helper.assertTrue(!be.isOpen, "the gate stayed open")
                helper.assertTrue(!Chambers.closeGate(helper.level.server, slot), "a closed gate was closed again")
            }
            .thenSucceed()
    }

    @GameTest(template = "empty7", timeoutTicks = 60)
    fun remainsGoInAChestBesideTheReturnPoint(helper: GameTestHelper) {
        /*
         * Lay the floor rather than assume one.
         *
         * This used to take the plot's own ground as read -- "its three layers are air; the floor is under
         * y = 0" -- and that stopped being true at 1.21.2, which moved where a test structure sits relative
         * to the floor beneath it. The chest then went a block low and the test failed, reporting a
         * PRODUCT bug that was not one: `stash` prefers a cell with something solid under it, there was no
         * such cell at the return point's own level, and going down to find one is exactly what it should
         * do. The premise was wrong, not the behaviour.
         *
         * So the premise is stated here now, in the plot's own terms, and holds on any version.
         */
        for (dx in 0..6) for (dz in 0..6) helper.setBlock(BlockPos(dx, 0, dz), Blocks.STONE)
        val at = helper.absolutePos(BlockPos(3, 1, 3))
        val first = Chambers.stash(helper.level, at, null, Component.literal("test"), listOf(ItemStack(Items.DIAMOND, 5)))
        helper.assertTrue(first != null, "nothing was stashed")
        helper.assertTrue(first != at && first!!.distManhattan(at) <= 3 && first.y == at.y, "the chest went to $first, the return point being $at")
        val chest = helper.level.getBlockEntity(first) as? ChestBlockEntity
        helper.assertTrue(chest != null && chest.countItem(Items.DIAMOND) == 5, "the diamonds are not in the chest")
        val second = Chambers.stash(helper.level, at, null, null, listOf(ItemStack(Items.EMERALD, 3)))
        helper.assertTrue(second == first, "a second stash did not reuse the chest ($second vs $first)")
        helper.assertTrue(chest!!.countItem(Items.EMERALD) == 3, "the emeralds are not in the chest")
        helper.succeed()
    }

    /**
     * The room is a function of a level and an origin, so it is checked here on the test level: the game-test
     * server bakes its world from the flat preset against an empty stem registry, so `bpm:decoherence` (a data
     * pack dimension) only exists in real worlds. The builder is the same code either way.
     */
    @GameTest(template = "empty7", timeoutTicks = 100)
    fun chamberRoomIsStampedFromItsLayout(helper: GameTestHelper) {
        val chamber = helper.level.server.getLevel(ChamberDimension.KEY) ?: helper.level
        val slot = ChamberSlot(UUID.randomUUID(), 90)
        ChamberBuilder.build(chamber, slot)
        val o = slot.origin
        fun at(x: Int, y: Int, z: Int) = chamber.getBlockState(o.offset(x, y, z))
        val py = ChamberBuilder.DAIS_HEIGHT
        helper.assertTrue(at(20, py, 20).`is`(DeviceBlocks.CORE_PEDESTAL.get()) && at(20, py, 20).getValue(PedestalBlock.HAS_CORE), "no charged pedestal on the dais")
        // The dais pyramid: a stair rim on every tier with rounded corners, alloy inside, a slab skirt, a collar and corner slabs at the summit.
        val stairs = ContentBlocks.QUANTUM_ALLOY_STAIRS.get()
        val slab = ContentBlocks.QUANTUM_ALLOY_SLAB.get()
        val alloy = ContentBlocks.QUANTUM_ALLOY_BLOCK.get()
        helper.assertTrue(at(15, 1, 20).`is`(stairs) && at(16, 1, 20).`is`(alloy), "tier 1's rim and inside are wrong: ${at(15, 1, 20).block}, ${at(16, 1, 20).block}")
        // The skirt ring is slabs, except where the draw put a trap plate (the spike ring runs at the pyramid's foot).
        var skirtSlabs = 0
        var skirtOther = 0
        for (dx in -6..6) for (dz in -6..6) {
            if (Math.abs(dx) != 6 && Math.abs(dz) != 6) continue
            if (Math.hypot(dx.toDouble(), dz.toDouble()) >= 7.5) continue
            val b = at(20 + dx, 1, 20 + dz)
            if (b.`is`(slab)) skirtSlabs++ else if (b.`is`(DeviceBlocks.PHASE_SPIKE.get()) || b.`is`(DeviceBlocks.DECOHERENCE_VENT.get())) skirtOther++ else helper.fail("skirt cell (${20 + dx}, 1, ${20 + dz}) holds ${b.block}")
        }
        helper.assertTrue(skirtSlabs > 0, "no skirt slabs at all ($skirtOther plates)")
        helper.assertTrue(at(15, 1, 20).getValue(net.minecraft.world.level.block.StairBlock.FACING) == Direction.EAST, "the west rim does not face inward")
        val corner = at(15, 1, 15).getValue(net.minecraft.world.level.block.StairBlock.SHAPE)
        helper.assertTrue(corner == net.minecraft.world.level.block.state.properties.StairsShape.OUTER_LEFT || corner == net.minecraft.world.level.block.state.properties.StairsShape.OUTER_RIGHT, "the rim corner is not rounded ($corner)")
        helper.assertTrue(at(14, 1, 14).isAir, "the skirt corner hangs over the trench")
        helper.assertTrue(at(18, 4, 20).`is`(stairs) && at(20, 4, 20).`is`(alloy), "tier 4 is wrong")
        helper.assertTrue(at(20, py - 1, 20).`is`(alloy) && at(21, py - 1, 20).`is`(stairs) && at(21, py - 1, 21).`is`(slab) && at(22, py - 1, 20).isAir, "the summit is wrong")
        helper.assertTrue(at(10, 0, 10).`is`(ContentBlocks.CHAMBER_FLOOR.get()), "no floor at (10, 0, 10)")
        // The trench, checked at the headings no bridge crosses (bridges are drawn per room).
        //
        // The invariant is that the trench ring EXISTS and the bridges are what cross it — not that any
        // particular heading shows it. The room's layout is a fresh random draw every run and can put
        // something else in a given trench cell, so asserting on the first unbridged heading made this
        // test fail roughly one run in seven with "no trench at (29, 0, 20), heading 0.0, bridges [270]".
        // Ask whether any unbridged heading shows the trench instead; that is the property being tested.
        val bridges = slot.layout!!.bridges
        val clear = (0 until 8).map { it * 45.0 }.filter { h -> bridges.none { RoomLayout.angleDiff(h, it.toDouble()) <= 16.0 } }
        helper.assertTrue(clear.isNotEmpty(), "every heading is bridged, so the trench cannot be checked: $bridges")
        val trench = clear.any { heading ->
            val tx = 20 + Math.round(8.5 * Math.cos(Math.toRadians(heading))).toInt()
            val tz = 20 + Math.round(8.5 * Math.sin(Math.toRadians(heading))).toInt()
            at(tx, 0, tz).isAir && at(tx, -1, tz).isAir && at(tx, -2, tz).`is`(ContentBlocks.CHAMBER_WALL.get())
        }
        helper.assertTrue(trench, "no trench at any unbridged heading $clear, bridges $bridges")
        val bx = 20 + Math.round(8.5 * Math.cos(Math.toRadians(270.0))).toInt()
        val bz = 20 + Math.round(8.5 * Math.sin(Math.toRadians(270.0))).toInt()
        helper.assertTrue(at(bx, 0, bz).`is`(DeviceBlocks.PHASE_BLOCK.get()), "no bridge on the gate side at ($bx, 0, $bz)")
        helper.assertTrue(at(20, 0, 11).`is`(DeviceBlocks.PHASE_BLOCK.get()), "no bridge at (20, 0, 11)")
        val wallish = setOf(ContentBlocks.CHAMBER_WALL.get(), ContentBlocks.CHAMBER_WALL_PANEL.get(), ContentBlocks.CHAMBER_WALL_VENT.get())
        helper.assertTrue(at(0, 5, 21).block in wallish && at(0, 7, 21).`is`(ContentBlocks.CHAMBER_WALL_CONDUIT.get()) && at(0, 1, 21).`is`(ContentBlocks.CHAMBER_WALL_TRIM.get()) && at(0, 5, 2).`is`(ContentBlocks.CHAMBER_PILLAR.get()), "walls, trim, conduit and pillars missing")
        helper.assertTrue(at(0, 13, 2).`is`(ContentBlocks.CHAMBER_LIGHT.get()) && at(0, 14, 20).`is`(ContentBlocks.CONTAINMENT_BARRIER.get()), "pillar caps and the barrier band missing")
        helper.assertTrue(at(20, 16, 20).`is`(ContentBlocks.QUANTUM_ALLOY_BLOCK.get()), "no ceiling")
        var spikes = 0
        var vents = 0
        var consoles = 0
        // The traps are plates that sit on the floor cells the layout names (`ChamberBuilder.trapPos`).
        for (x in 0..40) for (z in 0..40) {
            val s = at(x, 1, z)
            if (s.`is`(DeviceBlocks.PHASE_SPIKE.get())) spikes++
            if (s.`is`(DeviceBlocks.DECOHERENCE_VENT.get())) vents++
            if (at(x, 0, z).`is`(ContentBlocks.CHAMBER_LIGHT.get())) consoles++
        }
        val diag = slot.layout!!.spikes.take(2).joinToString { "$it=" + at(it.x, it.y, it.z).block.descriptionId.substringAfterLast('.') } + "; " + slot.layout!!.vents.take(2).joinToString { "$it=" + at(it.x, it.y, it.z).block.descriptionId.substringAfterLast('.') }
        helper.assertTrue(spikes in 6..10 && vents == 4, "$spikes spikes and $vents vents, wanted 6–10 and 4 ($diag)")
        val layout = slot.layout
        helper.assertTrue(layout != null, "the slot has no layout after the build")
        helper.assertTrue(consoles == layout!!.consoles.size + layout.cover.indices.count { it % 3 == 0 } * 0 && consoles >= 4, "$consoles lit tiles on the floor, wanted at least the 4 consoles")
        var turrets = 0
        for ((p, _) in layout.turrets) if (at(p.x, p.y, p.z).`is`(DeviceBlocks.OBSERVER_TURRET.get()) && at(p.x, p.y - 1, p.z).`is`(ContentBlocks.CHAMBER_PILLAR.get())) turrets++
        helper.assertTrue(turrets == 4, "$turrets turrets standing on pillars, wanted 4")
        var crystals = 0
        for (p in layout.crystals) if (at(p.x, p.y, p.z).`is`(ContentBlocks.ENTANGLIUM_BLOCK.get())) crystals++
        helper.assertTrue(crystals == 4, "$crystals crystals standing, wanted 4")
        helper.assertTrue(layout.cover.size in 6..10 && layout.cover.all { (p, h) -> at(p.x, h, p.z).`is`(ContentBlocks.CHAMBER_PILLAR.get()) }, "cover columns missing")
        // A different draw for the next reset of the same room.
        val again = ChamberSlot(slot.owner, slot.index).also { it.resetCount = slot.resetCount + 1 }
        helper.assertTrue(RoomLayout.generate(again.seed).spikes != layout.spikes || RoomLayout.generate(again.seed).cover != layout.cover, "two resets drew the same room")
        val gate = chamber.getBlockEntity(o.offset(20, 4, 0)) as? GateBlockEntity
        helper.assertTrue(gate != null && gate.returnGate && gate.isOpen && gate.frameOk, "the return gate is not an open, whole return gate: ${gate?.describe()}")
        helper.assertTrue(at(20, 1, 0).isAir && at(20, 2, 0).isAir, "the way out is blocked")
        helper.succeed()
    }
}
