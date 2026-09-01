package bpm.dev

import bpm.Bpm
import bpm.catalog.BpmCatalog
import dev.ziggle.vscript.compile.Severity
import dev.ziggle.vscript.compile.Validator
import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.model.Graph
import dev.ziggle.vscript.model.GraphDoc
import dev.ziggle.vscript.model.Link
import dev.ziggle.vscript.model.Node
import net.neoforged.fml.loading.FMLPaths
import java.nio.file.Files
import java.util.UUID

/**
 * Sample documents for `/bpm doc load`, written to `<gamedir>/bpm/graphs/` when the dev server starts (and
 * on the console's `samples` command). Built by hand from the model so they double as a check that the
 * catalogue's pin names are what the docs say.
 */
object SampleDocs {
    private class B(val name: String) {
        val nodes = ArrayList<Node>()
        val links = ArrayList<Link>()
        fun node(type: String, vararg literals: Pair<String, Any?>, x: Float = 0f, y: Float = 0f): Int {
            val id = nodes.size + 1
            nodes += Node(id, type, x = x, y = y, literals = LinkedHashMap(literals.toMap()))
            return id
        }
        fun exec(from: Int, to: Int, fromPin: String = "Exec") { links += Link(links.size + 1, from, fromPin, to, "Exec") }
        fun data(from: Int, fromPin: String, to: Int, toPin: String) { links += Link(links.size + 1, from, fromPin, to, toPin) }
        fun build() = Graph(UUID.nameUUIDFromBytes("bpm-sample-$name".toByteArray()).toString(), name, nodes, links)
    }

    /** Every tick: move up to 8 items from the link `chest` to the link `hopper`. */
    fun move(): Graph = B("move").run {
        val tick = node(BuiltinNodes.ENTRY_TICK, x = 40f, y = 80f)
        val mv = node("items.move", "From" to "chest", "To" to "hopper", "Max" to 8L, x = 320f, y = 80f)
        exec(tick, mv)
        build()
    }

    /** Every tick: break `stone` with the tool in buffer slot 0, vacuum the drops, wait, push the cobblestone (not the tool) into `chest`. */
    fun mine(): Graph = B("mine").run {
        val tick = node(BuiltinNodes.ENTRY_TICK, x = 40f, y = 80f)
        val brk = node("world.breakBlock", "Link" to "stone", "Tool" to 0L, x = 300f, y = 80f)
        val vac = node("world.vacuum", "Link" to "stone", "Radius" to 2.0, x = 560f, y = 80f)
        val wait = node("controller.wait", "Ticks" to 20L, x = 820f, y = 80f)
        val cobble = node("items.filter", "item" to "minecraft:cobblestone", x = 820f, y = 260f)
        val push = node("items.move", "From" to "self", "To" to "chest", "Max" to 64L, x = 1080f, y = 80f)
        exec(tick, brk)
        exec(brk, vac)
        exec(vac, wait)
        exec(wait, push)
        data(cobble, "Filter", push, "Filter")
        build()
    }

