package bpm.chamber

import bpm.world.ContentBlocks
import bpm.world.DeviceBlocks
import bpm.world.FrameCorner
import bpm.world.FrameRun
import bpm.world.GateFrames
import bpm.world.devices.GateBlock
import bpm.world.devices.GateBlockEntity
import bpm.world.devices.PedestalBlock
import bpm.world.devices.PedestalBlockEntity
import bpm.world.devices.PhaseBlock
import bpm.world.devices.PhaseBlockEntity
import bpm.world.devices.TrapBlockEntity
import bpm.world.devices.TrapMode
import bpm.world.devices.TurretBlock
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.RotatedPillarBlock
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.block.state.BlockState
import java.util.Random
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What varies from room to room, drawn once from a slot's seed (its owner and how many times it has reset):
 * how many bridges cross the trench and where, where the spikes and vents sit, which four wall spots hold
 * turrets (each with a console tile on the floor in front of it), the cover pillars scattered on the outer
 * floor, and the four coherence crystals. Positions are relative to the room's origin.
 */
class RoomLayout(
    /** Bridge headings in degrees (0 = east, 90 = south, 270 = north — the gate side always has one). */
    val bridges: List<Int>,
    val spikes: List<BlockPos>,
    val vents: List<BlockPos>,
    val turrets: List<Pair<BlockPos, Direction>>,
    /** One console tile per turret, in the same order: stand on it to turn the turret on the Warden. */
    val consoles: List<BlockPos>,
    /** Cover columns: base and height; every third one carries a light. */
    val cover: List<Pair<BlockPos, Int>>,
    /** The crystal blocks (on two-high pillars): breaking one weakens the Warden's cage. */
    val crystals: List<BlockPos>,
    /** The wall pillars' light caps — what a blackout takes away. */
    val lights: List<BlockPos>,
) {
    fun save(): CompoundTag = CompoundTag().also { t ->
        t.putIntArray("bridges", bridges.toIntArray())
        t.putIntArray("spikes", flat(spikes))
        t.putIntArray("vents", flat(vents))
        t.putIntArray("turrets", turrets.flatMap { (p, d) -> listOf(p.x, p.y, p.z, d.get3DDataValue()) }.toIntArray())
        t.putIntArray("consoles", flat(consoles))
        t.putIntArray("cover", cover.flatMap { (p, h) -> listOf(p.x, p.y, p.z, h) }.toIntArray())
        t.putIntArray("crystals", flat(crystals))
        t.putIntArray("lights", flat(lights))
    }

    companion object {
        private fun flat(list: List<BlockPos>) = list.flatMap { listOf(it.x, it.y, it.z) }.toIntArray()
        private fun unflat(a: IntArray): List<BlockPos> = (0 until a.size / 3).map { BlockPos(a[it * 3], a[it * 3 + 1], a[it * 3 + 2]) }

        fun load(t: CompoundTag): RoomLayout? {
            if (!t.contains("spikes")) return null
            val tr = t.getIntArray("turrets")
            val cv = t.getIntArray("cover")
            return RoomLayout(
                bridges = t.getIntArray("bridges").toList(),
                spikes = unflat(t.getIntArray("spikes")),
                vents = unflat(t.getIntArray("vents")),
                turrets = (0 until tr.size / 4).map { BlockPos(tr[it * 4], tr[it * 4 + 1], tr[it * 4 + 2]) to Direction.from3DDataValue(tr[it * 4 + 3]) },
                consoles = unflat(t.getIntArray("consoles")),
                cover = (0 until cv.size / 4).map { BlockPos(cv[it * 4], cv[it * 4 + 1], cv[it * 4 + 2]) to cv[it * 4 + 3] },
                crystals = unflat(t.getIntArray("crystals")),
                lights = unflat(t.getIntArray("lights")),
            )
        }

        fun generate(seed: Long): RoomLayout {
            val rnd = Random(seed)
            val cx = ChamberBuilder.CX
            val cz = ChamberBuilder.CZ

            // Bridges: the gate side always; one or two more on other headings.
            val bridges = linkedSetOf(270)
            val extra = rnd.nextInt(3)
            while (bridges.size < 1 + extra) bridges += rnd.nextInt(8) * 45

            // Spikes: six to ten on the ring at the dais's foot — the cells one out from the pyramid's base
            // that are still inside the trench (the skirt ring, corners clipped) — evenly spaced by angle from
            // a random start. On the ring rather than at a radius, so none ever rounds onto a stair.
            val foot = ChamberBuilder.DAIS_BASE + 1
            val footRing = ArrayList<BlockPos>()
            for (dx in -foot..foot) for (dz in -foot..foot) {
                if (abs(dx) != foot && abs(dz) != foot) continue
                if (Math.hypot(dx.toDouble(), dz.toDouble()) >= 7.5) continue
                footRing += BlockPos(cx + dx, 0, cz + dz)
            }
            val byAngle = footRing.sortedBy { (Math.toDegrees(atan2((it.z - cz).toDouble(), (it.x - cx).toDouble())) + 360) % 360 }
            val spikes = LinkedHashSet<BlockPos>()
            val nSpikes = 6 + rnd.nextInt(5)
            val start = rnd.nextInt(byAngle.size)
            for (k in 0 until nSpikes) spikes += byAngle[(start + k * byAngle.size / nSpikes) % byAngle.size]

            // Vents: four of eight spots on the outer ring.
            val ventOffset = if (rnd.nextBoolean()) 0.0 else 22.5
            val ventSpots = (0 until 8).map { k ->
                val a = Math.toRadians(k * 45.0 + ventOffset)
                BlockPos(cx + (12 * cos(a)).roundToInt(), 0, cz + (12 * sin(a)).roundToInt())
            }.shuffled(rnd)
            val vents = ventSpots.take(4)

            // Turrets: four pillars of their own on the outer floor, one to a quadrant, three or four high, the
            // turret on top facing up (the model's own frame) and a console three blocks toward the centre.
            val size = ChamberBuilder.SIZE
            val turrets = ArrayList<Pair<BlockPos, Direction>>()
            val consoles = ArrayList<BlockPos>()
            val t0 = rnd.nextDouble() * 90.0
            for (k in 0 until 4) {
                val a = Math.toRadians(t0 + k * 90.0 + (rnd.nextDouble() * 40.0 - 20.0))
                var r = 11.5 + rnd.nextDouble() * 3.0
                var x = cx + (r * cos(a)).roundToInt()
                var z = cz + (r * sin(a)).roundToInt()
                // A pillar on a vent's cell would bury the vent: step outward until the spot is clear of them.
                var tries = 0
                while (tries++ < 4 && vents.any { it.distSqr(BlockPos(x, 0, z)) <= 2.0 }) {
                    r += 1.0
                    x = cx + (r * cos(a)).roundToInt()
                    z = cz + (r * sin(a)).roundToInt()
                }
                val h = 3 + rnd.nextInt(2)
                turrets += BlockPos(x, h + 1, z) to Direction.UP
                // The console is a lit tile on the floor in front of its turret, three and a half in.
                //
                // Never inside the trench, though. The trench ring runs from 7.5 to 9.5 and a turret at
                // the near end of its range puts r - 3.5 at 8.0, so a console lands in the ring often
                // enough to matter. Consoles are written AFTER the floor pass, so one that does either
                // plugs the pit with a solid light or -- worse, and this is how it was found -- lands on
                // a bridge cell and punches the crossing out.
                //
                // Step outward a cell at a time until the rounded cell is clear, the same way a turret
                // steps clear of a vent above. A fixed radius will not do it: the cell is rounded, and
                // at 20 degrees a radius of 10 rounds to (9, 3), which is 9.487 -- back in the ring.
                var cr = r - 3.5
                var cp = BlockPos(cx + (cr * cos(a)).roundToInt(), 0, cz + (cr * sin(a)).roundToInt())
                while (hypot((cp.x - cx).toDouble(), (cp.z - cz).toDouble()) < 9.5) {
                    cr += 1.0
                    cp = BlockPos(cx + (cr * cos(a)).roundToInt(), 0, cz + (cr * sin(a)).roundToInt())
                }
                consoles += cp
            }

            // Crystals: on the diagonals, near the walls, each nudged a little.
            val crystals = (0 until 4).map { k ->
                val a = Math.toRadians(45.0 + k * 90.0 + (rnd.nextDouble() * 30.0 - 15.0))
                BlockPos(cx + (15.5 * cos(a)).roundToInt(), 3, cz + (15.5 * sin(a)).roundToInt())
            }

            // Cover: six to ten columns on the outer floor, clear of everything else and of the bridge lines.
            val taken = ArrayList<BlockPos>()
            taken += vents
            taken += consoles
            taken += turrets.map { BlockPos(it.first.x, 0, it.first.z) }
            taken += crystals.map { BlockPos(it.x, 0, it.z) }
            val cover = ArrayList<Pair<BlockPos, Int>>()
            val nCover = 6 + rnd.nextInt(5)
            var tries = 0
            while (cover.size < nCover && tries++ < 200) {
                val r = 11.5 + rnd.nextDouble() * 4.5
                val a = rnd.nextDouble() * Math.PI * 2
                val p = BlockPos(cx + (r * cos(a)).roundToInt(), 0, cz + (r * sin(a)).roundToInt())
                val deg = Math.toDegrees(a).let { (it + 360) % 360 }
                if (bridges.any { angleDiff(deg, it.toDouble()) < 16.0 }) continue
                if (taken.any { it.distSqr(p) < 7.0 } || cover.any { it.first.distSqr(p) < 7.0 }) continue
                if (p.x !in 2..size - 2 || p.z !in 2..size - 2) continue
                cover += p to (2 + rnd.nextInt(2))
            }

            // The wall pillars' light caps.
            val lights = ArrayList<BlockPos>()
            for (i in 0..size) {
                if (i % 6 != 2) continue
                lights += BlockPos(i, ChamberBuilder.WALL_H + 1, 0)
                lights += BlockPos(i, ChamberBuilder.WALL_H + 1, size)
                lights += BlockPos(0, ChamberBuilder.WALL_H + 1, i)
                lights += BlockPos(size, ChamberBuilder.WALL_H + 1, i)
            }
            return RoomLayout(bridges.toList(), spikes.toList(), vents, turrets, consoles, cover, crystals, lights)
        }

        fun angleDiff(a: Double, b: Double): Double {
            val d = abs(((a - b) % 360.0 + 540.0) % 360.0 - 180.0)
            return d
        }
    }
}

