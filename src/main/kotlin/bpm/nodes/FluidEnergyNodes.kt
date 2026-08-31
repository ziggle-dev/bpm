package bpm.nodes

import bpm.BpmConfig.orDefault
import bpm.catalog.McVs
import bpm.catalog.values.FluidStackValue
import bpm.catalog.values.RegistryIds
import io.osrsx.vscript.nodes.Contribution
import io.osrsx.vscript.nodes.library
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.level.block.BucketPickup
import net.minecraft.world.level.block.LiquidBlockContainer
import net.minecraft.world.level.gameevent.GameEvent
import net.minecraft.world.level.material.FlowingFluid
import net.minecraft.world.level.material.Fluid
import net.neoforged.neoforge.common.SoundActions
import net.neoforged.neoforge.fluids.FluidStack
import net.neoforged.neoforge.fluids.FluidType
import net.neoforged.neoforge.fluids.capability.IFluidHandler

/** `fluids.*` — tanks reached through links. Amounts are millibuckets. */
object FluidNodes {
    fun contribution(host: ControllerHost): Contribution = library("fluids", "Fluids") {
        func("amount") {
            title("Fluid Amount")
            doc("How much a link holds, in millibuckets — of one fluid, or of everything.")
            val link = param("Link", McVs.link, "which tank")
            val fluid = param("Fluid", McVs.fluid.orNull(), "only this fluid; empty for any")
            result("Amount", McVs.int)
            query {
                val h = host.fluids(link()) ?: return@query 0L
                val want = fluid()?.takeIf { it.isNotBlank() }
                var total = 0L
                for (i in 0 until h.tanks) {
                    val s = h.getFluidInTank(i)
                    if (s.isEmpty) continue
                    if (want == null || RegistryIds.of(s.fluid) == want) total += s.amount
                }
                total
            }
        }
        func("tanks") {
            title("Tanks")
            doc("What each non-empty tank of a link holds.")
            val link = param("Link", McVs.link, "which tank")
            result("Tanks", McVs.fluidStack.list())
            query {
                val h = host.fluids(link()) ?: return@query emptyList<Any?>()
                (0 until h.tanks).map { h.getFluidInTank(it) }.filter { !it.isEmpty }.map(FluidStackValue::record)
            }
        }
        func("capacity") {
            title("Fluid Capacity")
            doc("How much a link's tanks can hold in total, in millibuckets.")
            val link = param("Link", McVs.link, "which tank")
            result("Capacity", McVs.int)
            query { host.fluids(link())?.let { h -> (0 until h.tanks).sumOf { h.getTankCapacity(it).toLong() } } ?: 0L }
        }
        func("move") {
            title("Move Fluid")
            doc("Move up to Max millibuckets from one link to another. Answers how much moved.")
            val from = param("From", McVs.link, "where from")
            val to = param("To", McVs.link, "where to")
            val fluid = param("Fluid", McVs.fluid.orNull(), "only this fluid; empty for whatever is first")
            val max = param("Max", McVs.int, "at most this many millibuckets", default = 1000L)
            val fx = param("Effects", McVs.bool, "draw the rift and what travels through it", default = true)
            result("Moved", McVs.int)
            command {
                val a = host.fluids(from()) ?: return@command 0L
                val b = host.fluids(to()) ?: return@command 0L
                val moved = Transfer.fluids(a, b, fluid(), max().toInt().coerceAtLeast(0))
                if (moved > 0 && fx()) host.transferred(from(), to(), moved, bpm.net.EffectKind.FLUID, fluid() ?: "")
                moved.toLong()
            }
        }
        func("drain") {
            title("Drain Fluid")
            doc("Take up to Max millibuckets out of a link. Nothing when there is nothing to take.")
            val from = param("From", McVs.link, "where from")
            val fluid = param("Fluid", McVs.fluid.orNull(), "only this fluid; empty for whatever is first")
            val max = param("Max", McVs.int, "at most this many millibuckets", default = 1000L)
            val simulate = param("Simulate", McVs.bool, "only ask what would come out", default = false)
            result("Fluid", McVs.fluidStack.orNull())
            command {
                val h = host.fluids(from()) ?: return@command null
                val action = if (simulate()) IFluidHandler.FluidAction.SIMULATE else IFluidHandler.FluidAction.EXECUTE
                val want = fluid()?.takeIf { it.isNotBlank() }?.let(RegistryIds::fluid)
                val out = if (want == null) h.drain(max().toInt(), action) else h.drain(FluidStack(want, max().toInt()), action)
                FluidStackValue.record(out)
            }
        }
        func("fill") {
            title("Fill Fluid")
            doc("Put some fluid into a link. Answers how much it took.")
            val to = param("To", McVs.link, "where to")
            val stack = param("Fluid", McVs.fluidStack, "what to put in")
            val simulate = param("Simulate", McVs.bool, "only ask how much would fit", default = false)
            result("Filled", McVs.int)
            command {
                val h = host.fluids(to()) ?: return@command 0L
                val action = if (simulate()) IFluidHandler.FluidAction.SIMULATE else IFluidHandler.FluidAction.EXECUTE
                h.fill(FluidStackValue.stack(stack()), action).toLong()
            }
        }
        func("place") {
            title("Place Fluid")
            doc("Pour a bucket's worth (1000 mB) from the controller's tanks into the world at a link — a source block where the link points, or into a block that can hold a liquid. Answers whether it went.")
            val link = param("Link", McVs.link, "where the source block goes")
            val fluid = param("Fluid", McVs.fluid.orNull(), "which fluid; empty for the first tank holding a bucket's worth")
            val fx = param("Effects", McVs.bool, "draw the rift and what travels through it", default = true)
            result("Ok", McVs.bool)
            command { FluidWorld.place(host, link(), fluid()?.takeIf { it.isNotBlank() }, fx()) }
        }
        func("pickup") {
            title("Pick Up Fluid")
            doc("Take the source block a link points at — or the liquid a block there holds — into the controller's tanks, as a bucket would. Answers whether it did.")
            val link = param("Link", McVs.link, "which block")
            val fx = param("Effects", McVs.bool, "draw the rift and what travels through it", default = true)
            result("Ok", McVs.bool)
            command { FluidWorld.pickup(host, link(), fx()) }
        }
    }
}

