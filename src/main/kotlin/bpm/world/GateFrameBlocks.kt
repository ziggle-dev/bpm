package bpm.world

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.StringRepresentable
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import kotlin.math.abs

/**
 * The gate's containment frame. A piece is placed as a plain full block with a dark conduit; when the
 * projector's validator finds the 5 × 5 ring whole it *forms* the ring ([GateFrames.setFormed]): side and top
 * pieces take the slab-shaped oriented models (`art/quantum_gate.README.md`) with the conduit lit, and the
 * bottom row plus its corners become lit full blocks — the threshold you walk over. Breaking any piece, or
 * the projector, un-forms the ring.
 *
 * `axis` is the horizontal axis the frame runs along, [bpm.world.devices.GateBlock.axisOf] the projector's facing
 * (X = the frame stands in the XY plane); "+along" is [GateFrames.along] — east for X, south for Z.
 * A straight piece runs [FrameRun.HORIZONTAL] (the top row), [FrameRun.VERTICAL] (a side) or is
 * [FrameRun.BASE] (the bottom row) or [FrameRun.BACK] (one of the nine backplate panels behind the opening — `flip`
 * turns the panel to face the opening for south/west-facing gates); a corner names the two directions its arms leave in: BOTTOM_LEFT's arms
 * go +along and up.
 */
enum class FrameRun(private val key: String) : StringRepresentable {
    HORIZONTAL("horizontal"), VERTICAL("vertical"), BASE("base"), BACK("back");

    override fun getSerializedName(): String = key
}

enum class FrameCorner(private val key: String, val alongSign: Int, val upSign: Int) : StringRepresentable {
    BOTTOM_LEFT("bottom_left", 1, 1), BOTTOM_RIGHT("bottom_right", -1, 1), TOP_LEFT("top_left", 1, -1), TOP_RIGHT("top_right", -1, -1);

    override fun getSerializedName(): String = key
    val isBottom: Boolean get() = upSign > 0
    fun mirrored(): FrameCorner = of(-alongSign, upSign)

    companion object {
        fun of(alongSign: Int, upSign: Int): FrameCorner = entries.first { it.alongSign == alongSign && it.upSign == upSign }
    }
}

object GateFrames {
    val AXIS: EnumProperty<Direction.Axis> = BlockStateProperties.HORIZONTAL_AXIS
    val ALONG: EnumProperty<FrameRun> = EnumProperty.create("along", FrameRun::class.java)
    val CORNER: EnumProperty<FrameCorner> = EnumProperty.create("corner", FrameCorner::class.java)
    val FORMED: BooleanProperty = BooleanProperty.create("formed")
    /** Backplate panels only: the gate faces south or west, so the panel's lit face is turned the other way. */
    val FLIP: BooleanProperty = BooleanProperty.create("flip")

    /** The slab-shaped formed pieces: 10 deep, centred in the block. */
    private val SLIM_X: VoxelShape = Block.box(0.0, 0.0, 3.0, 16.0, 16.0, 13.0)
    private val SLIM_Z: VoxelShape = Block.box(3.0, 0.0, 0.0, 13.0, 16.0, 16.0)
    /** Backplate panels: 6 deep, reaching 3 px out of their cell toward the opening so they sit flush against the
     * backs of the ring slabs (which end 3 px short of the cell boundary), and stopping 7 px into their own cell
     * so nothing hangs out behind the ring. Shapes past 0..16 are legal; collision lookups already scan one block
     * around the entity. */
    private const val BACK_PROTRUDE = 3.0
    private const val BACK_DEPTH = 3.0
    private val BACK_N: VoxelShape = Block.box(0.0, 0.0, -BACK_PROTRUDE, 16.0, 16.0, BACK_DEPTH)
    private val BACK_S: VoxelShape = Block.box(0.0, 0.0, 16.0 - BACK_DEPTH, 16.0, 16.0, 16.0 + BACK_PROTRUDE)
    private val BACK_E: VoxelShape = Block.box(16.0 - BACK_DEPTH, 0.0, 0.0, 16.0 + BACK_PROTRUDE, 16.0, 16.0)
    private val BACK_W: VoxelShape = Block.box(-BACK_PROTRUDE, 0.0, 0.0, BACK_DEPTH, 16.0, 16.0)

    fun along(axis: Direction.Axis): Direction = if (axis == Direction.Axis.X) Direction.EAST else Direction.SOUTH
    fun other(axis: Direction.Axis): Direction.Axis = if (axis == Direction.Axis.X) Direction.Axis.Z else Direction.Axis.X
    fun slim(axis: Direction.Axis): VoxelShape = if (axis == Direction.Axis.X) SLIM_X else SLIM_Z
    fun back(axis: Direction.Axis, flip: Boolean): VoxelShape =
        if (axis == Direction.Axis.X) (if (flip) BACK_S else BACK_N) else (if (flip) BACK_W else BACK_E)
    fun flipFor(facing: Direction): Boolean = facing == Direction.SOUTH || facing == Direction.WEST