/**
 * Stamps the arena (§6 of the mechanics design) around a slot's origin from its [RoomLayout], in code rather
 * than from a template so the layout is a function and a reset is a rebuild with a fresh draw.
 *
 * Fixed: a 41 × 41 footprint, walls 12 high with pillars every six blocks and lights on their caps, a
 * barrier band above the walls, an alloy ceiling at 16; the dais and pedestal at the centre; a two-deep
 * trench ring at radius 8–9; the return gate in the north wall with its threshold in circuit floor.
 * Drawn: the bridges over the trench, the spikes, vents, turrets and their consoles, the cover, the crystals.
 */
object ChamberBuilder {
    const val SIZE = 40
    const val WALL_H = 12
    const val CEILING = 16
    const val CX = 20
    const val CZ = 20

    /** The dais: [DAIS_TIERS] stair-rimmed tiers, the bottom one [DAIS_BASE] blocks out from the centre, the pedestal at y = [DAIS_HEIGHT]. */
    const val DAIS_BASE = 5
    const val DAIS_TIERS = 4
    const val DAIS_HEIGHT = DAIS_TIERS + 2

    fun build(level: ServerLevel, slot: ChamberSlot) {
        val layout = RoomLayout.generate(slot.seed)
        slot.layout = layout
        val o = slot.origin
        for (cx in ((o.x - 2) shr 4)..((o.x + SIZE + 2) shr 4)) for (cz in ((o.z - 2) shr 4)..((o.z + SIZE + 2) shr 4)) level.getChunk(cx, cz)

        val floor = ContentBlocks.CHAMBER_FLOOR.get().defaultBlockState()
        val circuit = ContentBlocks.CHAMBER_FLOOR_CIRCUIT.get().defaultBlockState()
        val wall = ContentBlocks.CHAMBER_WALL.get().defaultBlockState()
        val pillar = ContentBlocks.CHAMBER_PILLAR.get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y)
        val light = ContentBlocks.CHAMBER_LIGHT.get().defaultBlockState()
        val barrier = ContentBlocks.CONTAINMENT_BARRIER.get().defaultBlockState()
        val alloy = ContentBlocks.QUANTUM_ALLOY_BLOCK.get().defaultBlockState()
        val crystal = ContentBlocks.ENTANGLIUM_BLOCK.get().defaultBlockState()
        val frame = ContentBlocks.GATE_FRAME.get().defaultBlockState()
        val corner = ContentBlocks.GATE_FRAME_CORNER.get().defaultBlockState()
        val air = Blocks.AIR.defaultBlockState()

