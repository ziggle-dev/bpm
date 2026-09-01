package bpm.world.items

import bpm.library.BpmLibrary
import bpm.library.Programs
import bpm.world.ContentBlocks
import bpm.world.ContentItems
import bpm.world.DeviceBlocks
import bpm.world.LinkerItem
import bpm.world.ModComponents
import bpm.world.TooltipItem
import bpm.world.devices.GateBlock
import dev.ziggle.vscript.model.GraphDoc
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Equipable
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

private fun say(player: Player, text: String) = player.displayClientMessage(Component.literal("[bpm] $text"), true)

/** The Warden's Visor: worn on the head; the linker reaches half again as far and its lines show through walls. */
class WardenVisorItem(properties: Properties) : TooltipItem(properties), Equipable {
    override fun getEquipmentSlot(): EquipmentSlot = EquipmentSlot.HEAD

    override fun use(level: Level, player: Player, hand: InteractionHand): bpm.platform.UseResult = swapWithEquipmentSlot(this, level, player, hand)

    companion object {
        const val RANGE_FACTOR = 1.5

        fun worn(player: Player): Boolean = player.getItemBySlot(EquipmentSlot.HEAD).`is`(ContentItems.WARDEN_VISOR.get())
    }
}

/**
 * The Phase Gauntlet: use to blink up to eight blocks along the look vector, five seconds' cooldown; every
 * eighth blink eats an Entanglium Shard from the inventory (the count rides on the stack).
 */
class PhaseGauntletItem(properties: Properties) : TooltipItem(properties) {
    override fun use(level: Level, player: Player, hand: InteractionHand): bpm.platform.UseResult {
        val stack = player.getItemInHand(hand)
        if (player.cooldowns.isOnCooldown(this)) return bpm.platform.Use.pass(stack)
        if (level.isClientSide) return bpm.platform.Use.success(stack)
        val blinks = stack.getOrDefault(ModComponents.BLINKS.get(), 0)
        if (blinks >= BLINKS_PER_SHARD && !player.isCreative) {
            if (!take(player, ContentItems.ENTANGLIUM_SHARD.get())) {
                say(player, "the gauntlet needs an Entanglium Shard")
                return bpm.platform.Use.fail(stack)
            }
            stack.set(ModComponents.BLINKS.get(), 0)
        } else {
            stack.set(ModComponents.BLINKS.get(), blinks + 1)
        }
        val to = destination(level, player) ?: run {
            say(player, "nowhere to blink to")
            return bpm.platform.Use.fail(stack)
        }
        val from = player.position()
        (level as? ServerLevel)?.let { l ->
            l.sendParticles(ParticleTypes.PORTAL, from.x, from.y + 1.0, from.z, 24, 0.3, 0.6, 0.3, 0.2)
            l.sendParticles(ParticleTypes.PORTAL, to.x, to.y + 1.0, to.z, 24, 0.3, 0.6, 0.3, 0.2)
        }
        level.playSound(null, from.x, from.y, from.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.8f, 1.4f)
        player.teleportTo(to.x, to.y, to.z)
        player.fallDistance = 0f
        player.cooldowns.addCooldown(this, COOLDOWN_TICKS)
        return bpm.platform.Use.consume(stack)
    }

    /** The farthest free spot along the look, up to [RANGE] blocks, stepping back from whatever is in the way. */
    private fun destination(level: Level, player: Player): Vec3? {
        val eye = player.getEyePosition(1f)
        val look = player.lookAngle
        val end = eye.add(look.scale(RANGE))
        val hit = level.clip(ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
        val reach = if (hit.type == HitResult.Type.MISS) end else hit.location.subtract(look.scale(0.5))
        var d = reach.distanceTo(eye)
        while (d > 1.0) {
            val p = eye.add(look.scale(d))
            val feet = BlockPos.containing(p.x, p.y - player.eyeHeight, p.z)
            if (level.getBlockState(feet).getCollisionShape(level, feet).isEmpty && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty) {
                return Vec3(feet.x + 0.5, feet.y.toDouble(), feet.z + 0.5)
            }
            d -= 1.0
        }
        return null
    }

    private fun take(player: Player, item: net.minecraft.world.item.Item): Boolean {
        val inv = player.inventory
        for (i in 0 until inv.containerSize) {
            val s = inv.getItem(i)
            if (s.`is`(item)) {
                s.shrink(1)
                return true
            }
        }
        return false
    }

    override fun appendHoverText(stack: ItemStack, context: TooltipContext, tooltip: MutableList<Component>, flag: TooltipFlag) {
        super.appendHoverText(stack, context, tooltip, flag)
        tooltip.add(Component.literal("Charge: ${BLINKS_PER_SHARD - stack.getOrDefault(ModComponents.BLINKS.get(), 0)} blinks"))
    }

    companion object {
        const val RANGE = 8.0
        const val COOLDOWN_TICKS = 100
        const val BLINKS_PER_SHARD = 8
    }
}

/**
 * The Entangled Compass: held, it names the nearest Entanglium ore — or the nearest open gate, or the linker's
 * bound controller (sneak-use cycles) — with its distance and bearing on the action bar.
 */
class EntangledCompassItem(properties: Properties) : TooltipItem(properties) {
    private val found = HashMap<java.util.UUID, Pair<BlockPos, Long>>()

