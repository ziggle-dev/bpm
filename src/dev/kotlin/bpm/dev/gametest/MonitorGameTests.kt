package bpm.dev.gametest

import bpm.Bpm
import bpm.library.BpmLibrary
import bpm.world.ControllerBlockEntity
import bpm.world.DeviceBlocks
import bpm.world.Link
import bpm.world.ModBlocks
import bpm.world.devices.MonitorBlock
import bpm.world.devices.MonitorBlockEntity
import bpm.world.devices.MonitorWall
import bpm.world.devices.Widget
import dev.ziggle.vscript.model.Graph
import dev.ziggle.vscript.model.GraphDoc
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate

/** Monitors join into walls with one origin, and a script writes widgets to whichever tile it linked. */
@GameTestHolder(Bpm.ID)
@PrefixGameTestTemplate(false)
class MonitorGameTests {
    /** A 2 x 2 wall on the plot's x/y plane, facing so that the tile at higher plot-x is the viewer's LEFT (the origin's column). */
    private fun wall(helper: GameTestHelper): Direction {
        val d = helper.absolutePos(BlockPos(4, 1, 3)).subtract(helper.absolutePos(BlockPos(3, 1, 3)))
        val toward = Direction.getNearest(d.x.toDouble(), 0.0, d.z.toDouble())
        val facing = toward.counterClockWise // facing.clockWise == toward: the viewer's left is the +x tile
        val state = DeviceBlocks.QUANTUM_MONITOR.get().defaultBlockState().setValue(MonitorBlock.FACING, facing)
        for (p in listOf(BlockPos(3, 1, 3), BlockPos(4, 1, 3), BlockPos(3, 2, 3), BlockPos(4, 2, 3))) helper.setBlock(p, state)
        return facing
    }

    @GameTest(template = "empty7", timeoutTicks = 100)
    fun tilesJoinIntoAWallWithOneOrigin(helper: GameTestHelper) {
        wall(helper)
        val level = helper.level
        val origin = helper.absolutePos(BlockPos(4, 2, 3))
        for (rel in listOf(BlockPos(3, 1, 3), BlockPos(4, 1, 3), BlockPos(3, 2, 3), BlockPos(4, 2, 3))) {
            helper.assertTrue(MonitorWall.originOf(level, helper.absolutePos(rel)) == origin, "the origin of $rel is not the top-left tile")
        }
        helper.assertTrue(MonitorWall.sizeOf(level, origin) == (2 to 2), "the wall measures ${MonitorWall.sizeOf(level, origin)}, wanted 2 x 2")
        val be = level.getBlockEntity(origin) as MonitorBlockEntity
        be.show(listOf(Widget(Widget.TEXT, text = "hello")))
        MonitorWall.setOn(level, origin, true)
        helper.assertTrue(be.widgets.size == 1 && be.widgets[0].text == "hello", "the origin does not hold the text")
        for (p in MonitorWall.tiles(level, origin)) helper.assertTrue(level.getBlockState(p).getValue(MonitorBlock.ON), "tile $p is not lit")
        be.clear()
        MonitorWall.setOn(level, origin, false)
        helper.assertTrue(be.widgets.isEmpty(), "clear left widgets behind")
        helper.assertBlockProperty(BlockPos(3, 1, 3), MonitorBlock.ON, false)
        helper.succeed()
    }

    @GameTest(template = "empty7", timeoutTicks = 200)
    fun aScriptShowsTextOnTheWallThroughAnyTile(helper: GameTestHelper) {
        wall(helper)
        val level = helper.level
        val origin = helper.absolutePos(BlockPos(4, 2, 3))
        val linked = helper.absolutePos(BlockPos(3, 1, 3)) // the bottom-right tile, not the origin
        val graph = Graph(
            java.util.UUID.nameUUIDFromBytes("bpm-test-monitor".toByteArray()).toString(), "monitor-text",
            listOf(
                dev.ziggle.vscript.model.Node(1, dev.ziggle.vscript.model.BuiltinNodes.ENTRY_TICK),
                dev.ziggle.vscript.model.Node(2, "monitor.text", literals = linkedMapOf("Text" to "iron: 64", "Colour" to "teal")),
                dev.ziggle.vscript.model.Node(3, "value.list", literals = linkedMapOf("Of" to "Widget", "Count" to 1L)),
                dev.ziggle.vscript.model.Node(4, "monitor.show", literals = linkedMapOf("Link" to "screen")),
            ),
            listOf(
                dev.ziggle.vscript.model.Link(1, 1, "Exec", 4, "Exec"),
                dev.ziggle.vscript.model.Link(2, 2, "Widget", 3, "1"),
                dev.ziggle.vscript.model.Link(3, 3, "Value", 4, "Widgets"),
            ),
        )
        val lib = BpmLibrary.get(level.server)
        val record = lib.byName(graph.name)?.let { lib.store(it.id, GraphDoc.toJson(graph))!! } ?: lib.create(graph.name, GraphDoc.toJson(graph), null)
        val at = BlockPos(1, 1, 1)
        helper.setBlock(at, ModBlocks.CONTROLLER.get())
        val be = helper.getBlockEntity<ControllerBlockEntity>(at)
        be.links.add(Link("screen", linked, null, level.dimension()))
        be.bind(record.id)
        helper.succeedWhen {
            helper.assertTrue(be.lastError == null, "controller error: ${be.lastError}")
            val screen = level.getBlockEntity(origin) as MonitorBlockEntity
            helper.assertTrue(screen.widgets.size == 1 && screen.widgets[0].text == "iron: 64" && screen.widgets[0].colour == "teal", "the wall's origin shows ${screen.widgets}")
            helper.assertTrue((level.getBlockEntity(linked) as MonitorBlockEntity).widgets.isEmpty(), "the linked tile holds content of its own")
            helper.assertTrue(level.getBlockState(linked).getValue(MonitorBlock.ON), "the wall is not lit")
        }
    }
}