        fun set(x: Int, y: Int, z: Int, state: BlockState) {
            level.setBlock(o.offset(x, y, z), state, Block.UPDATE_CLIENTS or Block.UPDATE_KNOWN_SHAPE)
        }
        fun set(p: BlockPos, state: BlockState) = set(p.x, p.y, p.z, state)

        // Everything the room occupies, cleared — a reset drops whatever the visitors built.
        for (x in -1..SIZE + 1) for (z in -1..SIZE + 1) for (y in -3..CEILING + 1) set(x, y, z, air)

        // Floor with its trench, the ring under it, the circuit rings and the bridges.
        for (x in 0..SIZE) for (z in 0..SIZE) {
            val d = dist(x, z)
            val trench = d >= 7.5 && d < 9.5
            set(x, -2, z, wall)
            set(x, -1, z, if (trench) air else wall)
            val deg = Math.toDegrees(atan2((z - CZ).toDouble(), (x - CX).toDouble())).let { (it + 360) % 360 }
            val bridge = trench && layout.bridges.any { RoomLayout.angleDiff(deg, it.toDouble()) <= 11.0 }
            val ring = (d >= 4.5 && d < 5.5) || (d >= 12.5 && d < 13.5)
            set(
                x, 0, z,
                when {
                    bridge -> DeviceBlocks.PHASE_BLOCK.get().defaultBlockState().setValue(PhaseBlock.SOLID, true)
                    trench -> air
                    ring -> circuit
                    else -> floor
                },
            )
        }
        for (x in 19..21) for (z in 1..2) set(x, 0, z, circuit)

