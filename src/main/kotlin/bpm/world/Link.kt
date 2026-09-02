package bpm.world

import net.minecraft.core.BlockPos
import java.util.UUID
import net.minecraft.world.phys.Vec3
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceKey
import bpm.platform.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import bpm.platform.ports.EnergyPort
import bpm.platform.ports.Ports
import bpm.platform.ports.FluidPort
import bpm.platform.ports.ItemPort
import bpm.platform.intOr
import bpm.platform.stringOr
import bpm.platform.keyId

/**
 * One face in the world a controller may talk to, by name — or one person, when [player] is set.
 *
 * The ONE binding model — replacing the old mod's pipe-network proxies and projectile links both. A link is
 * a position, an optional face (capabilities are sided: a furnace's top is its input) and a name a script
 * writes. Direction of transfer lives in the verb (`items.move(from, to)`), not on the link.
 *
 * A **presence link** ([player] set) is the same thing pointed at a person: [pos] and [dimension] are then
 * only where they were last seen — a cache for the HUD and for saying something useful while they are
 * offline — and [side] is always null, because a person has no faces. What it actually resolves to is live;
 * see [PresenceLink] and `docs/DESIGN_PLAYER_LINK.md`.
 */
data class Link(
    val name: String,
    val pos: BlockPos,
    val side: Direction?,
    val dimension: ResourceKey<Level>,
    val player: UUID? = null,
) {
    val isPresence: Boolean get() = player != null

    fun save(): CompoundTag = CompoundTag().also { t ->
        t.putString("name", name)
        t.putInt("x", pos.x); t.putInt("y", pos.y); t.putInt("z", pos.z)
        side?.let { t.putString("side", it.name.lowercase()) }
        t.putString("dim", dimension.keyId().toString())
        player?.let { bpm.platform.putUuid(t, "player", it) }
    }

    companion object {
        fun load(t: CompoundTag): Link? {
            val name = t.stringOr("name", "").ifBlank { return null }
            val dim = ResourceLocation.tryParse(t.stringOr("dim", "")) ?: return null
            val side = t.stringOr("side", "").takeIf { it.isNotBlank() }?.let { s -> Direction.entries.firstOrNull { it.name.equals(s, true) } }
            val player = bpm.platform.uuidOrNull(t, "player")
            return Link(
                name, BlockPos(t.intOr("x", 0), t.intOr("y", 0), t.intOr("z", 0)), side,
                ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dim), player,
            )
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

/**
 * How many links of each kind a table may hold.
 *
 * One value rather than two lambdas on purpose: two `() -> Int` parameters with defaults let a trailing
 * lambda bind silently to the wrong one, and `LinkTable { 8 }` reading as "eight *people*, unlimited chests"
 * is the kind of mistake that compiles and then lies.
 */
data class LinkCaps(val links: Int, val presence: Int) {
    companion object {
        val UNLIMITED = LinkCaps(Int.MAX_VALUE, Int.MAX_VALUE)
    }
}

/**
 * A controller's links, by name — blocks and people in one table, since a script names them the same way.
 *
 * The two kinds are capped separately ([capacity] and [presenceCapacity]) because a person is a scarcer thing
 * than a chest: the core tier decides both, and a tier can change under a table that is already full (a config
 * edit, a datapack), so each is asked afresh each time.
 */
class LinkTable(private val capsOf: () -> LinkCaps = { LinkCaps.UNLIMITED }) {
    private val byName = LinkedHashMap<String, Link>()

    /** A change counter, so a cache can tell a renamed or removed link from a stale one. */
    var version: Int = 0
        private set

    val all: List<Link> get() = byName.values.toList()
    val names: Set<String> get() = byName.keys

    /** The block links, and the people, in the order they were made. */
    val blocks: List<Link> get() = byName.values.filter { !it.isPresence }
    val presence: List<Link> get() = byName.values.filter { it.isPresence }

    /** How many of each this table may hold, and whether it is there already. */
    val capacity: Int get() = capsOf().links
    val presenceCapacity: Int get() = capsOf().presence
    val full: Boolean get() = blocks.size >= capacity
    val presenceFull: Boolean get() = presence.size >= presenceCapacity

    /** The presence link for [player], if this controller has one. */
    fun byPlayer(player: UUID): Link? = byName.values.firstOrNull { it.player == player }

    /** Forget [player] entirely — what unbinding a tether does from the controller's side. */
    fun removePlayer(player: UUID): Boolean = byPlayer(player)?.let { remove(it.name) } ?: false

    /**
     * Remember where [player] was last seen, for the offline row in the links panel. Answers whether anything
     * moved. Deliberately does NOT bump [version]: a position is not a change of shape, and invalidating every
     * runtime's resolved-link cache five times a minute per tethered player would be pure churn.
     */
    fun seen(player: UUID, pos: BlockPos, dimension: ResourceKey<Level>): Boolean {
        val link = byPlayer(player) ?: return false
        if (link.pos == pos && link.dimension == dimension) return false
        byName[link.name] = link.copy(pos = pos, dimension = dimension)
        return true
    }

    /** The name a presence link for [playerName] gets: their name, lowercased, numbered if it is taken. */
    fun presenceName(playerName: String): String = previewName(playerName.lowercase())

    /**
     * The names past the capacity: the oldest links keep working and the tail goes quiet. A table filled under a
     * bigger core, or under a config since lowered, keeps every link it has — losing a build to an edited config
     * is not acceptable, going quiet with a warning is.
     */
    val overCapacity: List<String>
        get() = blocks.drop(capacity).map { it.name } + presence.drop(presenceCapacity).map { it.name }

    /** Whether [name] is one of those — [ResolvedLink.capped] is how a script sees it. Each kind counts its own. */
    fun isOverCapacity(name: String): Boolean {
        val link = byName[name] ?: return false
        return if (link.isPresence) presence.indexOf(link) >= presenceCapacity else blocks.indexOf(link) >= capacity
    }

    operator fun get(name: String): Link? = byName[name]

    /** The name [add] would give a block of [blockPath] right now — for the HUD's "use to link 'chest-2'". */
    fun previewName(blockPath: String): String {
        val base = blockPath.substringAfterLast('/').substringAfterLast(':')
        if (byName[base] == null) return base
        var n = 2
        while (byName["$base-$n"] != null) n++
        return "$base-$n"
    }

    /**
     * Add [link], renaming it if its name is taken. Null when the table is full — the caller says so.
     *
     * Every add is a new entry: a taken name is numbered rather than replaced, so a full table refuses even
     * a link whose name it already holds.
     */
    fun add(link: Link): Link? {
        if (if (link.isPresence) presenceFull else full) return null
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
open class ResolvedLink(open val link: Link, val level: ServerLevel, val capped: Boolean = false) {
    open val loaded: Boolean get() = !capped && level.hasChunkAt(link.pos)

    private val ports by lazy { Ports.cache(level, link.pos, link.side) }

    open fun items(): ItemPort? = if (loaded) ports.items else null
    open fun fluids(): FluidPort? = if (loaded) ports.fluids else null
    open fun energy(): EnergyPort? = if (loaded) ports.energy else null
}
