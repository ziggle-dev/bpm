package bpm.world

import bpm.platform.store.PlayerKey
import com.mojang.serialization.Codec
import net.minecraft.nbt.CompoundTag

/**
 * Per-player data that must outlive death: where a chamber visit returns to, and whether a death sends
 * them there.
 *
 * These were NeoForge data attachments. They are now keys into [bpm.platform.store.PlayerStore], which
 * is a plain vanilla `SavedData` keyed by UUID — see that file for why the seam was deleted rather than
 * abstracted. Nothing registers, so there is no registrar here any more and nothing for
 * `BpmRegistries.install` to do with them.
 */
object ModAttachments {

    /** `{dim, x, y, z}` of the spot outside the gate a player last entered a chamber through; empty when never. */
    val CHAMBER_RETURN = PlayerKey("chamber_return", CompoundTag.CODEC) { CompoundTag() }

    /** The Warden pity counter, in percent (0–16) — see `ChamberFight`. */
    val WARDEN_PITY = PlayerKey("warden_pity", Codec.INT) { 0 }

    /** Set when a player dies inside a chamber: the next respawn goes to [CHAMBER_RETURN]. */
    val RESPAWN_AT_GATE = PlayerKey("respawn_at_gate", Codec.BOOL) { false }
}
