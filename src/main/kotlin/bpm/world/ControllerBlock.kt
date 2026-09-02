package bpm.world

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.util.StringRepresentable
import net.minecraft.world.Containers
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.phys.BlockHitResult

/** What a controller is doing, as a block-state property so that the model and light need no entity sync. */
enum class ControllerStatus(private val key: String) : StringRepresentable {
    IDLE("idle"),
    RUNNING("running"),
    ERROR("error"),
    ASLEEP("asleep");

    override fun getSerializedName(): String = key

    val isOn: Boolean get() = this == RUNNING || this == ASLEEP
}

/**
 * The Quantum Controller: the block that owns a program, its links and its nine-slot buffer.
 *
 * Rendered by GeckoLib through the block entity, so the block itself draws nothing. A redstone source on all
 * six faces, driven by `redstone.emit`. Right-click reports what the controller is doing; sneak + right-click
 * toggles it. The editor opens through the client (phase 5).
 */
class ControllerBlock(properties: Properties) : bpm.platform.RemovalAwareBlock(properties), EntityBlock {

    init {
        registerDefaultState(stateDefinition.any().setValue(STATUS, ControllerStatus.IDLE))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(STATUS)
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = ControllerBlockEntity(pos, state)

    override fun getRenderShape(state: BlockState): RenderShape = bpm.platform.ANIMATED_BLOCK_SHAPE

    override fun isSignalSource(state: BlockState): Boolean = true

    /** [direction] points from this block to the block asking, so the asking neighbour sits on `direction.opposite`. */
    override fun getSignal(state: BlockState, level: BlockGetter, pos: BlockPos, direction: Direction): Int =
        (level.getBlockEntity(pos) as? ControllerBlockEntity)?.signal(direction.opposite) ?: 0

    override fun getDirectSignal(state: BlockState, level: BlockGetter, pos: BlockPos, direction: Direction): Int =
        getSignal(state, level, pos, direction)

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hit: BlockHitResult): InteractionResult {
        if (player.isShiftKeyDown) {
            if (level.isClientSide) return InteractionResult.SUCCESS
            val be = level.getBlockEntity(pos) as? ControllerBlockEntity ?: return InteractionResult.PASS
            be.setEnabled(!be.enabled)
            player.displayClientMessage(Component.literal("[bpm] controller ${if (be.enabled) "enabled" else "disabled"}"), true)
            return InteractionResult.CONSUME
        }
        // The editor is a client screen; the class named here is only ever loaded on the client.
        if (level.isClientSide) bpm.client.ClientHooks.openWorkbench(pos)
        return bpm.platform.Interact.sided(level.isClientSide)
    }

    override fun onBlockRemoved(state: BlockState, level: net.minecraft.server.level.ServerLevel, pos: BlockPos, movedByPiston: Boolean) {
        run {
            (level.getBlockEntity(pos) as? ControllerBlockEntity)?.let { be ->
                val inv = be.inventory
                for (i in 0 until inv.slots) {
                    val stack = inv.stackIn(i)
                    if (!stack.isEmpty) Containers.dropItemStack(level, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, stack)
                }
                // The controller's own graph goes with it; a library it happened to run stays.
                val server = level.server
                val id = be.docId
                if (server != null && id != null && !movedByPiston) {
                    val lib = bpm.library.BpmLibrary.get(server)
                    if (lib[id]?.isLibrary == false) bpm.net.ServerNet.deleteDocument(server, id)
                }
            }
        }
    }

    companion object {
        val STATUS: EnumProperty<ControllerStatus> = EnumProperty.create("status", ControllerStatus::class.java)
    }
}