    fun isFrame(state: BlockState): Boolean = state.block is GateFrameBlock || state.block is GateFrameCornerBlock

    /** The axis a player is building in: facing north/south they look at an XY frame, which runs along X. */
    fun axisFor(ctx: BlockPlaceContext): Direction.Axis = if (ctx.horizontalDirection.axis == Direction.Axis.Z) Direction.Axis.X else Direction.Axis.Z

    /** The axis of any frame piece touching [pos], so a piece joins the frame it is placed against. */
    fun neighbourAxis(level: BlockGetter, pos: BlockPos): Direction.Axis? {
        for (d in Direction.entries) {
            val s = level.getBlockState(pos.relative(d))
            if (isFrame(s)) return s.getValue(AXIS)
        }
        return null
    }

    fun frameAt(level: BlockGetter, pos: BlockPos, d: Direction): Boolean = isFrame(level.getBlockState(pos.relative(d)))

    /**
     * Forms or un-forms the ring around [projector]: every frame piece on the 5 × 5 edge gets its place in the
     * ring (base row, side, top row, or which corner) and `formed`. Called by the projector's validator whenever
     * its verdict changes, and when the projector goes away. Cells that are not frame pieces are left alone.
     */
    fun setFormed(level: Level, projector: BlockPos, facing: Direction, formed: Boolean) {
        val axis = bpm.world.devices.GateBlock.axisOf(facing)
        for (p in bpm.world.devices.GateFrame.backplate(projector, facing)) {
            val state = level.getBlockState(p)
            if (state.block !is GateFrameBlock) continue
            val next = state.setValue(AXIS, axis).setValue(FORMED, formed).setValue(ALONG, FrameRun.BACK).setValue(FLIP, flipFor(facing))
            if (next != state) level.setBlock(p, next, Block.UPDATE_ALL)
        }
        val u = along(axis)
        val c = projector.below(2)
        for (du in -2..2) for (dv in -2..2) {
            if (abs(du) != 2 && abs(dv) != 2) continue
            if (du == 0 && dv == 2) continue
            val p = c.relative(u, du).above(dv)
            val state = level.getBlockState(p)
            val next = when (state.block) {
                is GateFrameBlock -> state.setValue(AXIS, axis).setValue(FORMED, formed).setValue(
                    ALONG,
                    when {
                        dv == -2 -> FrameRun.BASE
                        dv == 2 -> FrameRun.HORIZONTAL
                        else -> FrameRun.VERTICAL
                    },
                )
                is GateFrameCornerBlock -> state.setValue(AXIS, axis).setValue(FORMED, formed)
                    .setValue(CORNER, FrameCorner.of(if (du < 0) 1 else -1, if (dv < 0) 1 else -1))
                else -> continue
            }
            if (next != state) level.setBlock(p, next, Block.UPDATE_ALL)
        }
    }

    /**
     * A frame piece was broken: wake every projector whose gate could include [pos] (at most two blocks away
     * horizontally — ring pieces along the plane, backplate pieces one behind and one along — and four up), so
     * it revalidates next tick and un-forms the rest.
     */
    fun pieceRemoved(level: Level, pos: BlockPos) {
        for (dx in -2..2) for (dz in -2..2) for (dy in 0..4) {
            (level.getBlockEntity(pos.offset(dx, dy, dz)) as? bpm.world.devices.GateBlockEntity)?.frameChanged()
        }
    }

    /** A rotation's effect on the corner naming: a quarter turn keeps or flips the +along side. */
    fun rotateCorner(corner: FrameCorner, rotation: Rotation): FrameCorner = when (rotation) {
        Rotation.CLOCKWISE_90 -> corner            // east -> south: +along stays +along
        Rotation.COUNTERCLOCKWISE_90 -> corner.mirrored() // east -> north: +along becomes -along
        Rotation.CLOCKWISE_180 -> corner.mirrored()
        else -> corner
    }

    /** Whether [mirror] flips the frame's along axis (LEFT_RIGHT mirrors Z, FRONT_BACK mirrors X). */
    fun mirrorFlips(axis: Direction.Axis, mirror: Mirror): Boolean =
        (mirror == Mirror.FRONT_BACK && axis == Direction.Axis.X) || (mirror == Mirror.LEFT_RIGHT && axis == Direction.Axis.Z)
}

