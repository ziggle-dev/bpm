package bpm.world

import bpm.platform.registry.BlockRegistrar
import bpm.platform.registry.ComponentRegistrar
import bpm.platform.registry.ItemRegistrar
import bpm.platform.registry.Registrar
import bpm.platform.registry.RegistryRef
import bpm.platform.registry.Registrars
import bpm.Bpm
import bpm.world.devices.AssemblerBlock
import bpm.world.devices.AssemblerBlockEntity
import bpm.world.devices.GateBlock
import bpm.world.devices.GateBlockEntity
import bpm.world.devices.MonitorBlock
import bpm.world.devices.MonitorBlockEntity
import bpm.world.devices.PedestalBlock
import bpm.world.devices.PedestalBlockEntity
import bpm.world.devices.PhaseBlock
import bpm.world.devices.PhaseBlockEntity
import bpm.world.devices.SpikeBlockEntity
import bpm.world.devices.TrapBlock
import bpm.world.devices.TurretBlock
import bpm.world.devices.TurretBlockEntity
import bpm.world.devices.VentBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.MapColor
import bpm.platform.GeoItem
import bpm.platform.AnimatableInstanceCache
import bpm.platform.AnimatableManager
import bpm.platform.AnimationController
import bpm.platform.RawAnimation
import bpm.platform.GeckoLibUtil
import java.util.function.Consumer

/**
 * The six GeckoLib devices of the mechanics design: the gate, the pedestal, the two floor traps, the turret
 * and the superposition block. Blocks, block entities and block items in one place; the behaviour lives in
 * `bpm.world.devices`, the renderers in `bpm.client.render.DeviceRenderers`.
 */
object DeviceBlocks {
    val REG: BlockRegistrar = Registrars.blocks(Bpm.ID)

    private fun armour(colour: MapColor = MapColor.COLOR_BLUE, hardness: Float = 4f) =
        BlockBehaviour.Properties.of().mapColor(colour).strength(hardness, 1200f).sound(SoundType.METAL).noOcclusion().requiresCorrectToolForDrops()

    val QUANTUM_GATE: RegistryRef<GateBlock> = REG.registerBlock("quantum_gate", ::GateBlock, armour().lightLevel { s -> if (s.getValue(GateBlock.OPEN)) 12 else 3 })
    val CORE_PEDESTAL: RegistryRef<PedestalBlock> = REG.registerBlock("core_pedestal", ::PedestalBlock, armour().lightLevel { s -> if (s.getValue(PedestalBlock.HAS_CORE)) 9 else 3 })
    val PHASE_SPIKE: RegistryRef<TrapBlock> = REG.registerBlock("phase_spike", { p -> TrapBlock(p, ::SpikeBlockEntity, TrapBlock.PLATE) }, armour(MapColor.COLOR_BLACK))
    val DECOHERENCE_VENT: RegistryRef<TrapBlock> = REG.registerBlock("decoherence_vent", { p -> TrapBlock(p, ::VentBlockEntity, TrapBlock.GRATE) }, armour(MapColor.COLOR_BLACK).lightLevel { 5 })
    val OBSERVER_TURRET: RegistryRef<TurretBlock> = REG.registerBlock("observer_turret", ::TurretBlock, armour().lightLevel { 4 })
    val PHASE_BLOCK: RegistryRef<PhaseBlock> = REG.registerBlock("phase_block", ::PhaseBlock, armour().lightLevel { 6 })
    /** A tiling screen panel (`Monitor.kt`): joins same-facing neighbours into one display. */
    val QUANTUM_MONITOR: RegistryRef<MonitorBlock> = REG.registerBlock("quantum_monitor", ::MonitorBlock, armour(MapColor.COLOR_BLACK, 2f).lightLevel { s -> if (s.getValue(MonitorBlock.ON)) 6 else 0 })

    /** The fabricator (`Assembler.kt`): pedestals feed it, and a job has to be kept supplied to survive. */
    val QUANTUM_ASSEMBLER: RegistryRef<AssemblerBlock> = REG.registerBlock("quantum_assembler", ::AssemblerBlock, armour(MapColor.COLOR_BLUE, 5f).lightLevel { s -> if (s.getValue(AssemblerBlock.RUNNING)) 11 else 4 })

    val all: List<RegistryRef<out Block>> get() = listOf(QUANTUM_ASSEMBLER, QUANTUM_GATE, CORE_PEDESTAL, PHASE_SPIKE, DECOHERENCE_VENT, OBSERVER_TURRET, PHASE_BLOCK, QUANTUM_MONITOR)
}

