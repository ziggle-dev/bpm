package bpm.world.items

import bpm.world.ControllerBlockEntity
import bpm.world.Grant
import bpm.world.Grants
import bpm.world.Link
import bpm.world.ModComponents
import bpm.world.Tethers
import bpm.world.TooltipItem
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.GlobalPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
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
import java.util.UUID
import java.util.function.Consumer
import bpm.platform.showMessage

/**
 * The Quantum Tether: carry it, and a controller may reach *you*.
 *
 * Sneak-use a controller to bind — that controller gains a presence link named after you, and the stack
 * carries the credential ([ModComponents.TETHER_CONTROLLER] + [ModComponents.TETHER_GRANTS]). Sneak-use the
 * air to cycle what it allows; sneak-use the air twice quickly to let go. A plain use on a controller opens
 * the editor as always: this is not a wand.
 *
 * Binding is deliberately the bearer's own gesture. The Quantum Linker cannot make a presence link, so there
 * is no way for someone else to attach you to their machine — and because the credential lives on the stack,
 * putting it in a chest, dropping it, or dying with it revokes everything that same tick. See
 * `docs/DESIGN_PLAYER_LINK.md`.
 */
class QuantumTetherItem(properties: Properties) : TooltipItem(properties), GeoItem {

    private val animCache: AnimatableInstanceCache = GeckoLibUtil.createInstanceCache(this)

    /** When each player last cycled in the air, so a second click inside [UNBIND_TICKS] means "let go". */
    private val lastCycle = HashMap<UUID, Long>()

    // ---- binding ----------------------------------------------------------------------------------------

    override fun useOn(ctx: UseOnContext): InteractionResult {
        val player = ctx.player ?: return InteractionResult.PASS
        // Only a sneak-use is ours; a plain use falls through to the block, which opens the editor.
        if (!player.isShiftKeyDown) return InteractionResult.PASS
        val level = ctx.level
        if (level.getBlockEntity(ctx.clickedPos) !is ControllerBlockEntity) return InteractionResult.PASS
        if (level.isClientSide) return InteractionResult.SUCCESS
        return bind(level, player, ctx.itemInHand, ctx.clickedPos)
    }

    private fun bind(level: Level, player: Player, stack: ItemStack, pos: BlockPos): InteractionResult {
        val be = level.getBlockEntity(pos) as? ControllerBlockEntity ?: return InteractionResult.PASS
        val here = GlobalPos.of(level.dimension(), pos)

        if (stack.get(ModComponents.TETHER_CONTROLLER.get()) == here && be.links.byPlayer(player.uuid) != null) {
            say(player, "already tethered to ${pos.toShortString()} · ${Grants.label(grantsOf(stack))}")
            return InteractionResult.CONSUME
        }

        val link = be.links.byPlayer(player.uuid) ?: be.links.add(
            Link(be.links.presenceName(player.name.string), player.blockPosition(), null, level.dimension(), player.uuid),
        ) ?: run {
            say(player, "this controller already holds ${be.links.presenceCapacity} people — a bigger core holds more")
            return InteractionResult.CONSUME
        }

        val grants = grantsOf(stack).ifEmpty { Grants.DELIVERY }
        Tethers.bind(stack, here, grants)
        changed(level, be)
        level.playSound(null, pos, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 0.3f, 1.8f)
        animate(level, player, stack, "bind")
        be.triggerAnim("overlay", "open")
        say(player, "tethered as '${link.name}' to ${pos.toShortString()} · ${Grants.label(grants)}")
        return InteractionResult.CONSUME
    }

    // ---- grants, and letting go -------------------------------------------------------------------------

    override fun use(level: Level, player: Player, hand: InteractionHand): bpm.platform.UseResult {
        val stack = player.getItemInHand(hand)
        if (!player.isShiftKeyDown) return bpm.platform.Use.pass(stack)
        if (level.isClientSide) return bpm.platform.Use.success(stack)

        val bound = stack.get(ModComponents.TETHER_CONTROLLER.get()) ?: run {
            say(player, "sneak-use a controller to tether yourself to it")
            return bpm.platform.Use.fail(stack)
        }

        val now = level.gameTime
        if (lastCycle[player.uuid]?.let { now - it <= UNBIND_TICKS } == true) {
            lastCycle.remove(player.uuid)
            release(level, player, stack, bound)
            return bpm.platform.Use.consume(stack)
        }
        lastCycle[player.uuid] = now

        val grants = Grants.next(grantsOf(stack))
        Tethers.bind(stack, bound, grants)
        say(player, "${Grants.label(grants)} · ${Grants.format(grants)}${if (Grant.TAKE in grants) " — it may take from you" else ""}")
        return bpm.platform.Use.consume(stack)
    }

