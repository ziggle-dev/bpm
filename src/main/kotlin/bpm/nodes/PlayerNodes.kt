package bpm.nodes

import bpm.catalog.McVs
import bpm.catalog.values.BlockPosValue
import bpm.catalog.values.EntityHandle
import bpm.catalog.values.ItemStackValue
import bpm.runtime.PredicateJob
import bpm.world.Grant
import bpm.world.PresenceLink
import dev.ziggle.vscript.nodes.Contribution
import dev.ziggle.vscript.nodes.library
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import kotlin.math.sqrt
import bpm.platform.keyId

/**
 * `player.*` — reading the people who have tethered themselves to this controller.
 *
 * Every verb here goes through a **presence link** ([PresenceLink]): the player carries a Quantum Tether bound
 * to this controller, is in reach of it, and granted [Grant.STATE]. Without all three these answer empty, and
 * say why once in the console. `world.entityInfo` stays the public surface for anyone's name, position and
 * kind; this library is what a tether opens, and nothing here works on a stranger.
 *
 * See `docs/DESIGN_PLAYER_LINK.md` §11.
 */
object PlayerNodes {

    fun contribution(host: ControllerHost): Contribution = library("player", "Player") {

        // ---- finding people ------------------------------------------------------------------------

        func("of") {
            title("Player Of Link")
            doc("The person a presence link points at. Nothing when the link is not one, or nobody is there.")
            val link = param("Link", McVs.link, "a presence link")
            result("Player", McVs.player.orNull())
            query { (host.link(link()) as? PresenceLink)?.player?.let(EntityHandle::of) }
        }
        func("link") {
            title("Link Of Player")
            doc("The presence link this controller knows a player by, or nothing when they have not tethered to it.")
            val player = param("Player", McVs.player, "who")
            result("Link", McVs.link.orNull())
            query {
                val p = host.entity(player()) as? Player ?: return@query null
                host.links.byPlayer(p.uuid)?.name
            }
        }
        func("tethered") {
            title("Is Tethered")
            doc(
                """
                Whether this controller can reach a player right now — they are online, in range, carrying a
                tether bound to it, and it grants something. The question to branch on before the rest.
                """,
            )
            val player = param("Player", McVs.player, "who")
            result("Tethered", McVs.bool)
            query { presence(host, player()) != null }
        }
        func("grants") {
            title("Grants")
            doc("What this controller may do to a player, as the words their tether carries: `state`, `read`, `give`, `take`, `hud`, `input`, `equip`, `ender`. Empty when it may do nothing.")
            val player = param("Player", McVs.player, "who")
            result("Grants", McVs.string.list())
            query { presence(host, player())?.grants?.map { it.key } ?: emptyList<String>() }
        }
        func("online") {
            title("Player By Name")
            doc("A player on this server by name, or nothing when nobody is called that.")
            val name = param("Name", McVs.string, "their name")
            result("Player", McVs.player.orNull())
            query {
                val want = name().trim()
                if (want.isEmpty()) return@query null
                host.level.server.playerList.players.firstOrNull { it.name.string.equals(want, ignoreCase = true) }?.let(EntityHandle::of)
            }
        }
        func("all") {
            title("Tethered Players")
            doc("Everyone this controller can reach right now — its presence links, minus whoever is offline, out of range or no longer carrying a tether.")
            result("Players", McVs.player.list())
            query {
                host.links.presence.mapNotNull { l ->
                    (host.link(l.name) as? PresenceLink)?.takeIf { it.loaded }?.player?.let(EntityHandle::of)
                }
            }
        }

        // ---- what they are ------------------------------------------------------------------------

        func("state") {
            title("Player State")
            doc(
                """
                How a tethered player is doing: hit points of a maximum, food and saturation, air left,
                armour, and experience. Zeroes when the tether does not grant `state`.
                """,
            )
            val player = param("Player", McVs.player, "who")
            val health = result("Health", McVs.float)
            val maxHealth = result("MaxHealth", McVs.float)
            val food = result("Food", McVs.int)
            val saturation = result("Saturation", McVs.float)
            val air = result("Air", McVs.int)
            val armour = result("Armour", McVs.int)
            val xpLevel = result("XpLevel", McVs.int)
            val xpProgress = result("XpProgress", McVs.float)
            val totalXp = result("TotalXp", McVs.int)
            query {
                health set 0.0; maxHealth set 0.0; food set 0L; saturation set 0.0
                air set 0L; armour set 0L; xpLevel set 0L; xpProgress set 0.0; totalXp set 0L
                val p = granted(host, player(), Grant.STATE, "state") ?: return@query null
                health set p.health.toDouble()
                maxHealth set p.maxHealth.toDouble()
                food set p.foodData.foodLevel.toLong()
                saturation set p.foodData.saturationLevel.toDouble()
                air set p.airSupply.toLong()
                armour set p.armorValue.toLong()
                xpLevel set p.experienceLevel.toLong()
                xpProgress set p.experienceProgress.toDouble()
                totalXp set p.totalExperience.toLong()
                null
            }
        }
        func("where") {
            title("Player Position")
            doc(
                """
                Where a tethered player is and which way they face: the block they stand in, their dimension,
                how far they are from the controller, the block they are looking at within 24 blocks, and
                whether their feet are down. Empty when the tether does not grant `state`.
                """,
            )
            val player = param("Player", McVs.player, "who")
            val pos = result("Pos", McVs.blockPos.orNull())
            val dimension = result("Dimension", McVs.string)
            val yaw = result("Yaw", McVs.float)
            val pitch = result("Pitch", McVs.float)
            val distance = result("Distance", McVs.float)
            val lookingAt = result("LookingAt", McVs.blockPos.orNull())
            val onGround = result("OnGround", McVs.bool)
            query {
                pos set null; dimension set ""; yaw set 0.0; pitch set 0.0
                distance set 0.0; lookingAt set null; onGround set false
                val p = granted(host, player(), Grant.STATE, "state") ?: return@query null
                pos set BlockPosValue.of(p.blockPosition())
                dimension set p.level().dimension().keyId().toString()
                yaw set p.yRot.toDouble()
                pitch set p.xRot.toDouble()
                distance set sqrt(p.blockPosition().distSqr(host.pos).toDouble())
                onGround set p.onGround()
                val hit = p.pick(LOOK_REACH, 1f, false) as? net.minecraft.world.phys.BlockHitResult
                if (hit != null && hit.type == net.minecraft.world.phys.HitResult.Type.BLOCK) lookingAt set BlockPosValue.of(hit.blockPos)
                null
            }
        }
        func("flags") {
            title("Player Flags")
            doc("What a tethered player is doing with their body, and which game mode they are in. All false when the tether does not grant `state`.")
            val player = param("Player", McVs.player, "who")
            val sneaking = result("Sneaking", McVs.bool)
            val sprinting = result("Sprinting", McVs.bool)
            val flying = result("Flying", McVs.bool)
            val swimming = result("Swimming", McVs.bool)
            val sleeping = result("Sleeping", McVs.bool)
            val creative = result("Creative", McVs.bool)
            val spectator = result("Spectator", McVs.bool)
            query {
                sneaking set false; sprinting set false; flying set false; swimming set false
                sleeping set false; creative set false; spectator set false
                val p = granted(host, player(), Grant.STATE, "state") ?: return@query null
                sneaking set p.isShiftKeyDown
                sprinting set p.isSprinting
                flying set p.abilities.flying
                swimming set p.isSwimming
                sleeping set p.isSleeping
                creative set p.isCreative
                spectator set p.isSpectator
                null
            }
        }
        func("selectedSlot") {
            title("Selected Slot")
            doc("Which hotbar slot a tethered player has out, 0 to 8. -1 when the tether does not grant `state`.")
            val player = param("Player", McVs.player, "who")
            result("Slot", McVs.int)
            query { granted(host, player(), Grant.STATE, "state")?.inventory?.let { bpm.platform.selectedSlot(it).toLong() } ?: -1L }
        }
        func("held") {
            title("Held Stack")
            doc("What a tethered player has in a hand. Nothing for an empty hand, or when the tether does not grant `state`.")
            val player = param("Player", McVs.player, "who")
            val hand = param("Hand", McVs.hand, "which hand", default = "Main")
            result("Stack", McVs.itemStack.orNull())
            query {
                val p = granted(host, player(), Grant.STATE, "state") ?: return@query null
                ItemStackValue.record(if (hand().equals("Off", true)) p.offhandItem else p.mainHandItem)
            }
        }
        func("armour") {
            title("Worn Stack")
            doc("What a tethered player is wearing in a slot. Nothing for an empty slot, or when the tether does not grant `state`.")
            val player = param("Player", McVs.player, "who")
            val slot = param("Slot", McVs.equip, "which slot", default = "Head")
            result("Stack", McVs.itemStack.orNull())
            query {
                val p = granted(host, player(), Grant.STATE, "state") ?: return@query null
                ItemStackValue.record(p.getItemBySlot(equipSlot(slot())))
            }
        }
        func("effects") {
            title("Player Effects")
            doc("Every status effect on a tethered player, by id, mapped to its amplifier (0 is level I). Empty when the tether does not grant `state`.")
            val player = param("Player", McVs.player, "who")
            result("Effects", McVs.stringIntMap)
            query {
                val p = granted(host, player(), Grant.STATE, "state") ?: return@query LinkedHashMap<Any?, Any?>()
                val out = LinkedHashMap<Any?, Any?>()
                for (e in p.activeEffects) {
                    val id = BuiltInRegistries.MOB_EFFECT.getKey(e.effect.value())?.toString() ?: continue
                    out[id] = e.amplifier.toLong()
                }
                out
            }
        }
        func("effect") {
            title("Player Effect")
            doc("One status effect on a tethered player, by id (`minecraft:regeneration`): whether it is on them, how strong (0 is level I), and how many ticks are left.")
            val player = param("Player", McVs.player, "who")
            val id = param("Effect", McVs.string, "a mob effect id")
            val has = result("Has", McVs.bool)
            val amplifier = result("Amplifier", McVs.int)
            val ticks = result("Ticks", McVs.int)
            query {
                has set false; amplifier set 0L; ticks set 0L
                val p = granted(host, player(), Grant.STATE, "state") ?: return@query null
                val rl = bpm.platform.ResourceLocation.tryParse(id().trim()) ?: return@query null
                val kind = BuiltInRegistries.MOB_EFFECT.getOptional(rl).orElse(null) ?: return@query null
                val active = p.activeEffects.firstOrNull { it.effect.value() === kind } ?: return@query null
                has set true
                amplifier set active.amplifier.toLong()
                ticks set active.duration.toLong()
                null
            }
        }

        func("key") {
            title("Key")
            doc(
                """
                A key, by name — the standalone value to wire into Key Pressed, Key Held or Wait For Key, or
                to keep in a variable so one graph names its keys in one place.

                The pin has the whole keyboard behind it: type to search, or write the name yourself
                (`g`, `f7`, `left_shift`).
                """,
            )
            spelledAs("key")
            param("Key", McVs.key, "which key", default = "g")
            result("Key", McVs.key)
            construct()
        }

        // ---- what they press ------------------------------------------------------------------------

        func("keyPressed") {
            title("Key Pressed")
            doc(
                """
                Whether a tethered player pressed a key since this was last asked, and takes the press. Name
                the key itself — `g`, `f7`, `left_shift` — no binding needed: the graph asks and their client
                starts reporting that one key, and only that one.

                `Modifier` wants it only while the bpm modifier key is held (Left Alt by default, rebindable in
                Controls), which is on by default so a graph cannot quietly claim a bare letter. `Consume`
                stops the game seeing the key at all, so `alt+W` can run a graph without walking you forward.
                Needs the tether's `input` grant.
                """,
            )
            val player = param("Player", McVs.player, "who")
            val key = param("Key", McVs.key, "which key, by name")
            val modifier = param("Modifier", McVs.bool, "only while the bpm modifier key is held", default = true)
            val consume = param("Consume", McVs.bool, "and stop the game seeing it", default = false)
            result("Pressed", McVs.bool)
            query {
                val p = granted(host, player(), Grant.INPUT, "keyPressed") ?: return@query false
                val k = bpm.world.KeyNames.normalise(key())
                if (k.isEmpty()) return@query false
                host.watchKey(p.uuid, k, modifier(), consume())
                host.takeKey(p.uuid, k)
            }
        }
        func("keyHeld") {
            title("Key Held")
            doc("Whether a tethered player is holding a key down right now. The same naming, modifier and consume rules as Key Pressed. Needs the tether's `input` grant.")
            val player = param("Player", McVs.player, "who")
            val key = param("Key", McVs.key, "which key, by name")
            val modifier = param("Modifier", McVs.bool, "only while the bpm modifier key is held", default = true)
            val consume = param("Consume", McVs.bool, "and stop the game seeing it", default = false)
            result("Held", McVs.bool)
            query {
                val p = granted(host, player(), Grant.INPUT, "keyHeld") ?: return@query false
                val k = bpm.world.KeyNames.normalise(key())
                if (k.isEmpty()) return@query false
                host.watchKey(p.uuid, k, modifier(), consume())
                host.keyHeld(p.uuid, k)
            }
        }
        func("waitForKey") {
            title("Wait For Key")
            doc(
                """
                Park until a tethered player presses a named key, or the timeout passes.

                **Exec carries on either way, and `Ok` is the only thing that tells them apart.** A timeout
                is not a dead end — it resumes the fiber exactly as a press does. Leave Timeout Ticks at 0
                for a key you mean to wait on indefinitely, which is nearly always what a key wants; give it
                a timeout only when something downstream reads `Ok` and branches on it. A toggle wired
                straight off Exec with a timeout set will flip itself on that timer.

                A whole branch of `on tick` can sit here as long as it likes — that is what the fibers are
                for, and it costs nothing while it waits. The same naming, modifier and consume rules as
                Key Pressed.
                """,
            )
            val player = param("Player", McVs.player, "who")
            val key = param("Key", McVs.key, "which key, by name")
            val modifier = param("Modifier", McVs.bool, "only while the bpm modifier key is held", default = true)
            val consume = param("Consume", McVs.bool, "and stop the game seeing it", default = false)
            val timeout = param("Timeout Ticks", McVs.int, "give up after this many ticks; 0 = never", default = 0L)
            result("Ok", McVs.bool)
            action {
                val handle = player()
                val k = bpm.world.KeyNames.normalise(key())
                val wantMod = modifier()
                val wantConsume = consume()
                // Ask for the key BEFORE parking, not from inside the predicate. The watch has to reach the
                // client before it will report anything, and registering on the job's first poll left a
                // window — a tick, plus the round trip — in which the very first press was never sent at all.
                (host.entity(handle) as? Player)?.let { host.watchKey(it.uuid, k, wantMod, wantConsume) }
                host.jobs.start(
                    PredicateJob("player.waitForKey", timeout().toInt()) {
                        if (k.isEmpty()) return@PredicateJob false
                        val link = (host.entity(handle) as? Player)?.let { host.presence(it.uuid) }
                        val p = link?.takeIf { it.mayI(Grant.INPUT) }?.player ?: return@PredicateJob false
                        // Registered from inside the job: the watch has to survive as long as the wait does.
                        host.watchKey(p.uuid, k, wantMod, wantConsume)
                        host.takeKey(p.uuid, k)
                    },
                )
            }
        }

        // ---- waiting on them ------------------------------------------------------------------------

        func("waitForNear") {
            title("Wait For Player")
            doc(
                """
                Park until a tethered player comes within a radius of the controller, or the timeout passes.

                **Exec carries on either way; `Ok` is what tells them apart.** A player who is offline or has
                put their tether away never arrives, so a timeout is usually right here — but whatever runs
                next must read `Ok`, or it will treat "gave up" as "arrived".
                """,
            )
            val player = param("Player", McVs.player, "who")
            val radius = param("Radius", McVs.float, "how close, in blocks", default = 8.0)
            val timeout = param("Timeout Ticks", McVs.int, "give up after this many ticks; 0 = never", default = 200L)
            result("Ok", McVs.bool)
            action {
                val handle = player()
                val r = radius().coerceAtLeast(0.0)
                val centre = Vec3.atCenterOf(host.pos)
                host.jobs.start(
                    PredicateJob("player.waitForNear", timeout().toInt()) {
                        val p = presence(host, handle)?.player
                        p != null && p.position().distanceToSqr(centre) <= r * r
                    },
                )
            }
        }
    }