    /**
     * The dump, the long way round: when `dump toggle` is powered, find the pickaxe's slot, then walk every
     * slot of the buffer and move each one that is not it into `dump` — reading `slot` off the `Slot` record
     * with Get Field, which is the step the loop needs (a `Slot` is a record, not a number). Without a
     * pickaxe, everything goes. `Move(self -> dump, Filter = Not(pickaxes))` says the same in one node.
     */
    fun dumpButTool(): Graph {
        fun n(id: Int, type: String, x: Float, y: Float, vararg literals: Pair<String, Any?>, variable: String? = null) =
            Node(id, type, x = x, y = y, literals = linkedMapOf(*literals), variable = variable)
        val nodes = listOf(
            n(1, BuiltinNodes.ENTRY_TICK, 40f, 80f),
            n(2, "redstone.waitFor", 260f, 80f, "Link" to "dump toggle"),
            n(3, "items.filter", 40f, 300f, "tag" to "minecraft:pickaxes"),
            n(4, "items.find", 260f, 300f, "Link" to "self"),
            n(5, "flow.branch", 520f, 80f),
            n(6, "var.set", 780f, 80f, variable = "tool slot"),
            n(7, "items.listSlots", 780f, 300f, "Link" to "self"),
            n(8, "flow.forEach", 1040f, 80f),
            n(9, "struct.get", 1300f, 300f, "Of" to "Slot", "Field" to "slot"),
            n(10, "var.get", 1300f, 460f, variable = "tool slot"),
            n(11, "compare.ne", 1560f, 300f),
            n(12, "flow.branch", 1560f, 80f),
            n(13, "items.moveSlot", 1820f, 80f, "From" to "self", "To" to "dump"),
            n(14, "items.move", 780f, -120f, "From" to "self", "To" to "dump"),
        )
        val links = listOf(
            Link(1, 1, "Exec", 2, "Exec"),
            Link(2, 2, "Exec", 5, "Exec"),
            Link(3, 3, "Filter", 4, "Filter"),
            Link(4, 4, "Found", 5, "Condition"),
            Link(5, 5, "True", 6, "Exec"),
            Link(6, 4, "Slot", 6, "Value"),
            Link(7, 5, "False", 14, "Exec"),
            Link(8, 6, "Exec", 8, "Exec"),
            Link(9, 7, "Slots", 8, "List"),
            Link(10, 8, "Element", 9, "Value"),
            Link(11, 9, "slot", 11, "A"),
            Link(12, 10, "Value", 11, "B"),
            Link(13, 8, "Body", 12, "Exec"),
            Link(14, 11, "Result", 12, "Condition"),
            Link(15, 12, "True", 13, "Exec"),
            Link(16, 9, "slot", 13, "Slot"),
        )
        val toolSlot = dev.ziggle.vscript.model.GraphVariable("tool slot", dev.ziggle.vscript.model.TypeRef(dev.ziggle.vscript.model.PinType.INT), 0)
        return Graph(UUID.nameUUIDFromBytes("bpm-sample-dump-but-tool".toByteArray()).toString(), "dump-but-tool", nodes, links, variables = listOf(toolSlot))
    }

    /** Every tick: strike whatever stands at `post` with the sword in buffer slot 0 — a guard. */
    fun guard(): Graph = B("guard").run {
        val tick = node(BuiltinNodes.ENTRY_TICK, x = 40f, y = 80f)
        val hit = node("world.click", "Link" to "post", "Button" to "Left", "Slot" to 0L, "Aim" to "Entity", x = 300f, y = 80f)
        exec(tick, hit)
        build()
    }

    /** Every tick: break `stone` bare-handed — nothing drops from stone that way. */
    fun mineBareHanded(): Graph = B("mine-bare").run {
        val tick = node(BuiltinNodes.ENTRY_TICK, x = 40f, y = 80f)
        val brk = node("world.breakBlock", "Link" to "stone", x = 300f, y = 80f)
        val vac = node("world.vacuum", "Link" to "stone", "Radius" to 2.0, x = 560f, y = 80f)
        exec(tick, brk)
        exec(brk, vac)
        build()
    }

    /** Every tick: emit 15 on the top face while `chest` holds anything, else 0. */
    fun redstone(): Graph = B("redstone").run {
        val tick = node(BuiltinNodes.ENTRY_TICK, x = 40f, y = 80f)
        val has = node("items.has", "Link" to "chest", x = 200f, y = 220f)
        val branch = node(BuiltinNodes.BRANCH, x = 320f, y = 80f)
        val on = node("redstone.emit", "Side" to "Up", "Level" to 15L, x = 600f, y = 40f)
        val off = node("redstone.emit", "Side" to "Up", "Level" to 0L, x = 600f, y = 160f)
        exec(tick, branch)
        data(has, "Has", branch, "Condition")
        exec(branch, on, fromPin = "True")
        exec(branch, off, fromPin = "False")
        build()
    }

    /** Every tick: three log lines, for the run view. */
    fun logger(): Graph = B("logger").run {
        val tick = node(BuiltinNodes.ENTRY_TICK, x = 40f, y = 80f)
        val a = node(BuiltinNodes.LOG, "Message" to "one", x = 300f, y = 80f)
        val b = node(BuiltinNodes.LOG, "Message" to "two", "Level" to "warn", x = 560f, y = 80f)
        val c = node(BuiltinNodes.LOG, "Message" to "three", x = 820f, y = 80f)
        exec(tick, a)
        exec(a, b)
        exec(b, c)
        build()
    }

