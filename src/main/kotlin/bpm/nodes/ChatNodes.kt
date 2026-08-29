package bpm.nodes

import bpm.catalog.McVs
import io.osrsx.vscript.nodes.Contribution
import io.osrsx.vscript.nodes.library
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3

/** `chat.*` — talking to the people in the world. */
object ChatNodes {
    fun contribution(host: ControllerHost): Contribution = library("chat", "Chat") {
        func("say") {
            title("Say")
            doc("Send a chat line to everyone in this dimension, or only to those within a radius of the controller.")
            val message = param("Message", McVs.string, "what to say")
            val radius = param("Radius", McVs.float, "0 for everyone in the dimension", default = 0.0)
            command {
                val text = Component.literal(message())
                val r = radius()
                val c = Vec3.atCenterOf(host.pos)
                host.level.players().forEach { p -> if (r <= 0.0 || p.position().distanceToSqr(c) <= r * r) p.sendSystemMessage(text) }
                null
            }
        }
        func("tell") {
            title("Tell Player")
            doc("Send a chat line to one player.")
            val player = param("Player", McVs.player, "who")
            val message = param("Message", McVs.string, "what to say")
            command {
                (host.entity(player()) as? ServerPlayer)?.sendSystemMessage(Component.literal(message()))
                null
            }
        }
        func("title") {
            title("Show Title")
            doc("Show a title across a player's screen, with an optional subtitle.")
            val player = param("Player", McVs.player, "who")
            val text = param("Title", McVs.string, "the big line")
            val subtitle = param("Subtitle", McVs.string, "the small line", default = "")
            command {
                val p = host.entity(player()) as? ServerPlayer ?: return@command null
                p.connection.send(ClientboundSetTitleTextPacket(Component.literal(text())))
                subtitle().takeIf { it.isNotBlank() }?.let { p.connection.send(ClientboundSetSubtitleTextPacket(Component.literal(it))) }
                null
            }
        }
        func("notify") {
            title("Notify")
            doc("A toast for whoever is watching this controller in the editor, at a level.")
            val message = param("Message", McVs.string, "what to say")
            val level = param("Level", McVs.notify, "how loud", default = "Info")
            command { host.notify(level(), message()); null }
        }
    }
}
