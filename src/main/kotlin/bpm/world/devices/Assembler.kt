package bpm.world.devices

import bpm.platform.ports.Droplets
import bpm.platform.ports.FluidVolume
import bpm.platform.ports.EnergyCell
import bpm.world.DeviceBlockEntities
import bpm.world.DeviceBlocks
import bpm.world.ModFluids
import bpm.platform.ports.MultiTank
import bpm.platform.ports.SlotStore
import bpm.world.assembly.AssemblyInput
import bpm.world.assembly.AssemblyRecipe
import bpm.world.assembly.ModRecipes
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import bpm.platform.ResourceLocation
import net.minecraft.world.Containers
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.item.crafting.RecipeHolder
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.phys.BlockHitResult
import bpm.platform.AnimatableManager
import software.bernie.geckolib.animation.AnimationController
import software.bernie.geckolib.animation.RawAnimation
import bpm.platform.intOr
import bpm.platform.floatOr
import bpm.platform.stringOr
import bpm.platform.boolOr
import bpm.platform.byteOr
import bpm.platform.compoundOr
import bpm.platform.listOr

/**
 * The Quantum Assembler: the block bpm's own things are made on, instead of a crafting grid.
 *
 * [RUNNING] is on the blockstate rather than only in the entity so the light level can follow it without the
 * client needing the entity at all.
 */
class AssemblerBlock(properties: Properties) : HorizontalDirectionalBlock(properties), EntityBlock {

    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, net.minecraft.core.Direction.NORTH).setValue(RUNNING, false))
    }

    override fun codec(): com.mojang.serialization.MapCodec<out HorizontalDirectionalBlock> = CODEC

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING, RUNNING)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(FACING, context.horizontalDirection.opposite)

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = AssemblerBlockEntity(pos, state)

    override fun getRenderShape(state: BlockState): RenderShape = bpm.platform.ANIMATED_BLOCK_SHAPE

    override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? =
        DeviceBlockEntity.ticker(level, type, DeviceBlockEntities.ASSEMBLER.get())

    /** The catalyst goes in by hand; anything else is a miss and falls through to placing a block. */
    override fun useItemOn(
        stack: ItemStack,
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: net.minecraft.world.InteractionHand,
        hit: BlockHitResult,
    ): bpm.platform.BlockUseResult {
        if (level.isClientSide) return bpm.platform.BlockUse.sidedSuccess(true)
        val be = level.getBlockEntity(pos) as? AssemblerBlockEntity ?: return bpm.platform.BlockUse.PASS_TO_BLOCK
        val refusal = be.offer(stack)
        if (refusal != null) {
            player.displayClientMessage(Component.literal("[bpm] $refusal"), true)
            return bpm.platform.BlockUse.FAIL
        }
        return bpm.platform.BlockUse.SUCCESS
    }

    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, moved: Boolean) {
        if (!state.`is`(newState.block)) {
            (level.getBlockEntity(pos) as? AssemblerBlockEntity)?.let { be ->
                Containers.dropItemStack(level, pos.x + 0.5, pos.y + 1.0, pos.z + 0.5, be.catalyst)
            }
        }
        super.onRemove(state, level, pos, newState, moved)
    }

    companion object {
        val CODEC: com.mojang.serialization.MapCodec<AssemblerBlock> = simpleCodec(::AssemblerBlock)
        val FACING = BlockStateProperties.HORIZONTAL_FACING
        val RUNNING: BooleanProperty = BooleanProperty.create("running")
    }
}

/**
 * A fabrication in progress, and the machine that keeps it alive.
 *
 * The whole point of the design is that a job is a *process you must keep supplied* rather than a click:
 * every tick it draws its share of power and liquid experience, and every tick it cannot, [coherence] falls.
 * At zero the job decoheres and most of the ingredients are gone. Keeping something fed for hundreds of ticks
 * is exactly what a controller graph is for — see `docs/DESIGN_TIERS_AND_FABRICATION.md` §4.3.
 */
