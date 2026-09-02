package bpm.runtime

import bpm.platform.world.Actor
import bpm.platform.world.WorldActor
import bpm.Bpm
import bpm.net.EffectKind
import bpm.net.EffectOp
import bpm.catalog.values.ItemStackValue
import bpm.catalog.values.RegistryIds
import bpm.nodes.ControllerHost
import bpm.world.ResolvedLink
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.commands.arguments.EntityAnchorArgument
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.Attribute
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.item.Items
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.block.Block
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3

/** The one pseudo-player every controller acts through: breaking, using, placing. */
object BpmFakePlayer {
    fun get(level: ServerLevel): ServerPlayer = Actor.player(level)

    /** A breaker id for the crack overlay that can never collide with an entity's. */
    fun breakerId(host: ControllerHost): Int = -((host.pos.hashCode() and 0x3fffffff) + 1)
}

/**
 * The stack in a buffer slot, held by the fake player for the length of one action. -1 (or an empty slot)
 * is a bare hand. The *same* stack object goes in the hand, so what the world does to it — durability,
 * a bucket filling, a stack shrinking — is what happens in the buffer.
 */
internal object Hand {
    fun take(host: ControllerHost, slot: Int): ItemStack {
        if (slot < 0 || slot >= host.selfInventory.slots) return ItemStack.EMPTY
        return host.selfInventory.stackIn(slot)
    }

    /** Puts back whatever is in the hand after the action; a stack the world used up leaves the slot empty. */
    fun release(host: ControllerHost, player: ServerPlayer, slot: Int) {
        val left = player.getItemInHand(InteractionHand.MAIN_HAND)
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY)
        val inv = host.selfInventory
        if (slot < 0 || slot >= inv.slots) {
            if (!left.isEmpty) Block.popResource(host.level, host.pos, left)
            return
        }
        // A store that will not be written wholesale says so, and then the tool goes on the floor rather
        // than nowhere. The cast this replaces returned null for a read-only handler and did nothing,
        // so whatever was in the hand was simply gone.
        if (!inv.setStackIn(slot, left) && !left.isEmpty) Block.popResource(host.level, host.pos, left)
    }
}

/**
 * Break the linked block exactly as a player would: a little progress per tick with cracks for everyone
 * watching, the tool from a buffer slot (or a bare hand) deciding the speed, whether the block is
 * harvested at all, and what its loot table drops — fortune and silk touch included — with the drops
 * landing on the ground, the tool losing durability, and other mods' break events honoured.
 * Answers whether the block broke and what it dropped (empty when it was not harvestable by that hand).
 */
class BreakBlockJob(private val host: ControllerHost, private val target: ResolvedLink, private val toolSlot: Int = -1) : TickJob("world.breakBlock") {
    private var progress = 0f
    private var lastStage = -1
    private val breaker = BpmFakePlayer.breakerId(host)
    private var effect = -1

    /** The rift at the block with the tool swinging: opened on the first blow, a swing every few ticks, closed with the job. */
    private fun show(op: EffectOp, tool: ItemStack) {
        if (op == EffectOp.BEGIN) effect = host.newEffectId()
        if (effect < 0) return
        host.action(effect, op, EffectKind.MINE, target.link.pos, (target.link.side ?: Direction.UP).get3DDataValue(), toolId(tool))
        if (op == EffectOp.END) effect = -1
    }

