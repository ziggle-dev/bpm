package bpm.world.devices

import bpm.BpmConfig
import bpm.BpmConfig.orDefault
import bpm.chamber.Chambers
import bpm.world.ContentBlocks
import bpm.world.ContentItems
import bpm.world.DeviceBlockEntities
import bpm.world.GateFrames
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import bpm.platform.BlockUseResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.DirectionProperty
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import software.bernie.geckolib.animation.AnimatableManager
import software.bernie.geckolib.animation.AnimationController
import software.bernie.geckolib.animation.PlayState
import software.bernie.geckolib.animation.RawAnimation
import java.util.UUID
import kotlin.math.abs

/**
 * The Quantum Gate projector: the top-centre block of a 5 × 5 ring of gate frames in a vertical plane, which
 * projects the walk-through rift downward. A Coherence Lens opens it for a while; an entity standing in the
 * opening for ten ticks travels — into the player's Decoherence Chamber from the overworld, back out through
 * the chamber's return gate, which is always open.
 */
class GateBlock(properties: Properties) : Block(properties), EntityBlock {
    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(OPEN, false))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING, OPEN)
    }

    /** The oval faces the placer; the ring spans the axis across that, and the spine runs away behind it. */
    override fun getStateForPlacement(ctx: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(FACING, ctx.horizontalDirection.opposite)

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = GateBlockEntity(pos, state)
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.ENTITYBLOCK_ANIMATED

    override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? =
        DeviceBlockEntity.ticker(level, type, DeviceBlockEntities.GATE.get())

    override fun useItemOn(stack: ItemStack, state: BlockState, level: Level, pos: BlockPos, player: Player, hand: InteractionHand, hit: BlockHitResult): bpm.platform.BlockUseResult {
        if (!stack.`is`(ContentItems.COHERENCE_LENS.get())) return bpm.platform.BlockUse.PASS_TO_BLOCK
        if (level.isClientSide) return bpm.platform.BlockUse.SUCCESS
        val be = level.getBlockEntity(pos) as? GateBlockEntity ?: return bpm.platform.BlockUse.PASS_TO_BLOCK
        if (be.tryOpen(player) && !player.isCreative) stack.shrink(1)
        return bpm.platform.BlockUse.CONSUME
    }

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hit: BlockHitResult): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val be = level.getBlockEntity(pos) as? GateBlockEntity ?: return InteractionResult.PASS
        be.revalidate()
        player.displayClientMessage(Component.literal("[bpm] " + be.describe()), true)
        if (!state.getValue(OPEN)) be.triggerAnim("overlay", "pulse")
        return InteractionResult.CONSUME
    }

    override fun neighborChanged(state: BlockState, level: Level, pos: BlockPos, block: Block, fromPos: BlockPos, moving: Boolean) {
        super.neighborChanged(state, level, pos, block, fromPos, moving)
        if (!level.isClientSide) (level.getBlockEntity(pos) as? GateBlockEntity)?.frameChanged()
    }

    /** The projector going away un-forms its ring. */
    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, moving: Boolean) {
        if (!state.`is`(newState.block) && !level.isClientSide) GateFrames.setFormed(level, pos, state.getValue(FACING), false)
        super.onRemove(state, level, pos, newState, moving)
    }

    companion object {
        /** The side the oval faces (where you approach from); the spine of three frames runs the other way. */
        val FACING: DirectionProperty = BlockStateProperties.HORIZONTAL_FACING
        val OPEN: BooleanProperty = BlockStateProperties.OPEN

        /** The horizontal axis the ring spans: a north-facing gate stretches east–west. */
        fun axisOf(facing: Direction): Direction.Axis = facing.clockWise.axis
    }
}

/** The 5 × 5 ring the projector needs, checked against the world. */
object GateFrame {
    /** The direction along the ring's plane for [axis]. */
    fun along(axis: Direction.Axis): Direction = if (axis == Direction.Axis.X) Direction.EAST else Direction.SOUTH

    /** The ring's centre — two below the projector. */
    fun centre(projector: BlockPos): BlockPos = projector.below(2)

    /** The nine cells one block behind the 3 × 3 opening: the backplate the rift ends on. */
    fun backplate(projector: BlockPos, facing: Direction): List<BlockPos> {
        val u = along(GateBlock.axisOf(facing))
        val c = centre(projector).relative(facing.opposite)
        return (-1..1).flatMap { du -> (-1..1).map { dv -> c.relative(u, du).above(dv) } }
    }

    /** Whether the ring around [projector] is complete — frames on the edges, corners where asked, an empty 3 × 3
     * inside — and a 3 × 3 backplate of frames stands directly behind the opening (away from [facing]). */
    fun check(level: BlockGetter, projector: BlockPos, facing: Direction, requireCorners: Boolean): Boolean =
        firstProblem(level, projector, facing, requireCorners) == null

