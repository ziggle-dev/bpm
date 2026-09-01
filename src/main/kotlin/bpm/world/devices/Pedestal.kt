package bpm.world.devices

import bpm.world.DeviceBlockEntities
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import bpm.platform.ports.ItemPort
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.phys.BlockHitResult
import software.bernie.geckolib.animation.AnimatableManager
import software.bernie.geckolib.animation.AnimationController
import software.bernie.geckolib.animation.PlayState
import software.bernie.geckolib.animation.RawAnimation
import java.util.UUID

/**
 * The Core Pedestal: holds the Quantum Core the Warden guards. Using it with the core in place wakes the
 * Warden (the core rises into it); using it after the Warden fell hands the core over. Outside a chamber it
 * is a decorative socket that simply holds a core.
 */
class PedestalBlock(properties: Properties) : Block(properties), EntityBlock {
    init {
        registerDefaultState(stateDefinition.any().setValue(HAS_CORE, false))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(HAS_CORE)
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = PedestalBlockEntity(pos, state)
    override fun getRenderShape(state: BlockState): RenderShape = bpm.platform.ANIMATED_BLOCK_SHAPE

    override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? =
        DeviceBlockEntity.ticker(level, type, DeviceBlockEntities.PEDESTAL.get())

    override fun useItemOn(stack: net.minecraft.world.item.ItemStack, state: BlockState, level: Level, pos: BlockPos, player: Player, hand: net.minecraft.world.InteractionHand, hit: BlockHitResult): bpm.platform.BlockUseResult {
        val be = level.getBlockEntity(pos) as? PedestalBlockEntity
        val linker = stack.item as? bpm.world.LinkerItem
        if (linker != null && bpm.chamber.ChamberDimension.isChamber(level)) {
            if (!level.isClientSide) linker.recharge(stack, player)
            return bpm.platform.BlockUse.sidedSuccess(level.isClientSide)
        }
        // A chamber altar is the fight's, not a bench: only a pedestal a player put down holds ingredients.
        if (be == null || be.slotOwner != null || !be.held.isEmpty) {
            return bpm.platform.BlockUse.PASS_TO_BLOCK
        }
        if (!level.isClientSide) be.put(stack.split(1))
        return bpm.platform.BlockUse.sidedSuccess(level.isClientSide)
    }

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hit: BlockHitResult): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val be = level.getBlockEntity(pos) as? PedestalBlockEntity ?: return InteractionResult.PASS
        be.use(player)
        return InteractionResult.CONSUME
    }

    companion object {
        val HAS_CORE: BooleanProperty = BooleanProperty.create("has_core")
    }
}

/**
 * A pedestal as one slot, so every item verb in the catalogue already works on it.
 *
 * This is the same move that made presence links work: teach the capability rather than add bespoke nodes,
 * and `items.count`, `items.stacks` and `items.move` all come free — counting is how a graph asks "is this
 * plinth already loaded", and moving is how it loads one, with no click and no staging in the controller's
 * buffer. The slot limit of ONE is what makes `items.move` with Max 1 exact.
 *
 * A chamber altar ([PedestalBlockEntity.slotOwner]) refuses everything: the Warden fight's state machine owns
 * what sits on it, and a hopper must not be able to take the core off the altar mid-fight.
 */
class PedestalSlot(private val be: PedestalBlockEntity) : ItemPort {

    private val open: Boolean get() = be.slotOwner == null

    override val slots: Int get() = 1

    override fun stackIn(slot: Int): ItemStack = if (slot == 0) be.held else ItemStack.EMPTY

    override fun insert(slot: Int, stack: ItemStack, simulate: Boolean): ItemStack {
        if (slot != 0 || stack.isEmpty || !open || !be.held.isEmpty) return stack
        if (!simulate) be.put(stack.copyWithCount(1))
        return stack.copy().also { it.shrink(1) }
    }

    override fun extract(slot: Int, amount: Int, simulate: Boolean): ItemStack {
        if (slot != 0 || amount <= 0 || !open || be.held.isEmpty) return ItemStack.EMPTY
        val out = be.held.copy()
        if (!simulate) be.take()
        return out
    }

    /** A plinth displays one thing. That is what makes "move one" mean one. */
    override fun slotLimit(slot: Int): Int = 1

    override fun isValid(slot: Int, stack: ItemStack): Boolean = slot == 0 && open && be.held.isEmpty
}