class AssemblerBlockEntity(pos: BlockPos, state: BlockState) : DeviceBlockEntity(DeviceBlockEntities.ASSEMBLER.get(), pos, state) {

    /**
     * The catalyst — the thing that actually starts a job.
     *
     * It cannot be put in until the pedestals already spell out a recipe for it ([offer]), and it is spent
     * the moment the job begins rather than when it ends. Handing the machine a lens IS the start button,
     * so a job that decoheres has cost you the lens along with half the ingredients.
     */
    var catalyst: ItemStack = ItemStack.EMPTY
        private set

    val energy = EnergyCell(ENERGY_FE.toLong(), ENERGY_RATE.toLong()) { setChanged() }
    val tanks = MultiTank(1, Droplets.ofMb(TANK_MB)) { setChanged() }

    /**
     * One slot, the catalyst, so a pipe can feed the machine. There is no output slot: a finished job puts
     * its result on top of the assembler as a real item, so a hopper under it is the way to collect.
     */
    val items = object : SlotStore(1, {}) {
        private fun changed() {
            catalyst = stackIn(CATALYST)
            sync()
            setChanged()
        }

        override fun setStackIn(slot: Int, stack: ItemStack): Boolean =
            super.setStackIn(slot, stack).also { if (it) changed() }

        override fun insert(slot: Int, stack: ItemStack, simulate: Boolean): ItemStack =
            super.insert(slot, stack, simulate).also { if (!simulate) changed() }

        override fun extract(slot: Int, amount: Int, simulate: Boolean): ItemStack =
            super.extract(slot, amount, simulate).also { if (!simulate) changed() }

        /**
         * Only while the machine is actually ready for one — a pipe must not be able to stuff a lens into
         * an assembler whose pedestals are wrong and leave it sitting there doing nothing.
         */
        override fun isValid(slot: Int, stack: ItemStack): Boolean =
            slot == CATALYST && !running && recipeFor(stack) != null
    }

    var running: Boolean = false
        private set
    var ticksDone: Int = 0
        private set
    var totalTicks: Int = 0
        private set

    /** 1.0 while fed, 0 when the job falls apart. The number a graph watches and defends. */
    var coherence: Float = 1f
        private set

    /** What is wrong with the feed right now, or [Instability.NONE]. Synced: it is what the effects colour by. */
    var instability: Instability = Instability.NONE
        private set

    /** What the running job will make — synced so the client can render it forming in the focus. */
    var forming: ItemStack = ItemStack.EMPTY
        private set

    private var recipeId: ResourceLocation? = null

    /** The fault as of the last packet, so a change of colour is never held back by the sync interval. */
    private var wasUnstable: Instability = Instability.NONE

    /**
     * Where the pedestals are, recomputed on demand: a player may add one mid-build.
     *
     * Every one of them, not the first [AssemblyRecipe.MAX_INGREDIENTS]. Capping the SCAN meant eight empty
     * pedestals could hide the loaded ones behind them and the machine would report that nothing matched
     * while staring at a correct layout. The cap belongs on how many ingredients a recipe may ask for, and
     * a layout with too many loaded pedestals simply matches no recipe, which is already the right answer.
     */
    fun pedestals(): List<PedestalBlockEntity> {
        val l = level ?: return emptyList()
        val found = ArrayList<PedestalBlockEntity>()
        for (p in BlockPos.betweenClosed(worldPosition.offset(-REACH, -REACH, -REACH), worldPosition.offset(REACH, REACH, REACH))) {
            val be = l.getBlockEntity(p) as? PedestalBlockEntity ?: continue
            // The chamber's altars are not anybody's crafting bench.
            if (be.slotOwner != null) continue
            found += be
        }
        return found
    }

