package bpm.world

import bpm.Bpm
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
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions
import net.neoforged.neoforge.registries.DeferredBlock
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredItem
import net.neoforged.neoforge.registries.DeferredRegister
import software.bernie.geckolib.animatable.GeoItem
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache
import software.bernie.geckolib.animation.AnimatableManager
import software.bernie.geckolib.animation.AnimationController
import software.bernie.geckolib.animation.RawAnimation
import software.bernie.geckolib.util.GeckoLibUtil
import java.util.function.Consumer

/**
 * The six GeckoLib devices of the mechanics design: the gate, the pedestal, the two floor traps, the turret
 * and the superposition block. Blocks, block entities and block items in one place; the behaviour lives in
 * `bpm.world.devices`, the renderers in `bpm.client.render.DeviceRenderers`.
 */
object DeviceBlocks {
    val REG: DeferredRegister.Blocks = DeferredRegister.createBlocks(Bpm.ID)

    private fun armour(colour: MapColor = MapColor.COLOR_BLUE, hardness: Float = 4f) =
        BlockBehaviour.Properties.of().mapColor(colour).strength(hardness, 1200f).sound(SoundType.METAL).noOcclusion().requiresCorrectToolForDrops()

    val QUANTUM_GATE: DeferredBlock<GateBlock> = REG.registerBlock("quantum_gate", ::GateBlock, armour().lightLevel { s -> if (s.getValue(GateBlock.OPEN)) 12 else 3 })
    val CORE_PEDESTAL: DeferredBlock<PedestalBlock> = REG.registerBlock("core_pedestal", ::PedestalBlock, armour().lightLevel { s -> if (s.getValue(PedestalBlock.HAS_CORE)) 9 else 3 })
    val PHASE_SPIKE: DeferredBlock<TrapBlock> = REG.registerBlock("phase_spike", { p -> TrapBlock(p, ::SpikeBlockEntity, TrapBlock.PLATE) }, armour(MapColor.COLOR_BLACK))
    val DECOHERENCE_VENT: DeferredBlock<TrapBlock> = REG.registerBlock("decoherence_vent", { p -> TrapBlock(p, ::VentBlockEntity, TrapBlock.GRATE) }, armour(MapColor.COLOR_BLACK).lightLevel { 5 })
    val OBSERVER_TURRET: DeferredBlock<TurretBlock> = REG.registerBlock("observer_turret", ::TurretBlock, armour().lightLevel { 4 })
    val PHASE_BLOCK: DeferredBlock<PhaseBlock> = REG.registerBlock("phase_block", ::PhaseBlock, armour().lightLevel { 6 })
    /** A tiling screen panel (`Monitor.kt`): joins same-facing neighbours into one display. */
    val QUANTUM_MONITOR: DeferredBlock<MonitorBlock> = REG.registerBlock("quantum_monitor", ::MonitorBlock, armour(MapColor.COLOR_BLACK, 2f).lightLevel { s -> if (s.getValue(MonitorBlock.ON)) 6 else 0 })

    val all: List<DeferredBlock<out Block>> get() = listOf(QUANTUM_GATE, CORE_PEDESTAL, PHASE_SPIKE, DECOHERENCE_VENT, OBSERVER_TURRET, PHASE_BLOCK, QUANTUM_MONITOR)
}

object DeviceBlockEntities {
    val REG: DeferredRegister<BlockEntityType<*>> = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Bpm.ID)

    @Suppress("DataFlowIssue")
    private fun <T : BlockEntity> type(name: String, block: DeferredBlock<out Block>, factory: (BlockPos, BlockState) -> T): DeferredHolder<BlockEntityType<*>, BlockEntityType<T>> =
        REG.register(name) { -> BlockEntityType.Builder.of(factory, block.get()).build(null) }

    val GATE = type("quantum_gate", DeviceBlocks.QUANTUM_GATE, ::GateBlockEntity)
    val PEDESTAL = type("core_pedestal", DeviceBlocks.CORE_PEDESTAL, ::PedestalBlockEntity)
    val SPIKE = type("phase_spike", DeviceBlocks.PHASE_SPIKE, ::SpikeBlockEntity)
    val VENT = type("decoherence_vent", DeviceBlocks.DECOHERENCE_VENT, ::VentBlockEntity)
    val TURRET = type("observer_turret", DeviceBlocks.OBSERVER_TURRET, ::TurretBlockEntity)
    val PHASE = type("phase_block", DeviceBlocks.PHASE_BLOCK, ::PhaseBlockEntity)
    val MONITOR = type("quantum_monitor", DeviceBlocks.QUANTUM_MONITOR, ::MonitorBlockEntity)
}

/** A device in item form: the same GeckoLib model, resting, in hand and in the inventory. */
class DeviceBlockItem(block: Block, properties: Properties, val model: String, private val restAnimation: String) : BlockItem(block, properties), GeoItem {
    private val animCache: AnimatableInstanceCache = GeckoLibUtil.createInstanceCache(this)
    private val rest: RawAnimation = RawAnimation.begin().thenLoop(restAnimation)

    override fun registerControllers(controllers: AnimatableManager.ControllerRegistrar) {
        controllers.add(AnimationController(this, "rest", 0) { state -> state.setAndContinue(rest) })
    }

    override fun getAnimatableInstanceCache(): AnimatableInstanceCache = animCache

    override fun initializeClient(consumer: Consumer<IClientItemExtensions>) {
        consumer.accept(bpm.client.render.DeviceItemExtensions.of(model))
    }
}

object DeviceItems {
    val REG: DeferredRegister.Items = DeferredRegister.createItems(Bpm.ID)

    private fun device(name: String, block: DeferredBlock<out Block>, rest: String): DeferredItem<DeviceBlockItem> =
        REG.registerItem(name, { p -> DeviceBlockItem(block.get(), p, name, rest) }, Item.Properties())

    val QUANTUM_GATE = device("quantum_gate", DeviceBlocks.QUANTUM_GATE, "animation.quantum_gate.idle")
    val CORE_PEDESTAL = device("core_pedestal", DeviceBlocks.CORE_PEDESTAL, "animation.core_pedestal.idle")
    val PHASE_SPIKE = device("phase_spike", DeviceBlocks.PHASE_SPIKE, "animation.phase_spike.idle")
    val DECOHERENCE_VENT = device("decoherence_vent", DeviceBlocks.DECOHERENCE_VENT, "animation.decoherence_vent.idle")
    val OBSERVER_TURRET = device("observer_turret", DeviceBlocks.OBSERVER_TURRET, "animation.observer_turret.idle")
    val PHASE_BLOCK = device("phase_block", DeviceBlocks.PHASE_BLOCK, "animation.phase_block.solid")
    val QUANTUM_MONITOR = device("quantum_monitor", DeviceBlocks.QUANTUM_MONITOR, "animation.quantum_monitor.idle")

    val all: List<DeferredItem<out Item>> get() = listOf(QUANTUM_GATE, CORE_PEDESTAL, PHASE_SPIKE, DECOHERENCE_VENT, OBSERVER_TURRET, PHASE_BLOCK, QUANTUM_MONITOR)
}