        // Walls, pillars, lights, the barrier band, the ceiling.
        for (x in 0..SIZE) for (z in 0..SIZE) {
            val edge = x == 0 || x == SIZE || z == 0 || z == SIZE
            if (!edge) {
                set(x, CEILING, z, alloy)
                continue
            }
            val onPillar = (x % 6 == 2 && (z == 0 || z == SIZE)) || (z % 6 == 2 && (x == 0 || x == SIZE))
            for (y in 1..WALL_H) set(x, y, z, if (onPillar) pillar else wallAt(x, y, z, slot.seed))
            set(x, WALL_H + 1, z, if (onPillar) light else barrier)
            for (y in WALL_H + 2..CEILING - 1) set(x, y, z, barrier)
            set(x, CEILING, z, alloy)
        }

        // The dais: a stepped alloy pyramid. Each tier is a square one block in from the last, rimmed with stairs
        // facing inward (the corners rounded by a shape pass after, since the builder places with known shapes);
        // a slab skirt at the foot, clipped to the island; and a summit — the pedestal's column with four collar
        // stairs and corner slabs around it. Every step is half a block, so it is walked, not jumped.
        val stairs = ContentBlocks.QUANTUM_ALLOY_STAIRS.get().defaultBlockState()
        val slab = ContentBlocks.QUANTUM_ALLOY_SLAB.get().defaultBlockState()
        val shaped = ArrayList<BlockPos>()
        fun stair(x: Int, y: Int, z: Int, facing: Direction) {
            set(x, y, z, stairs.setValue(StairBlock.FACING, facing))
            shaped += o.offset(x, y, z)
        }
        fun inward(dx: Int, dz: Int, w: Int): Direction = when {
            abs(dx) == w && abs(dz) < w -> if (dx > 0) Direction.WEST else Direction.EAST
            abs(dz) == w && abs(dx) < w -> if (dz > 0) Direction.NORTH else Direction.SOUTH
            else -> if (dz > 0) Direction.NORTH else Direction.SOUTH
        }
        for (k in 1..DAIS_TIERS) {
            val w = DAIS_BASE + 1 - k
            for (dx in -w..w) for (dz in -w..w) {
                if (abs(dx) == w || abs(dz) == w) stair(CX + dx, k, CZ + dz, inward(dx, dz, w)) else set(CX + dx, k, CZ + dz, alloy)
            }
        }
        val skirt = DAIS_BASE + 1
        for (dx in -skirt..skirt) for (dz in -skirt..skirt) {
            if (abs(dx) != skirt && abs(dz) != skirt) continue
            if (dist(CX + dx, CZ + dz) >= 7.5) continue
            set(CX + dx, 1, CZ + dz, slab)
        }
        val summit = DAIS_TIERS + 1
        set(CX, summit, CZ, alloy)
        stair(CX + 1, summit, CZ, Direction.WEST)
        stair(CX - 1, summit, CZ, Direction.EAST)
        stair(CX, summit, CZ + 1, Direction.NORTH)
        stair(CX, summit, CZ - 1, Direction.SOUTH)
        for (dx in intArrayOf(-1, 1)) for (dz in intArrayOf(-1, 1)) set(CX + dx, summit, CZ + dz, slab)
        for (p in shaped) level.setBlock(p, Block.updateFromNeighbourShapes(level.getBlockState(p), level, p), Block.UPDATE_CLIENTS or Block.UPDATE_KNOWN_SHAPE)
        set(CX, DAIS_HEIGHT, CZ, DeviceBlocks.CORE_PEDESTAL.get().defaultBlockState().setValue(PedestalBlock.HAS_CORE, true))
        (level.getBlockEntity(o.offset(CX, DAIS_HEIGHT, CZ)) as? PedestalBlockEntity)?.let { it.slotOwner = slot.owner; it.sync() }