    /** Begin the job the catalyst in the slot pays for. Answers why not, or null when it started. */
    fun start(): String? {
        if (running) return "already assembling"
        if (catalyst.isEmpty) return "no catalyst in the assembler"
        val found = recipeFor(catalyst) ?: return "the pedestals do not spell out a recipe for that"
        val recipe = found.value()
        // Spent on starting, not on finishing: the lens is what sets the process going.
        items.extract(CATALYST, 1, false)
        recipeId = bpm.platform.recipeIdOf(found)
        running = true
        ticksDone = 0
        totalTicks = recipe.ticks
        coherence = 1f
        instability = Instability.NONE
        // Whatever arrived before the job began is not this job's first delivery.
        empty()
        forming = recipe.result.copy()
        setRunningState(true)
        triggerAnim("main", "assemble")
        // Without this the client learns nothing until the first periodic sync, so the machine spins up
        // holding an invisible item for half a second.
        sync()
        return null
    }

    override fun serverTick() {
        if (!running) {
            // Emptied even while idle. A feed that runs before the job does would otherwise fill the
            // buffers, and a full buffer accepts nothing — which reads as starvation and, since a fault
            // means nothing is drawn, never clears. That deadlock took about 24 seconds of an idle graph.
            empty()
            // Only a real check when something is actually in the slot, so an idle assembler costs one
            // field read a tick. A pipe may feed a catalyst too, and `isItemValid` has already made sure a
            // machine that is not ready cannot be given one.
            if (!catalyst.isEmpty) start()
            return
        }
        val l = level ?: return
        val recipe = recipe() ?: run { stop(); return }

        // Judged on the RATE that arrived, both ways. A fabrication is a resonance, not a furnace: starve
        // it and it fades, flood it and it tears. Either is a fault, and each has its own colour.
        //
        // Whatever is in the buffers IS this tick's delivery, because they were emptied at the end of the
        // last one — the machine meters a flow rather than storing it.
        val gotEnergy = energy.stored.toInt()
        val gotXp = xpStored()
        empty()
        instability = when {
            gotEnergy < recipe.energyPerTick -> Instability.POWER_LOW
            gotEnergy > recipe.energyPerTick -> Instability.POWER_HIGH
            gotXp < recipe.experiencePerTick -> Instability.XP_LOW
            gotXp > recipe.experiencePerTick -> Instability.XP_HIGH
            else -> Instability.NONE
        }

        if (instability == Instability.NONE) {
            coherence = (coherence + RECOVER).coerceAtMost(1f)
            ticksDone++
        } else {
            coherence -= DECAY
        }

        if (coherence <= 0f) {
            decohere()
            return
        }
        if (ticksDone >= totalTicks) finish(recipe)
        // Every tick would be a packet a tick for a 600-tick job; the client interpolates the rest. A
        // change of fault is always worth a packet, though — it is the thing the player is looking at.
        if (ticksDone % SYNC_EVERY == 0 || coherence < 1f || instability != wasUnstable) sync()
        wasUnstable = instability
    }

    /**
     * Offer [held] as the catalyst: takes one and begins, or answers why it will not.
     *
     * Refusing rather than accepting-and-waiting is the point. A catalyst sitting in a machine that will not
     * run looks exactly like a machine that is broken; this way the refusal names the actual problem while
     * the player is still stood there holding the thing.
     */
    fun offer(held: ItemStack): String? {
        if (running) return "already assembling"
        if (!catalyst.isEmpty) return "a catalyst is already in it"
        if (pedestals().none { !it.held.isEmpty }) return "nothing is on the pedestals"
        if (recipeFor(held) == null) return "the pedestals do not spell out a recipe for that"
        items.setStackIn(CATALYST, held.split(1))
        start()
        return null
    }

    /** The recipe these pedestals make with [withCatalyst], or null — the capstone aside, which cannot run yet. */
    private fun recipeFor(withCatalyst: ItemStack): RecipeHolder<AssemblyRecipe>? {
        val l = level ?: return null
        if (withCatalyst.isEmpty) return null
        val input = AssemblyInput(pedestals().map { it.held }, withCatalyst)
        val found = bpm.platform.findRecipe(l, ModRecipes.ASSEMBLY.get(), input) ?: return null
        return if (found.value().paired) null else found
    }

