package bpm.world

import bpm.Bpm
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.GlobalPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.Level
import bpm.platform.GeoItem
import bpm.platform.AnimatableInstanceCache
import bpm.platform.AnimatableManager
import bpm.platform.AnimationController
import bpm.platform.PlayState
import bpm.platform.RawAnimation
import bpm.platform.GeckoLibUtil
import java.util.function.Consumer
import bpm.platform.showMessage

/**
 * The Quantum Linker: names world blocks for a controller.
 *
 * Sneak-use a controller to select it (stored on the stack as [ModComponents.SELECTED_CONTROLLER]); use a
 * block face to link it under an automatic name (`chest`, `chest-2`, …) — the wand takes the click before the
 * block does, so a chest links rather than opens; sneak-use a linked face to unlink; sneak-use the air while
 * looking at a link whose block is gone to unlink that, or otherwise to forget the controller. Links stay in
 * one dimension and within [RANGE] blocks. The face matters because capabilities are sided; direction does
 * not — it lives in the verb (`items.move(from, to)`), not on the link.
 */
class LinkerItem(properties: Properties) : bpm.platform.BpmItem(properties), GeoItem {

    private val animCache: AnimatableInstanceCache = GeckoLibUtil.createInstanceCache(this)

    /**
     * Before the block gets a say — so a chest, a furnace, anything with a screen, links instead of opening.
     * Only the plain link is taken here (a bound controller, no sneaking, a block that is not a controller):
     * a sneak-use skips the block anyway and comes through [useOn], and a plain use on a controller still
     * opens the editor.
     */
    /*
     * Not an override any more.
     *
     * This used to be NeoForge's `Item.onItemUseFirst`, a method that loader adds so a held item can
     * answer a click on a block before the block does. Vanilla has no such method and Fabric offers the
     * same thing as an event, so it is reached through [bpm.platform.events.BpmEvents.useOnBlock] now and
     * the listener is installed in [installHooks]. The behaviour below is unchanged.
     */
    internal fun useFirst(level: Level, player: Player, stack: ItemStack, pos: BlockPos, face: Direction): InteractionResult {
        if (bpm.chamber.ChamberDimension.isChamber(level) || player.isShiftKeyDown) return InteractionResult.PASS
        if (!stack.has(ModComponents.SELECTED_CONTROLLER.get())) return InteractionResult.PASS
        if (level.getBlockEntity(pos) is ControllerBlockEntity) return InteractionResult.PASS
        if (level.isClientSide) return InteractionResult.SUCCESS
        return link(level, player, stack, pos, face)
    }

    override fun useOn(ctx: UseOnContext): InteractionResult {
        val level = ctx.level
        val player = ctx.player ?: return InteractionResult.PASS
        if (bpm.chamber.ChamberDimension.isChamber(level)) {
            if (player.isShiftKeyDown) {
                if (player is net.minecraft.server.level.ServerPlayer) trackingPulse(player, ctx.hand)
                return bpm.platform.Interact.sided(level.isClientSide)
            }
            return pulse(level, player, ctx.hand)
        }
        if (level.isClientSide) return InteractionResult.SUCCESS
        return link(level, player, ctx.itemInHand, ctx.clickedPos, ctx.clickedFace)
    }