/**
 * Whether a source block belongs to a body big enough to treat as bottomless.
 *
 * **The flood fill is bounded and the answer is cached, and it has to be both.** `fluids.pickup` is
 * ordinarily called from `on tick`, so an unbounded search of an ocean would run every tick and stall the
 * server; the fill therefore stops the moment it has counted enough to decide, and never visits more than
 * the threshold. Even so, ten thousand block lookups is not something to do sixty times a second, so the
 * verdict is remembered per block for [TTL_TICKS].
 *
 * Caching a verdict means it can be stale. In the direction that matters it cannot bite: a body large
 * enough to pass has to lose thousands of blocks before it would fail, which nothing does inside ten
 * seconds. A body that GROWS past the threshold merely stays finite a little longer than it needs to.
 */
private object Bottomless {

    /** How long a verdict is trusted. Short enough to notice the world changing, long enough to matter. */
    private const val TTL_TICKS = 200L

    private class Verdict(val bottomless: Boolean, val until: Long)

    private data class Key(val dim: net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, val pos: Long)

    private val verdicts = HashMap<Key, Verdict>()

    fun covers(level: net.minecraft.server.level.ServerLevel, pos: net.minecraft.core.BlockPos, fluid: Fluid): Boolean {
        val threshold = bpm.BpmConfig.FLUID_INFINITE_SOURCES.orDefault()
        if (threshold <= 0) return false
        val now = level.gameTime
        val key = Key(level.dimension(), pos.asLong())
        verdicts[key]?.let { if (now < it.until) return it.bottomless }
        if (verdicts.size > MAX_CACHED) verdicts.entries.removeIf { now >= it.value.until }
        val bottomless = count(level, pos, fluid, threshold) >= threshold
        verdicts[key] = Verdict(bottomless, now + TTL_TICKS)
        return bottomless
    }

