package bpm.nodes

import bpm.catalog.McVs
import bpm.world.ControllerStores
import bpm.world.ModFluids
import dev.ziggle.vscript.nodes.Contribution
import dev.ziggle.vscript.nodes.library
import net.minecraft.world.entity.ExperienceOrb
import net.minecraft.world.phys.AABB
import net.neoforged.neoforge.fluids.FluidStack
import net.neoforged.neoforge.fluids.capability.IFluidHandler

/**
 * `xp.*` — experience as a thing the controller can hold. Orbs on the ground become liquid experience in
 * the controller's tanks (twenty millibuckets a point); the tanks can pour it back out as orbs anywhere a
 * link points. Between the two it is an ordinary fluid: `fluids.move` it into another mod's tank, or
 * bucket it.
 */
object XpNodes {
    private const val MB = ControllerStores.XP_MB_PER_POINT

    private fun liquid(points: Int): FluidStack = FluidStack(ModFluids.EXPERIENCE.get(), points * MB)

    fun contribution(host: ControllerHost): Contribution = library("xp", "Experience") {
        func("vacuum") {
            title("Vacuum Experience")
            doc(
                """
                Pick up the experience orbs around a link (or around the controller, with `self`) into the
                controller's tanks as liquid experience. Answers how many points were taken; an orb the tanks
                cannot hold stays where it is.
                """,
            )
            val link = param("Link", McVs.link, "around which block", default = "self")
            val radius = param("Radius", McVs.float, "how far, in blocks", default = 1.5)
            val fx = param("Effects", McVs.bool, "draw the rift and what travels through it", default = true)
            result("Points", McVs.int)
            command {
                val center = centerOf(host, link()) ?: return@command 0L
                val box = AABB(center, center).inflate(radius().coerceIn(0.5, 8.0))
                var points = 0
                for (orb in host.level.getEntitiesOfClass(ExperienceOrb::class.java, box)) {
                    val value = orb.value
                    if (value <= 0) continue
                    val want = liquid(value)
                    if (host.selfTanks.fill(want, IFluidHandler.FluidAction.SIMULATE) < want.amount) continue
                    host.selfTanks.fill(want, IFluidHandler.FluidAction.EXECUTE)
                    orb.discard()
                    points += value
                }
                if (points > 0 && fx()) host.transferred(link(), ControllerHost.SELF, points, bpm.net.EffectKind.XP)
                points.toLong()
            }
        }
        func("drop") {
            title("Drop Experience")
            doc("Pour experience out of the controller's tanks as orbs at a link (or at the controller, with `self`). Answers how many points came out — fewer than asked when the tanks run dry.")
            val link = param("Link", McVs.link, "where the orbs appear", default = "self")
            val points = param("Points", McVs.int, "how many points", default = 1L)
            val fx = param("Effects", McVs.bool, "draw the rift and what travels through it", default = true)
            result("Dropped", McVs.int)
            command {
                val center = centerOf(host, link()) ?: return@command 0L
                val asked = points().toInt().coerceAtLeast(0)
                if (asked == 0) return@command 0L
                val have = host.selfTanks.drain(liquid(asked), IFluidHandler.FluidAction.SIMULATE).amount / MB
                if (have <= 0) return@command 0L
                host.selfTanks.drain(liquid(have), IFluidHandler.FluidAction.EXECUTE)
                ExperienceOrb.award(host.level, center, have)
                if (fx()) host.transferred(ControllerHost.SELF, link(), have, bpm.net.EffectKind.XP)
                have.toLong()
            }
        }
        func("stored") {
            title("Experience Stored")
            doc("How many points of liquid experience a link's tanks hold (the controller's own, with `self`).")
            val link = param("Link", McVs.link, "which tank", default = "self")
            result("Points", McVs.int)
            query {
                val h = host.fluids(link()) ?: return@query 0L
                var mb = 0L
                for (i in 0 until h.tanks) {
                    val s = h.getFluidInTank(i)
                    if (!s.isEmpty && s.fluid == ModFluids.EXPERIENCE.get()) mb += s.amount
                }
                mb / MB
            }
        }
    }
}
