package bpm.chamber

import bpm.Bpm
import bpm.world.ModAttachments
import bpm.world.devices.GateBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.HolderLookup
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Containers
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.level.material.Fluids
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.UUID

/** The Decoherence Chamber's dimension — `bpm:decoherence`, a void with one room per player stamped into it. */
object ChamberDimension {
    val KEY: ResourceKey<Level> = ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(Bpm.ID, "decoherence"))

    fun level(server: MinecraftServer): ServerLevel? = server.getLevel(KEY)
    fun isChamber(level: Level): Boolean = level.dimension() == KEY
}

/** Where a slot's fight stands. Blocks in the room hold while it is not [CLEARED]. */
enum class SlotState { DORMANT, FIGHTING, CLEARED }

/** One player's room: a numbered plot along the dimension's X axis. */
class ChamberSlot(val owner: UUID, val index: Int) {
    var built: Boolean = false
    var state: SlotState = SlotState.DORMANT
    var lastVisit: Long = 0
    var claimedAt: Long = 0
    var stage3StartedAt: Long = 0
    var killedAt: Long = 0
    var lastKillByPlayer: Boolean = false
    var resetCount: Int = 0
    var crystalsBroken: Int = 0

    /** Whether anyone has been in since the room was last stamped: a room that then empties resets. */
    var occupied: Boolean = false

    /** The gate this visit came through (the overworld side), if any: it closes when the run ends. */
    var gateDim: ResourceKey<Level>? = null
    var gatePos: BlockPos? = null

    /** What this room's draw put where; null until it was built. */
    var layout: RoomLayout? = null

    /** The draw for this build of the room: the owner, and how many times it has been reset. */
    val seed: Long get() = owner.mostSignificantBits xor (owner.leastSignificantBits * 31) xor (resetCount.toLong() * 0x9E3779B97F4A7C15uL.toLong())

    /** The room's origin corner: block (0, 0, 0) of the layout is the floor's north-west corner. */
    val origin: BlockPos get() = BlockPos(index * Chambers.SPACING, Chambers.FLOOR_Y, 0)

    /** Whether the room's blocks refuse to break — while a Warden stands (or waits) in it. */
    val locked: Boolean get() = state != SlotState.CLEARED

    fun contains(pos: BlockPos): Boolean {
        val o = origin
        return pos.x in (o.x - 2)..(o.x + ChamberBuilder.SIZE + 2) && pos.z in (o.z - 2)..(o.z + ChamberBuilder.SIZE + 2)
    }

    /** Where a traveller arrives: the threshold in front of the return gate, facing into the room. */
    fun arrival(): Vec3 = Vec3(origin.x + 20.5, origin.y + 1.0, origin.z + 2.5)

    /** The pedestal at the centre of the dais. */
    val pedestal: BlockPos get() = origin.offset(ChamberBuilder.CX, ChamberBuilder.DAIS_HEIGHT, ChamberBuilder.CZ)

    /** The return gate's projector in the north wall. */
    val returnGate: BlockPos get() = origin.offset(20, 4, 0)

    fun save(): CompoundTag = CompoundTag().also { t ->
        t.putUUID("owner", owner)
        t.putInt("index", index)
        t.putBoolean("built", built)
        t.putString("state", state.name)
        t.putLong("lastVisit", lastVisit)
        t.putLong("claimedAt", claimedAt)
        t.putLong("stage3StartedAt", stage3StartedAt)
        t.putLong("killedAt", killedAt)
        t.putBoolean("lastKillByPlayer", lastKillByPlayer)
        t.putInt("resetCount", resetCount)
        t.putInt("crystalsBroken", crystalsBroken)
        t.putBoolean("occupied", occupied)
        gateDim?.let { t.putString("gateDim", it.location().toString()) }
        gatePos?.let { t.putLong("gatePos", it.asLong()) }
        layout?.let { t.put("layout", it.save()) }
    }

    companion object {
        fun load(t: CompoundTag): ChamberSlot? {
            if (!t.hasUUID("owner")) return null
            return ChamberSlot(t.getUUID("owner"), t.getInt("index")).also { s ->
                s.built = t.getBoolean("built")
                s.state = runCatching { SlotState.valueOf(t.getString("state")) }.getOrDefault(SlotState.DORMANT)
                s.lastVisit = t.getLong("lastVisit")
                s.claimedAt = t.getLong("claimedAt")
                s.stage3StartedAt = t.getLong("stage3StartedAt")
                s.killedAt = t.getLong("killedAt")
                s.lastKillByPlayer = t.getBoolean("lastKillByPlayer")
                s.resetCount = t.getInt("resetCount")
                s.crystalsBroken = t.getInt("crystalsBroken")
                s.occupied = t.getBoolean("occupied")
                s.gateDim = if (t.contains("gateDim")) ResourceLocation.tryParse(t.getString("gateDim"))?.let { ResourceKey.create(Registries.DIMENSION, it) } else null
                s.gatePos = if (t.contains("gatePos")) BlockPos.of(t.getLong("gatePos")) else null
                s.layout = if (t.contains("layout")) RoomLayout.load(t.getCompound("layout")) else null
            }
        }
    }
}

