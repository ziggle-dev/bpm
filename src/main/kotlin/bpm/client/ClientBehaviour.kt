package bpm.client

import bpm.client.mc.SmokeRun
import bpm.client.mc.WorkbenchSession
import bpm.client.net.ClientNet
import bpm.platform.events.BpmEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.commands.Commands

/**
 * What the client DOES, as opposed to what each loader has to be told about it.
 *
 * Every line here subscribes to a `BpmEvents` hook, and a hook is the mod's own — both loaders already
 * fire all of these from their event bridges. So none of it is loader-specific, and it used to live in
 * `BpmClient` anyway, which is to say on NeoForge only.
 *
 * That was a real bug rather than untidiness. Fabric installed the seams and registered its renderers,
 * keys and HUD, and then subscribed NOTHING: no world-render listener, so no transfer effects drew at
 * all; no tick listener, so `ClientNet`'s queue was never pumped and `EffectManager` never advanced; no
 * key handling; no cleanup on disconnect. The Fabric client looked like it worked because everything
 * that fails here fails SILENTLY -- a hook with no listeners is not an error, it is just quiet.
 *
 * Splitting it out is what stops the two entry points drifting again: there is now one list, and a
 * loader that forgets to call [install] has no client behaviour at all rather than most of it.
 */
object ClientBehaviour {

    /**
     * Subscribe the client's behaviour. Call once per client start, after the seams are installed.
     *
     * Ordering does not matter among these -- they are all subscriptions, and nothing fires until the
     * game ticks or draws -- but the seams they eventually reach must already be installed, which is why
     * both entry points call this after their `install` block.
     */
    fun install() {
        BpmEvents.screenOpened.listen(::onScreenOpened)
        BpmEvents.leftClickEmpty.listen { player ->
            if (player.isShiftKeyDown && bpm.chamber.ChamberDimension.isChamber(player.level())) {
                bpm.world.LinkerItem.handWith(player)?.let { ClientNet.sendLinkerTrack(it) }
            }
        }
        BpmEvents.registerClientCommands.listen(::onRegisterClientCommands)
        BpmEvents.clientTickEnd.listen { ClientNet.tick() }
        BpmEvents.clientTickStart.listen { Keys.tick() }
        BpmEvents.rawKey.listen(Keys::onKey)
        BpmEvents.clientTickEnd.listen { bpm.client.fx.EffectManager.tick() }
        BpmEvents.clientDisconnect.listen {
            ClientNet.reset(); WorkbenchSession.reset(); bpm.client.mc.BlockPreviewRenderer.clear(); bpm.client.mc.HudOverlay.reset(); Keys.reset()
        }
        BpmEvents.worldRenderTranslucent.listen(bpm.client.fx.EffectManager::render)
    }

    private fun onScreenOpened(screen: net.minecraft.client.gui.screens.Screen) {
        if (screen is TitleScreen && SmokeRun.claim()) {
            // Not from inside the init of the screen being replaced: next tick.
            Minecraft.getInstance().execute { SmokeRun.start() }
        }
    }

    private fun onRegisterClientCommands(event: bpm.platform.events.CommandRegistration) {
        event.dispatcher.register(
            Commands.literal("bpm")
                .then(
                    Commands.literal("editor").executes {
                        ClientHooks.openWorkbench(null)
                        1
                    },
                )
                .then(
                    // For "the pictures are blank in my modpack": says whether anything was asked for,
                    // whether it drew, and what went wrong if it threw.
                    Commands.literal("previews").executes { ctx ->
                        ctx.source.sendSuccess({ net.minecraft.network.chat.Component.literal(bpm.client.mc.BlockPreviewRenderer.diagnostics()) }, false)
                        1
                    },
                )
                // Both rift looks are built; this picks which one draws, live, with no restart.
                .then(
                    Commands.literal("rift").then(
                        Commands.argument("style", com.mojang.brigadier.arguments.StringArgumentType.word())
                            .suggests { _, builder ->
                                bpm.client.render.RiftStyle.entries.forEach { builder.suggest(it.name.lowercase()) }
                                builder.buildFuture()
                            }
                            .executes { ctx ->
                                val name = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "style")
                                val picked = bpm.client.render.RiftStyle.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                                if (picked == null) {
                                    ctx.source.sendFailure(net.minecraft.network.chat.Component.literal("unknown rift style '$name' (cube, tear)"))
                                    0
                                } else {
                                    bpm.client.render.RiftRenderer.style = picked
                                    ctx.source.sendSuccess({ net.minecraft.network.chat.Component.literal("rift style: ${picked.name.lowercase()}") }, false)
                                    1
                                }
                            },
                    ),
                ),
        )
    }
}
