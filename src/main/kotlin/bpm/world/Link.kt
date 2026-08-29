package bpm.world

import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.neoforged.neoforge.capabilities.BlockCapabilityCache
import net.neoforged.neoforge.capabilities.Capabilities
import net.neoforged.neoforge.energy.IEnergyStorage
import net.neoforged.neoforge.fluids.capability.IFluidHandler
import net.neoforged.neoforge.items.IItemHandler

/**
 * One face in the world a controller may talk to, by name.
 *
 * The ONE binding model — replacing the old mod's pipe-network proxies and projectile links both. A link is
 * a position, an optional face (capabilities are sided: a furnace's top is its input) and a name a script
 * writes. Direction of transfer lives in the verb (`items.move(from, to)`), not on the link.
 */
data class Link(val name: String, val pos: BlockPos, val side: Direction?, val dimension: ResourceKey<Level>) {
    fun save(): CompoundTag = CompoundTag().also { t ->
        t.putString("name", name)
        t.putInt("x", pos.x); t.putInt("y", pos.y); t.putInt("z", pos.z)
        side?.let { t.putString("side", it.name.lowercase()) }
        t.putString("dim", dimension.location().toString())
    }

    companion object {
        fun load(t: CompoundTag): Link? {
            val name = t.getString("name").ifBlank { return null }
            val dim = ResourceLocation.tryParse(t.getString("dim")) ?: return null
            val side = t.getString("side").takeIf { it.isNotBlank() }?.let { s -> Direction.entries.firstOrNull { it.name.equals(s, true) } }
            return Link(name, BlockPos(t.getInt("x"), t.getInt("y"), t.getInt("z")), side, ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dim))
        }
    }
}

/** A controller's links, by name. Names are unique; a second `chest` becomes `chest-2`. */
/**
 * A link made on the spot from coordinates — `controller.linkAt` answers one of these names and the
 * runtime resolves it like any other, so every verb that takes a Link takes a position too. Same
 * dimension as the controller, within the linker's range; never stored in the table.
 */
object AdHocLink {
    const val PREFIX = "@"

    fun name(pos: BlockPos, side: Direction?): String =
        "$PREFIX${pos.x},${pos.y},${pos.z}" + (side?.let { ":${it.name.lowercase()}" } ?: "")

    fun parse(name: String, dimension: ResourceKey<Level>): Link? {
        if (!name.startsWith(PREFIX)) return null
        val body = name.substring(PREFIX.length)
        val colon = body.indexOf(':')
        val coords = if (colon < 0) body else body.substring(0, colon)
        val side = if (colon < 0) null else Direction.byName(body.substring(colon + 1).lowercase()) ?: return null
        val parts = coords.split(',')
        if (parts.size != 3) return null
        val x = parts[0].trim().toIntOrNull() ?: return null
        val y = parts[1].trim().toIntOrNull() ?: return null
        val z = parts[2].trim().toIntOrNull() ?: return null
        return Link(name, BlockPos(x, y, z), side, dimension)
    }
}

class LinkTable {
    private val byName = LinkedHashMap<String, Link>()

    /** A change counter, so a cache can tell a renamed or removed link from a stale one. */
    var version: Int = 0
        private set

    val all: List<Link> get() = byName.values.toList()
    val names: Set<String> get() = byName.keys

    operator fun get(name: String): Link? = byName[name]

    /** Add [link], renaming it if its name is taken. Returns the link as stored. */
    /** The name [add] would give a block of [blockPath] right now — for the HUD's "use to link 'chest-2'". */
    fun previewName(blockPath: String): String {
        val base = blockPath.substringAfterLast('/').substringAfterLast(':')
        if (byName[base] == null) return base
        var n = 2
        while (byName["$base-$n"] != null) n++
        return "$base-$n"
    }

    fun add(link: Link): Link {
        val stored = if (link.name !in byName) link else link.copy(name = uniqueName(link.name))
        byName[stored.name] = stored
        version++
        return stored
    }

    fun remove(name: String): Boolean = (byName.remove(name) != null).also { if (it) version++ }

    /** The link at [pos]/[side], if any — what the wand asks before adding a duplicate. */
    fun at(pos: BlockPos, side: Direction?): Link? = byName.values.firstOrNull { it.pos == pos && it.side == side }

    /**
     * The first link whose cell a ray from [from] along [dir] enters within [reach] blocks, a fifth of a block
     * at a time, giving up at the first cell [blocked] says the ray cannot pass — how the wand finds a link
     * whose block is gone, since there is no face left to click.
     */
    fun firstAlong(from: Vec3, dir: Vec3, reach: Double, blocked: (BlockPos) -> Boolean = { false }): Link? {
        if (dir.lengthSqr() < 1e-8) return null
        val d = dir.normalize()
        var last: BlockPos? = null
        var t = 0.0
        while (t <= reach) {
            val p = BlockPos.containing(from.add(d.scale(t)))
            if (p != last) {
                last = p
                if (blocked(p)) return null
                byName.values.firstOrNull { it.pos == p }?.let { return it }
            }
            t += 0.2
        }
        return null
    }

    fun rename(old: String, new: String): Boolean {
        val link = byName[old] ?: return false
        val target = new.trim()
        if (target.isEmpty() || (target != old && target in byName)) return false
        byName.remove(old)
        byName[target] = link.copy(name = target)
        version++
        return true
    }

    fun clear() {
        if (byName.isNotEmpty()) version++
        byName.clear()
    }

    fun save(): ListTag = ListTag().also { list -> byName.values.forEach { list.add(it.save()) } }

    fun load(list: ListTag) {
        byName.clear()
        for (i in 0 until list.size) {
            val tag = list.get(i) as? CompoundTag ?: continue
            Link.load(tag)?.let { byName[it.name] = it }
        }
        version++
    }

    private fun uniqueName(base: String): String {
        var n = 2
        while ("$base-$n" in byName) n++
        return "$base-$n"
    }

    companion object {
        /** A name for a freshly linked block: its registry path, so a chest is `chest`. */
        fun autoName(blockId: ResourceLocation): String = blockId.path.substringAfterLast('/')

        @Suppress("unused")
        private val TAG_TYPE = Tag.TAG_COMPOUND
    }
}

/**
 * A link looked up against the world, with the capability lookups cached the way NeoForge asks.
 *
 * `loaded` never loads a chunk: a script may not pull the world in around its links, so an unloaded link
 * answers null and the node says so once. The caches are created lazily on the server thread and are only
 * valid for the level they were made against — [LinkTable.version] tells the owner when to drop one.
 */
class ResolvedLink(val link: Link, val level: ServerLevel) {
    val loaded: Boolean get() = level.hasChunkAt(link.pos)

    private val itemCache by lazy { BlockCapabilityCache.create(Capabilities.ItemHandler.BLOCK, level, link.pos, link.side) }
    private val fluidCache by lazy { BlockCapabilityCache.create(Capabilities.FluidHandler.BLOCK, level, link.pos, link.side) }
    private val energyCache by lazy { BlockCapabilityCache.create(Capabilities.EnergyStorage.BLOCK, level, link.pos, link.side) }

    fun items(): IItemHandler? = if (loaded) itemCache.capability else null
    fun fluids(): IFluidHandler? = if (loaded) fluidCache.capability else null
    fun energy(): IEnergyStorage? = if (loaded) energyCache.capability else null
}
