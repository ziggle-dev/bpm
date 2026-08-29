package bpm.dev.gametest

import bpm.Bpm
import bpm.dev.SampleDocs
import bpm.library.BpmLibrary
import bpm.runtime.RuntimeManager
import bpm.world.ControllerBlockEntity
import bpm.world.Link
import bpm.world.ModBlocks
import io.osrsx.vscript.model.Graph
import io.osrsx.vscript.model.GraphDoc
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import bpm.world.ControllerStores
import bpm.world.ModFluids
import net.minecraft.world.entity.ExperienceOrb
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.material.Fluids
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.fluids.FluidStack
import net.neoforged.neoforge.fluids.capability.IFluidHandler
import net.minecraft.world.level.block.RedstoneLampBlock
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate

/**
 * `gradlew runGameTestServer`: the phase-3 scenarios against a real server, one empty 7×3×7 plot each.
 *
 * Each test writes a sample document into the world's library, places a controller with links to blocks
 * on the plot, binds it and waits for the world to show the program ran.
 */
@GameTestHolder(Bpm.ID)
@Suppress("unused")
@PrefixGameTestTemplate(false)
class ControllerGameTests {

    private fun deploy(helper: GameTestHelper, graph: Graph, at: BlockPos, vararg links: Pair<String, Pair<BlockPos, Direction?>>): ControllerBlockEntity {
        val lib = BpmLibrary.get(helper.level.server)
        val json = GraphDoc.toJson(graph)
        val record = lib.byName(graph.name)?.let { lib.store(it.id, json)!! } ?: lib.create(graph.name, json, null)
        helper.setBlock(at, ModBlocks.CONTROLLER.get())
        val be = helper.getBlockEntity<ControllerBlockEntity>(at)
        for ((name, target) in links) be.links.add(Link(name, helper.absolutePos(target.first), target.second, helper.level.dimension()))
        be.bind(record.id)
        return be
    }

    private fun container(helper: GameTestHelper, at: BlockPos): BaseContainerBlockEntity = helper.getBlockEntity(at)

