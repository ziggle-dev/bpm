package bpm.world

import bpm.Bpm
import com.mojang.serialization.Codec
import net.minecraft.nbt.CompoundTag
import net.neoforged.neoforge.attachment.AttachmentType
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister
import net.neoforged.neoforge.registries.NeoForgeRegistries

/** Per-player data that must outlive death: where a chamber visit returns to, and whether a death sends them there. */
object ModAttachments {
    val REG: DeferredRegister<AttachmentType<*>> = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Bpm.ID)

    /** `{dim, x, y, z}` of the spot outside the gate a player last entered a chamber through; empty when never. */
    val CHAMBER_RETURN: DeferredHolder<AttachmentType<*>, AttachmentType<CompoundTag>> =
        REG.register("chamber_return") { -> AttachmentType.builder { -> CompoundTag() }.serialize(CompoundTag.CODEC).copyOnDeath().build() }

    /** The Warden pity counter, in percent (0–16) — see `ChamberFight`. */
    val WARDEN_PITY: DeferredHolder<AttachmentType<*>, AttachmentType<Int>> =
        REG.register("warden_pity") { -> AttachmentType.builder { -> 0 }.serialize(Codec.INT).copyOnDeath().build() }

    /** Set when a player dies inside a chamber: the next respawn goes to [CHAMBER_RETURN]. */
    val RESPAWN_AT_GATE: DeferredHolder<AttachmentType<*>, AttachmentType<Boolean>> =
        REG.register("respawn_at_gate") { -> AttachmentType.builder { -> false }.serialize(Codec.BOOL).copyOnDeath().build() }
}
