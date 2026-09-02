package bpm.world.devices

import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import software.bernie.geckolib.animatable.GeoBlockEntity
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache
import software.bernie.geckolib.util.GeckoLibUtil

/**
 * What every device block entity shares: a GeckoLib instance cache, a per-block Molang phase, and a small
 * "what the client needs" sync — subclasses write it in [saveSynced] / read it in [loadSynced] and the
 * same fields go into the world save.
 */
abstract class DeviceBlockEntity(type: BlockEntityType<*>, pos: BlockPos, state: BlockState) : bpm.platform.SavingBlockEntity(type, pos, state), GeoBlockEntity {
    private val animCache: AnimatableInstanceCache = GeckoLibUtil.createInstanceCache(this)

    /** A per-block Molang phase so neighbouring devices never animate in step. */
    val animPhase: Double = ((pos.hashCode() and 0xffff) * 360.0 / 0x10000)

    override fun getAnimatableInstanceCache(): AnimatableInstanceCache = animCache

    /** Server tick; devices override. */
    open fun serverTick() {}

    /** Fields both sides need. */
    protected open fun saveSynced(tag: CompoundTag, registries: HolderLookup.Provider) {}
    protected open fun loadSynced(tag: CompoundTag, registries: HolderLookup.Provider) {}

    override fun saveExtra(tag: CompoundTag, registries: HolderLookup.Provider) = saveSynced(tag, registries)

    override fun loadExtra(tag: CompoundTag, registries: HolderLookup.Provider) = loadSynced(tag, registries)

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag = syncTag { saveSynced(it, registries) }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> = ClientboundBlockEntityDataPacket.create(this)

    /** Pushes the synced fields to the clients watching the block and marks the chunk dirty. */
    fun sync() {
        setChanged()
        val l = level ?: return
        if (!l.isClientSide) l.sendBlockUpdated(worldPosition, blockState, blockState, 3)
    }

    companion object {
        /** The server-side ticker for an [EntityBlock] whose entity is a [DeviceBlockEntity]. */
        fun <T : BlockEntity> ticker(level: Level, type: BlockEntityType<T>, expected: BlockEntityType<out DeviceBlockEntity>): BlockEntityTicker<T>? {
            if (level.isClientSide || type !== expected) return null
            return BlockEntityTicker { _, _, _, be -> (be as? DeviceBlockEntity)?.serverTick() }
        }
    }
}