    private fun count(helper: GameTestHelper, at: BlockPos): Int {
        val c = container(helper, at)
        var n = 0
        for (i in 0 until c.containerSize) n += c.getItem(i).count
        return n
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    fun chestToHopper(helper: GameTestHelper) {
        val chest = BlockPos(1, 1, 1)
        val hopper = BlockPos(3, 1, 1)
        helper.setBlock(chest, Blocks.CHEST)
        helper.setBlock(hopper, Blocks.HOPPER)
        container(helper, chest).setItem(0, ItemStack(Items.COAL, 40))
        container(helper, chest).setItem(1, ItemStack(Items.IRON_INGOT, 10))
        val be = deploy(helper, SampleDocs.move(), BlockPos(5, 1, 1), "chest" to (chest to null), "hopper" to (hopper to Direction.UP))
        helper.succeedWhen {
            helper.assertTrue(be.lastError == null, "controller error: ${be.lastError}")
            helper.assertTrue(count(helper, chest) == 0, "chest still holds ${count(helper, chest)}")
            helper.assertTrue(count(helper, hopper) == 50, "hopper holds ${count(helper, hopper)}")
        }
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    fun redstoneFollowsTheChest(helper: GameTestHelper) {
        val controller = BlockPos(1, 1, 1)
        val lamp = BlockPos(1, 2, 1)
        val chest = BlockPos(3, 1, 1)
        helper.setBlock(chest, Blocks.CHEST)
        container(helper, chest).setItem(0, ItemStack(Items.STONE, 3))
        val be = deploy(helper, SampleDocs.redstone(), controller, "chest" to (chest to null))
        helper.setBlock(lamp, Blocks.REDSTONE_LAMP)
        helper.startSequence()
            .thenWaitUntil {
                helper.assertTrue(be.lastError == null, "controller error: ${be.lastError}")
                helper.assertBlockProperty(lamp, RedstoneLampBlock.LIT, true)
            }
            .thenExecute { container(helper, chest).setItem(0, ItemStack.EMPTY) }
            .thenWaitUntil { helper.assertBlockProperty(lamp, RedstoneLampBlock.LIT, false) }
            .thenSucceed()
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    fun mineIntoChest(helper: GameTestHelper) {
        val controller = BlockPos(1, 1, 1)
        val stone = BlockPos(1, 1, 3)
        val chest = BlockPos(3, 1, 1)
        helper.setBlock(stone, Blocks.STONE)
        helper.setBlock(chest, Blocks.CHEST)
        val be = deploy(helper, SampleDocs.mine(), controller, "stone" to (stone to null), "chest" to (chest to null))
        val pick = ItemStack(Items.IRON_PICKAXE)
        be.inventory.setStackInSlot(0, pick)
        helper.succeedWhen {
            helper.assertTrue(be.lastError == null, "controller error: ${be.lastError}")
            helper.assertBlock(stone, { it == Blocks.AIR }, "stone not broken")
            val c = container(helper, chest)
            helper.assertTrue(c.getItem(0).`is`(Items.COBBLESTONE), "no cobblestone in the chest: ${be.describe()}")
            val tool = be.inventory.getStackInSlot(0)
            helper.assertTrue(tool.`is`(Items.IRON_PICKAXE) && tool.damageValue == 1, "the pickaxe lost ${tool.damageValue} durability, wanted 1")
        }
    }

    private fun around(helper: GameTestHelper, at: BlockPos, r: Double): AABB = AABB(helper.absolutePos(at)).inflate(r)

    /** The slot-walking dump: the pickaxe's slot is read off each `Slot` record and skipped; everything else goes. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    fun dumpKeepsThePickaxe(helper: GameTestHelper) {
        val controller = BlockPos(1, 1, 1)
        val dump = BlockPos(3, 1, 1)
        val toggle = BlockPos(1, 1, 3)
        helper.setBlock(dump, Blocks.CHEST)
        helper.setBlock(toggle, Blocks.STONE)
        helper.setBlock(toggle.below(), Blocks.REDSTONE_BLOCK) // powers the toggle block, so the wait passes at once
        val be = deploy(helper, SampleDocs.dumpButTool(), controller, "dump" to (dump to null), "dump toggle" to (toggle to null))
        be.inventory.setStackInSlot(0, ItemStack(Items.IRON_PICKAXE))
        be.inventory.setStackInSlot(1, ItemStack(Items.COBBLESTONE, 20))
        be.inventory.setStackInSlot(2, ItemStack(Items.IRON_INGOT, 5))
        helper.succeedWhen {
            helper.assertTrue(be.lastError == null, "controller error: ${be.lastError}")
            helper.assertTrue(count(helper, dump) == 25, "the dump holds ${count(helper, dump)}, wanted the 25 non-tool items")
            helper.assertTrue(be.inventory.getStackInSlot(0).`is`(Items.IRON_PICKAXE), "the pickaxe was dumped too")
            helper.assertTrue((1 until be.inventory.slots).all { be.inventory.getStackInSlot(it).isEmpty }, "something besides the tool stayed behind")
        }
    }

    /** `on tick → emit(Up, Count In Area(pigs))`: two pigs in the box, one outside — the top face reads 2. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    fun countInAreaSeesWhatStandsInTheBox(helper: GameTestHelper) {
        val a = helper.absolutePos(BlockPos(3, 0, 3))
        val b = helper.absolutePos(BlockPos(5, 2, 5))
        helper.spawn(net.minecraft.world.entity.EntityType.PIG, Vec3(3.5, 0.0, 3.5)).setNoAi(true) // inside
        helper.spawn(net.minecraft.world.entity.EntityType.PIG, Vec3(5.5, 0.0, 5.5)).setNoAi(true) // inside, on the far corner
        helper.spawn(net.minecraft.world.entity.EntityType.PIG, Vec3(1.5, 0.0, 5.5)).setNoAi(true) // outside
        val graph = Graph(
            java.util.UUID.nameUUIDFromBytes("bpm-test-count-in".toByteArray()).toString(), "count-in",
            listOf(
                io.osrsx.vscript.model.Node(1, io.osrsx.vscript.model.BuiltinNodes.ENTRY_TICK),
                io.osrsx.vscript.model.Node(2, "world.countIn", literals = linkedMapOf("From" to "${a.x},${a.y},${a.z}", "To" to "${b.x},${b.y},${b.z}", "Type" to "minecraft:pig")),
                io.osrsx.vscript.model.Node(3, "redstone.emit", literals = linkedMapOf("Side" to "Up")),
            ),
            listOf(
                io.osrsx.vscript.model.Link(1, 1, "Exec", 3, "Exec"),
                io.osrsx.vscript.model.Link(2, 2, "Count", 3, "Level"),
            ),
        )
        val be = deploy(helper, graph, BlockPos(1, 1, 1))
        helper.succeedWhen {
            helper.assertTrue(be.lastError == null, "controller error: ${be.lastError}")
            helper.assertTrue(be.signal(Direction.UP) == 2, "Count In Area answered ${be.signal(Direction.UP)}, wanted 2")
        }
    }

    /** `on tick → emitAt(node, 15)`: the linked Signal Emitter lights the lamp beside it, three blocks from the controller. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    fun emitAtLinkPowersTheLampBesideTheEmitter(helper: GameTestHelper) {
        val emitter = BlockPos(3, 1, 3)
        val lamp = BlockPos(3, 1, 4)
        helper.setBlock(emitter, bpm.world.ContentBlocks.SIGNAL_EMITTER.get())
        helper.setBlock(lamp, Blocks.REDSTONE_LAMP)
        val graph = Graph(
            java.util.UUID.nameUUIDFromBytes("bpm-test-emit-at".toByteArray()).toString(), "emit-at",
            listOf(
                io.osrsx.vscript.model.Node(1, io.osrsx.vscript.model.BuiltinNodes.ENTRY_TICK),
                io.osrsx.vscript.model.Node(2, "redstone.emitAt", literals = linkedMapOf("Link" to "node", "Level" to 15L)),
            ),
            listOf(io.osrsx.vscript.model.Link(1, 1, "Exec", 2, "Exec")),
        )
        val be = deploy(helper, graph, BlockPos(1, 1, 1), "node" to (emitter to null))
        helper.succeedWhen {
            helper.assertTrue(be.lastError == null, "controller error: ${be.lastError}")
            helper.assertBlockProperty(emitter, bpm.world.SignalEmitterBlock.POWER, 15)
            helper.assertBlockProperty(lamp, RedstoneLampBlock.LIT, true)
        }
    }

    /** `Link Info(chest).Pos` is the chest's position: In Area(that, chest, chest) is true, and the top face reads 15. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    fun linkInfoAnswersWhereTheLinkIs(helper: GameTestHelper) {
        val chest = BlockPos(4, 1, 1)
        helper.setBlock(chest, Blocks.CHEST)
        val abs = helper.absolutePos(chest)
        val here = "${abs.x},${abs.y},${abs.z}"
        val graph = Graph(
            java.util.UUID.nameUUIDFromBytes("bpm-test-link-info".toByteArray()).toString(), "link-info",
            listOf(
                io.osrsx.vscript.model.Node(1, io.osrsx.vscript.model.BuiltinNodes.ENTRY_TICK),
                io.osrsx.vscript.model.Node(2, "controller.linkInfo", literals = linkedMapOf("Link" to "chest")),
                io.osrsx.vscript.model.Node(3, "world.inArea", literals = linkedMapOf("From" to here, "To" to here)),
                io.osrsx.vscript.model.Node(4, "flow.branch"),
                io.osrsx.vscript.model.Node(5, "redstone.emit", literals = linkedMapOf("Side" to "Up", "Level" to 15L)),
                io.osrsx.vscript.model.Node(6, "redstone.emit", literals = linkedMapOf("Side" to "Up", "Level" to 3L)),
            ),
            listOf(
                io.osrsx.vscript.model.Link(1, 1, "Exec", 4, "Exec"),
                io.osrsx.vscript.model.Link(2, 2, "Pos", 3, "Pos"),
                io.osrsx.vscript.model.Link(3, 3, "Inside", 4, "Condition"),
                io.osrsx.vscript.model.Link(4, 4, "True", 5, "Exec"),
                io.osrsx.vscript.model.Link(5, 4, "False", 6, "Exec"),
            ),
        )
        val be = deploy(helper, graph, BlockPos(1, 1, 1), "chest" to (chest to Direction.UP))
        helper.succeedWhen {
            helper.assertTrue(be.lastError == null, "controller error: ${be.lastError}")
            helper.assertTrue(be.signal(Direction.UP) == 15, "the link's position was not the chest's (signal ${be.signal(Direction.UP)})")
        }
    }

    /** `Block Property(Block At(lamp), "lit")` reads the lamp's state: lit → the top face reads 15, unlit → 3. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    fun blockPropertyReadsTheState(helper: GameTestHelper) {
        val lamp = BlockPos(4, 1, 1)
        helper.setBlock(lamp, Blocks.REDSTONE_LAMP.defaultBlockState().setValue(RedstoneLampBlock.LIT, true))
        val abs = helper.absolutePos(lamp)
        val graph = Graph(
            java.util.UUID.nameUUIDFromBytes("bpm-test-block-property".toByteArray()).toString(), "block-property",
            listOf(
                io.osrsx.vscript.model.Node(1, io.osrsx.vscript.model.BuiltinNodes.ENTRY_TICK),
                io.osrsx.vscript.model.Node(2, "world.blockAt", literals = linkedMapOf("Pos" to "${abs.x},${abs.y},${abs.z}")),
                io.osrsx.vscript.model.Node(3, "world.property", literals = linkedMapOf("Name" to "lit")),
                io.osrsx.vscript.model.Node(4, "compare.eq", literals = linkedMapOf("B" to "true")),
                io.osrsx.vscript.model.Node(5, "flow.branch"),
                io.osrsx.vscript.model.Node(6, "redstone.emit", literals = linkedMapOf("Side" to "Up", "Level" to 15L)),
                io.osrsx.vscript.model.Node(7, "redstone.emit", literals = linkedMapOf("Side" to "Up", "Level" to 3L)),
            ),
            listOf(
                io.osrsx.vscript.model.Link(1, 1, "Exec", 5, "Exec"),
                io.osrsx.vscript.model.Link(2, 2, "State", 3, "State"),
                io.osrsx.vscript.model.Link(3, 3, "Value", 4, "A"),
                io.osrsx.vscript.model.Link(4, 4, "Result", 5, "Condition"),
                io.osrsx.vscript.model.Link(5, 5, "True", 6, "Exec"),
                io.osrsx.vscript.model.Link(6, 5, "False", 7, "Exec"),
            ),
        )
        val be = deploy(helper, graph, BlockPos(1, 1, 1))
        helper.startSequence()
            .thenWaitUntil {
                helper.assertTrue(be.lastError == null, "controller error: ${be.lastError}")
                helper.assertTrue(be.signal(Direction.UP) == 15, "a lit lamp read as unlit (signal ${be.signal(Direction.UP)})")
            }
            .thenExecute { helper.setBlock(lamp, Blocks.REDSTONE_LAMP.defaultBlockState().setValue(RedstoneLampBlock.LIT, false)) }
            .thenWaitUntil { helper.assertTrue(be.signal(Direction.UP) == 3, "an unlit lamp still read as lit (signal ${be.signal(Direction.UP)})") }
            .thenSucceed()
    }

    /** `Blocks In Area(Filter(tag = c:ores)).Count` over stone, iron ore and coal ore reads 2. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    fun blocksInAreaFindsTheOres(helper: GameTestHelper) {
        helper.setBlock(BlockPos(3, 1, 3), Blocks.STONE)
        helper.setBlock(BlockPos(4, 1, 3), Blocks.IRON_ORE)
        helper.setBlock(BlockPos(5, 1, 3), Blocks.COAL_ORE)
        val a = helper.absolutePos(BlockPos(3, 1, 3))
        val b = helper.absolutePos(BlockPos(5, 1, 3))
        val graph = Graph(
            java.util.UUID.nameUUIDFromBytes("bpm-test-blocks-in".toByteArray()).toString(), "blocks-in",
            listOf(
                io.osrsx.vscript.model.Node(1, io.osrsx.vscript.model.BuiltinNodes.ENTRY_TICK),
                io.osrsx.vscript.model.Node(2, "items.filter", literals = linkedMapOf("tag" to "c:ores")),
                io.osrsx.vscript.model.Node(3, "world.blocksIn", literals = linkedMapOf("From" to "${a.x},${a.y},${a.z}", "To" to "${b.x},${b.y},${b.z}")),
                io.osrsx.vscript.model.Node(4, "redstone.emit", literals = linkedMapOf("Side" to "Up")),
            ),
            listOf(
                io.osrsx.vscript.model.Link(1, 1, "Exec", 4, "Exec"),
                io.osrsx.vscript.model.Link(2, 2, "Filter", 3, "Filter"),
                io.osrsx.vscript.model.Link(3, 3, "Count", 4, "Level"),
            ),
        )
        val be = deploy(helper, graph, BlockPos(1, 1, 1))
        helper.succeedWhen {
            helper.assertTrue(be.lastError == null, "controller error: ${be.lastError}")
            helper.assertTrue(be.signal(Direction.UP) == 2, "Blocks In Area counted ${be.signal(Direction.UP)} ores, wanted 2")
        }
    }

    /** `on tick → world.click(...)` with the given literals. */
    private fun clicker(name: String, vararg literals: Pair<String, Any?>): Graph = Graph(
        java.util.UUID.nameUUIDFromBytes("bpm-test-$name".toByteArray()).toString(), name,
        listOf(
            io.osrsx.vscript.model.Node(1, io.osrsx.vscript.model.BuiltinNodes.ENTRY_TICK),
            io.osrsx.vscript.model.Node(2, "world.click", literals = linkedMapOf(*literals)),
        ),
        listOf(io.osrsx.vscript.model.Link(1, 1, "Exec", 2, "Exec")),
    )

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    fun clickLeftBreaksTheBlock(helper: GameTestHelper) {
        val stone = BlockPos(1, 1, 3)
        helper.setBlock(stone, Blocks.STONE)
        val be = deploy(helper, clicker("click-break", "Link" to "stone", "Button" to "Left", "Slot" to 0L), BlockPos(1, 1, 1), "stone" to (stone to null))
        be.inventory.setStackInSlot(0, ItemStack(Items.IRON_PICKAXE))
        helper.succeedWhen {
            helper.assertTrue(be.lastError == null, "controller error: ${be.lastError}")
            helper.assertBlock(stone, { it == Blocks.AIR }, "a Left click did not break the stone")
        }
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    fun clickLeftStrikesWhatStandsThere(helper: GameTestHelper) {
        // The plot's ground is under y = 0, so a pig at y = 0 stands in the block the link names.
        val post = BlockPos(3, 0, 3)
        val pig = helper.spawn(net.minecraft.world.entity.EntityType.PIG, Vec3(3.5, 0.0, 3.5)).also { it.setNoAi(true) }
        val start = pig.health
        val be = deploy(helper, SampleDocs.guard(), BlockPos(1, 1, 1), "post" to (post to null))
        be.inventory.setStackInSlot(0, ItemStack(Items.DIAMOND_SWORD))
        helper.succeedWhen {
            helper.assertTrue(be.lastError == null, "controller error: ${be.lastError}")
            helper.assertTrue(pig.isDeadOrDying || pig.health < start, "the pig at the post is untouched (${pig.health} of $start)")
        }
    }

    /** The usual guard post is a floor block: the mob stands ON it, in the cell above, and is still struck. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    fun clickLeftStrikesWhatStandsOnTheBlock(helper: GameTestHelper) {
        val post = BlockPos(3, 0, 3)
        helper.setBlock(post, Blocks.STONE)
        val pig = helper.spawn(net.minecraft.world.entity.EntityType.PIG, Vec3(3.5, 1.0, 3.5)).also { it.setNoAi(true) }
        val start = pig.health
        val be = deploy(helper, SampleDocs.guard(), BlockPos(1, 1, 1), "post" to (post to Direction.UP))
        be.inventory.setStackInSlot(0, ItemStack(Items.DIAMOND_SWORD))
        helper.succeedWhen {
            helper.assertTrue(be.lastError == null, "controller error: ${be.lastError}")
            helper.assertTrue(pig.isDeadOrDying || pig.health < start, "the pig standing on the post is untouched (${pig.health} of $start)")
        }
    }

    /** A sword swung from the ground sweeps: the second mob beside the target is hurt by the same swing. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    fun clickLeftSweepsTheCrowd(helper: GameTestHelper) {
        val post = BlockPos(3, 0, 3)
        helper.setBlock(post, Blocks.STONE)
        val first = helper.spawn(net.minecraft.world.entity.EntityType.PIG, Vec3(3.5, 1.0, 3.5)).also { it.setNoAi(true) }
        val second = helper.spawn(net.minecraft.world.entity.EntityType.PIG, Vec3(4.4, 1.0, 3.5)).also { it.setNoAi(true) }
        val startFirst = first.health
        val startSecond = second.health
        val be = deploy(helper, SampleDocs.guard(), BlockPos(1, 1, 1), "post" to (post to Direction.UP))
        be.inventory.setStackInSlot(0, ItemStack(Items.DIAMOND_SWORD))
        helper.succeedWhen {
            helper.assertTrue(be.lastError == null, "controller error: ${be.lastError}")
            helper.assertTrue(first.isDeadOrDying || first.health < startFirst, "the pig on the post is untouched")
            helper.assertTrue(second.isDeadOrDying || second.health < startSecond, "the pig beside it was not swept (${second.health} of $startSecond)")
        }
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    fun clickRightPullsTheLever(helper: GameTestHelper) {
        val lever = BlockPos(1, 1, 3)
        helper.setBlock(lever.below(), Blocks.STONE)
        helper.setBlock(lever, Blocks.LEVER.defaultBlockState().setValue(net.minecraft.world.level.block.LeverBlock.FACE, net.minecraft.world.level.block.state.properties.AttachFace.FLOOR))
        val be = deploy(helper, clicker("click-lever", "Link" to "lever", "Button" to "Right"), BlockPos(1, 1, 1), "lever" to (lever to Direction.UP))
        helper.succeedWhen {
            helper.assertTrue(be.lastError == null, "controller error: ${be.lastError}")
            helper.assertBlockProperty(lever, net.minecraft.world.level.block.LeverBlock.POWERED, true)
        }
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    fun experienceOrbsBecomeLiquid(helper: GameTestHelper) {
        val controller = BlockPos(1, 1, 1)
        val spot = BlockPos(3, 1, 3)
        val be = deploy(helper, SampleDocs.xpVacuum(), controller, "spot" to (spot to null))
        val at = helper.absoluteVec(Vec3(3.5, 1.5, 3.5))
        helper.level.addFreshEntity(ExperienceOrb(helper.level, at.x, at.y, at.z, 7))
        helper.succeedWhen {
            helper.assertTrue(be.lastError == null, "controller error: ${be.lastError}")
            val mb = be.tanks.amountOf(FluidStack(ModFluids.EXPERIENCE.get(), 1))
            helper.assertTrue(mb == 7 * ControllerStores.XP_MB_PER_POINT, "the tanks hold $mb mB of experience")
            helper.assertTrue(helper.level.getEntitiesOfClass(ExperienceOrb::class.java, around(helper, spot, 3.0)).isEmpty(), "the orb is still there")
        }
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    fun liquidExperienceDropsAsOrbs(helper: GameTestHelper) {
        val controller = BlockPos(1, 1, 1)
        val spot = BlockPos(3, 1, 3)
        val be = deploy(helper, SampleDocs.xpDrop(), controller, "spot" to (spot to null))
        be.tanks.fill(FluidStack(ModFluids.EXPERIENCE.get(), 5 * ControllerStores.XP_MB_PER_POINT), IFluidHandler.FluidAction.EXECUTE)
        helper.succeedWhen {
            helper.assertTrue(be.lastError == null, "controller error: ${be.lastError}")
            // Orbs are thrown with a random kick and bounce, so the net is cast wide; and equal orbs MERGE —
            // a merged orb keeps its per-orb value and counts how many it stands for, so both are summed.
            val orbs = helper.level.getEntitiesOfClass(ExperienceOrb::class.java, around(helper, spot, 8.0))
            val points = orbs.sumOf { it.value * ORB_COUNT.getInt(it) }
            helper.assertTrue(points == 5, "$points points of orbs on the ground, wanted 5")
            helper.assertTrue(be.tanks.amountOf(FluidStack(ModFluids.EXPERIENCE.get(), 1)) == 0, "the tanks still hold experience")
        }
    }

    /** `Dropped` is the count that went out, and `Max` caps it — `on tick → drop(spot, Max 2) → emit(Up, Dropped)`. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    fun dropAnswersHowManyAndHonoursMax(helper: GameTestHelper) {
        val controller = BlockPos(1, 1, 1)
        val spot = BlockPos(3, 1, 3)
        val graph = Graph(
            java.util.UUID.nameUUIDFromBytes("bpm-test-drop-count".toByteArray()).toString(), "drop-count",
            listOf(
                io.osrsx.vscript.model.Node(1, io.osrsx.vscript.model.BuiltinNodes.ENTRY_TICK),
                io.osrsx.vscript.model.Node(2, "items.drop", literals = linkedMapOf("Link" to "spot", "Max" to 2L)),
                io.osrsx.vscript.model.Node(3, "redstone.emit", literals = linkedMapOf("Side" to "Up")),
                io.osrsx.vscript.model.Node(4, "controller.wait", literals = linkedMapOf("Ticks" to 100L)),
            ),
            listOf(
                io.osrsx.vscript.model.Link(1, 1, "Exec", 2, "Exec"),
                io.osrsx.vscript.model.Link(2, 2, "Exec", 3, "Exec"),
                io.osrsx.vscript.model.Link(3, 2, "Dropped", 3, "Level"),
                io.osrsx.vscript.model.Link(4, 3, "Exec", 4, "Exec"),
            ),
        )
        val be = deploy(helper, graph, controller, "spot" to (spot to null))
        be.inventory.setStackInSlot(0, ItemStack(Items.COBBLESTONE, 3))
        helper.succeedWhen {
            helper.assertTrue(be.lastError == null, "controller error: ${be.lastError}")
            val cobble = helper.level.getEntitiesOfClass(ItemEntity::class.java, around(helper, spot, 3.0)).filter { it.item.`is`(Items.COBBLESTONE) }.sumOf { it.item.count }
            helper.assertTrue(cobble == 2, "$cobble cobblestone on the ground, wanted Max = 2")
            helper.assertTrue(be.inventory.getStackInSlot(0).count == 1, "the buffer holds ${be.inventory.getStackInSlot(0)}, wanted the one left over")
            helper.assertTrue(be.signal(Direction.UP) == 2, "Dropped answered ${be.signal(Direction.UP)}, wanted 2")
        }
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    fun itemsDropOnTheGround(helper: GameTestHelper) {
        val controller = BlockPos(1, 1, 1)
        val spot = BlockPos(3, 1, 3)
        val be = deploy(helper, SampleDocs.dropItems(), controller, "spot" to (spot to null))
        be.inventory.setStackInSlot(0, ItemStack(Items.COBBLESTONE, 3))
        helper.succeedWhen {
            helper.assertTrue(be.lastError == null, "controller error: ${be.lastError}")
            val items = helper.level.getEntitiesOfClass(ItemEntity::class.java, around(helper, spot, 3.0))
            val cobble = items.filter { it.item.`is`(Items.COBBLESTONE) }.sumOf { it.item.count }
            helper.assertTrue(cobble == 3, "$cobble cobblestone on the ground, wanted 3")
            helper.assertTrue(be.inventory.getStackInSlot(0).isEmpty, "the buffer still holds ${be.inventory.getStackInSlot(0)}")
        }
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    fun fluidPlacedFromTheTanks(helper: GameTestHelper) {
        val controller = BlockPos(1, 1, 1)
        val spot = BlockPos(3, 1, 3)
        val be = deploy(helper, SampleDocs.placeFluid(), controller, "spot" to (spot to null))
        be.tanks.fill(FluidStack(Fluids.WATER, 1000), IFluidHandler.FluidAction.EXECUTE)
        helper.succeedWhen {
            helper.assertTrue(be.lastError == null, "controller error: ${be.lastError}")
            helper.assertBlock(spot, { it == Blocks.WATER }, "no water at the spot")
            helper.assertTrue(be.tanks.amountOf(FluidStack(Fluids.WATER, 1)) == 0, "the tanks still hold water")
        }
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    fun fluidPickedUpIntoTheTanks(helper: GameTestHelper) {
        val controller = BlockPos(1, 1, 1)
        val spot = BlockPos(3, 1, 3)
        helper.setBlock(spot, Blocks.WATER)
        val be = deploy(helper, SampleDocs.pickupFluid(), controller, "spot" to (spot to null))
        helper.succeedWhen {
            helper.assertTrue(be.lastError == null, "controller error: ${be.lastError}")
            val mb = be.tanks.amountOf(FluidStack(Fluids.WATER, 1))
            helper.assertTrue(mb == 1000, "the tanks hold $mb mB of water, wanted 1000")
            helper.assertTrue(helper.getBlockState(spot).fluidState.isEmpty, "the water is still there")
        }
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    fun toolFoundByComposedFilter(helper: GameTestHelper) {
        val controller = BlockPos(1, 1, 1)
        val stone = BlockPos(1, 1, 3)
        val chest = BlockPos(3, 1, 1)
        helper.setBlock(stone, Blocks.STONE)
        helper.setBlock(chest, Blocks.CHEST)
        val be = deploy(helper, SampleDocs.toolFromTag(), controller, "stone" to (stone to null), "chest" to (chest to null))
        be.inventory.setStackInSlot(0, ItemStack(Items.STONE, 4))
        be.inventory.setStackInSlot(1, ItemStack(Items.IRON_PICKAXE).also { it.damageValue = 5 })
        be.inventory.setStackInSlot(2, ItemStack(Items.IRON_PICKAXE))
        helper.succeedWhen {
            helper.assertTrue(be.lastError == null, "controller error: ${be.lastError}")
            helper.assertBlock(stone, { it == Blocks.AIR }, "stone not broken")
            helper.assertTrue(container(helper, chest).getItem(0).`is`(Items.COBBLESTONE), "no cobblestone in the chest: ${be.describe()}")
            helper.assertTrue(be.inventory.getStackInSlot(2).damageValue == 1, "the fresh pickaxe has damage ${be.inventory.getStackInSlot(2).damageValue}, wanted 1")
            helper.assertTrue(be.inventory.getStackInSlot(1).damageValue == 5, "the worn pickaxe was used")
        }
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    fun areaMinedThroughLinksMadeOnTheSpot(helper: GameTestHelper) {
        val controller = BlockPos(1, 1, 1)
        val corners = listOf(BlockPos(3, 1, 3), BlockPos(4, 1, 3), BlockPos(3, 1, 4), BlockPos(4, 1, 4))
        for (p in corners) helper.setBlock(p, Blocks.DIRT)
        val a = helper.absolutePos(BlockPos(3, 1, 3))
        val b = helper.absolutePos(BlockPos(4, 1, 4))
        val be = deploy(helper, SampleDocs.areaMiner("${a.x},${a.y},${a.z}", "${b.x},${b.y},${b.z}"), controller)
        helper.succeedWhen {
            helper.assertTrue(be.lastError == null, "controller error: ${be.lastError}")
            for (p in corners) helper.assertBlock(p, { it == Blocks.AIR }, "dirt at $p not broken")
        }
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    fun bareHandsBreakStoneWithoutDrops(helper: GameTestHelper) {
        val controller = BlockPos(1, 1, 1)
        val stone = BlockPos(1, 1, 3)
        helper.setBlock(stone, Blocks.STONE)
        val be = deploy(helper, SampleDocs.mineBareHanded(), controller, "stone" to (stone to null))
        helper.startSequence()
            .thenWaitUntil { helper.assertBlock(stone, { it == Blocks.AIR }, "stone not broken") }
            .thenExecuteAfter(10) {
                helper.assertTrue(be.lastError == null, "controller error: ${be.lastError}")
                val held = (0 until be.inventory.slots).sumOf { be.inventory.getStackInSlot(it).count }
                helper.assertTrue(held == 0, "a bare hand harvested $held items from stone")
                helper.assertTrue(helper.level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity::class.java, net.minecraft.world.phys.AABB(helper.absolutePos(stone)).inflate(3.0)).isEmpty(), "stone dropped an item")
            }
            .thenSucceed()
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    fun disabledControllerStopsAndClearsRedstone(helper: GameTestHelper) {
        val controller = BlockPos(1, 1, 1)
        val lamp = BlockPos(1, 2, 1)
        val chest = BlockPos(3, 1, 1)
        helper.setBlock(chest, Blocks.CHEST)
        container(helper, chest).setItem(0, ItemStack(Items.STONE, 3))
        val be = deploy(helper, SampleDocs.redstone(), controller, "chest" to (chest to null))
        helper.setBlock(lamp, Blocks.REDSTONE_LAMP)
        helper.startSequence()
            .thenWaitUntil { helper.assertBlockProperty(lamp, RedstoneLampBlock.LIT, true) }
            .thenExecute { be.setEnabled(false) }
            .thenWaitUntil {
                helper.assertTrue(be.runtime == null, "runtime still there")
                helper.assertBlockProperty(lamp, RedstoneLampBlock.LIT, false)
            }
            .thenExecute { be.setEnabled(true) }
            .thenWaitUntil { helper.assertBlockProperty(lamp, RedstoneLampBlock.LIT, true) }
            .thenSucceed()
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    fun manyControllersStayWithinBudget(helper: GameTestHelper) {
        // Twenty-five controllers on one plot, each running the mover with nothing to move: the per-tick cost
        // of a live program that finds nothing to do, times twenty-five, must stay well inside the budget.
        val bes = ArrayList<ControllerBlockEntity>()
        for (x in 0 until 5) for (z in 0 until 5) {
            bes += deploy(helper, SampleDocs.move(), BlockPos(1 + x, 1, 1 + z), "chest" to (BlockPos(0, 0, 0) to null), "hopper" to (BlockPos(0, 0, 0) to null))
        }
        helper.runAfterDelay(60) {
            val running = bes.count { it.runtime != null }
            helper.assertTrue(running == 25, "only $running of 25 running: ${bes.firstOrNull { it.runtime == null }?.lastError}")
            val ms = RuntimeManager.lastTickNanos / 1e6
            helper.assertTrue(ms < RuntimeManager.globalBudgetMs + 2.0, "scripting took $ms ms in one tick")
            helper.succeed()
        }
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    fun logLinesReachTheServerLog(helper: GameTestHelper) {
        val be = deploy(helper, SampleDocs.logger(), BlockPos(1, 1, 1))
        helper.succeedWhen {
            helper.assertTrue(be.lastError == null, "controller error: ${be.lastError}")
            val messages = be.runtime?.runtime?.log?.records?.map { it.message } ?: emptyList()
            helper.assertTrue(listOf("one", "two", "three").all { it in messages }, "log holds $messages")
            val warn = be.runtime?.runtime?.log?.records?.firstOrNull { it.message == "two" }
            helper.assertTrue(warn?.level == io.osrsx.vscript.log.LogLevel.WARN, "level of 'two' is ${warn?.level}")
        }
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    fun breakpointPausesOnlyItsController(helper: GameTestHelper) {
        // Two movers with the same document; only the first has a breakpoint on the move node.
        val chestA = BlockPos(0, 1, 0)
        val hopperA = BlockPos(0, 1, 2)
        val chestB = BlockPos(4, 1, 0)
        val hopperB = BlockPos(4, 1, 2)
        for (p in listOf(chestA, chestB)) {
            helper.setBlock(p, Blocks.CHEST)
            container(helper, p).setItem(0, ItemStack(Items.COAL, 40))
        }
        helper.setBlock(hopperA, Blocks.HOPPER)
        helper.setBlock(hopperB, Blocks.HOPPER)
        val a = deploy(helper, SampleDocs.move(), BlockPos(2, 1, 0), "chest" to (chestA to null), "hopper" to (hopperA to Direction.UP))
        a.setBreakpoint(2, enabled = true, remove = false)
        val b = deploy(helper, SampleDocs.move(), BlockPos(2, 1, 4), "chest" to (chestB to null), "hopper" to (hopperB to Direction.UP))
        helper.runAfterDelay(60) {
            helper.assertTrue(a.runtime?.debug?.isPaused == true, "A is not paused: ${a.describe()}")
            helper.assertTrue(count(helper, hopperA) == 0, "A moved ${count(helper, hopperA)} despite the breakpoint")
            helper.assertTrue(count(helper, hopperB) == 40, "B moved ${count(helper, hopperB)} of 40")
            a.runtime!!.debug.resume()
        }
        helper.runAfterDelay(100) {
            helper.assertTrue(count(helper, hopperA) > 0, "A did not resume")
            helper.succeed()
        }
    }

    companion object {
        const val TEMPLATE = "empty7"

        /** `ExperienceOrb.count` — how many orbs a merged one stands for; private, with no accessor in 1.21.1. */
        private val ORB_COUNT: java.lang.reflect.Field = ExperienceOrb::class.java.getDeclaredField("count").also { it.isAccessible = true }
    }

    @GameTest(template = "empty7", timeoutTicks = 40)
    fun theWandLinksAChestInsteadOfOpeningIt(helper: GameTestHelper) {
        val at = BlockPos(1, 1, 1)
        helper.setBlock(at, ModBlocks.CONTROLLER.get())
        val be = helper.getBlockEntity<ControllerBlockEntity>(at)
        val chest = BlockPos(3, 1, 3)
        helper.setBlock(chest, Blocks.CHEST)
        val player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL)
        val stack = ItemStack(bpm.world.ModItems.LINKER.get())
        stack.set(bpm.world.ModComponents.SELECTED_CONTROLLER.get(), net.minecraft.core.GlobalPos.of(helper.level.dimension(), helper.absolutePos(at)))
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, stack)
        val abs = helper.absolutePos(chest)
        val hit = net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(abs).add(0.0, 0.5, 0.0), Direction.UP, abs, false)
        fun click() = stack.item.onItemUseFirst(stack, net.minecraft.world.item.context.UseOnContext(player, net.minecraft.world.InteractionHand.MAIN_HAND, hit))
        // The wand takes the click before the chest can open, and links the face.
        helper.assertTrue(click() == net.minecraft.world.InteractionResult.CONSUME, "the wand let the chest have the click")
        helper.assertTrue(be.links.at(abs, Direction.UP)?.name == "chest", "no link was made: ${be.links.names}")
        click()
        helper.assertTrue(be.links.all.size == 1, "the same face was linked twice: ${be.links.names}")
        // The chest gone, the link has no face left: the air click along the line of sight finds it.
        helper.level.setBlock(abs, Blocks.AIR.defaultBlockState(), 3)
        val eye = Vec3.atCenterOf(helper.absolutePos(BlockPos(3, 1, 1))).add(0.0, 0.5, 0.0)
        player.setPos(eye.x, eye.y - player.eyeHeight, eye.z)
        val toChest = Vec3.atCenterOf(abs).subtract(eye)
        player.yRot = Math.toDegrees(kotlin.math.atan2(-toChest.x, toChest.z)).toFloat()
        player.xRot = Math.toDegrees(-kotlin.math.atan2(toChest.y, toChest.horizontalDistance())).toFloat()
        val ahead = bpm.world.LinkerItem.linkAhead(helper.level, player, be)
        helper.assertTrue(ahead?.name == "chest", "the air click did not find the link whose block is gone (${ahead?.name}, look ${player.lookAngle})")
        helper.succeed()
    }
}