    /**
     * Take everything in the buffers, spent or wasted.
     *
     * The assembler is a metering chamber, not a battery: nothing carries between ticks, so what is in it
     * is always exactly what one tick delivered. That is what makes "exactly this much per tick" a rule the
     * machine can actually check — and it is also what stops a buffer filling up and locking out the very
     * delivery it is waiting for. Over-feeding therefore wastes the surplus, which is the point.
     */
    private fun empty() {
        if (energy.stored > 0L) energy.set(0L)
        val held = xpStored()
        if (held > 0) tanks.drain(FluidVolume.ofMb(ModFluids.EXPERIENCE.get(), held), simulate = false)
    }

    /** The liquid experience in the tank right now. */
    private fun xpStored(): Int = tanks.inTank(0).mb

    /**
     * It held together: the pedestals give up their stacks and the result appears where it was forming.
     *
     * A real item entity rather than an output slot — it drops in exactly the spot it has been hovering in
     * all job, so the thing you watched being made is the thing that lands, and a hopper under the machine
     * is all the automation it needs. `releaseStack` places it by its VISUAL point, which is why this does
     * not go through `Containers.dropItemStack` and its random offset (see `LinkAnchors`).
     */
    private fun finish(recipe: AssemblyRecipe) {
        val l = level ?: return
        val around = pedestals()
        recipe.pairUp(around.map { it.held })?.forEach { around[it].take() }
        bpm.nodes.releaseStack(
            l,
            net.minecraft.world.phys.Vec3(worldPosition.x + 0.5, worldPosition.y + SHOW_HEIGHT, worldPosition.z + 0.5),
            recipe.result.copy(),
        )
        triggerAnim("main", "finish")
        l.playSound(null, worldPosition, net.minecraft.sounds.SoundEvents.BEACON_ACTIVATE, net.minecraft.sounds.SoundSource.BLOCKS, 0.7f, 1.6f)
        stop()
    }

    /**
     * It came apart: half of what was on the pedestals hits the floor and the rest is gone.
     *
     * Half rather than none because losing everything to a power flicker is the kind of punishment that makes
     * people stop using a machine, and half rather than all because a process that costs nothing to fail is
     * not a process worth defending.
     */
    private fun decohere() {
        val l = level ?: return
        for (pedestal in pedestals()) {
            val stack = pedestal.take()
            if (stack.isEmpty) continue
            if (l.random.nextBoolean()) Containers.dropItemStack(l, worldPosition.x + 0.5, worldPosition.y + 1.2, worldPosition.z + 0.5, stack)
        }
        triggerAnim("main", "decohere")
        l.playSound(null, worldPosition, net.minecraft.sounds.SoundEvents.BEACON_DEACTIVATE, net.minecraft.sounds.SoundSource.BLOCKS, 1.0f, 0.6f)
        stop()
    }

    private fun stop() {
        running = false
        ticksDone = 0
        totalTicks = 0
        coherence = 1f
        instability = Instability.NONE
        forming = ItemStack.EMPTY
        recipeId = null
        setRunningState(false)
        sync()
    }

    private fun setRunningState(on: Boolean) {
        val l = level ?: return
        if (blockState.getValue(AssemblerBlock.RUNNING) != on) {
            l.setBlock(worldPosition, blockState.setValue(AssemblerBlock.RUNNING, on), 3)
        }
    }

    private fun recipe(): AssemblyRecipe? {
        val l = level ?: return null
        val id = recipeId ?: return null
        // The VALUE is type-checked, not the holder: an unchecked cast of the holder proves nothing at
        // runtime and would let a datapack that reused the id hand us some other recipe entirely.
        val holder = bpm.platform.recipeById(l, id) ?: return null
        return holder.value() as? AssemblyRecipe
    }