        // Hazards, as drawn. The layout names floor cells; the traps are low plates that sit ON the floor
        // (`trapPos`), so the floor under them stays.
        // Never on the pyramid itself: the spike ring runs at its foot, and a cell the draw rounded inward
        // would put a plate on a stair. The skirt ring may take a plate; the dais proper may not.
        fun onDais(p: BlockPos) = abs(p.x - CX) <= DAIS_BASE && abs(p.z - CZ) <= DAIS_BASE
        for (p in layout.spikes) {
            if (onDais(p)) continue
            set(trapPos(p), DeviceBlocks.PHASE_SPIKE.get().defaultBlockState())
            trapMode(level, trapPos(o, p), TrapMode.CYCLE)
        }
        for (p in layout.vents) {
            if (onDais(p)) continue
            set(trapPos(p), DeviceBlocks.DECOHERENCE_VENT.get().defaultBlockState())
            trapMode(level, trapPos(o, p), TrapMode.CYCLE)
        }
        for (p in layout.vents) {
            (level.getBlockEntity(trapPos(o, p)) as? bpm.world.devices.VentBlockEntity)?.let { vent ->
                vent.peers.clear()
                vent.peers += layout.vents.filter { it != p }.map { trapPos(o, it) }
                vent.sync()
            }
        }
        val turret = DeviceBlocks.OBSERVER_TURRET.get().defaultBlockState()
        for ((p, facing) in layout.turrets) {
            for (y in 1 until p.y) set(p.x, y, p.z, pillar)
            set(p, turret.setValue(TurretBlock.FACING, facing))
        }
        for (p in layout.consoles) set(p, light)