    override fun advance(): Boolean {
        val level = host.level
        val pos = target.link.pos
        if (!target.loaded) {
            fail("'${target.link.name}' is not loaded")
            return true
        }
        val state = level.getBlockState(pos)
        if (state.isAir) {
            finish(arrayOf<Any?>(false, emptyList<Any?>()))
            return true
        }
        val player = BpmFakePlayer.get(level)
        val tool = Hand.take(host, toolSlot)
        player.setPos(pos.x + 0.5, pos.y + 1.0, pos.z + 0.5)
        player.setOnGround(true)
        player.setItemInHand(InteractionHand.MAIN_HAND, tool)
        if (effect < 0) show(EffectOp.BEGIN, tool)
        try {
            val step = state.getDestroyProgress(player, level, pos)
            if (step <= 0f) {
                clearCracks(level, pos)
                show(EffectOp.END, tool)
                fail("'${target.link.name}' cannot be broken")
                return true
            }
            progress += step
            val stage = (progress * 10f).toInt().coerceIn(0, 9)
            if (stage != lastStage) {
                level.destroyBlockProgress(breaker, pos, stage)
                lastStage = stage
            }
            if (progress < 1f) return false

            // The vanilla player sequence (ServerPlayerGameMode.destroyBlock), minus spawn protection:
            // the break event other mods listen to, the block's own farewell, removal, then the drops if
            // this hand can harvest it — from the loot table, with the tool's enchantments.
            if (!Actor.mayBreak(level, pos, state, player)) {
                clearCracks(level, pos)
                show(EffectOp.END, tool)
                fail("breaking '${target.link.name}' was refused")
                return true
            }
            val be = level.getBlockEntity(pos)
            // Vanilla's own question, which NeoForge merely wraps as BlockState.canHarvestBlock: does the
            // held tool actually harvest this, or only break it?
            val harvest = !state.requiresCorrectToolForDrops() || player.hasCorrectToolForDrops(state)
            val drops = if (harvest) Block.getDrops(state, level, pos, be, player, tool) else emptyList()
            val toolCopy = tool.copy()
            val block = state.block
            block.playerWillDestroy(level, pos, state, player)
            val removed = level.removeBlock(pos, false)
            if (removed) block.destroy(level, pos, state)
            clearCracks(level, pos)
            if (removed && harvest) {
                // Durability first (a tool that breaks on this block still harvests it), then the drops.
                tool.mineBlock(level, state, pos, player)
                block.playerDestroy(level, player, pos, state, be, toolCopy)
                bpm.platform.world.Actor.dropExperience(level, pos, state, be, player, toolCopy)
            } else if (removed) {
                tool.mineBlock(level, state, pos, player)
            }
            show(EffectOp.END, tool)
            finish(arrayOf<Any?>(removed, drops.map { ItemStackValue.record(it) }))
            return true
        } finally {
            Hand.release(host, player, toolSlot)
        }
    }

    override fun cancel() {
        clearCracks(host.level, target.link.pos)
        show(EffectOp.END, ItemStack.EMPTY)
    }

    private fun clearCracks(level: ServerLevel, pos: net.minecraft.core.BlockPos) {
        if (lastStage >= 0) level.destroyBlockProgress(breaker, pos, -1)
        lastStage = -1
    }
}

/**
 * Use the stack in a buffer slot on the linked face, as a player right-clicking would — pull a lever, place
 * a block, plant a seed, fill a bucket. The real stack is in the hand, so what the use does to it is what
 * happens in the buffer. Answers whether the world accepted it. Waits [cooldownTicks] afterwards so a
 * loop cannot spam a lever faster than a hand could.
 */
class UseItemJob(
    private val host: ControllerHost,
    private val target: ResolvedLink,
    private val slot: Int = -1,
    private val cooldownTicks: Int = 4,
    /** Hold shift while using — what many modded blocks ask for. */
    private val sneak: Boolean = false,
) : TickJob("world.useItem") {
    private var result: Boolean? = null
    private var effect = -1

    private fun end() {
        if (effect >= 0) host.action(effect, EffectOp.END, EffectKind.USE, target.link.pos, (target.link.side ?: Direction.UP).get3DDataValue(), "")
        effect = -1
    }

    override fun advance(): Boolean {
        if (result == null) {
            result = use()
            if (cooldownTicks <= 0) {
                end()
                finish(result)
                return true
            }
            return false
        }
        if (ticks > cooldownTicks) {
            end()
            finish(result)
            return true
        }
        return false
    }

    override fun cancel() = end()

    private fun use(): Boolean {
        if (!target.loaded) {
            fail("'${target.link.name}' is not loaded")
            return false
        }
        val level = host.level
        val player = BpmFakePlayer.get(level)
        val pos = target.link.pos
        val face = target.link.side ?: Direction.UP
        val stack = Hand.take(host, slot)
        player.setPos(Vec3.atCenterOf(pos.relative(face)))
        player.setItemInHand(InteractionHand.MAIN_HAND, stack)
        player.isShiftKeyDown = sneak
        effect = host.newEffectId()
        host.action(effect, EffectOp.BEGIN, EffectKind.USE, pos, face.get3DDataValue(), toolId(stack))
        val hit = BlockHitResult(Vec3.atCenterOf(pos), face, pos, false)
        try {
            val outcome = runCatching { player.gameMode.useItemOn(player, level, player.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND, hit) }
                .getOrElse { fail("using slot $slot failed: ${it.message}"); return false }
            return outcome.consumesAction()
        } finally {
            player.isShiftKeyDown = false
            Hand.release(host, player, slot)
        }
    }
}