    override fun use(level: Level, player: Player, hand: InteractionHand): bpm.platform.UseResult {
        val stack = player.getItemInHand(hand)
        if (!player.isShiftKeyDown) return bpm.platform.Use.pass(stack)
        if (!level.isClientSide) {
            val next = when (stack.getOrDefault(ModComponents.COMPASS_MODE.get(), MODE_ORE)) {
                MODE_ORE -> MODE_GATE
                MODE_GATE -> MODE_CONTROLLER
                else -> MODE_ORE
            }
            stack.set(ModComponents.COMPASS_MODE.get(), next)
            found.remove(player.uuid)
            say(player, "compass seeks: ${label(next)}")
        }
        return bpm.platform.Use.sided(stack, level.isClientSide)
    }

    override fun inventoryTick(stack: ItemStack, level: Level, entity: Entity, slot: Int, selected: Boolean) {
        val player = entity as? ServerPlayer ?: return
        if (!selected && player.offhandItem !== stack) return
        if (level.gameTime % 20 != 0L) return
        val mode = stack.getOrDefault(ModComponents.COMPASS_MODE.get(), MODE_ORE)
        val target = target(level as ServerLevel, player, mode)
        if (target == null) {
            say(player, "${label(mode)}: nothing within ${SCAN.toInt()} blocks")
            return
        }
        val d = Vec3.atCenterOf(target).subtract(player.position())
        val dist = sqrt(d.x * d.x + d.z * d.z).roundToInt()
        val up = (target.y - player.blockY)
        say(player, "${label(mode)}: $dist m ${bearing(d)}${if (up > 1) " · $up up" else if (up < -1) " · ${-up} down" else ""}")
    }

    private fun label(mode: String) = when (mode) {
        MODE_GATE -> "open gate"
        MODE_CONTROLLER -> "bound controller"
        else -> "Entanglium ore"
    }

    private fun target(level: ServerLevel, player: ServerPlayer, mode: String): BlockPos? {
        if (mode == MODE_CONTROLLER) {
            val inv = player.inventory
            for (i in 0 until inv.containerSize) {
                val s = inv.getItem(i)
                if (s.item is LinkerItem) LinkerItem.selectedPos(s)?.let { return it }
            }
            return null
        }
        found[player.uuid]?.let { (pos, at) -> if (level.gameTime - at < RESCAN_TICKS && still(level, pos, mode)) return pos }
        val here = player.blockPosition()
        var best: BlockPos? = null
        var bestD = Double.MAX_VALUE
        val r = SCAN.toInt()
        for (p in BlockPos.betweenClosed(here.offset(-r, -r, -r), here.offset(r, r, r))) {
            if (!still(level, p, mode)) continue
            val dd = p.distSqr(here)
            if (dd < bestD) {
                bestD = dd
                best = p.immutable()
            }
        }
        found[player.uuid] = (best ?: BlockPos.ZERO) to level.gameTime
        return best
    }

    private fun still(level: Level, pos: BlockPos, mode: String): Boolean {
        val s = level.getBlockState(pos)
        return when (mode) {
            MODE_GATE -> s.`is`(DeviceBlocks.QUANTUM_GATE.get()) && s.getValue(GateBlock.OPEN)
            else -> s.`is`(ContentBlocks.ENTANGLIUM_ORE.get()) || s.`is`(ContentBlocks.DEEPSLATE_ENTANGLIUM_ORE.get())
        }
    }

    private fun bearing(d: Vec3): String {
        val angle = Math.toDegrees(atan2(-d.x, d.z))
        val names = listOf("south", "south-west", "west", "north-west", "north", "north-east", "east", "south-east")
        val i = (((angle + 360 + 22.5) % 360) / 45).toInt().coerceIn(0, 7)
        return names[i]
    }

    companion object {
        const val MODE_ORE = "ore"
        const val MODE_GATE = "gate"
        const val MODE_CONTROLLER = "controller"
        const val SCAN = 24.0
        const val RESCAN_TICKS = 100L
    }
}

/** The Warden's Program: a document item — using it puts the room's trap program into the world's library. */
class WardenProgramItem(properties: Properties) : TooltipItem(properties) {
    override fun use(level: Level, player: Player, hand: InteractionHand): bpm.platform.UseResult {
        val stack = player.getItemInHand(hand)
        if (level.isClientSide) return bpm.platform.Use.success(stack)
        val server = level.server ?: return bpm.platform.Use.pass(stack)
        val lib = BpmLibrary.get(server)
        val existing = lib.byName(Programs.WARDEN_PROGRAM)
        if (existing != null) {
            say(player, "'${Programs.WARDEN_PROGRAM}' is already in the library — import it into a controller's graph")
        } else {
            lib.create(Programs.WARDEN_PROGRAM, GraphDoc.toJson(Programs.wardenProgram()), player.uuid, isLibrary = true)
            say(player, "'${Programs.WARDEN_PROGRAM}' added to the library")
        }
        return bpm.platform.Use.consume(stack)
    }
}