        // Cover columns and the crystals on their pillars.
        for ((i, c) in layout.cover.withIndex()) {
            val (p, h) = c
            for (y in 1..h) set(p.x, y, p.z, pillar)
            if (i % 3 == 0) set(p.x, h + 1, p.z, light)
        }
        for (p in layout.crystals) {
            set(p.x, 1, p.z, pillar)
            set(p.x, 2, p.z, pillar)
            set(p, crystal)
        }

        // The return gate in the north wall: a ring in the wall, the way out behind it walled off from the void.
        for (x in 17..23) for (y in -1..5) set(x, y, -1, wall)
        // The frame runs along X (the gate's axis below); corners name their arms seen from the east side.
        for (du in -2..2) for (dv in 0..4) {
            val x = CX + du
            val edge = abs(du) == 2 || dv == 0 || dv == 4
            val isCorner = abs(du) == 2 && (dv == 0 || dv == 4)
            val state = when {
                !edge -> air
                isCorner -> corner.setValue(GateFrames.AXIS, Direction.Axis.X)
                    .setValue(GateFrames.CORNER, FrameCorner.of(if (du < 0) 1 else -1, if (dv == 0) 1 else -1))
                abs(du) == 2 -> frame.setValue(GateFrames.AXIS, Direction.Axis.X).setValue(GateFrames.ALONG, FrameRun.VERTICAL)
                else -> frame.setValue(GateFrames.AXIS, Direction.Axis.X).setValue(GateFrames.ALONG, FrameRun.HORIZONTAL)
            }
            set(x, dv, 0, state)
        }
        // The projector faces the room (south); the 3 x 3 backplate behind the opening replaces the wall at z = -1.
        for (du in -1..1) for (dv in 1..3) set(CX + du, dv, -1, frame.setValue(GateFrames.AXIS, Direction.Axis.X))
        set(CX, 4, 0, DeviceBlocks.QUANTUM_GATE.get().defaultBlockState().setValue(GateBlock.FACING, Direction.SOUTH).setValue(GateBlock.OPEN, true))
        (level.getBlockEntity(o.offset(CX, 4, 0)) as? GateBlockEntity)?.let { gate ->
            gate.returnGate = true
            gate.slotOwner = slot.owner
            gate.revalidate()
            gate.sync()
        }
    }

    /**
     * The wall palette, as the art notes place it: trim rows at the baseboard and the cornice, a conduit row
     * at mid-height wiring pillar to pillar, panels every fourth block or so (never on a grid — a hash of the
     * cell and the room's seed decides), a vent here and there on its own.
     */
    private fun wallAt(x: Int, y: Int, z: Int, seed: Long): BlockState {
        if (y == 1 || y == WALL_H) return ContentBlocks.CHAMBER_WALL_TRIM.get().defaultBlockState()
        if (y == 7) return ContentBlocks.CHAMBER_WALL_CONDUIT.get().defaultBlockState()
        var h = seed xor (x * 73856093L) xor (y * 19349663L) xor (z * 83492791L)
        h = (h xor (h ushr 29)) * -0x4498517a7b3558c5L
        h = h xor (h ushr 32)
        val roll = ((h % 100 + 100) % 100).toInt()
        return when {
            roll < 4 -> ContentBlocks.CHAMBER_WALL_VENT.get().defaultBlockState()
            roll < 26 -> ContentBlocks.CHAMBER_WALL_PANEL.get().defaultBlockState()
            else -> ContentBlocks.CHAMBER_WALL.get().defaultBlockState()
        }
    }

    /** A trap's block sits one above the floor cell the layout names. */
    fun trapPos(p: BlockPos): BlockPos = p.above()
    fun trapPos(origin: BlockPos, p: BlockPos): BlockPos = origin.offset(p.x, p.y + 1, p.z)

    private fun trapMode(level: ServerLevel, pos: BlockPos, mode: TrapMode) {
        (level.getBlockEntity(pos) as? TrapBlockEntity)?.let { it.mode = mode; it.sync() }
    }

    private fun dist(x: Int, z: Int): Double {
        val dx = (x - CX).toDouble()
        val dz = (z - CZ).toDouble()
        return sqrt(dx * dx + dz * dz)
    }
}