    /**
     * The diamond miner: break `ore` with the Fortune pickaxe in slot 0, vacuum the drops, send diamonds to
     * `out`, take one diamond ore from `supply`, place it on `floor`'s top face (where `ore` is), wait, repeat.
     */
    fun diamondMiner(): Graph = B("miner").run {
        val tick = node(BuiltinNodes.ENTRY_TICK, x = 40f, y = 120f)
        val brk = node("world.breakBlock", "Link" to "ore", "Tool" to 0L, x = 300f, y = 120f)
        val vac = node("world.vacuum", "Link" to "ore", x = 560f, y = 120f)
        val diamonds = node("items.filter", "item" to "minecraft:diamond", x = 560f, y = 300f)
        val out = node("items.move", "From" to "self", "To" to "out", "Max" to 64L, x = 820f, y = 120f)
        val ores = node("items.filter", "item" to "minecraft:diamond_ore", x = 820f, y = 300f)
        val take = node("items.move", "From" to "supply", "To" to "self", "Max" to 1L, x = 1080f, y = 120f)
        val place = node("world.placeBlock", "Link" to "floor", "Slot" to 1L, x = 1340f, y = 120f)
        val wait = node("controller.wait", "Ticks" to 10L, x = 1600f, y = 120f)
        exec(tick, brk); exec(brk, vac); exec(vac, out); exec(out, take); exec(take, place); exec(place, wait)
        data(diamonds, "Filter", out, "Filter")
        data(ores, "Filter", take, "Filter")
        build()
    }

    /** Every tick: the experience orbs around `spot` become liquid experience in the tanks. */
    fun xpVacuum(): Graph = B("xp-vacuum").run {
        val tick = node(BuiltinNodes.ENTRY_TICK, x = 40f, y = 80f)
        val vac = node("xp.vacuum", "Link" to "spot", "Radius" to 2.0, x = 300f, y = 80f)
        exec(tick, vac)
        build()
    }

    /** Every tick: five points of liquid experience pour out at `spot` as orbs, then a long pause. */
    fun xpDrop(): Graph = B("xp-drop").run {
        val tick = node(BuiltinNodes.ENTRY_TICK, x = 40f, y = 80f)
        val drop = node("xp.drop", "Link" to "spot", "Points" to 5L, x = 300f, y = 80f)
        val wait = node("controller.wait", "Ticks" to 100L, x = 560f, y = 80f)
        exec(tick, drop)
        exec(drop, wait)
        build()
    }

    /** Every tick: whatever is in the buffer is thrown on the ground at `spot`, then a long pause. */
    fun dropItems(): Graph = B("drop-items").run {
        val tick = node(BuiltinNodes.ENTRY_TICK, x = 40f, y = 80f)
        val drop = node("items.drop", "Link" to "spot", x = 300f, y = 80f)
        val wait = node("controller.wait", "Ticks" to 100L, x = 560f, y = 80f)
        exec(tick, drop)
        exec(drop, wait)
        build()
    }

    /** Every tick: a bucket's worth from the tanks becomes a source block at `spot`, then a long pause. */
    fun placeFluid(): Graph = B("place-fluid").run {
        val tick = node(BuiltinNodes.ENTRY_TICK, x = 40f, y = 80f)
        val place = node("fluids.place", "Link" to "spot", x = 300f, y = 80f)
        val wait = node("controller.wait", "Ticks" to 100L, x = 560f, y = 80f)
        exec(tick, place)
        exec(place, wait)
        build()
    }

    /** Every tick: the source block at `spot` goes into the tanks, then a long pause. */
    fun pickupFluid(): Graph = B("pickup-fluid").run {
        val tick = node(BuiltinNodes.ENTRY_TICK, x = 40f, y = 80f)
        val pick = node("fluids.pickup", "Link" to "spot", x = 300f, y = 80f)
        val wait = node("controller.wait", "Ticks" to 100L, x = 560f, y = 80f)
        exec(tick, pick)
        exec(pick, wait)
        build()
    }

