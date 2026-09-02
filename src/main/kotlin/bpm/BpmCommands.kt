package bpm

import bpm.library.BpmLibrary
import bpm.runtime.RuntimeManager
import bpm.world.ControllerBlockEntity
import bpm.world.Link
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import dev.ziggle.vscript.compile.Severity
import dev.ziggle.vscript.compile.Validator
import dev.ziggle.vscript.model.GraphDoc
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.coordinates.BlockPosArgument
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.server.MinecraftServer
import net.minecraft.server.dedicated.DedicatedServer
import java.nio.file.Files
import java.nio.file.Path
import bpm.catalog.BpmCatalog

/**
 * `/bpm …` — the operator's and the developer's handle on documents and controllers until the editor ships
 * its own UI for each of these. Every subcommand answers in chat and returns 1 on success.
 *
 * Documents come from `<gamedir>/bpm/graphs/<file>.json` (`GraphDoc` JSON, as the vscript editor saves it).
 */
object BpmCommands {
    fun register(event: bpm.platform.events.CommandRegistration) {
        val root = Commands.literal("bpm").requires { bpm.platform.mayAdminister(it) }
            .then(Commands.literal("stats").executes { ctx -> reply(ctx, RuntimeManager.stats()); 1 })
            .then(Commands.literal("ticks").executes { ctx -> reply(ctx, tickReport(ctx.source.server)); 1 })
            .then(
                Commands.literal("chamber")
                    .then(Commands.literal("enter").executes { ctx -> bpm.chamber.Chambers.enter(ctx.source.playerOrException, null); 1 })
                    .then(Commands.literal("leave").executes { ctx -> bpm.chamber.Chambers.leave(ctx.source.playerOrException); 1 })
                    .then(
                        Commands.literal("awaken").executes { ctx ->
                            val player = ctx.source.playerOrException
                            val slot = bpm.chamber.Chambers.get(ctx.source.server).slotAt(player.blockPosition())
                            val be = slot?.let { bpm.platform.levelOf(player).getBlockEntity(it.pedestal) as? bpm.world.devices.PedestalBlockEntity }
                            if (be == null || !bpm.chamber.ChamberFight.awaken(be)) {
                                ctx.source.sendFailure(Component.literal("no dormant chamber pedestal here"))
                                0
                            } else {
                                reply(ctx, "the Warden wakes")
                                1
                            }
                        },
                    )
                    .then(
                        Commands.literal("reset").then(
                            Commands.argument("player", net.minecraft.commands.arguments.GameProfileArgument.gameProfile()).executes { ctx ->
                                val server = ctx.source.server
                                val profiles = net.minecraft.commands.arguments.GameProfileArgument.getGameProfiles(ctx, "player")
                                var n = 0
                                for (profile in profiles) {
                                    val slot = bpm.chamber.Chambers.get(server).slots[profile.id] ?: continue
                                    bpm.chamber.Chambers.reset(server, slot)
                                    n++
                                }
                                reply(ctx, "reset $n chamber(s)")
                                n
                            },
                        ),
                    )
                    .then(
                        Commands.literal("list").executes { ctx ->
                            val slots = bpm.chamber.Chambers.get(ctx.source.server).slots.values
                            reply(ctx, if (slots.isEmpty()) "no chambers yet" else slots.joinToString("\n") { "${it.owner} -> slot ${it.index} at ${it.origin.toShortString()} / ${it.state.name.lowercase()}${if (it.built) "" else " (unbuilt)"}" })
                            slots.size
                        },
                    ),
            )
            .then(
                Commands.literal("warden").then(
                    Commands.literal("spawn").then(
                        Commands.argument("pos", BlockPosArgument.blockPos()).executes { ctx ->
                            val pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos")
                            val w = bpm.world.entity.QuantumWardenEntity.spawnAt(ctx.source.level, pos, null)
                            reply(ctx, "a Warden rises at ${pos.toShortString()} (${w.health.toInt()} hp)")
                            1
                        },
                    ),
                ),
            )
            .then(
                Commands.literal("gate").then(
                    Commands.literal("open").then(
                        Commands.argument("pos", BlockPosArgument.blockPos()).executes { ctx -> gateOpen(ctx, 10) }.then(
                            Commands.argument("minutes", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 600)).executes { ctx ->
                                gateOpen(ctx, com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "minutes"))
                            },
                        ),
                    ),
                ),
            )
            .then(
                Commands.literal("fluid").then(
                    Commands.argument("pos", BlockPosArgument.blockPos()).then(
                        Commands.argument("fluid", bpm.platform.idArgument()).then(
                            Commands.argument("mb", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0)).executes { ctx -> fluid(ctx) },
                        ),
                    ),
                ),
            )
            .then(
                Commands.literal("energy").then(
                    Commands.argument("pos", BlockPosArgument.blockPos()).then(
                        Commands.argument("fe", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0)).executes { ctx -> energy(ctx) },
                    ),
                ),
            )
            .then(
                Commands.literal("slot").then(
                    Commands.argument("pos", BlockPosArgument.blockPos()).then(
                        Commands.argument("slot", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 8)).then(
                            Commands.argument("item", net.minecraft.commands.arguments.item.ItemArgument.item(event.buildContext)).executes { ctx -> slot(ctx, 1) }.then(
                                Commands.argument("count", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 64)).executes { ctx ->
                                    slot(ctx, com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "count"))
                                },
                            ),
                        ),
                    ),
                ),
            )
            .then(Commands.literal("catalog").executes { ctx -> reply(ctx, "catalogue ${BpmCatalog.catalog.all.size} nodes, hash ${BpmCatalog.hash.take(16)}"); 1 })
            .then(
                Commands.literal("docs").executes { ctx ->
                    val lib = BpmLibrary.get(ctx.source.server)
                    if (lib.all.isEmpty()) reply(ctx, "no documents") else lib.all.forEach { reply(ctx, "${it.name}  v${it.version}  ${it.rawSize} B${if (it.hasErrors) "  (errors)" else ""}  ${it.id}") }
                    1
                },
            )
            .then(
                Commands.literal("doc")
                    .then(
                        Commands.literal("load").then(
                            Commands.argument("name", StringArgumentType.word()).then(
                                Commands.argument("file", StringArgumentType.word()).executes { ctx -> loadDoc(ctx) },
                            ),
                        ),
                    )
                    .then(
                        Commands.literal("export").then(
                            Commands.argument("name", StringArgumentType.word()).then(
                                Commands.argument("file", StringArgumentType.word()).executes { ctx -> exportDoc(ctx) },
                            ),
                        ),
                    )
                    .then(
                        Commands.literal("delete").then(
                            Commands.argument("name", StringArgumentType.word()).executes { ctx ->
                                val lib = BpmLibrary.get(ctx.source.server)
                                val rec = lib.byName(StringArgumentType.getString(ctx, "name")) ?: return@executes fail(ctx, "no such document")
                                RuntimeManager.all().filter { it.docId == rec.id }.forEach { it.bind(null) }
                                lib.delete(rec.id)
                                reply(ctx, "deleted '${rec.name}'")
                                1
                            },
                        ),
                    ),
            )
            .then(
                Commands.literal("bind").then(
                    Commands.argument("pos", BlockPosArgument.blockPos()).then(
                        Commands.argument("name", StringArgumentType.word()).executes { ctx ->
                            val be = controller(ctx) ?: return@executes 0
                            val lib = BpmLibrary.get(ctx.source.server)
                            val rec = lib.byName(StringArgumentType.getString(ctx, "name")) ?: return@executes fail(ctx, "no such document")
                            be.bind(rec.id)
                            reply(ctx, "bound '${rec.name}' to ${be.blockPos.toShortString()}${if (be.enabled) ", starting" else " (disabled)"}")
                            1
                        },
                    ),
                ),
            )
            .then(Commands.literal("unbind").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes { ctx -> controller(ctx)?.let { it.bind(null); reply(ctx, "unbound"); 1 } ?: 0 }))
            .then(Commands.literal("start").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes { ctx -> controller(ctx)?.let { it.setEnabled(true); reply(ctx, "enabled; ${it.describe()}"); 1 } ?: 0 }))
            .then(Commands.literal("restart").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes { ctx -> controller(ctx)?.let { it.requestRestart(); reply(ctx, "restart queued"); 1 } ?: 0 }))
            .then(Commands.literal("stop").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes { ctx -> controller(ctx)?.let { it.setEnabled(false); reply(ctx, "disabled"); 1 } ?: 0 }))
            .then(
                Commands.literal("status").then(
                    Commands.argument("pos", BlockPosArgument.blockPos()).executes { ctx ->
                        val be = controller(ctx) ?: return@executes 0
                        reply(ctx, be.describe())
                        be.lastError?.let { reply(ctx, "last error: $it") }
                        be.runtime?.runtime?.log?.records?.takeLast(8)?.forEach { reply(ctx, "  [${it.level}] ${it.message}") }
                        1
                    },
                ),
            )
            .then(
                Commands.literal("links").then(
                    Commands.argument("pos", BlockPosArgument.blockPos()).executes { ctx ->
                        val be = controller(ctx) ?: return@executes 0
                        if (be.links.all.isEmpty()) reply(ctx, "no links") else be.links.all.forEach { reply(ctx, "${it.name} → ${it.pos.toShortString()} ${it.side?.name?.lowercase() ?: "(any side)"}") }
                        1
                    },
                ),
            )
            .then(
                Commands.literal("tether").then(
                    Commands.argument("pos", BlockPosArgument.blockPos()).then(
                        Commands.literal("list").executes(::tetherList),
                    ).then(
                        Commands.literal("bind").then(
                            Commands.argument("player", net.minecraft.commands.arguments.EntityArgument.player())
                                .executes { ctx -> tetherBind(ctx, null) }
                                .then(
                                    Commands.argument("grants", StringArgumentType.greedyString()).executes { ctx ->
                                        tetherBind(ctx, StringArgumentType.getString(ctx, "grants"))
                                    },
                                ),
                        ),
                    ).then(
                        Commands.literal("unbind").then(
                            Commands.argument("player", net.minecraft.commands.arguments.EntityArgument.player()).executes(::tetherUnbind),
                        ),
                    ),
                ),
            )
            .then(
                Commands.literal("link").then(
                    Commands.argument("pos", BlockPosArgument.blockPos()).then(
                        Commands.argument("name", StringArgumentType.word()).then(
                            Commands.argument("target", BlockPosArgument.blockPos()).executes { ctx -> link(ctx, null) }.then(
                                Commands.argument("side", StringArgumentType.word()).executes { ctx ->
                                    val side = Direction.byName(StringArgumentType.getString(ctx, "side")) ?: return@executes fail(ctx, "no such side")
                                    link(ctx, side)
                                },
                            ),
                        ),
                    ),
                ),
            )
            .then(
                Commands.literal("unlink").then(
                    Commands.argument("pos", BlockPosArgument.blockPos()).then(
                        Commands.argument("name", StringArgumentType.word()).executes { ctx ->
                            val be = controller(ctx) ?: return@executes 0
                            val name = StringArgumentType.getString(ctx, "name")
                            if (be.links.remove(name)) { be.setChanged(); reply(ctx, "removed '$name'"); 1 } else fail(ctx, "no link '$name'")
                        },
                    ),
                ),
            )
        event.dispatcher.register(root)
    }

    /** `/bpm gate open <pos> [minutes]`: open a gate without a lens (0 minutes = stays open). */
    private fun gateOpen(ctx: CommandContext<CommandSourceStack>, minutes: Int): Int {
        val pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos")
        val be = ctx.source.level.getBlockEntity(pos) as? bpm.world.devices.GateBlockEntity
        if (be == null) {
            ctx.source.sendFailure(Component.literal("no gate at ${pos.toShortString()}"))
            return 0
        }
        be.revalidate()
        be.open(minutes * 60L * 20L)
        reply(ctx, "gate at ${pos.toShortString()} open (frame ${if (be.frameOk) "whole" else "incomplete"})")
        return 1
    }

    /** `/bpm fluid <pos> <fluid> <mb>`: put some fluid into the controller's tanks (0 mB empties every tank). */
    private fun fluid(ctx: CommandContext<CommandSourceStack>): Int {
        val be = controller(ctx) ?: return 0
        val id = bpm.platform.idArgumentOf(ctx, "fluid")
        val mb = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "mb")
        if (mb == 0) {
            be.tanks.clear()
            reply(ctx, "tanks of ${be.blockPos.toShortString()} emptied")
            return 1
        }
        val fluid = bpm.catalog.values.RegistryIds.fluid(id.toString())
        if (fluid == null) {
            ctx.source.sendFailure(Component.literal("no fluid called $id"))
            return 0
        }
        val filled = bpm.platform.ports.Droplets.toMb(be.tanks.fill(bpm.platform.ports.FluidVolume.ofMb(fluid, mb), simulate = false))
        reply(ctx, "tanks of ${be.blockPos.toShortString()}: +$filled mB of $id")
        return 1
    }

    /** `/bpm energy <pos> <fe>`: set the controller's energy cell. */
    private fun energy(ctx: CommandContext<CommandSourceStack>): Int {
        val be = controller(ctx) ?: return 0
        be.energy.set(com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "fe").toLong())
        reply(ctx, "energy of ${be.blockPos.toShortString()}: ${be.energy.stored} / ${be.energy.capacity} FE")
        return 1
    }

    /** `/bpm slot <pos> <slot> <item> [count]`: put a stack (components and all) into the controller's buffer. */
    private fun slot(ctx: CommandContext<CommandSourceStack>, count: Int): Int {
        val be = controller(ctx) ?: return 0
        val slot = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "slot")
        val stack = bpm.platform.itemStackOf(net.minecraft.commands.arguments.item.ItemArgument.getItem(ctx, "item"), count)
        be.inventory.setStackIn(slot, stack)
        be.setChanged()
        reply(ctx, "slot $slot of ${be.blockPos.toShortString()}: ${stack.count} × ${stack.hoverName.string}${if (stack.isEnchanted) " (enchanted)" else ""}")
        return 1
    }

    private fun link(ctx: CommandContext<CommandSourceStack>, side: Direction?): Int {
        val be = controller(ctx) ?: return 0
        val name = StringArgumentType.getString(ctx, "name")
        val target = BlockPosArgument.getBlockPos(ctx, "target")
        val link = be.links.add(Link(name, target, side, ctx.source.level.dimension()))
            ?: return fail(ctx, "the controller is full at ${be.links.capacity} links (${be.coreTier.label} core)")
        be.setChanged()
        reply(ctx, "linked '${link.name}' → ${target.toShortString()} ${side?.name?.lowercase() ?: "(any side)"}")
        return 1
    }

    private fun loadDoc(ctx: CommandContext<CommandSourceStack>): Int {
        val name = StringArgumentType.getString(ctx, "name")
        val file = graphFile(StringArgumentType.getString(ctx, "file"))
        if (!Files.isRegularFile(file)) return fail(ctx, "no such file: $file")
        val json = Files.readString(file)
        val graph = runCatching { GraphDoc.fromJson(json) }.getOrElse { return fail(ctx, "not a graph document: $it") }
        val lib = BpmLibrary.get(ctx.source.server)
        val issues = Validator(BpmCatalog.catalog, lib.graphSource(), tickMayWait = true).validate(graph)
        val errors = issues.filter { it.severity == Severity.ERROR }
        val existing = lib.byName(name)
        val record = if (existing != null) lib.store(existing.id, json, errors.isNotEmpty())!! else lib.create(name, json, ctx.source.player?.uuid, isLibrary = true)
        reply(ctx, "${if (existing != null) "updated" else "created"} '${record.name}' v${record.version}: ${graph.nodes.size} nodes, ${issues.size} issues")
        errors.take(6).forEach { reply(ctx, "  error: ${it.message}${it.nodeId?.let { n -> " (node $n)" } ?: ""}") }
        if (existing != null) {
            val bound = RuntimeManager.all().filter { it.docId == record.id }
            if (errors.isEmpty()) bound.forEach { it.requestRestart() }
            reply(ctx, "${bound.size} controllers bound${if (errors.isEmpty()) ", restarting" else " — not restarted, the document has errors"}")
        }
        return 1
    }

    private fun exportDoc(ctx: CommandContext<CommandSourceStack>): Int {
        val lib = BpmLibrary.get(ctx.source.server)
        val rec = lib.byName(StringArgumentType.getString(ctx, "name")) ?: return fail(ctx, "no such document")
        val file = graphFile(StringArgumentType.getString(ctx, "file"))
        Files.createDirectories(file.parent)
        Files.writeString(file, lib.text(rec.id) ?: return fail(ctx, "document unreadable"))
        reply(ctx, "wrote $file")
        return 1
    }

    private fun graphFile(name: String): Path {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").removeSuffix(".json")
        return bpm.platform.Platform.gameDir.resolve("bpm").resolve("graphs").resolve("$safe.json")
    }

    /**
     * `/bpm tether bind <pos> <player> [grants]`: make <player> a presence link of the controller, and stamp
     * the credential onto whatever they are holding.
     *
     * The credential is two data components, not an item class (see `bpm.world.Tethers`), so this exercises
     * exactly the path the Quantum Tether will — it is a debug affordance, not a bypass around the checks.
     */
    private fun tetherBind(ctx: CommandContext<CommandSourceStack>, grantText: String?): Int {
        val be = controller(ctx) ?: return 0
        val player = net.minecraft.commands.arguments.EntityArgument.getPlayer(ctx, "player")
        val grants = if (grantText == null) bpm.world.Grants.DELIVERY else bpm.world.Grants.parse(grantText)
        if (grants.isEmpty()) return fail(ctx, "no grants understood in '$grantText' — try: ${bpm.world.Grant.entries.joinToString(",") { it.key }}")

        val held = player.mainHandItem
        if (held.isEmpty) return fail(ctx, "${player.name.string} is holding nothing to bind — a tether has to live on a stack")

        val existing = be.links.byPlayer(player.uuid)
        val link = existing ?: be.links.add(
            bpm.world.Link(be.links.presenceName(player.name.string), player.blockPosition(), null, player.level().dimension(), player.uuid),
        ) ?: return fail(ctx, "this controller already holds ${be.links.presenceCapacity} presence links (${be.coreTier.label} core)")

        bpm.world.Tethers.bind(held, net.minecraft.core.GlobalPos.of(ctx.source.level.dimension(), be.blockPos), grants)
        be.setChanged()
        reply(ctx, "'${link.name}' → ${player.name.string} · ${bpm.world.Grants.label(grants)} (${bpm.world.Grants.format(grants)}) · on ${held.hoverName.string}")
        return 1
    }

    private fun tetherUnbind(ctx: CommandContext<CommandSourceStack>): Int {
        val be = controller(ctx) ?: return 0
        val player = net.minecraft.commands.arguments.EntityArgument.getPlayer(ctx, "player")
        val here = net.minecraft.core.GlobalPos.of(ctx.source.level.dimension(), be.blockPos)
        bpm.world.Tethers.stack(player, here)?.let { bpm.world.Tethers.unbind(it) }
        if (!be.links.removePlayer(player.uuid)) return fail(ctx, "${player.name.string} is not a presence link of this controller")
        be.setChanged()
        reply(ctx, "${player.name.string} is no longer linked to ${be.blockPos.toShortString()}")
        return 1
    }

    private fun tetherList(ctx: CommandContext<CommandSourceStack>): Int {
        val be = controller(ctx) ?: return 0
        val links = be.links.presence
        if (links.isEmpty()) {
            reply(ctx, "no presence links (room for ${be.links.presenceCapacity})")
            return 1
        }
        reply(ctx, "${links.size}/${be.links.presenceCapacity} presence links:")
        val level = ctx.source.level
        for (l in links) {
            val resolved = bpm.world.PresenceLink(l, level, be)
            val who = resolved.player?.name?.string ?: l.player.toString()
            val why = resolved.reason()
            reply(ctx, "  ${l.name} → $who · " + (why ?: "reachable · ${bpm.world.Grants.format(resolved.grants)}"))
        }
        return 1
    }

    private fun controller(ctx: CommandContext<CommandSourceStack>): ControllerBlockEntity? {
        val pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos")
        val be: BlockEntity? = ctx.source.level.getBlockEntity(pos)
        if (be !is ControllerBlockEntity) {
            fail(ctx, "no controller at ${pos.toShortString()}")
            return null
        }
        return be
    }

    private fun reply(ctx: CommandContext<CommandSourceStack>, text: String) {
        ctx.source.sendSuccess({ Component.literal(text) }, false)
    }

    private fun fail(ctx: CommandContext<CommandSourceStack>, text: String): Int {
        ctx.source.sendFailure(Component.literal(text))
        return 0
    }

    /**
     * The server's last 100 tick times — the first thing to look at when "everything stops for a moment": a
     * spike here is the tick (profile it with `/debug start` … `/debug stop`); no spike means the stall is
     * elsewhere (I/O, network, the client). A dedicated server also reports `sync-chunk-writes`, whose default
     * (true) makes every chunk save an fsync — with chunks that change every tick being re-saved every 10 s,
     * that is the classic periodic stutter on Windows.
     */
    fun tickReport(server: MinecraftServer): String {
        val ms = server.tickTimesNanos.map { it / 1e6 }
        val spikes = ms.filter { it > 50 }
        val where = if (spikes.isEmpty()) "" else " (" + spikes.joinToString(", ") { "%.0f".format(it) } + " ms)"
        val sync = (server as? DedicatedServer)?.properties?.syncChunkWrites
        val env = if (sync == null) "integrated server" else "dedicated server, sync-chunk-writes=$sync"
        return "last 100 ticks: avg %.1f ms, max %.1f ms, %d over 50 ms%s; %s".format(ms.average(), ms.max(), spikes.size, where, env)
    }
}