/**
 * One click at a link, as a player would make it, with a buffer slot in hand (-1 a bare hand).
 *
 * Left *hits*: the block, a little each tick with cracks for everyone watching (the whole of
 * [BreakBlockJob]) — or, when something living stands in the block, one full swing of whatever is in hand,
 * exactly as [Player.attack] does it (a sword's damage and speed, sharpness, knockback, fire aspect,
 * durability), after which the job waits out that weapon's swing so a loop strikes at the weapon's own rate.
 * Right *uses*: the stack on the face ([UseItemJob]) — or on whatever stands there: shears on a sheep, a
 * bucket on a cow, a name tag. [aim] chooses between the block and what stands in it; Auto takes a living
 * thing when one is there, else the block. Players are never struck. Answers (ok, drops) like Break Block.
 *
 * The fake player is never ticked, so two things a real player gets for free are done by hand: the hand's
 * attribute modifiers are applied for the swing and removed after, and the attack-strength ticker is set as
 * if it had stood still a long while, so the swing is a full one.
 */
class ClickJob(
    private val host: ControllerHost,
    private val target: ResolvedLink,
    private val button: Button,
    private val slot: Int = -1,
    private val sneak: Boolean = false,
    private val aim: Aim = Aim.AUTO,
) : TickJob("world.click") {
    enum class Button {
        LEFT, RIGHT;

        companion object {
            fun of(name: String): Button = entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: RIGHT
        }
    }

    enum class Aim {
        AUTO, BLOCK, ENTITY;

        companion object {
            fun of(name: String): Aim = entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: AUTO
        }
    }

    private var inner: TickJob? = null
    private var cooldown = -1
    private var pendingOk = false
    private var effect = -1
    private var effectKind = EffectKind.STRIKE
    private var effectAt: BlockPos? = null

    private fun show(op: EffectOp, kind: EffectKind, at: BlockPos, item: String) {
        if (op == EffectOp.BEGIN) { effect = host.newEffectId(); effectKind = kind; effectAt = at }
        if (effect < 0) return
        host.action(effect, op, effectKind, effectAt ?: at, (target.link.side ?: Direction.UP).get3DDataValue(), item)
        if (op == EffectOp.END) effect = -1
    }

    override fun advance(): Boolean {
        inner?.let { return relay(it) }
        if (cooldown >= 0) {
            if (--cooldown > 0) return false
            show(EffectOp.END, effectKind, target.link.pos, "")
            finish(arrayOf<Any?>(pendingOk, emptyList<Any?>()))
            return true
        }
        if (!target.loaded) {
            fail("'${target.link.name}' is not loaded")
            return true
        }
        val level = host.level
        val pos = target.link.pos
        val who = if (aim == Aim.BLOCK) null else victim(level, pos)
        if (who == null && aim == Aim.ENTITY) {
            finish(arrayOf<Any?>(false, emptyList<Any?>()))
            return true
        }
        if (who == null) {
            val job = if (button == Button.LEFT) BreakBlockJob(host, target, slot) else UseItemJob(host, target, slot, sneak = sneak)
            inner = job
            return relay(job)
        }
        return if (button == Button.LEFT) strike(level, pos, who) else touch(level, pos, who)
    }

    /** Runs the inner job a tick and hands its outcome on as this job's, in this job's two-result shape. */
    private fun relay(job: TickJob): Boolean {
        if (!job.step()) return false
        val r = job.await.box.get()
        if (r == null) {
            fail("$label ended without an answer")
        } else {
            r.fold(
                { v -> finish(if (v is Array<*>) v else arrayOf<Any?>(v == true, emptyList<Any?>())) },
                { e -> fail(e.message ?: "$label failed") },
            )
        }
        return true
    }

    override fun cancel() {
        inner?.cancel()
        show(EffectOp.END, effectKind, target.link.pos, "")
    }

    /**
     * The living thing at the link — in the block's cell, or standing on the block (the cell above it, which
     * is where a mob on a linked floor block is) — never a player — nearest the block's centre.
     */
    private fun victim(level: ServerLevel, pos: BlockPos): LivingEntity? =
        level.getEntitiesOfClass(LivingEntity::class.java, AABB(pos).expandTowards(0.0, 1.0, 0.0)) { it.isAlive && it !is Player }
            .minByOrNull { it.distanceToSqr(Vec3.atCenterOf(pos)) }

    /** One full swing at [who] with the slot in hand, then the weapon's swing time before the answer. */
    private fun strike(level: ServerLevel, pos: BlockPos, who: LivingEntity): Boolean {
        val player = BpmFakePlayer.get(level)
        val stack = Hand.take(host, slot)
        player.setPos(Vec3.atCenterOf(pos.relative(target.link.side ?: Direction.UP)))
        player.lookAt(EntityAnchorArgument.Anchor.EYES, who.position().add(0.0, who.bbHeight * 0.5, 0.0))
        player.setItemInHand(InteractionHand.MAIN_HAND, stack)
        val armed = arm(player, stack)
        try {
            fullStrength(player)
            // A swing from the ground, standing still, at full strength, with a sword: that is vanilla's
            // sweep, and `attack` does the rest — everything living beside the target (within a block of
            // it and three of the swinger) takes the sweep damage and knockback, exactly as for a player.
            // The fake player is never ticked, so it has to be TOLD it is on the ground.
            player.setOnGround(true)
            player.resetFallDistance()
            player.isSprinting = false
            player.attack(who)
            pendingOk = true
            show(EffectOp.BEGIN, EffectKind.STRIKE, target.link.pos, toolId(stack))
            cooldown = kotlin.math.ceil(player.currentItemAttackStrengthDelay.toDouble()).toInt().coerceAtLeast(1)
        } finally {
            disarm(player, armed)
            Hand.release(host, player, slot)
        }
        return false
    }

    /** The slot used on [who], as a right-click would; a short cooldown before the answer. */
    private fun touch(level: ServerLevel, pos: BlockPos, who: LivingEntity): Boolean {
        val player = BpmFakePlayer.get(level)
        val stack = Hand.take(host, slot)
        player.setPos(Vec3.atCenterOf(pos.relative(target.link.side ?: Direction.UP)))
        player.lookAt(EntityAnchorArgument.Anchor.EYES, who.position().add(0.0, who.bbHeight * 0.5, 0.0))
        player.setItemInHand(InteractionHand.MAIN_HAND, stack)
        player.isShiftKeyDown = sneak
        try {
            pendingOk = runCatching { player.interactOn(who, InteractionHand.MAIN_HAND).consumesAction() }
                .getOrElse {
                    fail("using slot $slot on ${who.name.string} failed: ${it.message}")
                    return true
                }
            cooldown = 4
            show(EffectOp.BEGIN, EffectKind.USE, who.blockPosition(), toolId(stack))
        } finally {
            player.isShiftKeyDown = false
            Hand.release(host, player, slot)
        }
        return false
    }

    private fun arm(player: ServerPlayer, stack: ItemStack): List<Pair<Holder<Attribute>, AttributeModifier>> {
        val applied = ArrayList<Pair<Holder<Attribute>, AttributeModifier>>()
        stack.forEachModifier(EquipmentSlot.MAINHAND) { attribute, modifier ->
            val instance = player.attributes.getInstance(attribute) ?: return@forEachModifier
            instance.addOrUpdateTransientModifier(modifier)
            applied += attribute to modifier
        }
        return applied
    }

    private fun disarm(player: ServerPlayer, applied: List<Pair<Holder<Attribute>, AttributeModifier>>) {
        for ((attribute, modifier) in applied) player.attributes.getInstance(attribute)?.removeModifier(modifier.id())
    }

    companion object {
        /**
         * As if the player had stood still a long while: the next swing is a full one.
         *
         * How that is achieved is the loader's business — the counter is private in vanilla, so it takes
         * either reflection against known mappings or a mixin accessor, and which of those is correct
         * depends on what the runtime is named. See [WorldActor.primeAttackStrength].
         */
        fun fullStrength(player: Player) = Actor.primeAttackStrength(player)
    }
}

/** The registry id of what is in the hand, for the tool the world shows; empty for a bare hand. */
internal fun toolId(stack: ItemStack): String =
    if (stack.isEmpty) "" else net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.item).toString()