/**
 * Every player's room, and the way in and out.
 *
 * One slot per player (§6.2 of the mechanics design), generated lazily the first time they step through a
 * gate, 256 blocks apart along X so nothing of one room can see another. The room itself is stamped by
 * [ChamberBuilder]; this keeps the bookkeeping: who owns which plot, whether it is built, how the fight in
 * it stands. Saved with the overworld so it exists before the chamber level is ever loaded.
 */
class Chambers : SavedData() {
    val slots = LinkedHashMap<UUID, ChamberSlot>()
    private var nextIndex = 0

    fun slotFor(owner: UUID): ChamberSlot = slots.getOrPut(owner) {
        setDirty()
        ChamberSlot(owner, nextIndex++)
    }

    fun slotAt(pos: BlockPos): ChamberSlot? = slots.values.firstOrNull { it.contains(pos) }

    override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag {
        tag.putInt("next", nextIndex)
        tag.put("slots", ListTag().also { list -> slots.values.forEach { list.add(it.save()) } })
        return tag
    }

    companion object {
        const val NAME = "bpm_chambers"
        const val SPACING = 256
        const val FLOOR_Y = 64
        const val ARRIVAL_COOLDOWN = 60

        private val FACTORY = Factory(::Chambers, ::load, null)

        fun get(server: MinecraftServer): Chambers = server.overworld().dataStorage.computeIfAbsent(FACTORY, NAME)

        private fun load(tag: CompoundTag, registries: HolderLookup.Provider): Chambers = Chambers().also { c ->
            c.nextIndex = tag.getInt("next")
            val list = tag.getList("slots", Tag.TAG_COMPOUND.toInt())
            for (i in 0 until list.size) ChamberSlot.load(list.getCompound(i))?.let { c.slots[it.owner] = it }
        }

        private fun say(player: Player, text: String) = player.displayClientMessage(Component.literal("[bpm] $text"), true)

        /** Builds the slot's room if it is not there yet. False when the dimension is missing. */
        fun ensureBuilt(server: MinecraftServer, slot: ChamberSlot): Boolean {
            val chamber = ChamberDimension.level(server) ?: return false
            if (!slot.built) {
                ChamberBuilder.build(chamber, slot)
                slot.built = true
                get(server).setDirty()
            }
            return true
        }

        /** Re-stamps the room from a fresh draw: player blocks and everything else in it go, the pedestal is recharged, the fight is dormant again. */
        fun reset(server: MinecraftServer, slot: ChamberSlot) {
            val chamber = ChamberDimension.level(server) ?: return
            ChamberFight.onReset(slot)
            for (e in chamber.getEntities(null as Entity?, ChamberFight.slotBox(slot)) { it !is Player }) e.discard()
            slot.resetCount++
            slot.crystalsBroken = 0
            ChamberBuilder.build(chamber, slot)
            slot.built = true
            slot.state = SlotState.DORMANT
            slot.claimedAt = 0
            slot.stage3StartedAt = 0
            slot.occupied = false
            slot.gateDim = null
            slot.gatePos = null
            get(server).setDirty()
        }

        /** Closes the gate this slot's visit came through, if it is still open; true when it was. */
        fun closeGate(server: MinecraftServer, slot: ChamberSlot): Boolean {
            val pos = slot.gatePos ?: return false
            val level = slot.gateDim?.let { server.getLevel(it) } ?: return false
            val gate = level.getBlockEntity(pos) as? GateBlockEntity ?: return false
            if (gate.returnGate || !gate.isOpen) return false
            gate.close()
            for (p in level.getEntitiesOfClass(ServerPlayer::class.java, AABB(pos).inflate(12.0))) say(p, "the gate closes — it takes another lens to open")
            return true
        }

        /** The run is over — the room emptied, its last player having left or died: the gate closes, the room is stamped afresh. */
        fun endRun(server: MinecraftServer, slot: ChamberSlot) {
            closeGate(server, slot)
            reset(server, slot)
        }

        /** Where [player] goes when they leave: the spot outside the gate they came through, or the world spawn if that is lost. */
        fun returnPoint(server: MinecraftServer, player: ServerPlayer): Pair<ServerLevel, Vec3> {
            val tag = player.getData(ModAttachments.CHAMBER_RETURN.get())
            val key = ResourceLocation.tryParse(tag.getString("dim"))?.let { ResourceKey.create(Registries.DIMENSION, it) }
            val target = key?.let { server.getLevel(it) } ?: server.overworld()
            if (tag.contains("x")) return target to Vec3(tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z"))
            val spawn = target.sharedSpawnPos
            return target to Vec3(spawn.x + 0.5, spawn.y.toDouble(), spawn.z + 0.5)
        }

        /**
         * Puts [stacks] in a chest beside [at]: an existing chest with room within three blocks, else a new one
         * (named [name]) on the nearest clear floor cell — never [at] itself, never inside [avoid] — and, failing
         * a floor, any clear cell. What fits nowhere drops at [at]. Answers the chest, or null when all was dropped.
         */
        fun stash(level: ServerLevel, at: BlockPos, avoid: AABB?, name: Component?, stacks: List<ItemStack>): BlockPos? {
            val live = stacks.filter { !it.isEmpty }
            if (live.isEmpty()) return null
            val cells = ArrayList<BlockPos>()
            for (dy in intArrayOf(0, 1, -1)) for (dx in -3..3) for (dz in -3..3) {
                if (dx == 0 && dz == 0) continue
                val c = at.offset(dx, dy, dz)
                if (avoid != null && avoid.intersects(AABB(c))) continue
                cells += c
            }
            cells.sortBy { it.distSqr(at) }
            fun room(be: ChestBlockEntity): Boolean = (0 until be.containerSize).any { be.getItem(it).isEmpty }
            var chest = cells.firstNotNullOfOrNull { c -> (level.getBlockEntity(c) as? ChestBlockEntity)?.takeIf { room(it) } }
            if (chest == null) {
                val floored = cells.firstOrNull { c -> level.getBlockState(c).canBeReplaced() && level.getBlockState(c.below()).isFaceSturdy(level, c.below(), Direction.UP) }
                val cell = floored ?: cells.firstOrNull { level.getBlockState(it).canBeReplaced() }
                if (cell != null) {
                    val facing = Direction.getNearest((at.x - cell.x).toDouble(), 0.0, (at.z - cell.z).toDouble())
                    val state = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing).setValue(ChestBlock.WATERLOGGED, level.getFluidState(cell).`is`(Fluids.WATER))
                    level.setBlock(cell, state, 3)
                    chest = level.getBlockEntity(cell) as? ChestBlockEntity
                    if (chest != null && name != null) chest.applyComponents(net.minecraft.core.component.DataComponentMap.EMPTY, net.minecraft.core.component.DataComponentPatch.builder().set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, name).build())
                }
            }
            val drop = Vec3(at.x + 0.5, at.y + 0.5, at.z + 0.5)
            if (chest == null) {
                for (s in live) Containers.dropItemStack(level, drop.x, drop.y, drop.z, s)
                return null
            }
            for (s in live) {
                val slot = (0 until chest.containerSize).firstOrNull { chest.getItem(it).isEmpty }
                if (slot == null) Containers.dropItemStack(level, drop.x, drop.y, drop.z, s) else chest.setItem(slot, s)
            }
            chest.setChanged()
            return chest.blockPos
        }

