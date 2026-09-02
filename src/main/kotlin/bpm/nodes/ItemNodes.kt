package bpm.nodes

import bpm.catalog.McVs
import bpm.catalog.values.FilterValue
import bpm.catalog.values.ItemStackValue
import bpm.catalog.values.RegistryIds
import bpm.catalog.values.SlotValue
import bpm.runtime.PredicateJob
import com.mojang.serialization.JsonOps
import dev.ziggle.vscript.json.Json
import dev.ziggle.vscript.nodes.Contribution
import dev.ziggle.vscript.nodes.library
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.RegistryOps
import net.minecraft.world.item.ItemStack
import bpm.platform.keyId

/** `items.*` — inventories reached through links, and what is in a stack. */
object ItemNodes {

    /** A Max pin as a real limit: 0 means no limit, because "move nothing" is never what anyone asked for. */
    private fun limit(max: Long): Int = if (max <= 0L) Int.MAX_VALUE else max.toInt()

    fun contribution(host: ControllerHost): Contribution = library("items", "Items") {

        // ---- what a link holds -------------------------------------------------------------------

        func("filter") {
            title("Filter")
            doc(
                """
                A filter for the item verbs. Leave a field empty to not care about it: an item, a tag, a
                minimum and a maximum count, an enchantment (by id, at least a level), a data component (by
                id), a display name fragment, damaged or not, or a vanilla item predicate as JSON — the
                game's own filter language, for everything else.
                """,
            )
            spelledAs("filter")
            param("item", McVs.item.orNull(), "only this item")
            param("tag", McVs.tag.orNull(), "only items in this tag")
            param("min", McVs.int, "at least this many (Has, Wait For); 0 = any", default = 0L)
            param("max", McVs.int, "at most this many per move; 0 = no cap", default = 0L)
            param("enchant", McVs.string.orNull(), "only stacks with this enchantment, by id")
            param("level", McVs.int, "…at least this level; 0 = any", default = 0L)
            param("component", McVs.string.orNull(), "only stacks with this data component, by id")
            param("name", McVs.string.orNull(), "only stacks whose name contains this")
            param("damaged", McVs.bool.orNull(), "only damaged (true) or undamaged (false) stacks")
            param("predicate", McVs.string.orNull(), "a vanilla item predicate, as JSON")
            result("Filter", McVs.filter)
            construct()
        }
        func("anyOf") {
            title("Any Of")
            doc("A filter admitting what any of these admit — pickaxes or axes, say. Empty pins are skipped; `min` and `max` come from A.")
            val a = param("A", McVs.filter, "one filter")
            val b = param("B", McVs.filter.orNull(), "another")
            val c = param("C", McVs.filter.orNull(), "another")
            val d = param("D", McVs.filter.orNull(), "another")
            result("Filter", McVs.filter)
            query { FilterValue.anyOf(listOfNotNull(a(), b(), c(), d())) }
        }
        func("allOf") {
            title("All Of")
            doc("A filter admitting only what all of these admit — in a tag and not damaged, say. Empty pins are skipped; `min` and `max` come from A.")
            val a = param("A", McVs.filter, "one filter")
            val b = param("B", McVs.filter.orNull(), "another")
            val c = param("C", McVs.filter.orNull(), "another")
            val d = param("D", McVs.filter.orNull(), "another")
            result("Filter", McVs.filter)
            query { FilterValue.allOf(listOfNotNull(a(), b(), c(), d())) }
        }
        func("not") {
            title("Not")
            doc("A filter admitting what this one refuses — everything but diamonds, say.")
            val f = param("Of", McVs.filter, "what to refuse")
            result("Filter", McVs.filter)
            query { FilterValue.not(f()) }
        }
        func("count") {
            title("Item Count")
            doc("How many items a link holds — all of them, or only those a filter admits. 0 when the link is missing or unloaded.")
            val link = param("Link", McVs.link, "which inventory")
            val filter = param("Filter", McVs.filter.orNull(), "only these; empty for everything")
            result("Count", McVs.int)
            query { host.items(link())?.let { Transfer.count(it, host.matcher(filter())).toLong() } ?: 0L }
        }
        func("list") {
            title("List Items")
            doc("Every non-empty stack a link holds, as snapshots.")
            val link = param("Link", McVs.link, "which inventory")
            val filter = param("Filter", McVs.filter.orNull(), "only these; empty for everything")
            result("Stacks", McVs.itemStack.list())
            query { host.items(link())?.let { h -> Transfer.stacks(h, host.matcher(filter())).map(ItemStackValue::record) } ?: emptyList<Any?>() }
        }
        func("listSlots") {
            title("List Slots")
            doc("Every non-empty slot of a link, with its number — for deciding stack by stack and then acting on a slot.")
            val link = param("Link", McVs.link, "which inventory")
            val filter = param("Filter", McVs.filter.orNull(), "only these; empty for everything")
            result("Slots", McVs.slot.list())
            query {
                val h = host.items(link()) ?: return@query emptyList<Any?>()
                val m = host.matcher(filter())
                (0 until h.slots).mapNotNull { i -> h.stackIn(i).takeIf { m.matches(it) }?.let { SlotValue.of(i, it) } }
            }
        }
        func("find") {
            title("Find Slot")
            doc(
                """
                The first slot of a link holding something a filter admits — the pickaxe in the buffer for
                a `Tool` pin, say. Works on any inventory. `Slot` is -1 when nothing matches, which the
                world verbs read as a bare hand.
                """,
            )
            val link = param("Link", McVs.link, "which inventory")
            val filter = param("Filter", McVs.filter.orNull(), "only these; empty for the first non-empty slot")
            val found = result("Found", McVs.bool)
            val slot = result("Slot", McVs.int)
            val stack = result("Stack", McVs.itemStack.orNull())
            query {
                found set false
                slot set -1L
                stack set null
                val h = host.items(link()) ?: return@query null
                val m = host.matcher(filter())
                for (i in 0 until h.slots) {
                    val s = h.stackIn(i)
                    if (!m.matches(s)) continue
                    found set true
                    slot set i.toLong()
                    stack set ItemStackValue.record(s)
                    return@query null
                }
            }
        }
        func("has") {
            title("Has Items")
            doc("Whether a link holds at least the filter's `min` matching items (at least one, when `min` is 0).")
            val link = param("Link", McVs.link, "which inventory")
            val filter = param("Filter", McVs.filter.orNull(), "what to look for")
            result("Has", McVs.bool)
            query {
                val h = host.items(link()) ?: return@query false
                val f = filter()
                Transfer.count(h, host.matcher(f)) >= FilterValue.min(f).coerceAtLeast(1)
            }
        }
        func("slots") {
            title("Slot Count")
            doc("How many slots a link's inventory has. 0 when it has none.")
            val link = param("Link", McVs.link, "which inventory")
            result("Slots", McVs.int)
            query { (host.items(link())?.slots ?: 0).toLong() }
        }
        func("at") {
            title("Stack At")
            doc("The stack in one slot, or nothing when the slot is empty.")
            val link = param("Link", McVs.link, "which inventory")
            val slot = param("Slot", McVs.int, "0-based", default = 0L)
            result("Stack", McVs.itemStack.orNull())
            query {
                val h = host.items(link()) ?: return@query null
                val i = slot().toInt()
                if (i < 0 || i >= h.slots) null else ItemStackValue.record(h.stackIn(i))
            }
        }
        func("canInsert") {
            title("Can Insert")
            doc("Whether a link would accept the whole stack right now.")
            val to = param("To", McVs.link, "where to")
            val stack = param("Stack", McVs.itemStack, "what to insert")
            result("Can", McVs.bool)
            query {
                val h = host.items(to()) ?: return@query false
                Transfer.insert(h, ItemStackValue.stack(stack()), true).isEmpty
            }
        }

        // ---- moving things -----------------------------------------------------------------------

        func("move") {
            title("Move Items")
            doc(
                """
                Move up to Max matching items from one link to another. Answers how many moved — 0 when
                either side is missing, unloaded or full.

                **Max 0 means everything.** One call moves at most Max items, so the default of 64 empties a
                stack a tick and a full inventory takes a while; 0 is the way to say "all of it, now".
                """,
            )
            val from = param("From", McVs.link, "where from")
            val to = param("To", McVs.link, "where to")
            val filter = param("Filter", McVs.filter.orNull(), "only these; empty for anything")
            val max = param("Max", McVs.int, "at most this many; 0 for no limit", default = 64L)
            val fx = param("Effects", McVs.bool, "draw the rift and what travels through it", default = true)
            result("Moved", McVs.int)
            command {
                val a = host.items(from()) ?: return@command 0L
                val b = host.items(to()) ?: return@command 0L
                val m = host.matcher(filter())
                val moved = Transfer.items(a, b, m, limit(max()))
                if (moved.count > 0 && fx()) host.transferred(from(), to(), moved.count, item = moved.name)
                moved.count.toLong()
            }
        }
        func("moveSlot") {
            title("Move Slot")
            doc("Move up to Max items out of one slot of a link into another link. Answers how many moved.")
            val from = param("From", McVs.link, "where from")
            val slot = param("Slot", McVs.int, "which slot, 0-based")
            val to = param("To", McVs.link, "where to")
            val max = param("Max", McVs.int, "at most this many", default = 64L)
            val fx = param("Effects", McVs.bool, "draw the rift and what travels through it", default = true)
            result("Moved", McVs.int)
            command {
                val a = host.items(from()) ?: return@command 0L
                val b = host.items(to()) ?: return@command 0L
                val s = slot().toInt()
                val moved = Transfer.moveSlot(a, s, b, limit(max()))
                if (moved.count > 0 && fx()) host.transferred(from(), to(), moved.count, item = moved.name)
                moved.count.toLong()
            }
        }
        func("insert") {
            title("Insert Stack")
            doc("Put a stack into a link. Answers what did not fit, or nothing when it all did.")
            val to = param("To", McVs.link, "where to")
            val stack = param("Stack", McVs.itemStack, "what to insert")
            val simulate = param("Simulate", McVs.bool, "only ask whether it would fit", default = false)
            result("Left", McVs.itemStack.orNull())
            command {
                val h = host.items(to()) ?: return@command stack()
                ItemStackValue.record(Transfer.insert(h, ItemStackValue.stack(stack()), simulate()))
            }
        }
        func("extract") {
            title("Extract Stack")
            doc("Take up to Max matching items out of a link as one stack. Nothing when there is nothing to take.")
            val from = param("From", McVs.link, "where from")
            val filter = param("Filter", McVs.filter.orNull(), "only these; empty for anything")
            val max = param("Max", McVs.int, "at most this many", default = 64L)
            val simulate = param("Simulate", McVs.bool, "only ask what would come out", default = false)
            result("Stack", McVs.itemStack.orNull())
            command {
                val h = host.items(from()) ?: return@command null
                ItemStackValue.record(Transfer.extract(h, host.matcher(filter()), max().toInt().coerceAtLeast(1), simulate()))
            }
        }
        func("extractAt") {
            title("Extract At")
            doc("Take up to Max items out of one slot of a link. Nothing when the slot is empty.")
            val from = param("From", McVs.link, "where from")
            val slot = param("Slot", McVs.int, "which slot, 0-based")
            val max = param("Max", McVs.int, "at most this many", default = 64L)
            val simulate = param("Simulate", McVs.bool, "only ask what would come out", default = false)
            result("Stack", McVs.itemStack.orNull())
            command {
                val h = host.items(from()) ?: return@command null
                val i = slot().toInt()
                if (i < 0 || i >= h.slots) null else ItemStackValue.record(h.extract(i, max().toInt().coerceAtLeast(1), simulate()))
            }
        }
        func("drop") {
            title("Drop Items")
            doc(
                """
                Let items out onto the ground at a link — released from the same spot the animation reaches to,
                and dropped straight down. Answers how many items went out.

                From is where they come from and Link is where they land, both defaulting to `self`: the
                controller's own buffer, and the ground beneath it. Any link can be either end, so a graph can
                empty a chest — or a person — onto the floor somewhere else entirely.

                **Max 0 means everything**, as it does for Move Items.
                """,
            )
            val link = param("Link", McVs.link, "where they land", default = ControllerHost.SELF)
            val filter = param("Filter", McVs.filter.orNull(), "only these; empty for anything")
            val max = param("Max", McVs.int, "at most this many items; 0 for no limit", default = 64L)
            val from = param("From", McVs.link, "where they come from", default = ControllerHost.SELF)
            val fx = param("Effects", McVs.bool, "draw the rift and what travels through it", default = true)
            result("Dropped", McVs.int)
            command {
                val at = riftPointOf(host, link()) ?: return@command 0L
                val m = host.matcher(filter())
                val inv = host.items(from()) ?: return@command 0L
                var left = limit(max())
                var dropped = 0
                var first = ""
                for (slot in 0 until inv.slots) {
                    if (left <= 0) break
                    if (!m.matches(inv.stackIn(slot))) continue
                    val out = inv.extract(slot, left, false)
                    if (out.isEmpty) continue
                    // Counted BEFORE the throw: `dropItemStack` splits the stack in place as it spawns the
                    // entities, so afterwards it is empty and would count for nothing.
                    val n = out.count
                    if (first.isEmpty()) first = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(out.item).toString()
                    // Held back until the drawn item has finished its flight, or the real one exists while
                    // its own animation is still in the air and you see two of it.
                    host.jobs.start(ReleaseJob(host.level, at, out))
                    dropped += n
                    left -= n
                }
                // DROP rather than ITEMS: the client draws the outgoing leg and stops, because what arrives
                // at the far end is the entity itself rather than a picture of one.
                if (dropped > 0 && fx()) host.transferred(from(), link(), dropped, kind = bpm.net.EffectKind.DROP, item = first)
                dropped.toLong()
            }
        }
        func("waitFor") {
            title("Wait For Items")
            doc("Park until a link holds at least Min matching items, or the timeout passes. Ok says which.")
            val link = param("Link", McVs.link, "which inventory")
            val filter = param("Filter", McVs.filter.orNull(), "what to wait for")
            val min = param("Min", McVs.int, "how many", default = 1L)
            val timeout = param("Timeout Ticks", McVs.int, "give up after this many ticks; 0 = never", default = 200L)
            result("Ok", McVs.bool)
            action {
                val name = link(); val m = host.matcher(filter()); val n = min().toInt()
                host.jobs.start(PredicateJob("items.waitFor", timeout().toInt()) { host.items(name)?.let { Transfer.count(it, m) >= n } ?: false })
            }
        }

        // ---- what is IN a stack ------------------------------------------------------------------

        func("matches") {
            title("Stack Matches")
            doc("Whether a stack is what a filter asks for — every field of the filter, the predicate included.")
            val stack = param("Stack", McVs.itemStack, "the stack")
            val filter = param("Filter", McVs.filter.orNull(), "the filter; empty matches anything")
            result("Matches", McVs.bool)
            query { host.matcher(filter()).matches(ItemStackValue.stack(stack())) }
        }
        func("matchesPredicate") {
            title("Matches Predicate")
            doc(
                """
                Whether a stack passes a vanilla item predicate, written as JSON — the same thing an advancement
                or a loot table uses, with every sub-predicate the game has: enchantments, damage, custom
                data, potion contents, trims, and whatever other mods add.
                """,
            )
            val stack = param("Stack", McVs.itemStack, "the stack")
            val predicate = param("Predicate", McVs.string, "the predicate as JSON")
            result("Matches", McVs.bool)
            query { FilterValue.predicate(predicate(), host.registries)?.let { bpm.platform.testItemPredicate(it, ItemStackValue.stack(stack())) } ?: false }
        }
        func("item") {
            title("Item By Id")
            doc("An item from its registry id, such as `minecraft:coal`. Nothing when there is no such item.")
            val id = param("Id", McVs.string, "the registry id")
            result("Item", McVs.item.orNull())
            query { RegistryIds.item(id())?.let(RegistryIds::of) }
        }
        func("inTag") {
            title("Item In Tag")
            doc("Whether an item is in a tag, such as `c:ingots/iron`.")
            val item = param("Item", McVs.item, "the item")
            val tag = param("Tag", McVs.tag, "the tag, without the #")
            result("In", McVs.bool)
            query {
                val it = RegistryIds.item(item()) ?: return@query false
                val key = RegistryIds.itemTag(tag()) ?: return@query false
                it.builtInRegistryHolder().`is`(key)
            }
        }
        func("displayName") {
            title("Display Name")
            doc("What a stack is called when you hover it — its custom name when it has one.")
            val stack = param("Stack", McVs.itemStack, "the stack")
            result("Name", McVs.string)
            query { ItemStackValue.stack(stack()).hoverName.string }
        }
        func("damage") {
            title("Damage")
            doc("How much damage a stack has taken; 0 for a stack that cannot be damaged. Max Damage is the whole bar.")
            val stack = param("Stack", McVs.itemStack, "the stack")
            result("Damage", McVs.int)
            query { ItemStackValue.stack(stack()).damageValue.toLong() }
        }
        func("maxDamage") {
            title("Max Damage")
            doc("How much damage a stack can take before it breaks; 0 for a stack that cannot be damaged.")
            val stack = param("Stack", McVs.itemStack, "the stack")
            result("Max", McVs.int)
            query { ItemStackValue.stack(stack()).let { if (it.isDamageableItem) it.maxDamage.toLong() else 0L } }
        }
        func("enchantments") {
            title("Enchantments")
            doc("Every enchantment on a stack, by id, with its level.")
            val stack = param("Stack", McVs.itemStack, "the stack")
            result("Levels", McVs.stringIntMap)
            query {
                val out = LinkedHashMap<Any?, Any?>()
                for ((id, level) in bpm.platform.enchantmentsOf(ItemStackValue.stack(stack()))) {
                    out[id] = level.toLong()
                }
                out
            }
        }
        func("enchantment") {
            title("Enchantment Level")
            doc("The level of one enchantment on a stack, or 0 when it is not there.")
            val stack = param("Stack", McVs.itemStack, "the stack")
            val id = param("Enchantment", McVs.string, "such as `minecraft:sharpness`")
            result("Level", McVs.int)
            query {
                val holder = FilterValue.enchantment(id(), host.registries) ?: return@query 0L
                bpm.platform.enchantmentLevel(ItemStackValue.stack(stack()), holder).toLong()
            }
        }
        func("components") {
            title("Component Ids")
            doc("Every data component a stack carries, by id — `minecraft:enchantments`, `minecraft:custom_data`, and so on.")
            val stack = param("Stack", McVs.itemStack, "the stack")
            result("Ids", McVs.string.list())
            query { bpm.platform.vanillaComponentIds(ItemStackValue.stack(stack())) }
        }
        func("hasComponent") {
            title("Has Component")
            doc("Whether a stack carries a data component, by id.")
            val stack = param("Stack", McVs.itemStack, "the stack")
            val id = param("Component", McVs.string, "such as `minecraft:custom_data`")
            result("Has", McVs.bool)
            query { bpm.platform.hasVanillaComponent(ItemStackValue.stack(stack()), FilterValue.componentType(id())) }
        }
        func("component") {
            title("Component")
            doc(
                """
                One data component of a stack as JSON — read it as your own record with `as`, or walk it as a
                map. `minecraft:custom_data` is where a mod's NBT lives; `minecraft:enchantments` is
                `{ levels: { "minecraft:sharpness": 3 } }`. Nothing when the stack has no such component.
                """,
            )
            val stack = param("Stack", McVs.itemStack, "the stack")
            val id = param("Component", McVs.string, "which component")
            result("Value", McVs.json.orNull())
            query {
                val type = FilterValue.componentType(id()) ?: return@query null
                componentJson(host, ItemStackValue.stack(stack()), type)
            }
        }
    }