    /** Connected source blocks of the same fluid, counting no further than [cap]. */
    private fun count(level: net.minecraft.server.level.ServerLevel, from: net.minecraft.core.BlockPos, fluid: Fluid, cap: Int): Int {
        val seen = HashSet<Long>()
        val queue = ArrayDeque<net.minecraft.core.BlockPos>()
        seen += from.asLong()
        queue += from
        var found = 0
        while (queue.isNotEmpty() && found < cap) {
            val at = queue.removeFirst()
            found++
            for (dir in net.minecraft.core.Direction.entries) {
                val next = at.relative(dir)
                if (!seen.add(next.asLong())) continue
                // An unloaded chunk stops the search rather than loading the world to answer a question.
                if (!level.hasChunkAt(next)) continue
                val fs = level.getBlockState(next).fluidState
                if (fs.isEmpty || !fs.isSource || fs.type != fluid) continue
                queue += next
            }
        }
        return found
    }

    private const val MAX_CACHED = 512
}

/** Buckets without the bucket: a source block into the tanks and back. */
internal object FluidWorld {
    private val BUCKET: Int = FluidType.BUCKET_VOLUME

    fun place(host: ControllerHost, linkName: String, fluidId: String?, effects: Boolean = true): Boolean {
        val r = host.link(linkName) ?: return false
        if (!r.loaded) return false
        for (pos in targets(r)) if (placeAt(host, pos, linkName, fluidId, effects)) return true
        return false
    }

    private fun placeAt(host: ControllerHost, pos: net.minecraft.core.BlockPos, linkName: String, fluidId: String?, effects: Boolean): Boolean {
        val level = host.level
        val tanks = host.selfTanks
        val kind: Fluid = (if (fluidId != null) RegistryIds.fluid(fluidId) else firstBucket(tanks)) ?: return false
        val flowing = kind as? FlowingFluid ?: return false
        val stack = FluidStack(kind, BUCKET)
        if (tanks.drain(stack, IFluidHandler.FluidAction.SIMULATE).amount < BUCKET) return false
        val state = level.getBlockState(pos)
        val block = state.block
        val type = kind.fluidType
        val placed = when {
            level.dimensionType().ultraWarm() && type.isVaporizedOnPlacement(level, pos, stack) -> {
                type.onVaporize(null, level, pos, stack)
                true
            }
            block is LiquidBlockContainer && block.canPlaceLiquid(null, level, pos, state, kind) -> block.placeLiquid(level, pos, state, flowing.defaultFluidState())
            state.isAir || (state.canBeReplaced() && !state.liquid()) -> level.setBlock(pos, flowing.defaultFluidState().createLegacyBlock(), 11)
            else -> false
        }
        if (!placed) return false
        tanks.drain(stack, IFluidHandler.FluidAction.EXECUTE)
        level.playSound(null, pos, type.getSound(SoundActions.BUCKET_EMPTY) ?: SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 1f, 1f)
        level.gameEvent(null, GameEvent.FLUID_PLACE, pos)
        if (effects) host.transferred(ControllerHost.SELF, linkName, BUCKET, bpm.net.EffectKind.FLUID, net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(stack.fluid).toString())
        return true
    }

    fun pickup(host: ControllerHost, linkName: String, effects: Boolean = true): Boolean {
        val r = host.link(linkName) ?: return false
        if (!r.loaded) return false
        for (pos in targets(r)) if (pickupAt(host, pos, linkName, effects)) return true
        return false
    }