        /** Sends [player] into their room through [from] (or from wherever they stand, for the command). */
        fun enter(player: ServerPlayer, from: GateBlockEntity?) {
            val server = player.server
            val chamber = ChamberDimension.level(server)
            if (chamber == null) {
                say(player, "the Decoherence Chamber is not part of this world")
                return
            }
            val data = get(server)
            val slot = data.slotFor(player.uuid)
            ensureBuilt(server, slot)
            slot.occupied = true
            if (from != null && !from.returnGate) {
                slot.gateDim = from.level?.dimension()
                slot.gatePos = from.blockPos
            }
            val back = from?.outsideOf(player) ?: player.position()
            player.setData(
                ModAttachments.CHAMBER_RETURN.get(),
                CompoundTag().also { t ->
                    t.putString("dim", player.level().dimension().location().toString())
                    t.putDouble("x", back.x)
                    t.putDouble("y", back.y)
                    t.putDouble("z", back.z)
                    t.putFloat("yaw", player.yRot)
                },
            )
            val at = slot.arrival()
            player.teleportTo(chamber, at.x, at.y, at.z, 0f, 0f)
            player.setPortalCooldown(ARRIVAL_COOLDOWN)
            slot.lastVisit = server.overworld().gameTime
            data.setDirty()
            say(player, "the Decoherence Chamber")
        }

        /** Sends [player] back to where they came in — or to the world spawn if that is lost. */
        fun leave(player: ServerPlayer) {
            val (target, at) = returnPoint(player.server, player)
            val yaw = player.getData(ModAttachments.CHAMBER_RETURN.get()).getFloat("yaw")
            player.teleportTo(target, at.x, at.y, at.z, yaw, 0f)
            player.setPortalCooldown(ARRIVAL_COOLDOWN)
        }
    }
}
