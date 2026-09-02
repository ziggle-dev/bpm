package bpm.nodes

import bpm.catalog.values.ItemStackValue

import bpm.platform.ports.insertStacked

import bpm.catalog.McTypes
import bpm.catalog.McVs
import bpm.catalog.values.BlockPosValue
import bpm.catalog.values.EntityHandle
import bpm.runtime.BreakBlockJob
import bpm.runtime.ClickJob
import bpm.runtime.UseItemJob
import dev.ziggle.vscript.nodes.Contribution
import dev.ziggle.vscript.nodes.library
import net.minecraft.core.particles.SimpleParticleType
import net.minecraft.core.registries.BuiltInRegistries
import bpm.platform.ResourceLocation
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.sqrt
import bpm.platform.keyId

/** `world.*` — reading and acting on the world around the controller. */
object WorldNodes {
    /** How many positions `world.area` will list — a 16×16×16 cube. */
    const val MAX_AREA = 4096

    fun contribution(host: ControllerHost): Contribution = library("world", "World") {
        func("blockAt") {
            title("Block At")
            doc("The block at a position, with its properties. Nothing when the chunk is not loaded.")
            val pos = param("Pos", McVs.blockPos, "where")
            result("State", McVs.blockState.orNull())
            query { BlockPosValue.toBlockPos(pos())?.takeIf { host.level.hasChunkAt(it) }?.let { host.level.getBlockState(it) } }
        }
        func("blockInfo") {
            title("Block Info")
            doc(
                """
                A block state taken apart: what block it is (a registry id), whether it is air, and every
                property as text — `lit` → `true`, `facing` → `north`, `age` → `7` — as a map for Map At.
                `Property` reads one by name; `Is Block` asks for a kind. Nothing useful for a missing state.
                """,
            )
            val state = param("State", McVs.blockState.orNull(), "from Block At or Block Of Link")
            val block = result("Block", McVs.block)
            val isAir = result("IsAir", McVs.bool)
            val properties = result("Properties", McVs.stringMap)
            query {
                val s = state()
                block set (s?.block?.let { bpm.catalog.values.RegistryIds.of(it) } ?: "")
                isAir set (s?.isAir ?: true)
                properties set McTypes.propertiesOf(s)
                null
            }
        }
        func("property") {
            title("Block Property")
            doc("One property of a block state, as text — `lit`, `powered`, `facing`, `age`, `level`… `Has` says whether the block has such a property at all; `Value` is empty when it does not.")
            val state = param("State", McVs.blockState.orNull(), "from Block At or Block Of Link")
            val name = param("Name", McVs.string, "the property's name")
            val value = result("Value", McVs.string)
            val has = result("Has", McVs.bool)
            query {
                val v = McTypes.propertiesOf(state())[name().trim()]
                value set (v ?: "")
                has set (v != null)
                null
            }
        }
        func("isBlock") {
            title("Is Block")
            doc("Whether a block state is a kind of block — `minecraft:redstone_lamp`, say.")
            val state = param("State", McVs.blockState.orNull(), "from Block At or Block Of Link")
            val block = param("Block", McVs.block, "which kind")
            result("Is", McVs.bool)
            query {
                val s = state() ?: return@query false
                val want = bpm.catalog.values.RegistryIds.block(block()) ?: return@query false
                s.`is`(want)
            }
        }
        func("blockMatches") {
            title("Block Matches Filter")
            doc(
                """
                Whether a block state passes a filter: its id as `item` (`minecraft:iron_ore`), a block tag
                or its item form's tag as `tag` (`c:ores`, `minecraft:logs`), its name, and Any Of / All Of /
                Not as usual. The stack-only questions (enchantment, component, damaged, predicate) do not
                apply. Air never matches; an empty filter admits any block.
                """,
            )
            val state = param("State", McVs.blockState.orNull(), "from Block At or Block Of Link")
            val filter = param("Filter", McVs.filter.orNull(), "which blocks; empty for any but air")
            result("Matches", McVs.bool)
            query {
                val s = state() ?: return@query false
                host.blockMatcher(filter()).matches(s)
            }
        }
        func("blocksIn") {
            title("Blocks In Area")
            doc("Every position in the box between two corners whose block passes a filter (air never does) — the ores in a quarry, say — top layer first, at most 4096. Count says how many.")
            val from = param("From", McVs.blockPos, "one corner")
            val to = param("To", McVs.blockPos, "the other corner")
            val filter = param("Filter", McVs.filter.orNull(), "which blocks; empty for any but air")
            val positions = result("Positions", McVs.blockPos.list())
            val count = result("Count", McVs.int)
            query {
                positions set emptyList<dev.ziggle.vscript.vm.StructValue?>()
                count set 0L
                val a = BlockPosValue.toBlockPos(from()) ?: return@query null
                val b = BlockPosValue.toBlockPos(to()) ?: return@query null
                val m = host.blockMatcher(filter())
                val out = ArrayList<dev.ziggle.vscript.vm.StructValue?>()
                scan@ for (y in maxOf(a.y, b.y) downTo minOf(a.y, b.y)) {
                    for (z in minOf(a.z, b.z)..maxOf(a.z, b.z)) {
                        for (x in minOf(a.x, b.x)..maxOf(a.x, b.x)) {
                            val p = net.minecraft.core.BlockPos(x, y, z)
                            if (!host.level.hasChunkAt(p) || !m.matches(host.level.getBlockState(p))) continue
                            out += BlockPosValue.of(p)
                            if (out.size >= MAX_AREA) break@scan
                        }
                    }
                }
                positions set out
                count set out.size.toLong()
                null
            }
        }
        func("blockOf") {
            title("Block Of Link")
            doc("The block a link points at. Nothing when the link is missing or unloaded.")
            val link = param("Link", McVs.link, "which link")
            result("State", McVs.blockState.orNull())
            query { host.link(link())?.takeIf { it.loaded }?.let { host.level.getBlockState(it.link.pos) } }
        }
        func("time") {
            title("Time Of Day")
            doc("The time of day in ticks, 0 to 23999. Day starts at 0, night around 13000.")
            result("Time", McVs.int)
            query { bpm.platform.dayTime(host.level) % 24000L }
        }
        func("day") {
            title("Day Number")
            doc("How many days the world has seen.")
            result("Day", McVs.int)
            query { bpm.platform.dayTime(host.level) / 24000L }
        }
        func("isDay") {
            title("Is Day")
            doc("Whether it is daytime.")
            result("Day", McVs.bool)
            query { bpm.platform.isDaytime(host.level) }
        }
        func("isRaining") {
            title("Is Raining")
            result("Raining", McVs.bool)
            query { host.level.isRaining }
        }
        func("isThundering") {
            title("Is Thundering")
            result("Thundering", McVs.bool)
            query { host.level.isThundering }
        }
        func("biome") {
            title("Biome At")
            doc("The biome at a position, as its id.")
            val pos = param("Pos", McVs.blockPos, "where")
            result("Biome", McVs.string)
            query {
                val p = BlockPosValue.toBlockPos(pos()) ?: return@query ""
                host.level.getBiome(p).unwrapKey().map { it.keyId().toString() }.orElse("")
            }
        }
        func("lightAt") {
            title("Light At")
            doc("The light level at a position, 0 to 15, sky and blocks together.")
            val pos = param("Pos", McVs.blockPos, "where")
            result("Light", McVs.int)
            query { BlockPosValue.toBlockPos(pos())?.takeIf { host.level.hasChunkAt(it) }?.let { host.level.getMaxLocalRawBrightness(it).toLong() } ?: 0L }
        }
        func("distance") {
            title("Distance")
            doc("The straight-line distance between two positions.")
            val a = param("A", McVs.blockPos, "one")
            val b = param("B", McVs.blockPos, "the other")
            result("Distance", McVs.float)
            query {
                val p = BlockPosValue.toBlockPos(a()) ?: return@query 0.0
                val q = BlockPosValue.toBlockPos(b()) ?: return@query 0.0
                sqrt(p.distSqr(q).toDouble())
            }
        }
        func("offset") {
            title("Offset Position")
            doc("A position moved N blocks along a face.")
            val pos = param("Pos", McVs.blockPos, "from where")
            val side = param("Side", McVs.direction, "which way")
            val n = param("N", McVs.int, "how far", default = 1L)
            result("Pos", McVs.blockPos)
            query {
                val p = BlockPosValue.toBlockPos(pos()) ?: return@query null
                val d = McTypes.direction(side()) ?: return@query BlockPosValue.of(p)
                BlockPosValue.of(p.relative(d, n().toInt()))
            }
        }
        func("area") {
            title("Area")
            doc("Every position in the box between two corners, both included, top layer first — For Each over it with Link At works a whole area. At most 4096 positions.")
            val from = param("From", McVs.blockPos, "one corner")
            val to = param("To", McVs.blockPos, "the other corner")
            result("Positions", McVs.blockPos.list())
            query {
                val a = BlockPosValue.toBlockPos(from()) ?: return@query emptyList<Any?>()
                val b = BlockPosValue.toBlockPos(to()) ?: return@query emptyList<Any?>()
                val out = ArrayList<Any?>()
                for (y in maxOf(a.y, b.y) downTo minOf(a.y, b.y)) {
                    for (z in minOf(a.z, b.z)..maxOf(a.z, b.z)) {
                        for (x in minOf(a.x, b.x)..maxOf(a.x, b.x)) {
                            if (out.size >= MAX_AREA) return@query out
                            out += BlockPosValue.of(x, y, z)
                        }
                    }
                }
                out
            }
        }
        func("entityInfo") {
            title("Entity Info")
            doc("About a live thing: the block it stands in, its name, its kind (a registry id such as `minecraft:zombie`), its hit points, and whether it is a player. Empty answers when it is gone.")
            val e = param("Entity", McVs.entity, "which")
            val pos = result("Pos", McVs.blockPos.orNull())
            val name = result("Name", McVs.string)
            val type = result("Type", McVs.string)
            val health = result("Health", McVs.float)
            val isPlayer = result("IsPlayer", McVs.bool)
            query {
                pos set null
                name set ""
                type set ""
                health set 0.0
                isPlayer set false
                val ent = host.entity(e()) ?: return@query null
                pos set BlockPosValue.of(ent.blockPosition())
                name set ent.name.string
                type set BuiltInRegistries.ENTITY_TYPE.getKey(ent.type).toString()
                health set ((ent as? net.minecraft.world.entity.LivingEntity)?.health?.toDouble() ?: 0.0)
                isPlayer set (ent is Player)
                null
            }
        }
        func("entitiesIn") {
            title("Entities In Area")
            doc("Every entity inside the box between two corners (both corners included), optionally of one kind (`minecraft:zombie`). Anything whose body overlaps the box counts.")
            val from = param("From", McVs.blockPos, "one corner")
            val to = param("To", McVs.blockPos, "the other corner")
            val type = param("Type", McVs.string, "an entity id; empty for any", default = "")
            result("Entities", McVs.entity.list())
            query { inBox(host, from(), to(), type()).map(EntityHandle::of) }
        }
        func("countIn") {
            title("Count In Area")
            doc("How many entities are inside the box between two corners, optionally of one kind — `Entities In Area`, counted.")
            val from = param("From", McVs.blockPos, "one corner")
            val to = param("To", McVs.blockPos, "the other corner")
            val type = param("Type", McVs.string, "an entity id; empty for any", default = "")
            result("Count", McVs.int)
            query { inBox(host, from(), to(), type()).size.toLong() }
        }
        func("playersIn") {
            title("Players In Area")
            doc("Every player inside the box between two corners.")
            val from = param("From", McVs.blockPos, "one corner")
            val to = param("To", McVs.blockPos, "the other corner")
            result("Players", McVs.player.list())
            query { inBox(host, from(), to(), "").filterIsInstance<Player>().map(EntityHandle::of) }
        }
        func("itemsIn") {
            title("Items In Area")
            doc("The item stacks lying on the ground inside the box between two corners, optionally only those a filter admits.")
            val from = param("From", McVs.blockPos, "one corner")
            val to = param("To", McVs.blockPos, "the other corner")
            val filter = param("Filter", McVs.filter.orNull(), "only these; empty for anything")
            result("Items", McVs.itemStack.list())
            query {
                val m = host.matcher(filter())
                inBox(host, from(), to(), "").filterIsInstance<net.minecraft.world.entity.item.ItemEntity>().map { it.item }.filter { m.matches(it) }.map { ItemStackValue.record(it) }
            }
        }
        func("inArea") {
            title("In Area")
            doc("Whether a position lies inside the box between two corners (both included).")
            val pos = param("Pos", McVs.blockPos, "which position")
            val from = param("From", McVs.blockPos, "one corner")
            val to = param("To", McVs.blockPos, "the other corner")
            result("Inside", McVs.bool)
            query {
                val p = BlockPosValue.toBlockPos(pos()) ?: return@query false
                boxOf(from(), to())?.contains(Vec3.atCenterOf(p)) ?: false
            }
        }
        func("blockPos") {
            title("Block Position")
            doc("A position from three numbers.")
            spelledAs("blockPos")
            param("x", McVs.int, "west to east", default = 0L)
            param("y", McVs.int, "down to up", default = 0L)
            param("z", McVs.int, "north to south", default = 0L)
            result("Pos", McVs.blockPos)
            construct()
        }
        func("entitiesNear") {
            title("Entities Near")
            doc("Every entity within a radius of the controller, optionally of one kind (`minecraft:cow`).")
            val radius = param("Radius", McVs.float, "how far", default = 8.0)
            val type = param("Type", McVs.string, "an entity id; empty for any", default = "")
            result("Entities", McVs.entity.list())
            query {
                val want = type().trim().takeIf { it.isNotEmpty() }
                near(host, radius()).filter { e -> want == null || BuiltInRegistries.ENTITY_TYPE.getKey(e.type).toString() == want }.map(EntityHandle::of)
            }
        }
        func("playersNear") {
            title("Players Near")
            doc("Every player within a radius of the controller.")
            val radius = param("Radius", McVs.float, "how far", default = 16.0)
            result("Players", McVs.player.list())
            query { near(host, radius()).filterIsInstance<Player>().map(EntityHandle::of) }
        }
        func("nearestPlayer") {
            title("Nearest Player")
            doc("The closest player within a radius of the controller, or nothing.")
            val radius = param("Radius", McVs.float, "how far", default = 16.0)
            result("Player", McVs.player.orNull())
            query {
                val c = Vec3.atCenterOf(host.pos)
                near(host, radius()).filterIsInstance<Player>().minByOrNull { it.position().distanceToSqr(c) }?.let(EntityHandle::of)
            }
        }
        func("click") {
            title("Click")
            doc(
                """
                One click at a link, as a player would make it, with a buffer slot in hand (-1 is a bare hand).
                Left *hits*: the block breaks a little each tick with cracks for everyone watching — the tool
                deciding the speed, whether anything drops and what (fortune and silk touch count), durability
                spent, the drops on the ground for `Vacuum` — or, when something living stands in or on the block,
                one full swing of whatever is in hand: a sword hits like a sword (sharpness, knockback, fire
                aspect), and the next click waits for that weapon's swing to come back. Right *uses*: the stack
                on the face — a lever, a block placed, a seed planted, a bucket filled — or on whatever stands
                there: shears on a sheep, a bucket on a cow, a name tag. Aim says which: Auto takes a living
                thing when one is there, else the block. Sneak holds shift. Players are never struck. Answers
                whether the world took it and what dropped. `Break Block` and `Use Item` are Left and Right.
                """,
            )
            val link = param("Link", McVs.link, "which block or face")
            val button = param("Button", McVs.click, "Left hits, Right uses", default = "Right")
            val slot = param("Slot", McVs.int, "the buffer slot in hand; -1 for a bare hand", default = -1L)
            val sneak = param("Sneak", McVs.bool, "hold shift while clicking", default = false)
            val aim = param("Aim", McVs.aim, "the block, whatever stands in it, or Auto", default = "Auto")
            result("Ok", McVs.bool)
            result("Drops", McVs.itemStack.list())
            action {
                val target = host.link(link()) ?: throw IllegalArgumentException("no link called '${link()}'")
                host.jobs.start(ClickJob(host, target, ClickJob.Button.of(button()), slot().toInt(), sneak(), ClickJob.Aim.of(aim())))
            }
        }
        func("breakBlock") {
            title("Break Block")
            doc(
                """
                Break the linked block exactly as a player would: a little each tick with cracks for everyone
                watching, using the tool in a buffer slot — or a bare hand with no slot. The tool decides the
                speed, whether the block drops anything at all (stone by hand does not), and what: fortune
                and silk touch count, durability is spent. Drops land on the ground, as they would for a
                player; `Vacuum` picks them up into the buffer. Answers whether it broke and what dropped.
                """,
            )
            val link = param("Link", McVs.link, "which block")
            val tool = param("Tool", McVs.int, "the buffer slot holding the tool; -1 for a bare hand", default = -1L)
            result("Broke", McVs.bool)
            result("Drops", McVs.itemStack.list())
            action {
                val target = host.link(link()) ?: throw IllegalArgumentException("no link called '${link()}'")
                host.jobs.start(BreakBlockJob(host, target, tool().toInt()))
            }
        }
        func("useItem") {
            title("Use Item")
            doc(
                """
                Right-click the linked face with the stack in a buffer slot, as a player would — pull a
                lever, place a block, plant a seed, fill a bucket; -1 is an empty hand. Whatever the use
                does to the stack happens in the buffer. Answers whether the world accepted it.
                """,
            )
            val link = param("Link", McVs.link, "which face")
            val slot = param("Slot", McVs.int, "the buffer slot to use; -1 for an empty hand", default = -1L)
            val sneak = param("Sneak", McVs.bool, "hold shift while using — many modded blocks ask for it", default = false)
            result("Ok", McVs.bool)
            action {
                val target = host.link(link()) ?: throw IllegalArgumentException("no link called '${link()}'")
                host.jobs.start(UseItemJob(host, target, slot().toInt(), sneak = sneak()))
            }
        }
        func("placeBlock") {
            title("Place Block")
            doc("Place a block from a buffer slot against the linked face. The same as Use Item with a block in the slot.")
            val link = param("Link", McVs.link, "which face")
            val slot = param("Slot", McVs.int, "the buffer slot holding the block", default = 0L)
            val sneak = param("Sneak", McVs.bool, "hold shift while placing", default = false)
            result("Ok", McVs.bool)
            action {
                val target = host.link(link()) ?: throw IllegalArgumentException("no link called '${link()}'")
                host.jobs.start(UseItemJob(host, target, slot().toInt(), sneak = sneak()))
            }
        }
        func("vacuum") {
            title("Vacuum")
            doc(
                """
                Pick up the items lying around a link — what a player walking over them would collect.
                Answers how many items were taken; what does not fit stays on the ground.

                Link is where to sweep and Into is where they go, both defaulting to `self`: around the
                controller, into its own buffer. Either end can be any link, so a graph can sweep a floor
                straight into a chest — or into a person's pack — without the buffer in the middle.
                """,
            )
            val link = param("Link", McVs.link, "around which block", default = ControllerHost.SELF)
            val radius = param("Radius", McVs.float, "how far, in blocks", default = 1.5)
            val filter = param("Filter", McVs.filter.orNull(), "only these; empty for anything")
            val into = param("Into", McVs.link, "where they go", default = ControllerHost.SELF)
            val fx = param("Effects", McVs.bool, "draw the rift and what travels through it", default = true)
            result("Collected", McVs.int)
            command {
                val center = centerOf(host, link()) ?: return@command 0L
                val matcher = host.matcher(filter())
                val r = radius().coerceIn(0.5, 8.0)
                // Resolved once, not per orb: for a presence link this builds a fresh gated view each call.
                val inv = host.items(into()) ?: return@command 0L
                var collected = 0L
                var first = ""
                val box = net.minecraft.world.phys.AABB(center, center).inflate(r)
                for (entity in host.level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity::class.java, box)) {
                    val stack = entity.item
                    if (stack.isEmpty || !matcher.matches(stack)) continue
                    // Taken from the entity and given to the buffer within the one tick: there is never a
                    // moment where both hold it, which is the only reason this cannot duplicate.
                    val left = inv.insertStacked(stack.copy(), false)
                    val taken = stack.count - left.count
                    if (taken <= 0) continue
                    collected += taken
                    if (first.isEmpty()) first = BuiltInRegistries.ITEM.getKey(stack.item).toString()
                    if (left.isEmpty) entity.discard() else entity.item = left
                }
                if (collected > 0 && fx()) host.transferred(link(), into(), collected.toInt(), item = first)
                collected
            }
        }
        func("itemsNear") {
            title("Items Near")
            doc("The item stacks lying on the ground around a link (or the controller, with `self`).")
            val link = param("Link", McVs.link, "around which block", default = "self")
            val radius = param("Radius", McVs.float, "how far, in blocks", default = 1.5)
            result("Items", McVs.itemStack.list())
            query {
                val center = centerOf(host, link()) ?: return@query emptyList<Any?>()
                val box = net.minecraft.world.phys.AABB(center, center).inflate(radius().coerceIn(0.5, 8.0))
                host.level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity::class.java, box).map { ItemStackValue.record(it.item) }
            }
        }
        func("playSound") {
            title("Play Sound")
            doc("Play a sound, by id, at a position or at the controller.")
            val id = param("Sound", McVs.string, "a sound id such as `minecraft:block.note_block.pling`")
            val pos = param("Pos", McVs.blockPos.orNull(), "where; empty for the controller")
            val volume = param("Volume", McVs.float, "", default = 1.0)
            val pitch = param("Pitch", McVs.float, "", default = 1.0)
            command {
                val sound = ResourceLocation.tryParse(id().trim())?.let { bpm.platform.valueOf(BuiltInRegistries.SOUND_EVENT, it) } ?: return@command null
                val at = BlockPosValue.toBlockPos(pos()) ?: host.pos
                host.level.playSound(null, at, sound, SoundSource.BLOCKS, volume().toFloat(), pitch().toFloat())
                null
            }
        }
        func("particles") {
            title("Particles")
            doc("Spawn some particles, by id, at a position or above the controller.")
            val id = param("Particle", McVs.string, "a particle id such as `minecraft:happy_villager`")
            val pos = param("Pos", McVs.blockPos.orNull(), "where; empty for above the controller")
            val count = param("Count", McVs.int, "", default = 8L)
            val spread = param("Spread", McVs.float, "how far they scatter", default = 0.5)
            command {
                val type = ResourceLocation.tryParse(id().trim())?.let { bpm.platform.valueOf(BuiltInRegistries.PARTICLE_TYPE, it) } as? SimpleParticleType
                    ?: return@command null
                val at = BlockPosValue.toBlockPos(pos())?.let { Vec3.atCenterOf(it) } ?: Vec3.atCenterOf(host.pos).add(0.0, 1.0, 0.0)
                val s = spread()
                host.level.sendParticles(type, at.x, at.y, at.z, count().toInt().coerceIn(0, 256), s, s, s, 0.0)
                null
            }
        }
    }

    /** The block box between two corners, both included; null when either is not a position. */
    private fun boxOf(from: Any?, to: Any?): AABB? {
        val a = BlockPosValue.toBlockPos(from) ?: return null
        val b = BlockPosValue.toBlockPos(to) ?: return null
        return AABB(
            minOf(a.x, b.x).toDouble(), minOf(a.y, b.y).toDouble(), minOf(a.z, b.z).toDouble(),
            maxOf(a.x, b.x) + 1.0, maxOf(a.y, b.y) + 1.0, maxOf(a.z, b.z) + 1.0,
        )
    }

    /** The live entities whose bodies overlap the box between [from] and [to], optionally of one kind. */
    private fun inBox(host: ControllerHost, from: Any?, to: Any?, type: String): List<Entity> {
        val box = boxOf(from, to) ?: return emptyList()
        val want = type.trim().takeIf { it.isNotEmpty() }
        return host.level.getEntities(null as Entity?, box) { e -> e.isAlive && (want == null || BuiltInRegistries.ENTITY_TYPE.getKey(e.type).toString() == want) }
    }

    private fun near(host: ControllerHost, radius: Double): List<Entity> {
        val r = radius.coerceIn(0.0, 128.0)
        val box = AABB.ofSize(Vec3.atCenterOf(host.pos), r * 2, r * 2, r * 2)
        val c = Vec3.atCenterOf(host.pos)
        return host.level.getEntities(null as Entity?, box).filter { it.position().distanceToSqr(c) <= r * r }
    }
}

/** The middle of the block a link names, or of the controller for `self`; null when the link is unknown or unloaded. */
internal fun centerOf(host: ControllerHost, link: String): net.minecraft.world.phys.Vec3? {
    if (link == ControllerHost.SELF || link.isEmpty()) return net.minecraft.world.phys.Vec3.atCenterOf(host.pos)
    val r = host.link(link) ?: return null
    if (!r.loaded) return null
    return net.minecraft.world.phys.Vec3.atCenterOf(r.link.pos)
}