    private fun pickupAt(host: ControllerHost, pos: net.minecraft.core.BlockPos, linkName: String, effects: Boolean): Boolean {
        val level = host.level
        val state = level.getBlockState(pos)
        val fs = state.fluidState
        if (fs.isEmpty || !fs.isSource) return false
        val block = state.block as? BucketPickup ?: return false
        val stack = FluidStack(fs.type, BUCKET)
        val tanks = host.selfTanks
        if (tanks.fill(stack, IFluidHandler.FluidAction.SIMULATE) < BUCKET) return false
        // A big enough body is bottomless: fill from it and leave the block where it is. An ocean or a
        // nether lava sea qualifies; a pond does not, and gets drained a block at a time as before.
        if (Bottomless.covers(level, pos, fs.type)) {
            tanks.fill(stack, IFluidHandler.FluidAction.EXECUTE)
        } else {
            val got = block.pickupBlock(null, level, pos, state)
            if (got.isEmpty) return false
            tanks.fill(stack, IFluidHandler.FluidAction.EXECUTE)
        }
        val sound = block.pickupSound.orElse(null) ?: fs.type.fluidType.getSound(SoundActions.BUCKET_FILL) ?: SoundEvents.BUCKET_FILL
        level.playSound(null, pos, sound, SoundSource.BLOCKS, 1f, 1f)
        level.gameEvent(null, GameEvent.FLUID_PICKUP, pos)
        if (effects) host.transferred(linkName, ControllerHost.SELF, BUCKET, bpm.net.EffectKind.FLUID, net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(fs.type).toString())
        return true
    }

    /**
     * The blocks a bucket would act on for this link: the one it names, then the space its face opens onto.
     *
     * **A link can never name a fluid directly when it was made with the wand.** Linking goes through
     * `useOn`, whose hit result comes from the player's ordinary reach, and that clips straight through
     * liquids — you cannot right-click water, you hit whatever is behind it. So "the water here" is always
     * a link to the SOLID block beside it plus the face you clicked, and looking only at the linked block
     * meant `fluids.pickup` found stone and gave up every time.
     *
     * Trying the face's outward neighbour second is exactly what a bucket does: right-click a block face
     * and the liquid comes from, or goes into, the space in front of it. The linked position is still tried
     * first, so a coordinate link (`controller.linkAt`) naming the fluid outright keeps working.
     */
    private fun targets(r: bpm.world.ResolvedLink): List<net.minecraft.core.BlockPos> {
        val side = r.link.side ?: return listOf(r.link.pos)
        return listOf(r.link.pos, r.link.pos.relative(side))
    }

    private fun firstBucket(tanks: IFluidHandler): Fluid? {
        for (i in 0 until tanks.tanks) {
            val s = tanks.getFluidInTank(i)
            if (!s.isEmpty && s.amount >= BUCKET && s.fluid is FlowingFluid) return s.fluid
        }
        return null
    }
}

/** `energy.*` — energy stores reached through links, in FE. */
object EnergyNodes {
    fun contribution(host: ControllerHost): Contribution = library("energy", "Energy") {
        func("stored") {
            title("Energy Stored")
            doc("How much energy a link holds.")
            val link = param("Link", McVs.link, "which store")
            result("Stored", McVs.int)
            query { (host.energy(link())?.energyStored ?: 0).toLong() }
        }
        func("capacity") {
            title("Energy Capacity")
            doc("How much energy a link can hold.")
            val link = param("Link", McVs.link, "which store")
            result("Capacity", McVs.int)
            query { (host.energy(link())?.maxEnergyStored ?: 0).toLong() }
        }
        func("canReceive") {
            title("Can Receive Energy")
            doc("Whether a link accepts energy at all.")
            val link = param("Link", McVs.link, "which store")
            result("Can", McVs.bool)
            query { host.energy(link())?.canReceive() ?: false }
        }
        func("move") {
            title("Move Energy")
            doc("Move up to Max energy from one link to another. Answers how much moved.")
            val from = param("From", McVs.link, "where from")
            val to = param("To", McVs.link, "where to")
            val max = param("Max", McVs.int, "at most this much", default = 1000L)
            val fx = param("Effects", McVs.bool, "draw the rift and what travels through it", default = true)
            result("Moved", McVs.int)
            command {
                val a = host.energy(from()) ?: return@command 0L
                val b = host.energy(to()) ?: return@command 0L
                val moved = Transfer.energy(a, b, max().toInt().coerceAtLeast(0))
                if (moved > 0 && fx()) host.transferred(from(), to(), moved, bpm.net.EffectKind.ENERGY)
                moved.toLong()
            }
        }
    }
}