    private fun release(level: Level, player: Player, stack: ItemStack, bound: GlobalPos) {
        Tethers.unbind(stack)
        val be = level.server?.getLevel(bound.dimension())
            ?.takeIf { it.isLoaded(bound.pos()) }
            ?.getBlockEntity(bound.pos()) as? ControllerBlockEntity
        if (be != null && be.links.removePlayer(player.uuid)) changed(level, be)
        animate(level, player, stack, "release")
        say(player, "tether released" + if (be == null) " — the controller was not loaded, it forgets you when it is" else "")
    }

    /**
     * Keep the controller's idea of where its people are roughly current, so an offline row in the links panel
     * says something useful. Every five seconds, and only for a bound tether: a player's *live* position comes
     * from the player, so this is only the last-seen fallback.
     */
    override fun carriedTick(stack: ItemStack, level: net.minecraft.server.level.ServerLevel, entity: Entity, inHand: Boolean) {
        if (level.gameTime % SEEN_EVERY != 0L) return
        val player = entity as? Player ?: return
        val bound = stack.get(ModComponents.TETHER_CONTROLLER.get()) ?: return
        if (bound.dimension() != level.dimension() || !level.isLoaded(bound.pos())) return
        val be = level.getBlockEntity(bound.pos()) as? ControllerBlockEntity ?: return
        if (be.links.seen(player.uuid, player.blockPosition(), level.dimension())) be.setChanged()
    }

    // ---- what it says -----------------------------------------------------------------------------------

    override fun lore(stack: ItemStack, add: (Component) -> Unit) {
        super.lore(stack, add)
        val bound = stack.get(ModComponents.TETHER_CONTROLLER.get())
        if (bound == null) {
            add(Component.translatable("item.bpm.quantum_tether.unbound").withStyle(ChatFormatting.GRAY))
            return
        }
        val grants = grantsOf(stack)
        add(Component.translatable("item.bpm.quantum_tether.bound", bound.pos().toShortString()).withStyle(ChatFormatting.AQUA))
        add(Component.literal("${Grants.label(grants)}: ${Grants.format(grants)}").withStyle(ChatFormatting.GRAY))
        if (Grant.TAKE in grants) add(Component.translatable("item.bpm.quantum_tether.take").withStyle(ChatFormatting.RED))
    }

    /** A bound tether glints, so it is obvious at a glance that something can reach you. */
    override fun isFoil(stack: ItemStack): Boolean = stack.has(ModComponents.TETHER_CONTROLLER.get())

    private fun grantsOf(stack: ItemStack): Set<Grant> = Grants.parse(stack.get(ModComponents.TETHER_GRANTS.get()))

    private fun say(player: Player, text: String) = player.showMessage(Component.literal("[bpm] $text"), true)

    /** The presence table changed: saved, and pushed to whoever is watching this controller in the editor. */
    private fun changed(level: Level, controller: ControllerBlockEntity) {
        controller.setChanged()
        level.sendBlockUpdated(controller.blockPos, controller.blockState, controller.blockState, 3)
        bpm.net.ServerNet.broadcastController(controller, withLinks = true)
    }

    /** One of the pendant's one-shot animations, for everyone who can see the hand. */
    private fun animate(level: Level, player: Player, stack: ItemStack, anim: String) {
        val server = level as? ServerLevel ?: return
        bpm.platform.triggerItemAnim(this, player, GeoItem.getOrAssignId(stack, server), "overlay", anim)
    }

    // ---- geckolib ---------------------------------------------------------------------------------------

    override fun registerControllers(controllers: bpm.platform.ControllerRegistrar) {
        controllers.add(bpm.platform.animController(this, "idle", 0) { state -> state.setAndContinue(IDLE) })
        controllers.add(
            bpm.platform.animController(this, "overlay", 0) { PlayState.STOP }
                .triggerableAnim("bind", BIND).triggerableAnim("release", RELEASE),
        )
    }

    override fun getAnimatableInstanceCache(): AnimatableInstanceCache = animCache

    /** Drawn by the table in [bpm.client.render.BpmItemRenderers]; GeckoLib asks the item, so the item asks it. */
    override fun createGeoRenderer(consumer: Consumer<software.bernie.geckolib.animatable.client.GeoRenderProvider>) =
        bpm.client.render.geoItemRenderer(this, consumer)


    companion object {
        /** A second sneak-use inside this many ticks releases the tether instead of cycling it. */
        const val UNBIND_TICKS = 10L

        /** How often a bound tether refreshes the controller's last-seen position. */
        const val SEEN_EVERY = 100L

        val IDLE: RawAnimation = RawAnimation.begin().thenLoop("animation.quantum_tether.idle")
        val BIND: RawAnimation = RawAnimation.begin().thenPlay("animation.quantum_tether.bind")
        val RELEASE: RawAnimation = RawAnimation.begin().thenPlay("animation.quantum_tether.release")
    }
}
