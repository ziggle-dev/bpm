package bpm.platform.net

import bpm.platform.ResourceLocation
import net.minecraft.network.FriendlyByteBuf

/**
 * What a packet IS, on a band that may not have vanilla's answer.
 *
 * `CustomPacketPayload` and `StreamCodec` arrived in 1.20.5. Every packet in this mod is written against
 * them, and on 1.20.1 neither type exists -- which is most of why that band starts at four figures of
 * compile errors.
 *
 * Almost nothing has to change to serve both, because the codecs here were always hand-written over
 * `FriendlyByteBuf` rather than composed from vanilla's codec combinators. The bytes on the wire are
 * already band-neutral; only the three NAMES around them are not. So this file supplies the three names,
 * and `bpm.net.Payloads` says `BpmPayload`, `payloadType(...)` and `PayloadCodec` instead of vanilla's
 * spellings. On 1.20.5 and up they are aliases and this costs literally nothing at runtime; below it
 * they are a two-field class and an interface with one method.
 *
 * The registration side -- where a payload is handed in and how one is sent -- is [PlatformNet]'s
 * problem, and that genuinely does differ per loader on this band.
 */

//? if >=1.20.5 {
/** A packet. Vanilla's own type from 1.20.5. */
typealias BpmPayload = net.minecraft.network.protocol.common.custom.CustomPacketPayload

/** A packet's identity, which is its id and its static type together. */
typealias PayloadType<T> = net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<T>

/**
 * A packet's wire form.
 *
 * Written as `FriendlyByteBuf` rather than `RegistryFriendlyByteBuf`: none of these codecs needs a
 * registry lookup, and the wider buffer type is what lets one codec serve both the play and the
 * configuration phase. It still satisfies the `? super RegistryFriendlyByteBuf` the loaders ask for.
 */
typealias PayloadCodec<T> = net.minecraft.network.codec.StreamCodec<FriendlyByteBuf, T>

/** Build a packet identity from its id. */
fun <T : BpmPayload> payloadType(id: ResourceLocation): PayloadType<T> =
    net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type(id)

/** Build a wire form from a write and a read. */
fun <T : BpmPayload> payloadCodec(
    write: (FriendlyByteBuf, T) -> Unit,
    read: (FriendlyByteBuf) -> T,
): PayloadCodec<T> = net.minecraft.network.codec.StreamCodec.of({ buf, v -> write(buf, v) }, { buf -> read(buf) })
//?} else {
/*/**
 * A packet, ours rather than vanilla's.
 *
 * The one method matches the shape `CustomPacketPayload` declares from 1.20.5, so every payload's
 * `override fun type() = TYPE` compiles unchanged on both sides of the boundary.
 */
interface BpmPayload {
    fun type(): PayloadType<*>
}

/** A packet's identity. Vanilla has no such type here, so the id is carried on its own. */
class PayloadType<T>(val id: ResourceLocation)

/**
 * A packet's wire form: the same two functions vanilla's `StreamCodec` would hold.
 *
 * A class rather than a pair of function references because the loader backends need to name the two
 * halves separately -- Forge's `SimpleChannel` wants an encoder and a decoder handed in apart from each
 * other, and Fabric 1.20.1 registers a receiver that reads straight from the buffer.
 */
class PayloadCodec<T>(
    val write: (FriendlyByteBuf, T) -> Unit,
    val read: (FriendlyByteBuf) -> T,
)

fun <T : BpmPayload> payloadType(id: ResourceLocation): PayloadType<T> = PayloadType(id)

fun <T : BpmPayload> payloadCodec(
    write: (FriendlyByteBuf, T) -> Unit,
    read: (FriendlyByteBuf) -> T,
): PayloadCodec<T> = PayloadCodec(write, read)
*///?}
