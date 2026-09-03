package bpm.platform.net

/*
 * Two eras, and this one is not a rename either.
 *
 * From 1.20.2 a packet is a `CustomPacketPayload` with its own id and codec, registered against a
 * `PayloadRegistrar` when NeoForge opens one. MinecraftForge 1.20.1 has `SimpleChannel`: one channel per
 * mod, messages registered by an integer index and a Java CLASS, encoder and decoder handed in apart.
 *
 * A class per payload is not something this seam can produce -- it is handed a type and a codec, not a
 * class -- so the older arm registers ONE message, an envelope carrying the payload's id and its bytes,
 * and dispatches on the id itself. That is the same shape the Fabric 1.20.1 backend uses, for the same
 * reason, and it costs one `ResourceLocation` on the wire per packet.
 */
//? if >=1.20.2 {
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.registration.PayloadRegistrar

/**
 * NeoForge's payload registration and `PacketDistributor`.
 *
 * Registrations are collected as they arrive and replayed against the registrar when
 * `RegisterPayloadHandlersEvent` fires, because that event is not open while the mod is wiring itself
 * up. Handlers run on the main thread of their side, which is the server thread for anything
 * server-bound and the render thread on the client — so nothing here needs a lock.
 */
object NeoNet : PlatformNet {

    private val pending = ArrayList<(PayloadRegistrar) -> Unit>()

    override fun <P : BpmPayload> toServer(
        type: PayloadType<P>,
        codec: PayloadCodec<P>,
        handler: (P, ServerPlayer) -> Unit,
    ) {
        pending += { r ->
            r.playToServer(type, codec) { p, ctx -> (ctx.player() as? ServerPlayer)?.let { handler(p, it) } }
        }
    }

    override fun <P : BpmPayload> toClient(
        type: PayloadType<P>,
        codec: PayloadCodec<P>,
        handler: (P) -> Unit,
    ) {
        pending += { r -> r.playToClient(type, codec) { p, _ -> handler(p) } }
    }

    override fun <P : BpmPayload> bidirectional(
        type: PayloadType<P>,
        codec: PayloadCodec<P>,
        onServer: (P, ServerPlayer) -> Unit,
        onClient: (P) -> Unit,
    ) {
        /*
         * Two handlers from the 1.21.6 band, one that inspects the flow before it.
         *
         * The single-handler `playBidirectional` used to register both directions. In the 1.21.6 band it
         * registers the SERVER side only -- and NeoForge VALIDATES that, refusing to load a mod with a
         * clientbound payload nobody handles. This is not a compile error: both overloads exist, the
         * three-argument one simply stops meaning what it used to, so it fails at load with
         * "Some clientbound payloads are missing client-side handlers: [bpm:chunk]" and takes the whole
         * client with it. Verified by javap: 21.1, 21.4 and 21.5 have only the three-argument form;
         * 21.8 and 21.11 have both.
         *
         * Keyed at >=1.21.6 rather than at the 21.8 where it was measured, deliberately. If a 1.21.6 or
         * 1.21.7 node is ever added and the overload is not there yet, this fails to COMPILE, which is
         * found immediately; keyed at >=1.21.8 the same node would compile and then die at load.
         *
         * This mod has exactly one bidirectional payload, `bpm:chunk`, which carries every big message in
         * pieces: the whole editor protocol rides on it. So on that band the check is the difference
         * between a loud failure at load and an editor that silently receives nothing. The four-argument
         * form is also the clearer statement, since the two handlers were already separate functions --
         * but it does not exist below 1.21.9, so both spellings stay.
         */
        //? if >=1.21.6 {
        /*pending += { r ->
            r.playBidirectional(
                type,
                codec,
                { p, ctx -> (ctx.player() as? ServerPlayer)?.let { onServer(p, it) } },
                { p, _ -> onClient(p) },
            )
        }
        *///?} else {
        pending += { r ->
            r.playBidirectional(type, codec) { p, ctx ->
                if (ctx.flow() == PacketFlow.SERVERBOUND) (ctx.player() as? ServerPlayer)?.let { onServer(p, it) } else onClient(p)
            }
        }
        //?}
    }

    /**
     * Client to server.
     *
     * Split out of `PacketDistributor` into a client-only `ClientPacketDistributor` at 1.21.6, which is
     * honest -- this direction was only ever callable from a client, and the class it lived in is loaded
     * on dedicated servers too.
     */
    //? if >=1.21.6 {
    /*override fun sendToServer(payload: BpmPayload) =
        net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(payload)
    *///?} else {
    override fun sendToServer(payload: BpmPayload) = PacketDistributor.sendToServer(payload)
    //?}