    private fun componentJson(host: ControllerHost, stack: ItemStack, type: Any?): Any? {
        val text = bpm.platform.vanillaComponentJson(host.registries, stack, type) ?: return null
        return Json.parse(text)
    }
}

/**
 * The mouth of a link's rift — where `items.drop` puts a stack into the world, and where `world.vacuum`
 * pulls one to before it is swallowed.
 *
 * A link with no face of its own, and the controller itself, use the point above rather than out of a side.
 */
internal fun riftPointOf(host: ControllerHost, link: String): net.minecraft.world.phys.Vec3? {
    if (link == ControllerHost.SELF || link.isEmpty()) return bpm.world.LinkAnchors.selfHand(host.pos)
    val r = host.link(link) ?: return null
    if (!r.loaded) return null
    // Out of the RIFT, not off the hand: with no drawn arrival, the entity itself is what emerges, so it
    // has to appear where the portal hangs rather than where a hand would have set it down.
    return bpm.world.LinkAnchors.offFace(r.link.pos, r.link.side)
}

/**
 * Hold a stack until its rift is ready to produce it, then put it in the world.
 *
 * Started fire-and-forget: the node ignores the await, so the fiber never parks and the graph carries on
 * while the item is still notionally in flight. The stack has already left the controller's inventory by
 * this point, so [cancel] must still put it somewhere — a stop or a redeploy mid-flight would otherwise
 * delete it.
 */