/** A straight frame piece: a full block until the ring forms, then a slab (sides, top) or a lit base block. */
class GateFrameBlock(properties: Properties) : Block(properties) {
    init {
        registerDefaultState(
            stateDefinition.any().setValue(GateFrames.AXIS, Direction.Axis.X).setValue(GateFrames.ALONG, FrameRun.HORIZONTAL).setValue(GateFrames.FORMED, false).setValue(GateFrames.FLIP, false),
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(GateFrames.AXIS, GateFrames.ALONG, GateFrames.FORMED, GateFrames.FLIP)
    }

    override fun getStateForPlacement(ctx: BlockPlaceContext): BlockState {
        val level = ctx.level
        val pos = ctx.clickedPos
        val axis = GateFrames.neighbourAxis(level, pos) ?: GateFrames.axisFor(ctx)
        val a = GateFrames.along(axis)
        val stacked = GateFrames.frameAt(level, pos, Direction.UP) || GateFrames.frameAt(level, pos, Direction.DOWN)
        val inRow = GateFrames.frameAt(level, pos, a) || GateFrames.frameAt(level, pos, a.opposite)
        val run = when {
            stacked && !inRow -> FrameRun.VERTICAL
            inRow && !stacked -> FrameRun.HORIZONTAL
            ctx.clickedFace.axis.isVertical -> FrameRun.HORIZONTAL
            else -> FrameRun.VERTICAL
        }
        return defaultBlockState().setValue(GateFrames.AXIS, axis).setValue(GateFrames.ALONG, run).setValue(GateFrames.FORMED, false)
    }

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, ctx: CollisionContext): VoxelShape {
        if (!state.getValue(GateFrames.FORMED)) return Shapes.block()
        return when (state.getValue(GateFrames.ALONG)) {
            FrameRun.BASE -> Shapes.block()
            FrameRun.BACK -> GateFrames.back(state.getValue(GateFrames.AXIS), state.getValue(GateFrames.FLIP))
            else -> GateFrames.slim(state.getValue(GateFrames.AXIS))
        }
    }

    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, moving: Boolean) {
        if (!state.`is`(newState.block) && !level.isClientSide) GateFrames.pieceRemoved(level, pos)
        super.onRemove(state, level, pos, newState, moving)
    }

    override fun rotate(state: BlockState, rotation: Rotation): BlockState = when (rotation) {
        Rotation.CLOCKWISE_90, Rotation.COUNTERCLOCKWISE_90 -> state.setValue(GateFrames.AXIS, GateFrames.other(state.getValue(GateFrames.AXIS)))
        else -> state
    }
}

/** A frame corner: a full block until the ring forms, then a slab at the top or a lit full block at the bottom. */
class GateFrameCornerBlock(properties: Properties) : Block(properties) {
    init {
        registerDefaultState(
            stateDefinition.any().setValue(GateFrames.AXIS, Direction.Axis.X).setValue(GateFrames.CORNER, FrameCorner.BOTTOM_LEFT).setValue(GateFrames.FORMED, false),
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(GateFrames.AXIS, GateFrames.CORNER, GateFrames.FORMED)
    }

    override fun getStateForPlacement(ctx: BlockPlaceContext): BlockState {
        val level = ctx.level
        val pos = ctx.clickedPos
        val axis = GateFrames.neighbourAxis(level, pos) ?: GateFrames.axisFor(ctx)
        val a = GateFrames.along(axis)
        val alongSign = when {
            GateFrames.frameAt(level, pos, a) -> 1
            GateFrames.frameAt(level, pos, a.opposite) -> -1
            else -> 1
        }
        val upSign = when {
            GateFrames.frameAt(level, pos, Direction.UP) -> 1
            GateFrames.frameAt(level, pos, Direction.DOWN) -> -1
            ctx.clickedFace == Direction.DOWN -> -1 // placed under something: a top corner
            else -> 1
        }
        return defaultBlockState().setValue(GateFrames.AXIS, axis).setValue(GateFrames.CORNER, FrameCorner.of(alongSign, upSign)).setValue(GateFrames.FORMED, false)
    }

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, ctx: CollisionContext): VoxelShape =
        if (state.getValue(GateFrames.FORMED) && !state.getValue(GateFrames.CORNER).isBottom) GateFrames.slim(state.getValue(GateFrames.AXIS)) else Shapes.block()

    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, moving: Boolean) {
        if (!state.`is`(newState.block) && !level.isClientSide) GateFrames.pieceRemoved(level, pos)
        super.onRemove(state, level, pos, newState, moving)
    }

    override fun rotate(state: BlockState, rotation: Rotation): BlockState {
        val axis = state.getValue(GateFrames.AXIS)
        val corner = GateFrames.rotateCorner(state.getValue(GateFrames.CORNER), rotation)
        val newAxis = if (rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90) GateFrames.other(axis) else axis
        return state.setValue(GateFrames.AXIS, newAxis).setValue(GateFrames.CORNER, corner)
    }

    override fun mirror(state: BlockState, mirror: Mirror): BlockState =
        if (GateFrames.mirrorFlips(state.getValue(GateFrames.AXIS), mirror)) state.setValue(GateFrames.CORNER, state.getValue(GateFrames.CORNER).mirrored()) else state
}
