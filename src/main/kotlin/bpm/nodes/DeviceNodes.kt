package bpm.nodes

import bpm.catalog.McVs
import bpm.catalog.values.BlockPosValue
import bpm.catalog.values.ItemStackValue
import bpm.world.ContentItems
import bpm.world.devices.GateBlockEntity
import bpm.world.devices.PedestalBlockEntity
import bpm.world.devices.PedestalHooks
import bpm.world.devices.PhaseBlockEntity
import bpm.world.devices.TrapBlockEntity
import bpm.world.devices.TrapMode
import bpm.world.devices.TurretBlockEntity
import io.osrsx.vscript.nodes.Contribution
import io.osrsx.vscript.nodes.library
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.phys.Vec3

/**
 * The chamber devices as things a controller can drive through links (§8 of the mechanics design): traps,
 * turrets, superposition blocks, gates and the pedestal. Every verb answers false / nothing when the link
 * does not point at that kind of device, is unloaded, or is missing.
 */
object DeviceNodes {
    private inline fun <reified T : BlockEntity> ControllerHost.device(link: String): T? {
        val r = link(link) ?: return null
        if (!r.loaded) return null
        return level.getBlockEntity(r.link.pos) as? T
    }

    fun trap(host: ControllerHost): Contribution = library("trap", "Traps") {
        func("fire") {
            title("Fire Trap")
            doc("Set off a spike plate or vent once — the tell, then the blades or the column. Answers whether it started (not while it is already going).")
            val link = param("Link", McVs.link, "which trap")
            result("Ok", McVs.bool)
            command { host.device<TrapBlockEntity>(link())?.fire() ?: false }
        }
        func("mode") {
            title("Trap Mode")
            doc("Whether a trap runs on its own timer (Cycle = true) or only when fired by a link or redstone (false).")
            val link = param("Link", McVs.link, "which trap")
            val cycle = param("Cycle", McVs.bool, "true for the timer, false for on demand", default = false)
            result("Ok", McVs.bool)
            command {
                val t = host.device<TrapBlockEntity>(link()) ?: return@command false
                t.mode = if (cycle()) TrapMode.CYCLE else TrapMode.LINKED
                t.sync()
                true
            }
        }
        func("proximity") {
            title("Trap Proximity")
            doc("Whether a trap on demand also goes off by itself when something living comes within 1.5 blocks.")
            val link = param("Link", McVs.link, "which trap")
            val on = param("On", McVs.bool, "armed or not", default = true)
            result("Ok", McVs.bool)
            command {
                val t = host.device<TrapBlockEntity>(link()) ?: return@command false
                t.proximity = on()
                t.sync()
                true
            }
        }
        func("state") {
            title("Trap State")
            doc("Where a trap is in its sequence: idle, armed, extended, retracting (spikes) — idle, charging, erupting (vents). Empty when the link is not a trap.")
            val link = param("Link", McVs.link, "which trap")
            result("State", McVs.string)
            query { host.device<TrapBlockEntity>(link())?.phase ?: "" }
        }
    }

    fun turret(host: ControllerHost): Contribution = library("turret", "Turrets") {
        func("target") {
            title("Turret Target")
            doc("Point a turret at a position — it stops hunting on its own until told to aim at nothing.")
            val link = param("Link", McVs.link, "which turret")
            val pos = param("Pos", McVs.blockPos.orNull(), "where to aim; empty to let it hunt")
            result("Ok", McVs.bool)
            command {
                val t = host.device<TurretBlockEntity>(link()) ?: return@command false
                t.aimAt(BlockPosValue.toBlockPos(pos())?.let { Vec3.atCenterOf(it) })
                true
            }
        }
        func("fire") {
            title("Turret Fire")
            doc("Fire the two-bolt volley at whatever the turret is aimed at. Answers whether it fired (not while reloading).")
            val link = param("Link", McVs.link, "which turret")
            result("Ok", McVs.bool)
            command { host.device<TurretBlockEntity>(link())?.takeIf { it.hasTarget }?.fire() ?: false }
        }
        func("tracking") {
            title("Turret Tracking")
            doc("Whether a turret has something in its sights.")
            val link = param("Link", McVs.link, "which turret")
            result("Tracking", McVs.bool)
            query { host.device<TurretBlockEntity>(link())?.hasTarget ?: false }
        }
    }

