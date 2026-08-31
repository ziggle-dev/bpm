package bpm.world

import net.minecraft.core.GlobalPos
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.capabilities.Capabilities

/**
 * What a tether lets a controller do to the person carrying it — see `docs/DESIGN_PLAYER_LINK.md` §2.2.
 *
 * The set rides on the stack, so it is the player's to change and the player's to revoke, and a controller
 * that asks for something it was not granted is refused with a line in its console rather than silently
 * moving nothing.
 */
enum class Grant(val key: String) {
    /** Position, health, hunger, experience, effects — everything `player.*` reads. */
    STATE("state"),

    /** See what they are carrying. Implied by [GIVE] and [TAKE]: you cannot stock an inventory blind. */
    READ("read"),

    /** Put things in. */
    GIVE("give"),

    /** Take things out — the dangerous one, and never on by default. */
    TAKE("take"),

    /** Draw a panel on their screen. */
    HUD("hud"),

    /** Read their hotkeys and their panel clicks. */
    INPUT("input"),

    /** Reach the four armour slots and the offhand as well as the pack. */
    EQUIP("equip"),

    /** Reach their ender chest as a second link. */
    ENDER("ender");

    companion object {
        fun byKey(key: String): Grant? = entries.firstOrNull { it.key == key.trim().lowercase() }
    }
}

/** The presets the tether cycles through, and how a set is spelled on the stack. */
object Grants {
    val WATCH: Set<Grant> = setOf(Grant.STATE)
    val DELIVERY: Set<Grant> = setOf(Grant.STATE, Grant.READ, Grant.GIVE, Grant.HUD)
    val FULL: Set<Grant> = DELIVERY + setOf(Grant.TAKE, Grant.INPUT)

    /** The cycle order a tether steps through, starting at what it is crafted with. */
    val PRESETS: List<Set<Grant>> = listOf(DELIVERY, FULL, WATCH)

    val NONE: Set<Grant> = emptySet()

    /** A stack's grants, from the comma-joined text it stores; unknown words are ignored, not fatal. */
    fun parse(text: String?): Set<Grant> {
        if (text.isNullOrBlank()) return NONE
        val out = LinkedHashSet<Grant>()
        for (word in text.split(',')) Grant.byKey(word)?.let { out += it }
        return out
    }

    fun format(grants: Set<Grant>): String = Grant.entries.filter { it in grants }.joinToString(",") { it.key }

    /** The preset's name, for the action bar and the panel — or `custom` for a set nobody offers. */
    fun label(grants: Set<Grant>): String = when (grants) {
        WATCH -> "watch only"
        DELIVERY -> "delivery"
        FULL -> "full access"
        else -> "custom"
    }

    /** The preset after this one, so sneak-use in the air cycles. */
    fun next(grants: Set<Grant>): Set<Grant> {
        val i = PRESETS.indexOf(grants)
        return if (i < 0) PRESETS.first() else PRESETS[(i + 1) % PRESETS.size]
    }

    /**
     * Whether [grants] opens an inventory handler at all.
     *
     * [Grant.READ] is implied by [Grant.GIVE] and [Grant.TAKE] on purpose: a handler that hid its contents
     * from a controller allowed to insert would break stacking (`insertItemStacked` reads the slots it is
     * about to fill), so the honest shapes are "you see it" and "you do not reach it at all".
     */
    fun opensInventory(grants: Set<Grant>): Boolean =
        Grant.READ in grants || Grant.GIVE in grants || Grant.TAKE in grants
}

/**
 * Where a tether may be kept.
 *
 * Core knows about the player's own 41 slots and nothing else; Curios (and anything like it) registers a
 * second source rather than core importing it — see `docs/DESIGN_PLAYER_LINK.md` §8.
 */
object TetherSources {
    private val extra = ArrayList<(Player) -> List<ItemStack>>()

    fun register(source: (Player) -> List<ItemStack>) {
        extra += source
    }

    /** Every stack [player] is keeping, as far as bpm can see. */
    fun stacks(player: Player): Sequence<ItemStack> = sequence {
        val own = player.getCapability(Capabilities.ItemHandler.ENTITY)
        if (own != null) for (i in 0 until own.slots) yield(own.getStackInSlot(i))
        for (source in extra) yieldAll(source(player))
    }
}

/**
 * The credential itself: a stack bound to a controller, and what it allows.
 *
 * The binding lives in two data components, not in an item class, so the Quantum Tether is simply the item
 * that is *made* to carry them — and `/bpm tether` can stamp them onto anything for testing, exercising this
 * exact path rather than a bypass around it.
 */
object Tethers {
    /** The grants a tether in [player]'s keeping gives the controller at [controller], or null when there is none. */
    fun credential(player: Player, controller: GlobalPos): Set<Grant>? {
        for (stack in TetherSources.stacks(player)) {
            if (stack.isEmpty) continue
            if (stack.get(ModComponents.TETHER_CONTROLLER.get()) != controller) continue
            return Grants.parse(stack.get(ModComponents.TETHER_GRANTS.get()))
        }
        return null
    }

    /** Whether [stack] is somebody's tether — bound to any controller, not just one. */
    fun isCredential(stack: ItemStack): Boolean = stack.has(ModComponents.TETHER_CONTROLLER.get())

    /** The tether stack [player] carries for [controller], for the commands and the tooltip. */
    fun stack(player: Player, controller: GlobalPos): ItemStack? =
        TetherSources.stacks(player).firstOrNull { !it.isEmpty && it.get(ModComponents.TETHER_CONTROLLER.get()) == controller }

    /** Bind [stack] to [controller] with [grants] — what the tether does on sneak-use, and what `/bpm tether` does. */
    fun bind(stack: ItemStack, controller: GlobalPos, grants: Set<Grant>) {
        stack.set(ModComponents.TETHER_CONTROLLER.get(), controller)
        stack.set(ModComponents.TETHER_GRANTS.get(), Grants.format(grants))
    }

    fun unbind(stack: ItemStack) {
        stack.remove(ModComponents.TETHER_CONTROLLER.get())
        stack.remove(ModComponents.TETHER_GRANTS.get())
    }
}
