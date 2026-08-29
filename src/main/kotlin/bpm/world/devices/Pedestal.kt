package bpm.world.devices

import bpm.world.DeviceBlockEntities
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
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
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.ENTITYBLOCK_ANIMATED

    override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? =
        DeviceBlockEntity.ticker(level, type, DeviceBlockEntities.PEDESTAL.get())

    override fun useItemOn(stack: net.minecraft.world.item.ItemStack, state: BlockState, level: Level, pos: BlockPos, player: Player, hand: net.minecraft.world.InteractionHand, hit: BlockHitResult): net.minecraft.world.ItemInteractionResult {
        val linker = stack.item as? bpm.world.LinkerItem ?: return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
        if (!bpm.chamber.ChamberDimension.isChamber(level)) return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
        if (!level.isClientSide) linker.recharge(stack, player)
        return net.minecraft.world.ItemInteractionResult.sidedSuccess(level.isClientSide)
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

class PedestalBlockEntity(pos: BlockPos, state: BlockState) : DeviceBlockEntity(DeviceBlockEntities.PEDESTAL.get(), pos, state) {
    /** The chamber slot this pedestal belongs to; null for a pedestal a player placed. */
    var slotOwner: UUID? = null

    /** What is going on around this pedestal — the fight's state machine lives on the slot; this mirrors it. */
    val hasCore: Boolean get() = blockState.getValue(PedestalBlock.HAS_CORE)

    fun setHasCore(value: Boolean) {
        val l = level ?: return
        if (hasCore != value) l.setBlock(worldPosition, blockState.setValue(PedestalBlock.HAS_CORE, value), 3)
        sync()
    }

    /** The player used the pedestal: the chamber decides what that means (wake the Warden, claim the core). */
    fun use(player: Player) {
        val handled = PedestalHooks.onUse(this, player)
        if (!handled) player.displayClientMessage(Component.literal("[bpm] " + if (hasCore) "the core hums in its socket" else "an empty socket"), true)
    }

    override fun saveSynced(tag: CompoundTag, registries: HolderLookup.Provider) {
        slotOwner?.let { tag.putUUID("slotOwner", it) }
    }

    override fun loadSynced(tag: CompoundTag, registries: HolderLookup.Provider) {
        slotOwner = if (tag.hasUUID("slotOwner")) tag.getUUID("slotOwner") else null
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