    /** The wand on a block face, server side: bind (sneak on a controller), unlink (sneak on a linked face), or link. */
    private fun link(level: Level, player: Player, stack: ItemStack, pos: BlockPos, face: Direction): InteractionResult {
        val target = level.getBlockEntity(pos)
        if (target is ControllerBlockEntity && player.isShiftKeyDown) {
            stack.set(ModComponents.SELECTED_CONTROLLER.get(), GlobalPos.of(level.dimension(), pos))
            say(player, "linker bound to the controller at ${pos.toShortString()}")
            animate(level, player, stack, "link")
            target.triggerAnim("overlay", "open")
            return InteractionResult.CONSUME
        }

        val controller = selected(level, stack) ?: run {
            say(player, "sneak-use a controller first")
            return InteractionResult.CONSUME
        }
        if (target === controller) {
            say(player, controller.describe())
            return InteractionResult.CONSUME
        }
        val reach = reach(controller, player)
        if (!pos.closerThan(controller.blockPos, reach)) {
            say(player, "too far from the controller (${reach.toInt()} blocks)")
            return InteractionResult.CONSUME
        }
        if (player.isShiftKeyDown) {
            val existing = controller.links.at(pos, face) ?: controller.links.at(pos, null) ?: controller.links.all.firstOrNull { it.pos == pos }
            if (existing == null) {
                say(player, "nothing linked at ${pos.toShortString()}")
            } else {
                controller.links.remove(existing.name)
                changed(level, controller)
                say(player, "unlinked '${existing.name}'")
                animate(level, player, stack, "unlink")
            }
            return InteractionResult.CONSUME
        }
        controller.links.at(pos, face)?.let {
            say(player, "'${it.name}' already links this face")
            return InteractionResult.CONSUME
        }
        val blockId = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).block)
        val link = controller.links.add(Link(LinkTable.autoName(blockId), pos, face, level.dimension())) ?: run {
            say(player, "this controller holds ${controller.links.capacity} of ${controller.links.capacity} links — a bigger core holds more")
            return InteractionResult.CONSUME
        }
        changed(level, controller)
        say(player, "linked '${link.name}' (${face.name.lowercase()} face of ${pos.toShortString()})")
        animate(level, player, stack, "link")
        controller.triggerAnim("overlay", "open")
        return InteractionResult.CONSUME
    }

    override fun use(level: Level, player: Player, hand: InteractionHand): bpm.platform.UseResult {
        val stack = player.getItemInHand(hand)
        if (bpm.chamber.ChamberDimension.isChamber(level)) {
            if (player.isShiftKeyDown) {
                if (player is net.minecraft.server.level.ServerPlayer) trackingPulse(player, hand)
                return bpm.platform.Use.sided(stack, level.isClientSide)
            }
            val r = pulse(level, player, hand)
            return if (r == InteractionResult.PASS) bpm.platform.Use.pass(stack) else bpm.platform.Use.sided(stack, level.isClientSide)
        }
        if (player.isShiftKeyDown && stack.has(ModComponents.SELECTED_CONTROLLER.get())) {
            if (!level.isClientSide) {
                // A link whose block is gone has no face to click: the air click along the line of sight finds it.
                val controller = selected(level, stack)
                val gone = controller?.let { linkAhead(level, player, it) }
                if (controller != null && gone != null) {
                    controller.links.remove(gone.name)
                    changed(level, controller)
                    say(player, "unlinked '${gone.name}' — its block was gone")
                } else {
                    stack.remove(ModComponents.SELECTED_CONTROLLER.get())
                    say(player, "linker cleared")
                }
                animate(level, player, stack, "unlink")
            }
            return bpm.platform.Use.sided(stack, level.isClientSide)
        }
        return bpm.platform.Use.pass(stack)
    }

    override fun lore(stack: ItemStack, add: (Component) -> Unit) {
        super.lore(stack, add)
        val sel = stack.get(ModComponents.SELECTED_CONTROLLER.get())
        add(
            if (sel == null) Component.translatable("item.bpm.quantum_linker.unbound")
            else Component.translatable("item.bpm.quantum_linker.bound", sel.pos().toShortString()),
        )
        if (stack.getOrDefault(ModComponents.CHARGED.get(), false)) add(Component.literal("Specials: ${charges(stack)} / $MAX_CHARGES"))
    }

    private fun selected(level: Level, stack: ItemStack): ControllerBlockEntity? {
        val sel = stack.get(ModComponents.SELECTED_CONTROLLER.get()) ?: return null
        if (sel.dimension() != level.dimension()) return null
        if (!level.isLoaded(sel.pos())) return null
        return level.getBlockEntity(sel.pos()) as? ControllerBlockEntity
    }

    private fun say(player: Player, text: String) = player.showMessage(Component.literal("[bpm] $text"), true)

    /** Inside a chamber the wand is a weapon of sorts: a decohering pulse, from whichever hand holds it. */
    /** Specials left. */
    fun charges(stack: ItemStack): Int = stack.getOrDefault(ModComponents.CHARGES.get(), MAX_CHARGES)

    /** Spends one special; false (and a word) when they are gone. */
    private fun spend(stack: ItemStack, player: Player): Boolean {
        val left = charges(stack)
        if (left <= 0) {
            say(player, "no specials left — use the linker on the core pedestal to recharge")
            return false
        }
        stack.set(ModComponents.CHARGES.get(), left - 1)
        return true
    }

    /** The pedestal fills the wand again — used with the linker in hand. */
    fun recharge(stack: ItemStack, player: Player) {
        stack.set(ModComponents.CHARGES.get(), MAX_CHARGES)
        bpm.platform.addCooldown(player, stack, 10)
        say(player, "the linker hums — $MAX_CHARGES specials")
        (player.level() as? net.minecraft.server.level.ServerLevel)?.let { l ->
            l.sendParticles(net.minecraft.core.particles.ParticleTypes.ELECTRIC_SPARK, player.x, player.y + 1.2, player.z, 24, 0.4, 0.4, 0.4, 0.2)
            l.playSound(null, player.x, player.y, player.z, net.minecraft.sounds.SoundEvents.RESPAWN_ANCHOR_CHARGE, net.minecraft.sounds.SoundSource.PLAYERS, 0.7f, 1.5f)
        }
    }

    private fun pulse(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        if (bpm.platform.onCooldown(player, player.getItemInHand(hand))) return InteractionResult.PASS
        if (level.isClientSide) return InteractionResult.SUCCESS
        val server = level as? net.minecraft.server.level.ServerLevel ?: return InteractionResult.PASS
        val stack = player.getItemInHand(hand)
        val pulse = bpm.world.entity.LinkerPulseEntity(bpm.world.entity.ModEntities.PULSE.get(), server)
        pulse.owner = player
        val eye = player.getEyePosition(1f)
        val side = if (hand == InteractionHand.OFF_HAND) -1.0 else 1.0
        val yaw = Math.toRadians(player.yRot.toDouble())
        val from = eye.add(Math.cos(yaw) * 0.3 * side * (if (player.mainArm == net.minecraft.world.entity.HumanoidArm.LEFT) -1.0 else 1.0), -0.15, Math.sin(yaw) * 0.3 * side)
        pulse.launch(from, player.lookAngle)
        server.addFreshEntity(pulse)
        server.playSound(null, player.x, player.y, player.z, net.minecraft.sounds.SoundEvents.BEACON_POWER_SELECT, net.minecraft.sounds.SoundSource.PLAYERS, 0.5f, 1.8f)
        bpm.platform.addCooldown(player, stack, PULSE_COOLDOWN)
        animate(level, player, stack, "link")
        return InteractionResult.CONSUME
    }

    /**
     * The special — sneak + click inside a chamber: a pulse locked on the Warden that turns until it lands and
     * takes it down whatever its cage is doing. Three a recharge, twenty seconds between shots, both kept on
     * the stack. Turrets are the quick pulse's business, not this one's.
     */
    fun trackingPulse(player: net.minecraft.server.level.ServerPlayer, hand: InteractionHand): Boolean {
        val level = bpm.platform.levelOf(player)
        if (!bpm.chamber.ChamberDimension.isChamber(level)) return false
        val stack = player.getItemInHand(hand)
        if (stack.item !is LinkerItem) return false
        val now = level.gameTime
        val ready = stack.getOrDefault(ModComponents.TRACK_READY_AT.get(), 0L)
        if (now < ready) {
            say(player, "tracking pulse ready in ${((ready - now) / 20) + 1} s")
            return true
        }
        if (charges(stack) <= 0) {
            say(player, "no specials left — use the linker on the core pedestal to recharge")
            return true
        }
        val eye = player.getEyePosition(1f)
        val warden = level.getEntitiesOfClass(bpm.world.entity.QuantumWardenEntity::class.java, player.boundingBox.inflate(TRACK_RANGE)) { it.isAlive && !it.disabled }
            .minByOrNull { it.distanceToSqr(player) }
        if (warden == null) {
            say(player, "nothing to lock on — no Warden in reach")
            return true
        }
        if (!spend(stack, player)) return true
        val pulse = bpm.world.entity.LinkerPulseEntity(bpm.world.entity.ModEntities.PULSE.get(), level)
        pulse.owner = player
        pulse.launch(eye.add(player.lookAngle.scale(0.6)), player.lookAngle)
        pulse.seek(warden)
        level.addFreshEntity(pulse)
        level.playSound(null, player.x, player.y, player.z, net.minecraft.sounds.SoundEvents.BEACON_ACTIVATE, net.minecraft.sounds.SoundSource.PLAYERS, 0.6f, 1.6f)
        stack.set(ModComponents.TRACK_READY_AT.get(), now + TRACK_COOLDOWN)
        animate(level, player, stack, "link")
        say(player, "locked on the Warden — ${charges(stack)} special${if (charges(stack) == 1) "" else "s"} left")
        return true
    }

    /** The wand hums in a chamber: the glint follows a component the server keeps in step with where the holder is. */
    override fun carriedTick(stack: ItemStack, level: net.minecraft.server.level.ServerLevel, entity: net.minecraft.world.entity.Entity, inHand: Boolean) {
        val charged = bpm.chamber.ChamberDimension.isChamber(level)
        if (stack.getOrDefault(ModComponents.CHARGED.get(), false) != charged) {
            stack.set(ModComponents.CHARGED.get(), charged)
            // Every visit starts with a full wand; the pedestal is the recharge inside.
            if (charged) stack.set(ModComponents.CHARGES.get(), MAX_CHARGES)
        }
    }

    override fun isFoil(stack: ItemStack): Boolean = stack.getOrDefault(ModComponents.CHARGED.get(), false)

    /**
     * The link table changed: saved, pushed to the clients tracking the block (the HUD reads it there), and
     * to everyone watching the controller — the open editor's links panel and pickers.
     */
    private fun changed(level: Level, controller: ControllerBlockEntity) {
        controller.setChanged()
        level.sendBlockUpdated(controller.blockPos, controller.blockState, controller.blockState, 3)
        bpm.net.ServerNet.broadcastController(controller, withLinks = true)
    }

    /** One of the wand's one-shot animations, for everyone who can see the hand. */
    private fun animate(level: Level, player: Player, stack: ItemStack, anim: String) {
        val server = level as? net.minecraft.server.level.ServerLevel ?: return
        bpm.platform.triggerItemAnim(this, player, GeoItem.getOrAssignId(stack, server), "overlay", anim)
    }

    // ---- geckolib ---------------------------------------------------------------------------------------

    override fun registerControllers(controllers: bpm.platform.ControllerRegistrar) {
        controllers.add(bpm.platform.animController(this, "idle", 0) { state -> state.setAndContinue(IDLE) })
        controllers.add(
            bpm.platform.animController(this, "overlay", 0) { PlayState.STOP }
                .triggerableAnim("select", SELECT).triggerableAnim("link", LINK).triggerableAnim("unlink", UNLINK),
        )
    }

    override fun getAnimatableInstanceCache(): AnimatableInstanceCache = animCache

    /** Drawn by the table in [bpm.client.render.BpmItemRenderers]; GeckoLib asks the item, so the item asks it. */
    override fun createGeoRenderer(consumer: Consumer<bpm.platform.GeoRenderProvider>) =
        bpm.client.render.geoItemRenderer(this, consumer)


    companion object {
        /**
         * Offer a use-click on a block to the linker before the block gets it.
         *
         * Installed once, from `BpmRegistries.install`. The click is only claimed when the held item is
         * actually a linker with a controller selected — everything else falls through to the block, which
         * is what makes "a plain use on a chest still opens the chest" true.
         */
        fun installHooks() {
            bpm.platform.events.BpmEvents.useOnBlock.listen { e ->
                val stack = e.player.getItemInHand(e.hand)
                val item = stack.item
                if (item is LinkerItem) {
                    val r = item.useFirst(e.level, e.player, stack, e.pos, e.face)
                    if (r != InteractionResult.PASS) e.result = r
                }
            }
        }

        const val RANGE = 32.0
        const val PULSE_COOLDOWN = 16
        const val TRACK_COOLDOWN = 400L
        const val MAX_CHARGES = 2
        const val TRACK_RANGE = 48.0

        /** The hand holding a linker, the main one first; null when neither does. */
        fun handWith(player: Player): InteractionHand? = when {
            player.mainHandItem.item is LinkerItem -> InteractionHand.MAIN_HAND
            player.offhandItem.item is LinkerItem -> InteractionHand.OFF_HAND
            else -> null
        }

        val IDLE: RawAnimation = RawAnimation.begin().thenLoop("animation.quantum_linker.idle")
        val SELECT: RawAnimation = RawAnimation.begin().thenPlay("animation.quantum_linker.select")
        val LINK: RawAnimation = RawAnimation.begin().thenPlay("animation.quantum_linker.link")
        val UNLINK: RawAnimation = RawAnimation.begin().thenPlay("animation.quantum_linker.unlink")

        fun selectedPos(stack: ItemStack): BlockPos? = stack.get(ModComponents.SELECTED_CONTROLLER.get())?.pos()

        /**
         * The link along [player]'s line of sight (within the wand's reach, through air only) — what a sneak-use
         * on the air unlinks, and what the HUD names when the crosshair rests on a link whose block is gone.
         */
        fun linkAhead(level: Level, player: Player, controller: ControllerBlockEntity): Link? =
            controller.links.firstAlong(player.getEyePosition(1f), player.lookAngle, reach(controller, player)) { p ->
                !level.getBlockState(p).getCollisionShape(level, p).isEmpty
            }

        /** How far [player] may link from [controller]: the core's reach, half again with the Warden's Visor on. */
        fun reach(controller: ControllerBlockEntity, player: Player): Double =
            controller.linkRange * (if (bpm.world.items.WardenVisorItem.worn(player)) bpm.world.items.WardenVisorItem.RANGE_FACTOR else 1.0)

        init {
            Bpm.LOGGER.debug("linker ready")
        }
    }
}