    /** How far through, 0…1 — the bar, and how far the focus has pulled itself together. */
    val progress: Float get() = if (totalTicks <= 0) 0f else (ticksDone.toFloat() / totalTicks).coerceIn(0f, 1f)

    override fun saveSynced(tag: CompoundTag, registries: HolderLookup.Provider) {
        tag.put("items", items.save(registries))
        tag.putInt("energy", energy.stored.toInt())
        tag.put("tanks", tanks.save())
        tag.putBoolean("running", running)
        tag.putInt("ticksDone", ticksDone)
        tag.putInt("totalTicks", totalTicks)
        tag.putFloat("coherence", coherence)
        tag.putByte("instability", instability.ordinal.toByte())
        tag.put("forming", forming.saveOptional(registries))
        recipeId?.let { tag.putString("recipe", it.toString()) }
    }

    override fun loadSynced(tag: CompoundTag, registries: HolderLookup.Provider) {
        if (tag.contains("items")) items.load(registries, tag.listOr("items"))
        catalyst = items.stackIn(CATALYST)
        energy.set(tag.intOr("energy", 0).toLong())
        if (tag.contains("tanks")) tanks.load(tag.listOr("tanks"))
        running = tag.boolOr("running", false)
        ticksDone = tag.intOr("ticksDone", 0)
        totalTicks = tag.intOr("totalTicks", 0)
        coherence = tag.floatOr("coherence", 0f)
        instability = Instability.entries.getOrElse(tag.byteOr("instability", 0).toInt()) { Instability.NONE }
        forming = ItemStack.parseOptional(registries, tag.compoundOr("forming"))
        recipeId = if (tag.contains("recipe")) ResourceLocation.tryParse(tag.stringOr("recipe", "")) else null
    }

    override fun registerControllers(controllers: bpm.platform.ControllerRegistrar) {
        controllers.add(
            bpm.platform.animController(this, "main", 5) { state -> state.setAndContinue(if (running) ASSEMBLE else IDLE) }
                .triggerableAnim("assemble", ASSEMBLE)
                .triggerableAnim("decohere", DECOHERE)
                .triggerableAnim("finish", FINISH),
        )
    }

    /**
     * What is wrong with a running job's supply.
     *
     * Both directions are faults and both have their own colour, because "you are feeding it wrong" is not
     * one problem: too little and too much need opposite corrections, and a machine that showed the same
     * warning for each would be telling you almost nothing.
     */
    enum class Instability {
        NONE,
        POWER_LOW,
        POWER_HIGH,
        XP_LOW,
        XP_HIGH,
    }

    companion object {
        /** How far a pedestal may sit from the machine and still feed it. */
        const val REACH = 2

        const val CATALYST = 0

        /**
         * How high above the block the result forms, and therefore where it is dropped.
         *
         * Shared with `AssemblerRenderer` rather than written twice: the item has to land exactly where it
         * was hovering, and it clears both the cage and a top-face link's own tear, which sits at
         * `0.5 + LinkAnchors.OFF_FACE`.
         */
        const val SHOW_HEIGHT = 0.5 + bpm.world.LinkAnchors.OFF_FACE + 0.55

        const val ENERGY_FE = 400_000
        const val ENERGY_RATE = 20_000
        const val TANK_MB = 32_000

        /** Per §4.3: it falls four times as fast as it recovers, so a starved job is not saved by a trickle. */
        const val DECAY = 0.02f
        const val RECOVER = 0.005f

        private const val SYNC_EVERY = 10

        private val IDLE: RawAnimation = RawAnimation.begin().thenLoop("animation.quantum_assembler.idle")
        private val ASSEMBLE: RawAnimation = RawAnimation.begin().thenLoop("animation.quantum_assembler.assemble")
        private val DECOHERE: RawAnimation = RawAnimation.begin().thenPlay("animation.quantum_assembler.decohere")
        private val FINISH: RawAnimation = RawAnimation.begin().thenPlay("animation.quantum_assembler.finish")
    }
}