internal class ReleaseJob(
    private val level: net.minecraft.world.level.Level,
    private val at: net.minecraft.world.phys.Vec3,
    private val stack: net.minecraft.world.item.ItemStack,
) : bpm.runtime.TickJob("items.release") {

    private var wait = bpm.world.EffectTiming.EMERGE_TICKS

    override fun advance(): Boolean {
        if (--wait > 0) return false
        releaseStack(level, at, stack)
        finish(null)
        return true
    }

    /** The graph stopped while this was in the air. The items are real; put them down rather than lose them. */
    override fun cancel() {
        releaseStack(level, at, stack)
    }
}

/**
 * Put [stack] in the world so that it LOOKS like it is at [at], and do nothing else.
 *
 * `Containers.dropItemStack` is what this used to call, and it is built for a broken chest: it scatters the
 * stack inside the block on all three axes and throws it with a random velocity. Both are wrong for a
 * controller placing something deliberately.
 *
 * [at] is where the item should appear, not where the entity goes — see
 * [bpm.world.LinkAnchors.ITEM_RENDER_LIFT] for why those differ by about a quarter of a block, and why the
 * correction is vertical no matter which way the link faces.
 */
internal fun releaseStack(level: net.minecraft.world.level.Level, at: net.minecraft.world.phys.Vec3, stack: net.minecraft.world.item.ItemStack) {
    if (stack.isEmpty) return
    val p = entityPointFor(at)
    val entity = net.minecraft.world.entity.item.ItemEntity(level, p.x, p.y, p.z, stack, 0.0, 0.0, 0.0)
    entity.setDefaultPickUpDelay()
    level.addFreshEntity(entity)
}

/** Where to PUT an item entity so that it LOOKS like it is at [visual]. See [bpm.world.LinkAnchors.ITEM_RENDER_LIFT]. */
internal fun entityPointFor(visual: net.minecraft.world.phys.Vec3): net.minecraft.world.phys.Vec3 =
    visual.subtract(0.0, bpm.world.LinkAnchors.ITEM_RENDER_LIFT, 0.0)

