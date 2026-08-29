package bpm.world.devices

import bpm.world.DeviceBlockEntities
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.DirectionProperty
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape
import software.bernie.geckolib.animation.AnimatableManager
import software.bernie.geckolib.animation.AnimationController
import software.bernie.geckolib.animation.RawAnimation

/**
 * The Quantum Monitor: a 6 px panel against the back of its cell whose screen faces [FACING]. Tiles that touch
 * another monitor with the same facing join into one screen — the bezel only runs along the outer edges, so
 * [UP] / [DOWN] / [LEFT] / [RIGHT] (in the viewer's frame) mean "this side is an outer edge" and follow the
 * neighbours automatically. [ON] swaps the dark glass for the lit screen; a controller will drive it, for now
 * an empty hand toggles it so the two looks can be checked in the world.
 *
 * Drawing on the screen is the renderer's job later: the model's `screen` bone marks the content plane, 11/16
 * of a block in from the front face, and each tile's visible area is 16 x 16 less a 2 px bezel on outer edges.
 */
class MonitorBlock(properties: Properties) : Block(properties), EntityBlock {
    init {
        registerDefaultState(
            stateDefinition.any().setValue(FACING, Direction.NORTH)
                .setValue(UP, true).setValue(DOWN, true).setValue(LEFT, true).setValue(RIGHT, true).setValue(ON, false),
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING, UP, DOWN, LEFT, RIGHT, ON)
    }

    /** The screen faces the placer; the edge flags come from whatever monitors already stand around it. */
    override fun getStateForPlacement(ctx: BlockPlaceContext): BlockState {
        val facing = ctx.horizontalDirection.opposite
        var state = defaultBlockState().setValue(FACING, facing)
        for (dir in Direction.values()) {
            val prop = edge(facing, dir) ?: continue
            state = state.setValue(prop, !joins(state, ctx.level.getBlockState(ctx.clickedPos.relative(dir))))
        }
        return state
    }

    override fun updateShape(state: BlockState, direction: Direction, neighborState: BlockState, level: LevelAccessor, pos: BlockPos, neighborPos: BlockPos): BlockState {
        val prop = edge(state.getValue(FACING), direction) ?: return state
        return state.setValue(prop, !joins(state, neighborState))
    }

    private fun joins(state: BlockState, other: BlockState): Boolean =
        other.`is`(this) && other.getValue(FACING) == state.getValue(FACING)

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, ctx: CollisionContext): VoxelShape =
        SHAPES.getValue(state.getValue(FACING))

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = MonitorBlockEntity(pos, state)
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.ENTITYBLOCK_ANIMATED

    override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? =
        DeviceBlockEntity.ticker(level, type, DeviceBlockEntities.MONITOR.get())

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hit: BlockHitResult): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        level.setBlock(pos, state.cycle(ON), Block.UPDATE_ALL)
        return InteractionResult.CONSUME
    }

    override fun rotate(state: BlockState, rotation: Rotation): BlockState = state.setValue(FACING, rotation.rotate(state.getValue(FACING)))

    override fun mirror(state: BlockState, mirror: Mirror): BlockState =
        state.rotate(mirror.getRotation(state.getValue(FACING))).setValue(LEFT, state.getValue(RIGHT)).setValue(RIGHT, state.getValue(LEFT))

    companion object {
        val FACING: DirectionProperty = BlockStateProperties.HORIZONTAL_FACING
        val UP: BooleanProperty = BooleanProperty.create("up")
        val DOWN: BooleanProperty = BooleanProperty.create("down")
        /** The viewer's left: `facing.clockWise` (a north-facing screen's left edge is its east side). */
        val LEFT: BooleanProperty = BooleanProperty.create("left")
        val RIGHT: BooleanProperty = BooleanProperty.create("right")
        val ON: BooleanProperty = BooleanProperty.create("on")

        /** The edge flag a neighbour in [dir] decides, or null for the front/back. */
        fun edge(facing: Direction, dir: Direction): BooleanProperty? = when (dir) {
            Direction.UP -> UP
            Direction.DOWN -> DOWN
            facing.clockWise -> LEFT
            facing.counterClockWise -> RIGHT
            else -> null
        }

        /** The panel: 6 px deep against the back of the cell, the bezel's front face 10 px in from the screen side. */
        private val SHAPES: Map<Direction, VoxelShape> = mapOf(
            Direction.NORTH to box(0.0, 0.0, 10.0, 16.0, 16.0, 16.0),
            Direction.SOUTH to box(0.0, 0.0, 0.0, 16.0, 16.0, 6.0),
            Direction.EAST to box(0.0, 0.0, 0.0, 6.0, 16.0, 16.0),
            Direction.WEST to box(10.0, 0.0, 0.0, 16.0, 16.0, 16.0),
        )
    }
}

class MonitorBlockEntity(pos: BlockPos, state: BlockState) : DeviceBlockEntity(DeviceBlockEntities.MONITOR.get(), pos, state) {
    val on: Boolean get() = blockState.getValue(MonitorBlock.ON)
    val facing: Direction get() = blockState.getValue(MonitorBlock.FACING)

    /** What the wall shows, top to bottom — held by the wall's origin tile (see [MonitorWall]); empty elsewhere. */
    var widgets: List<Widget> = emptyList()
        private set

    /**
     * Replaces the screen's content. A graph hands the same list over every tick, so nothing is sent or marked
     * for saving unless something on the screen actually changed — a chunk dirtied every tick is re-saved every
     * 10 s, which on a dedicated server with synchronous chunk writes is a visible stall.
     */
    fun show(list: List<Widget>) {
        val next = list.take(Widget.MAX_WIDGETS)
        if (next.size == widgets.size && next.indices.all { next[it].sameAs(widgets[it]) }) return
        widgets = next
        sync()
    }

    fun clear() {
        if (widgets.isEmpty()) return
        widgets = emptyList()
        sync()
    }

    override fun saveSynced(tag: CompoundTag, registries: HolderLookup.Provider) {
        tag.putWidgets("widgets", widgets, registries)
    }

    override fun loadSynced(tag: CompoundTag, registries: HolderLookup.Provider) {
        widgets = tag.getWidgets("widgets", registries)
    }

    override fun registerControllers(controllers: AnimatableManager.ControllerRegistrar) {
        controllers.add(AnimationController(this, "main", 0) { state -> state.setAndContinue(IDLE) })
    }

    companion object {
        val IDLE: RawAnimation = RawAnimation.begin().thenLoop("animation.quantum_monitor.idle")
    }
}