    /**
     * Every tick: break `stone` with the first *undamaged pickaxe* in the buffer, wherever it sits — the
     * slot comes from `items.find` over a composed filter (`allOf(tag pickaxes, not(damaged))`) — vacuum
     * the drops, push the cobblestone to `chest`.
     */
    fun toolFromTag(): Graph = B("tool-from-tag").run {
        val tick = node(BuiltinNodes.ENTRY_TICK, x = 40f, y = 80f)
        val pick = node("items.filter", "tag" to "minecraft:pickaxes", x = 40f, y = 260f)
        val dmg = node("items.filter", "damaged" to true, x = 40f, y = 420f)
        val notDmg = node("items.not", x = 300f, y = 420f)
        val both = node("items.allOf", x = 560f, y = 300f)
        val find = node("items.find", "Link" to "self", x = 820f, y = 300f)
        val brk = node("world.breakBlock", "Link" to "stone", x = 1080f, y = 80f)
        val vac = node("world.vacuum", "Link" to "stone", "Radius" to 2.0, x = 1340f, y = 80f)
        val wait = node("controller.wait", "Ticks" to 20L, x = 1600f, y = 80f)
        val cobble = node("items.filter", "item" to "minecraft:cobblestone", x = 1600f, y = 260f)
        val push = node("items.move", "From" to "self", "To" to "chest", "Max" to 64L, x = 1860f, y = 80f)
        exec(tick, brk)
        exec(brk, vac)
        exec(vac, wait)
        exec(wait, push)
        data(dmg, "Filter", notDmg, "Of")
        data(pick, "Filter", both, "A")
        data(notDmg, "Filter", both, "B")
        data(both, "Filter", find, "Filter")
        data(find, "Slot", brk, "Tool")
        data(cobble, "Filter", push, "Filter")
        build()
    }

    /**
     * Every tick: every block in the box between two corners is broken bare-handed — `world.area` lists
     * the positions, For Each walks them, `controller.linkAt` makes a link of each on the spot.
     */
    fun areaMiner(from: String = "0,64,0", to: String = "2,64,2"): Graph = B("area-miner").run {
        val tick = node(BuiltinNodes.ENTRY_TICK, x = 40f, y = 80f)
        val area = node("world.area", "From" to from, "To" to to, x = 40f, y = 260f)
        val each = node(BuiltinNodes.FOR_EACH, x = 300f, y = 80f)
        val at = node("controller.linkAt", x = 560f, y = 260f)
        val brk = node("world.breakBlock", x = 820f, y = 80f)
        val wait = node("controller.wait", "Ticks" to 20L, x = 1080f, y = 80f)
        exec(tick, each)
        exec(each, brk, fromPin = "Body")
        exec(each, wait, fromPin = "Completed")
        data(area, "Positions", each, "List")
        data(each, "Element", at, "Pos")
        data(at, "Link", brk, "Link")
        build()
    }

    fun write(force: Boolean = false): List<String> {
        val dir = FMLPaths.GAMEDIR.get().resolve("bpm").resolve("graphs")
        Files.createDirectories(dir)
        val validator = Validator(BpmCatalog.catalog, tickMayWait = true)
        val out = ArrayList<String>()
        for (graph in listOf(move(), mine(), redstone(), logger(), diamondMiner(), xpVacuum(), xpDrop(), dropItems(), placeFluid(), pickupFluid(), toolFromTag(), areaMiner(), guard(), dumpButTool())) {
            val issues = validator.validate(graph)
            val errors = issues.filter { it.severity == Severity.ERROR }
            val file = dir.resolve("${graph.name}.json")
            if (force || !Files.exists(file)) Files.writeString(file, GraphDoc.toJson(graph))
            val line = "${graph.name}: ${graph.nodes.size} nodes, ${errors.size} errors" + errors.joinToString("") { "\n    ${it.message} (node ${it.nodeId})" }
            out += line
            if (errors.isNotEmpty()) Bpm.LOGGER.warn("sample document {}", line) else Bpm.LOGGER.info("sample document {} -> {}", line, file)
        }
        return out
    }
}