    /** How far `where` looks for the block a player has their crosshair on. */
    private const val LOOK_REACH = 24.0

    /** The presence link for whoever [handle] names, when this controller may reach them at all. */
    private fun presence(host: ControllerHost, handle: Any?): PresenceLink? {
        val p = host.entity(handle) as? Player ?: return null
        return host.presence(p.uuid)?.takeIf { it.loaded }
    }

    /**
     * The live player behind [handle], but only if their tether is open for [grant]. Otherwise null, with one
     * line in the console saying which of the several possible reasons it was — a graph that reads zeroes
     * should be able to find out why without guessing.
     */
    private fun granted(host: ControllerHost, handle: Any?, grant: Grant, verb: String): ServerPlayer? {
        val p = host.entity(handle) as? Player ?: return null
        val link = host.presence(p.uuid) ?: run {
            host.warnOnce("player/${p.uuid}/tether", "'${p.name.string}' is not tethered to this controller")
            return null
        }
        val why = link.reason(grant)
        if (why != null) {
            host.warnOnce("player/${p.uuid}/$verb", "player.$verb on '${p.name.string}': $why")
            return null
        }
        return link.player
    }

    private fun equipSlot(name: String): EquipmentSlot = when (name.lowercase()) {
        "chest" -> EquipmentSlot.CHEST
        "legs" -> EquipmentSlot.LEGS
        "feet" -> EquipmentSlot.FEET
        "offhand" -> EquipmentSlot.OFFHAND
        else -> EquipmentSlot.HEAD
    }
}