    fun phase(host: ControllerHost): Contribution = library("phase", "Superposition") {
        func("solid") {
            title("Phase Solid")
            doc("Make a superposition block solid (true) or a walk-through ghost (false); the change takes half a second. Answers whether the link is one.")
            val link = param("Link", McVs.link, "which block")
            val solid = param("Solid", McVs.bool, "solid or ghost", default = true)
            result("Ok", McVs.bool)
            command {
                val p = host.device<PhaseBlockEntity>(link()) ?: return@command false
                p.mode = TrapMode.LINKED
                p.requestSolid(solid())
                true
            }
        }
        func("cycle") {
            title("Phase Cycle")
            doc("Let a superposition block run its own six-second cycle (true) or hold what it is told (false).")
            val link = param("Link", McVs.link, "which block")
            val on = param("On", McVs.bool, "cycle or hold", default = true)
            result("Ok", McVs.bool)
            command {
                val p = host.device<PhaseBlockEntity>(link()) ?: return@command false
                p.mode = if (on()) TrapMode.CYCLE else TrapMode.LINKED
                p.sync()
                true
            }
        }
        func("isSolid") {
            title("Phase Is Solid")
            doc("Whether a superposition block is solid right now.")
            val link = param("Link", McVs.link, "which block")
            result("Solid", McVs.bool)
            query { host.device<PhaseBlockEntity>(link())?.solid ?: false }
        }
    }

    fun gate(host: ControllerHost): Contribution = library("gate", "Gates") {
        func("isOpen") {
            title("Gate Is Open")
            doc("Whether a gate is open.")
            val link = param("Link", McVs.link, "which gate")
            result("Open", McVs.bool)
            query { host.device<GateBlockEntity>(link())?.isOpen ?: false }
        }
        func("isWhole") {
            title("Gate Is Whole")
            doc("Whether a gate's frame is complete.")
            val link = param("Link", McVs.link, "which gate")
            result("Whole", McVs.bool)
            query { host.device<GateBlockEntity>(link())?.also { it.revalidate() }?.frameOk ?: false }
        }
        func("open") {
            title("Open Gate")
            doc("Open a gate with a Coherence Lens taken from the controller's buffer. Answers whether it opened (a whole frame, a lens in the buffer, not already open).")
            val link = param("Link", McVs.link, "which gate")
            result("Ok", McVs.bool)
            command {
                val g = host.device<GateBlockEntity>(link()) ?: return@command false
                g.revalidate()
                if (g.isOpen || !g.frameOk) return@command false
                val inv = host.selfInventory
                var slot = -1
                for (i in 0 until inv.slots) if (inv.getStackInSlot(i).`is`(ContentItems.COHERENCE_LENS.get())) { slot = i; break }
                if (slot < 0) return@command false
                if (inv.extractItem(slot, 1, false).isEmpty) return@command false
                g.tryOpen(null)
            }
        }
        func("close") {
            title("Close Gate")
            doc("Close a gate (a chamber's return gate never closes).")
            val link = param("Link", McVs.link, "which gate")
            result("Ok", McVs.bool)
            command {
                val g = host.device<GateBlockEntity>(link()) ?: return@command false
                if (!g.isOpen || g.returnGate) return@command false
                g.close()
                true
            }
        }
    }

    fun pedestal(host: ControllerHost): Contribution = library("pedestal", "Pedestal") {
        func("hasCore") {
            title("Pedestal Has Core")
            doc("Whether a core sits on the pedestal.")
            val link = param("Link", McVs.link, "which pedestal")
            result("HasCore", McVs.bool)
            query { host.device<PedestalBlockEntity>(link())?.hasCore ?: false }
        }
        func("awaken") {
            title("Awaken Warden")
            doc("Wake the Warden from a chamber pedestal that holds its core. Answers whether it started.")
            val link = param("Link", McVs.link, "which pedestal")
            result("Ok", McVs.bool)
            command { host.device<PedestalBlockEntity>(link())?.let { PedestalHooks.awaken(it) } ?: false }
        }
        func("claim") {
            title("Claim Core")
            doc("Take the core from a pedestal whose Warden has fallen, into the controller's buffer. Answers the core, or nothing.")
            val link = param("Link", McVs.link, "which pedestal")
            result("Core", McVs.itemStack.orNull())
            command {
                val p = host.device<PedestalBlockEntity>(link()) ?: return@command null
                val core = PedestalHooks.claim(p, null) ?: return@command null
                val left = net.neoforged.neoforge.items.ItemHandlerHelper.insertItemStacked(host.selfInventory, core.copy(), false)
                if (!left.isEmpty) net.minecraft.world.Containers.dropItemStack(host.level, host.pos.x + 0.5, host.pos.y + 1.0, host.pos.z + 0.5, left)
                ItemStackValue.record(core)
            }
        }
    }
}
