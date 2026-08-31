package bpm.world

import net.minecraft.core.GlobalPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.capabilities.Capabilities
import net.neoforged.neoforge.items.IItemHandler
import net.neoforged.neoforge.items.wrapper.ForwardingItemHandler

/**
 * A player's own inventory, with only the doors their tether opened.
 *
 * NeoForge already exposes a player as 41 slots (`PlayerInvWrapper`: 0–35 the pack, 36–39 armour, 40 the
 * offhand) through `Capabilities.ItemHandler.ENTITY`, so this forwards to that rather than reimplementing
 * stacking, other mods' inventory extensions, or anything else about how a player's bag works. It only says
 * no.
 */
class GrantedInv(private val grants: Set<Grant>, delegate: IItemHandler) : ForwardingItemHandler(delegate) {

    override fun insertItem(slot: Int, stack: ItemStack, simulate: Boolean): ItemStack =
        if (allowed(slot, Grant.GIVE)) super.insertItem(slot, stack, simulate) else stack

    override fun extractItem(slot: Int, amount: Int, simulate: Boolean): ItemStack =
        if (allowed(slot, Grant.TAKE)) super.extractItem(slot, amount, simulate) else ItemStack.EMPTY

    override fun isItemValid(slot: Int, stack: ItemStack): Boolean =
        allowed(slot, Grant.GIVE) && super.isItemValid(slot, stack)

    /**
     * Reading is not gated here: a handler is only ever built for a tether that opened one at all
     * ([Grants.opensInventory]), and one that hid its slots from a controller allowed to insert would break
     * stacking. What is gated is what may be *changed*, and what may be worn.
     */
    private fun allowed(slot: Int, grant: Grant): Boolean =
        grant in grants && (slot < Inventory.INVENTORY_SIZE || Grant.EQUIP in grants)
}

/**
 * A link pointed at a person: the same name a script writes, resolved against whoever is carrying the tether.
 *
 * Everything a block link answers from a fixed position, this answers from a moving one, and the whole safety
 * model is [consent]: online, near enough, in reach of this controller, carrying a tether bound to *this*
 * controller, and granted the thing being asked for. Any of those failing makes it a link that exists and is
 * not loaded, which every verb in the mod already reads as "nothing there" — see
 * `docs/DESIGN_PLAYER_LINK.md` §4 and §5.
 */
class PresenceLink(
    private val stored: Link,
    level: ServerLevel,
    private val controller: ControllerBlockEntity,
) : ResolvedLink(stored, level) {

    /** What [consent] worked out, recomputed at most once a tick: a script asks many times per pass. */
    private class Consent(val player: ServerPlayer?, val grants: Set<Grant>, val why: String?)

    private var at: Long = -1
    private var last: Consent? = null

    private val here: GlobalPos get() = GlobalPos.of(controller.level?.dimension() ?: level.dimension(), controller.blockPos)

    /** The person, whether or not they are reachable — for `player.*` and for the panel's offline row. */
    val player: ServerPlayer?
        get() = stored.player?.let { level.server.playerList.getPlayer(it) }

    /** Where they are now, when they are anywhere; the stored last-seen position otherwise. */
    override val link: Link
        get() = player?.let { stored.copy(pos = it.blockPosition(), dimension = it.level().dimension()) } ?: stored

    override val loaded: Boolean get() = state().why == null

    override fun items(): IItemHandler? {
        val c = state()
        if (c.why != null || c.player == null || !Grants.opensInventory(c.grants)) return null
        val own = c.player.getCapability(Capabilities.ItemHandler.ENTITY) ?: return null
        return GrantedInv(c.grants, own)
    }

    /** A person is not a tank and not a battery. */
    override fun fluids() = null
    override fun energy() = null

    /** What this controller may do to them right now — empty when it may do nothing. */
    val grants: Set<Grant> get() = state().let { if (it.why == null) it.grants else Grants.NONE }

    /** Whether [grant] is open right now — the question every verb actually asks. */
    fun mayI(grant: Grant): Boolean = state().let { it.why == null && grant in it.grants }

    /**
     * Why this link is answering nothing, phrased for the script's console — or null when it is fine.
     * [grant], when given, also asks whether that particular door is open.
     */
    fun reason(grant: Grant? = null): String? {
        val c = state()
        c.why?.let { return it }
        if (grant != null && grant !in c.grants) return "their tether does not grant '${grant.key}'"
        return null
    }

    private fun state(): Consent {
        val now = level.gameTime
        last?.let { if (at == now) return it }
        val computed = compute()
        at = now
        last = computed
        return computed
    }

    private fun compute(): Consent {
        val uuid = stored.player ?: return Consent(null, Grants.NONE, "it is not a presence link")
        val p = level.server.playerList.getPlayer(uuid) ?: return Consent(null, Grants.NONE, "they are offline")
        if (!p.isAlive) return Consent(p, Grants.NONE, "they are dead")

        val tier = controller.coreTier
        if (p.level().dimension() != level.dimension() && !tier.unlimitedRange) {
            return Consent(p, Grants.NONE, "they are in another dimension, and a ${tier.label} core does not reach")
        }
        val range = controller.linkRange
        if (!p.blockPosition().closerThan(controller.blockPos, range)) {
            val d = kotlin.math.sqrt(p.blockPosition().distSqr(controller.blockPos)).toInt()
            return Consent(p, Grants.NONE, "they are $d blocks away, past this controller's ${CoreTier.rangeText(range)}")
        }
        val grants = Tethers.credential(p, here)
            ?: return Consent(p, Grants.NONE, "they are not carrying a tether bound to this controller")
        return Consent(p, grants, null)
    }
}