    /** The first cell that is not what the gate needs, or null when it is whole — for the check and for telling the player. */
    fun firstProblem(level: BlockGetter, projector: BlockPos, facing: Direction, requireCorners: Boolean): BlockPos? {
        val axis = GateBlock.axisOf(facing)
        val u = along(axis)
        for (p in backplate(projector, facing)) {
            if (!level.getBlockState(p).`is`(ContentBlocks.GATE_FRAME.get())) return p
        }
        val c = centre(projector)
        for (du in -2..2) for (dv in -2..2) {
            if (du == 0 && dv == 2) continue
            val p = c.relative(u, du).above(dv)
            val state = level.getBlockState(p)
            val edge = abs(du) == 2 || abs(dv) == 2
            if (edge) {
                val corner = abs(du) == 2 && abs(dv) == 2
                val frame = state.`is`(ContentBlocks.GATE_FRAME.get())
                val cornerBlock = state.`is`(ContentBlocks.GATE_FRAME_CORNER.get())
                val ok = if (corner) cornerBlock || (!requireCorners && frame) else frame
                if (!ok) return p
            } else if (!(state.isAir || state.canBeReplaced())) {
                return p
            }
        }
        return null
    }

    /** The walk-through volume: the 3-wide, 3-tall opening below the projector, a quarter block thick each way. */
    fun volume(projector: BlockPos, axis: Direction.Axis): AABB {
        val c = Vec3.atCenterOf(projector)
        val top = projector.y.toDouble()
        return if (axis == Direction.Axis.X) AABB(c.x - 1.5, top - 3.0, c.z - 0.3, c.x + 1.5, top, c.z + 0.3)
        else AABB(c.x - 0.3, top - 3.0, c.z - 1.5, c.x + 0.3, top, c.z + 1.5)
    }
}

class GateBlockEntity(pos: BlockPos, state: BlockState) : DeviceBlockEntity(DeviceBlockEntities.GATE.get(), pos, state) {
    var frameOk: Boolean = false
        private set

    /** Game time at which the gate closes; 0 = it stays open (the chamber's return gate). */
    var closeAtTick: Long = 0
        private set

    /** The chamber's way out: always open, flows outward, sends travellers home. */
    var returnGate: Boolean = false
    var slotOwner: UUID? = null

    /** +1 flows in (overworld), −1 flows out (the chamber) — what the model's `variable.direction` reads. */
    val direction: Int get() = if (returnGate) -1 else 1

    private val dwell = HashMap<UUID, Int>()
    private var validateIn = 1

    val isOpen: Boolean get() = blockState.getValue(GateBlock.OPEN)
    val facing: Direction get() = blockState.getValue(GateBlock.FACING)
    val axis: Direction.Axis get() = GateBlock.axisOf(facing)

    fun describe(): String = when {
        returnGate -> "the way out — step through"
        isOpen -> "open · ${((closeAtTick - (level?.gameTime ?: 0)).coerceAtLeast(0) / 20 / 60)} min left"
        frameOk -> "closed · use a Coherence Lens to open"
        else -> "the frame is incomplete at ${problem()?.toShortString() ?: "?"} — a 5 × 5 ring of gate frames, the projector at the top centre, nothing inside, and a 3 × 3 of gate frames directly behind the opening"
    }

    /** Opens for the configured time when the frame is whole; tells [player] why not otherwise. */
    fun tryOpen(player: Player?): Boolean {
        revalidate()
        if (isOpen) {
            player?.displayClientMessage(Component.literal("[bpm] the gate is already open"), true)
            return false
        }
        if (!frameOk) {
            player?.displayClientMessage(Component.literal("[bpm] the projection has nothing to focus — complete the frame"), true)
            triggerAnim("overlay", "pulse")
            return false
        }
        open(BpmConfig.GATE_OPEN_MINUTES.orDefault() * 60L * 20L)
        return true
    }

    fun open(forTicks: Long) {
        val l = level ?: return
        closeAtTick = if (forTicks <= 0 || returnGate) 0 else l.gameTime + forTicks
        if (!isOpen) l.setBlock(worldPosition, blockState.setValue(GateBlock.OPEN, true), 3)
        triggerAnim("main", "open")
        sync()
    }

    fun close() {
        val l = level ?: return
        closeAtTick = 0
        dwell.clear()
        if (isOpen) l.setBlock(worldPosition, blockState.setValue(GateBlock.OPEN, false), 3)
        triggerAnim("main", "close")
        sync()
    }

    fun frameChanged() {
        validateIn = 1
    }

    fun revalidate() {
        val l = level ?: return
        val ok = GateFrame.check(l, worldPosition, facing, BpmConfig.GATE_REQUIRE_CORNERS.orDefault())
        if (ok != frameOk) {
            frameOk = ok
            GateFrames.setFormed(l, worldPosition, facing, ok)
            sync()
        }
    }