    override fun sendToPlayer(player: ServerPlayer, payload: BpmPayload) =
        PacketDistributor.sendToPlayer(player, payload)

    fun onRegisterPayloads(event: net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent) {
        val registrar = event.registrar(bpm.Bpm.ID).versioned(bpm.net.BpmNetwork.VERSION)
        for (block in pending) block(registrar)
    }
}
//?} else {
/*import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.server.level.ServerPlayer
import bpm.platform.ResourceLocation
import net.minecraftforge.network.NetworkRegistry
import net.minecraftforge.network.PacketDistributor
import net.minecraftforge.network.simple.SimpleChannel

/** One message, carrying which payload it is and the bytes of it. */
private class Envelope(val id: ResourceLocation, val body: ByteArray)

object NeoNet : PlatformNet {

    private val writers = HashMap<ResourceLocation, (FriendlyByteBuf, BpmPayload) -> Unit>()
    private val serverbound = HashMap<ResourceLocation, (FriendlyByteBuf, ServerPlayer) -> Unit>()
    private val clientbound = HashMap<ResourceLocation, (FriendlyByteBuf) -> Unit>()

    /**
     * The channel, built on first use.
     *
     * Both accepted-version predicates answer true because the mod is not required on the other end for
     * a vanilla connection to work -- the editor simply does nothing there, which is what the versioned
     * registrar arranges on the newer band.
     */
    private val channel: SimpleChannel by lazy {
        val built = NetworkRegistry.ChannelBuilder
            .named(bpm.platform.idOf(bpm.Bpm.ID, "main"))
            .networkProtocolVersion { bpm.net.BpmNetwork.VERSION }
            .clientAcceptedVersions { true }
            .serverAcceptedVersions { true }
            .simpleChannel()
        built.registerMessage(
            0,
            Envelope::class.java,
            { message, buf -> buf.writeResourceLocation(message.id); buf.writeByteArray(message.body) },
            { buf -> Envelope(buf.readResourceLocation(), buf.readByteArray()) },
            { message, ctx ->
                val context = ctx.get()
                val sender = context.sender
                // The bytes are already a copy, so reading them on the work thread is safe; the netty
                // buffer this arrived in is released the moment this returns.
                context.enqueueWork {
                    val buf = FriendlyByteBuf(Unpooled.wrappedBuffer(message.body))
                    if (sender != null) serverbound[message.id]?.invoke(buf, sender) else clientbound[message.id]?.invoke(buf)
                }
                context.packetHandled = true
            },
        )
        built
    }

    /** Build the channel. Called from the entry point, where the newer band registers its payloads. */
    fun registerChannel() {
        channel
    }

    @Suppress("UNCHECKED_CAST")
    private fun <P : BpmPayload> remember(type: PayloadType<P>, codec: PayloadCodec<P>) {
        writers[type.id] = { buf, payload -> codec.write(buf, payload as P) }
    }

    override fun <P : BpmPayload> toServer(
        type: PayloadType<P>,
        codec: PayloadCodec<P>,
        handler: (P, ServerPlayer) -> Unit,
    ) {
        remember(type, codec)
        serverbound[type.id] = { buf, player -> handler(codec.read(buf), player) }
    }

    override fun <P : BpmPayload> toClient(
        type: PayloadType<P>,
        codec: PayloadCodec<P>,
        handler: (P) -> Unit,
    ) {
        remember(type, codec)
        clientbound[type.id] = { buf -> handler(codec.read(buf)) }
    }

    override fun <P : BpmPayload> bidirectional(
        type: PayloadType<P>,
        codec: PayloadCodec<P>,
        onServer: (P, ServerPlayer) -> Unit,
        onClient: (P) -> Unit,
    ) {
        remember(type, codec)
        serverbound[type.id] = { buf, player -> onServer(codec.read(buf), player) }
        clientbound[type.id] = { buf -> onClient(codec.read(buf)) }
    }

    private fun envelope(payload: BpmPayload): Envelope {
        val id = payload.type().id
        val buf = FriendlyByteBuf(Unpooled.buffer())
        val write = writers[id] ?: error("bpm: payload $id was sent before it was registered")
        write(buf, payload)
        val body = ByteArray(buf.readableBytes())
        buf.readBytes(body)
        return Envelope(id, body)
    }

    override fun sendToServer(payload: BpmPayload) {
        channel.sendToServer(envelope(payload))
    }

    override fun sendToPlayer(player: ServerPlayer, payload: BpmPayload) {
        channel.send(PacketDistributor.PLAYER.with { player }, envelope(payload))
    }
}
*///?}
