package bpm.nodes

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
            result("Moved", McVs.int)
            command {
                val a = host.fluids(from()) ?: return@command 0L
                val b = host.fluids(to()) ?: return@command 0L
                val moved = Transfer.fluids(a, b, fluid(), max().toInt().coerceAtLeast(0))
                if (moved > 0) host.transferred(from(), to(), moved, bpm.net.EffectKind.FLUID, fluid() ?: "")
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
            result("Ok", McVs.bool)
            command { FluidWorld.place(host, link(), fluid()?.takeIf { it.isNotBlank() }) }
        }
        func("pickup") {
            title("Pick Up Fluid")
            doc("Take the source block a link points at — or the liquid a block there holds — into the controller's tanks, as a bucket would. Answers whether it did.")
            val link = param("Link", McVs.link, "which block")
            result("Ok", McVs.bool)
            command { FluidWorld.pickup(host, link()) }
        }
    }
}

/** Buckets without the bucket: a source block into the tanks and back. */
internal object FluidWorld {
    private val BUCKET: Int = FluidType.BUCKET_VOLUME

    fun place(host: ControllerHost, linkName: String, fluidId: String?): Boolean {
        val r = host.link(linkName) ?: return false
        if (!r.loaded) return false
        val level = host.level
        val pos = r.link.pos
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
        host.transferred(ControllerHost.SELF, linkName, BUCKET, bpm.net.EffectKind.FLUID, net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(stack.fluid).toString())
        return true
    }

    fun pickup(host: ControllerHost, linkName: String): Boolean {
        val r = host.link(linkName) ?: return false
        if (!r.loaded) return false
        val level = host.level
        val pos = r.link.pos
        val state = level.getBlockState(pos)
        val fs = state.fluidState
        if (fs.isEmpty || !fs.isSource) return false
        val block = state.block as? BucketPickup ?: return false
        val stack = FluidStack(fs.type, BUCKET)
        val tanks = host.selfTanks
        if (tanks.fill(stack, IFluidHandler.FluidAction.SIMULATE) < BUCKET) return false
        val got = block.pickupBlock(null, level, pos, state)
        if (got.isEmpty) return false
        tanks.fill(stack, IFluidHandler.FluidAction.EXECUTE)
        val sound = block.pickupSound.orElse(null) ?: fs.type.fluidType.getSound(SoundActions.BUCKET_FILL) ?: SoundEvents.BUCKET_FILL
        level.playSound(null, pos, sound, SoundSource.BLOCKS, 1f, 1f)
        level.gameEvent(null, GameEvent.FLUID_PICKUP, pos)
        host.transferred(linkName, ControllerHost.SELF, BUCKET, bpm.net.EffectKind.FLUID, net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(fs.type).toString())
        return true
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
            result("Moved", McVs.int)
            command {
                val a = host.energy(from()) ?: return@command 0L
                val b = host.energy(to()) ?: return@command 0L
                val moved = Transfer.energy(a, b, max().toInt().coerceAtLeast(0))
                if (moved > 0) host.transferred(from(), to(), moved, bpm.net.EffectKind.ENERGY)
                moved.toLong()
            }
        }
    }
}