    fun volume(): AABB = GateFrame.volume(worldPosition, axis)

    /** The cell spoiling the frame, if any. */
    fun problem(): BlockPos? = level?.let { GateFrame.firstProblem(it, worldPosition, facing, BpmConfig.GATE_REQUIRE_CORNERS.orDefault()) }

    override fun serverTick() {
        val l = level as? ServerLevel ?: return
        if (--validateIn <= 0) {
            validateIn = 40
            revalidate()
            if (isOpen && !frameOk && !returnGate) {
                close()
                return
            }
        }
        if (!isOpen) return
        if (!returnGate && closeAtTick > 0 && l.gameTime >= closeAtTick) {
            close()
            return
        }
        val volume = volume()
        val inside = l.getEntitiesOfClass(ServerPlayer::class.java, volume)
        val seen = HashSet<UUID>()
        for (p in inside) {
            seen += p.uuid
            if (p.isOnPortalCooldown) continue
            val n = (dwell[p.uuid] ?: 0) + 1
            dwell[p.uuid] = n
            if (n >= DWELL_TICKS) {
                travel(l, p, volume)
                return
            }
        }
        dwell.keys.retainAll(seen)
    }

    /** Sends [first] and everyone within four blocks of the opening through, as a party. */
    private fun travel(l: ServerLevel, first: ServerPlayer, volume: AABB) {
        dwell.clear()
        val party = LinkedHashSet<ServerPlayer>()
        party += first
        party += l.getEntitiesOfClass(ServerPlayer::class.java, volume.inflate(4.0)).filter { !it.isOnPortalCooldown }
        triggerAnim("overlay", "pulse")
        for (p in party) {
            if (returnGate) Chambers.leave(p) else Chambers.enter(p, this)
        }
    }

    /** Where a traveller who entered from [player]'s side stands when sent back: just outside the plane, on the floor. */
    fun outsideOf(player: Player): Vec3 {
        val c = Vec3.atCenterOf(worldPosition)
        val floorY = worldPosition.y - 3.0
        return if (axis == Direction.Axis.X) {
            val side = if (player.z >= c.z) 1.0 else -1.0
            Vec3(c.x, floorY, c.z + side * 1.6)
        } else {
            val side = if (player.x >= c.x) 1.0 else -1.0
            Vec3(c.x + side * 1.6, floorY, c.z)
        }
    }

    // ---- sync / save ------------------------------------------------------------------------------------

    override fun saveSynced(tag: CompoundTag, registries: HolderLookup.Provider) {
        tag.putBoolean("frameOk", frameOk)
        tag.putLong("closeAt", closeAtTick)
        tag.putBoolean("returnGate", returnGate)
        slotOwner?.let { tag.putUUID("slotOwner", it) }
    }

    override fun loadSynced(tag: CompoundTag, registries: HolderLookup.Provider) {
        frameOk = tag.getBoolean("frameOk")
        closeAtTick = tag.getLong("closeAt")
        returnGate = tag.getBoolean("returnGate")
        slotOwner = if (tag.hasUUID("slotOwner")) tag.getUUID("slotOwner") else null
    }

    // ---- geckolib ---------------------------------------------------------------------------------------

    override fun registerControllers(controllers: AnimatableManager.ControllerRegistrar) {
        controllers.add(
            AnimationController(this, "main", 2) { state ->
                // Only the projector block renders until the ring is formed; a formed, closed gate shows its clamps.
                state.setAndContinue(if (isOpen) IDLE else if (frameOk) CLOSED else UNFORMED)
            }.triggerableAnim("open", OPEN_ANIM).triggerableAnim("close", CLOSE_ANIM),
        )
        controllers.add(AnimationController(this, "overlay", 0) { PlayState.STOP }.triggerableAnim("pulse", PULSE))
    }

    companion object {
        const val DWELL_TICKS = 10
        val IDLE: RawAnimation = RawAnimation.begin().thenLoop("animation.quantum_gate.idle")
        val OPEN_ANIM: RawAnimation = RawAnimation.begin().thenPlay("animation.quantum_gate.open").thenLoop("animation.quantum_gate.idle")
        val CLOSE_ANIM: RawAnimation = RawAnimation.begin().thenPlay("animation.quantum_gate.close").thenLoop("animation.quantum_gate.closed")
        val CLOSED: RawAnimation = RawAnimation.begin().thenLoop("animation.quantum_gate.closed")
        val UNFORMED: RawAnimation = RawAnimation.begin().thenLoop("animation.quantum_gate.unformed")
        val PULSE: RawAnimation = RawAnimation.begin().thenPlay("animation.quantum_gate.pulse")
    }
}