object DeviceBlockEntities {
    val REG: Registrar<BlockEntityType<*>> = Registrars.of(Registries.BLOCK_ENTITY_TYPE, Bpm.ID)

    private fun <T : BlockEntity> type(name: String, block: RegistryRef<out Block>, factory: (BlockPos, BlockState) -> T): RegistryRef<BlockEntityType<T>> =
        REG.register(name) { -> bpm.platform.blockEntityType(factory, block.get()) }

    val GATE = type("quantum_gate", DeviceBlocks.QUANTUM_GATE, ::GateBlockEntity)
    val PEDESTAL = type("core_pedestal", DeviceBlocks.CORE_PEDESTAL, ::PedestalBlockEntity)
    val SPIKE = type("phase_spike", DeviceBlocks.PHASE_SPIKE, ::SpikeBlockEntity)
    val VENT = type("decoherence_vent", DeviceBlocks.DECOHERENCE_VENT, ::VentBlockEntity)
    val TURRET = type("observer_turret", DeviceBlocks.OBSERVER_TURRET, ::TurretBlockEntity)
    val PHASE = type("phase_block", DeviceBlocks.PHASE_BLOCK, ::PhaseBlockEntity)
    val MONITOR = type("quantum_monitor", DeviceBlocks.QUANTUM_MONITOR, ::MonitorBlockEntity)
    val ASSEMBLER = type("quantum_assembler", DeviceBlocks.QUANTUM_ASSEMBLER, ::AssemblerBlockEntity)
}

/** A device in item form: the same GeckoLib model, resting, in hand and in the inventory. */
class DeviceBlockItem(block: Block, properties: Properties, val model: String, private val restAnimation: String) : BlockItem(block, properties), GeoItem {
    private val animCache: AnimatableInstanceCache = GeckoLibUtil.createInstanceCache(this)
    private val rest: RawAnimation = RawAnimation.begin().thenLoop(restAnimation)

    override fun registerControllers(controllers: bpm.platform.ControllerRegistrar) {
        controllers.add(bpm.platform.animController(this, "rest", 0) { state -> state.setAndContinue(rest) })
    }

    override fun getAnimatableInstanceCache(): AnimatableInstanceCache = animCache

    /** Drawn by the table in [bpm.client.render.BpmItemRenderers]; GeckoLib asks the item, so the item asks it. */
    override fun createGeoRenderer(consumer: Consumer<bpm.platform.GeoRenderProvider>) =
        bpm.client.render.geoItemRenderer(this, consumer)

}

object DeviceItems {
    val REG: ItemRegistrar = Registrars.items(Bpm.ID)

    private fun device(name: String, block: RegistryRef<out Block>, rest: String): RegistryRef<DeviceBlockItem> =
        REG.registerItem(name, { p -> DeviceBlockItem(block.get(), p, name, rest) }, Item.Properties())

    val QUANTUM_GATE = device("quantum_gate", DeviceBlocks.QUANTUM_GATE, "animation.quantum_gate.idle")
    val CORE_PEDESTAL = device("core_pedestal", DeviceBlocks.CORE_PEDESTAL, "animation.core_pedestal.idle")
    val PHASE_SPIKE = device("phase_spike", DeviceBlocks.PHASE_SPIKE, "animation.phase_spike.idle")
    val DECOHERENCE_VENT = device("decoherence_vent", DeviceBlocks.DECOHERENCE_VENT, "animation.decoherence_vent.idle")
    val OBSERVER_TURRET = device("observer_turret", DeviceBlocks.OBSERVER_TURRET, "animation.observer_turret.idle")
    val PHASE_BLOCK = device("phase_block", DeviceBlocks.PHASE_BLOCK, "animation.phase_block.solid")
    val QUANTUM_MONITOR = device("quantum_monitor", DeviceBlocks.QUANTUM_MONITOR, "animation.quantum_monitor.idle")
    val QUANTUM_ASSEMBLER = device("quantum_assembler", DeviceBlocks.QUANTUM_ASSEMBLER, "animation.quantum_assembler.idle")

    val all: List<RegistryRef<out Item>> get() = listOf(QUANTUM_ASSEMBLER, QUANTUM_GATE, CORE_PEDESTAL, PHASE_SPIKE, DECOHERENCE_VENT, OBSERVER_TURRET, PHASE_BLOCK, QUANTUM_MONITOR)
}