class PedestalBlockEntity(pos: BlockPos, state: BlockState) : DeviceBlockEntity(DeviceBlockEntities.PEDESTAL.get(), pos, state) {
    /** The chamber slot this pedestal belongs to; null for a pedestal a player placed. */
    var slotOwner: UUID? = null

    /**
     * The one thing on the plinth, for an assembler to read — empty for a chamber altar, which shows a core
     * by its blockstate alone and never holds a real stack.
     */
    var held: ItemStack = ItemStack.EMPTY
        private set

    /** What is going on around this pedestal — the fight's state machine lives on the slot; this mirrors it. */
    val hasCore: Boolean get() = blockState.getValue(PedestalBlock.HAS_CORE)

    /**
     * Whether the model should draw its own decorative core — what `variable.has_core` reads.
     *
     * The core and a held ingredient occupy the same socket, so a held item always wins. Stated as a rule
     * rather than trusted to the blockstate on purpose: an earlier build set [PedestalBlock.HAS_CORE] when
     * an item was placed, and those blockstates are saved in existing worlds. Deriving it here means such a
     * pedestal stops drawing the core the moment it loads, with no migration step.
     */
    val showsCore: Boolean get() = hasCore && held.isEmpty

    /**
     * Put [stack] on the plinth.
     *
     * [PedestalBlock.HAS_CORE] is deliberately NOT touched. It drives `variable.has_core`, which scales the
     * model's own decorative core bone — so setting it for a held item drew a teal core with the real item
     * sitting inside it. The blockstate stays the chamber's flag; a player's ingredient is drawn by
     * `PedestalRenderer` at the socket instead.
     */
    fun put(stack: ItemStack) {
        held = stack
        // Heal a blockstate an earlier build set for a held item: it still drives this block's light level.
        if (slotOwner == null && hasCore) setHasCore(false)
        sync()
    }

    /** Take whatever is on the plinth, leaving it bare. */
    fun take(): ItemStack {
        val was = held
        held = ItemStack.EMPTY
        sync()
        return was
    }

    fun setHasCore(value: Boolean) {
        val l = level ?: return
        if (hasCore != value) l.setBlock(worldPosition, blockState.setValue(PedestalBlock.HAS_CORE, value), 3)
        sync()
    }

    /** The player used the pedestal: the chamber decides what that means (wake the Warden, claim the core). */
    fun use(player: Player) {
        if (PedestalHooks.onUse(this, player)) return
        if (!held.isEmpty) {
            val stack = take()
            if (!player.inventory.add(stack)) player.drop(stack, false)
            return
        }
        player.displayClientMessage(Component.literal("[bpm] " + if (hasCore) "the core hums in its socket" else "an empty socket"), true)
    }

    override fun saveSynced(tag: CompoundTag, registries: HolderLookup.Provider) {
        slotOwner?.let { tag.putUUID("slotOwner", it) }
        tag.put("held", held.saveOptional(registries))
    }

    override fun loadSynced(tag: CompoundTag, registries: HolderLookup.Provider) {
        slotOwner = if (tag.hasUUID("slotOwner")) tag.getUUID("slotOwner") else null
        held = ItemStack.parseOptional(registries, tag.getCompound("held"))
    }

    override fun registerControllers(controllers: AnimatableManager.ControllerRegistrar) {
        controllers.add(
            AnimationController(this, "main", 3) { state -> state.setAndContinue(IDLE) }
                .triggerableAnim("awaken", AWAKEN).triggerableAnim("claim", CLAIM),
        )
    }

    companion object {
        val IDLE: RawAnimation = RawAnimation.begin().thenLoop("animation.core_pedestal.idle")
        val AWAKEN: RawAnimation = RawAnimation.begin().thenPlay("animation.core_pedestal.awaken").thenLoop("animation.core_pedestal.idle")
        val CLAIM: RawAnimation = RawAnimation.begin().thenPlayAndHold("animation.core_pedestal.claim")
    }
}

/** The seam the chamber hangs its fight logic on; a pedestal outside a chamber falls through to nothing. */
object PedestalHooks {
    /** A player used the pedestal; true when the chamber handled it. */
    var onUse: (PedestalBlockEntity, Player) -> Boolean = { _, _ -> false }

    /** Wake the Warden (a script asked); true when it started. */
    var awaken: (PedestalBlockEntity) -> Boolean = { _ -> false }

    /** Take the core (a player, or null for a script); the core stack, or null when there is nothing to claim. */
    var claim: (PedestalBlockEntity, Player?) -> net.minecraft.world.item.ItemStack? = { _, _ -> null }
}
