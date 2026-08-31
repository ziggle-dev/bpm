package bpm.world

import bpm.Bpm
import net.minecraft.network.chat.Component
import net.minecraft.util.valueproviders.UniformInt
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.DropExperienceBlock
import net.minecraft.world.level.block.RotatedPillarBlock
import net.minecraft.world.level.block.SlabBlock
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.material.MapColor
import net.neoforged.neoforge.registries.DeferredBlock
import net.neoforged.neoforge.registries.DeferredItem
import net.neoforged.neoforge.registries.DeferredRegister

/**
 * The material and structure content the mechanics design (`docs/DESIGN_MECHANICS.md`) and its data pack
 * name: ores, alloys, cores and the chamber's building blocks. Registered with vanilla behaviour so the data
 * pack's worldgen, recipes, loot tables and tags load; the devices with a GeckoLib model (gate, pedestal,
 * vent, turret, phase block, spike) and every mechanic are the mechanics phases' work and are not here.
 */
object ContentBlocks {
    val REG: DeferredRegister.Blocks = DeferredRegister.createBlocks(Bpm.ID)

    private fun stone(colour: MapColor, hardness: Float = 3f, resistance: Float = 6f) =
        BlockBehaviour.Properties.of().mapColor(colour).strength(hardness, resistance).requiresCorrectToolForDrops()

    val ENTANGLIUM_ORE: DeferredBlock<Block> = REG.registerBlock("entanglium_ore", { p -> DropExperienceBlock(UniformInt.of(2, 5), p) }, stone(MapColor.STONE))
    val DEEPSLATE_ENTANGLIUM_ORE: DeferredBlock<Block> = REG.registerBlock("deepslate_entanglium_ore", { p -> DropExperienceBlock(UniformInt.of(2, 5), p) }, stone(MapColor.DEEPSLATE, 4.5f, 6f).sound(SoundType.DEEPSLATE))
    val ENTANGLIUM_BLOCK: DeferredBlock<Block> = REG.registerSimpleBlock("entanglium_block", stone(MapColor.COLOR_CYAN, 5f, 6f).sound(SoundType.AMETHYST).lightLevel { 7 })
    val QUANTUM_ALLOY_BLOCK: DeferredBlock<Block> = REG.registerSimpleBlock("quantum_alloy_block", stone(MapColor.COLOR_BLUE, 5f, 6f).sound(SoundType.METAL))

    val CHAMBER_FLOOR: DeferredBlock<Block> = REG.registerSimpleBlock("chamber_floor", stone(MapColor.COLOR_BLACK, 4f, 1200f).sound(SoundType.DEEPSLATE_TILES))
    val CHAMBER_FLOOR_CIRCUIT: DeferredBlock<Block> = REG.registerSimpleBlock("chamber_floor_circuit", stone(MapColor.COLOR_BLACK, 4f, 1200f).sound(SoundType.DEEPSLATE_TILES).lightLevel { 4 })
    val CHAMBER_WALL: DeferredBlock<Block> = REG.registerSimpleBlock("chamber_wall", stone(MapColor.COLOR_BLACK, 4f, 1200f).sound(SoundType.DEEPSLATE_BRICKS))
    val CHAMBER_PILLAR: DeferredBlock<RotatedPillarBlock> = REG.registerBlock("chamber_pillar", ::RotatedPillarBlock, stone(MapColor.COLOR_BLACK, 4f, 1200f).sound(SoundType.DEEPSLATE_BRICKS))
    val CHAMBER_LIGHT: DeferredBlock<Block> = REG.registerSimpleBlock("chamber_light", stone(MapColor.COLOR_LIGHT_BLUE, 2f, 1200f).sound(SoundType.GLASS).lightLevel { 15 })
    val CONTAINMENT_BARRIER: DeferredBlock<Block> = REG.registerSimpleBlock("containment_barrier", stone(MapColor.COLOR_CYAN, 3f, 1200f).sound(SoundType.GLASS).noOcclusion().lightLevel { 7 })
    // Oriented pieces (GateFrameBlocks.kt): the blockstates carry axis + along / corner for the slab-shaped models.
    val GATE_FRAME: DeferredBlock<GateFrameBlock> = REG.registerBlock("gate_frame", ::GateFrameBlock, stone(MapColor.COLOR_BLUE, 5f, 1200f).sound(SoundType.METAL).noOcclusion())
    val GATE_FRAME_CORNER: DeferredBlock<GateFrameCornerBlock> = REG.registerBlock("gate_frame_corner", ::GateFrameCornerBlock, stone(MapColor.COLOR_BLUE, 5f, 1200f).sound(SoundType.METAL).noOcclusion())

    // The content pack's decorative variants (stonecutter recipes, loot tables and tags name them).
    val QUANTUM_ALLOY_SLAB: DeferredBlock<SlabBlock> = REG.registerBlock("quantum_alloy_slab", ::SlabBlock, stone(MapColor.COLOR_BLUE, 5f, 6f).sound(SoundType.METAL))
    val QUANTUM_ALLOY_STAIRS: DeferredBlock<StairBlock> = REG.registerBlock("quantum_alloy_stairs", { p -> StairBlock(QUANTUM_ALLOY_BLOCK.get().defaultBlockState(), p) }, stone(MapColor.COLOR_BLUE, 5f, 6f).sound(SoundType.METAL))
    val CHAMBER_WALL_CONDUIT: DeferredBlock<Block> = REG.registerSimpleBlock("chamber_wall_conduit", stone(MapColor.COLOR_BLACK, 4f, 1200f).sound(SoundType.DEEPSLATE_BRICKS).lightLevel { 5 })
    val CHAMBER_WALL_PANEL: DeferredBlock<Block> = REG.registerSimpleBlock("chamber_wall_panel", stone(MapColor.COLOR_BLACK, 4f, 1200f).sound(SoundType.DEEPSLATE_BRICKS))
    val CHAMBER_WALL_TRIM: DeferredBlock<Block> = REG.registerSimpleBlock("chamber_wall_trim", stone(MapColor.COLOR_BLACK, 4f, 1200f).sound(SoundType.DEEPSLATE_BRICKS).lightLevel { 3 })
    val CHAMBER_WALL_VENT: DeferredBlock<Block> = REG.registerSimpleBlock("chamber_wall_vent", stone(MapColor.COLOR_BLACK, 4f, 1200f).sound(SoundType.DEEPSLATE_BRICKS))

    /** A remote redstone output a controller drives through a link (`redstone.emitAt`). Glows a little while it emits. */
    val SIGNAL_EMITTER: DeferredBlock<SignalEmitterBlock> = REG.registerBlock(
        "signal_emitter", ::SignalEmitterBlock,
        stone(MapColor.COLOR_RED, 2f, 6f).sound(SoundType.METAL).lightLevel { s -> if (s.getValue(SignalEmitterBlock.POWER) > 0) 7 else 0 },
    )

    val all: List<DeferredBlock<out Block>>
        get() = listOf(
            ENTANGLIUM_ORE, DEEPSLATE_ENTANGLIUM_ORE, ENTANGLIUM_BLOCK, QUANTUM_ALLOY_BLOCK,
            CHAMBER_FLOOR, CHAMBER_FLOOR_CIRCUIT, CHAMBER_WALL, CHAMBER_PILLAR, CHAMBER_LIGHT, CONTAINMENT_BARRIER, GATE_FRAME, GATE_FRAME_CORNER,
            QUANTUM_ALLOY_SLAB, QUANTUM_ALLOY_STAIRS, CHAMBER_WALL_CONDUIT, CHAMBER_WALL_PANEL, CHAMBER_WALL_TRIM, CHAMBER_WALL_VENT,
            SIGNAL_EMITTER,
        )
}

/** An item whose lang file carries a `<key>.tooltip` line. */
open class TooltipItem(properties: Properties) : Item(properties) {
    override fun appendHoverText(stack: ItemStack, context: TooltipContext, tooltip: MutableList<Component>, flag: TooltipFlag) {
        tooltip.add(Component.translatable("${descriptionId}.tooltip"))
    }
}

object ContentItems {
    val REG: DeferredRegister.Items = DeferredRegister.createItems(Bpm.ID)

    private fun plain(name: String): DeferredItem<Item> = REG.registerItem(name, ::Item, Item.Properties())
    private fun tip(name: String, props: Item.Properties = Item.Properties()): DeferredItem<TooltipItem> = REG.registerItem(name, ::TooltipItem, props)

    val ENTANGLIUM_SHARD = plain("entanglium_shard")
    val QUANTUM_ALLOY_BLEND = plain("quantum_alloy_blend")
    val QUANTUM_ALLOY_INGOT = plain("quantum_alloy_ingot")
    val COHERENCE_LENS = plain("coherence_lens")
    val ENTANGLED_CIRCUIT = plain("entangled_circuit")
    val PHASE_CONDUIT = plain("phase_conduit")
    val QUANTUM_CORE = tip("quantum_core", Item.Properties().stacksTo(16))
    val REFINED_QUANTUM_CORE = tip("refined_quantum_core", Item.Properties().stacksTo(16))
    val PRISTINE_QUANTUM_CORE = tip("pristine_quantum_core", Item.Properties().stacksTo(16))
    val ENTANGLED_QUANTUM_CORE = tip("entangled_quantum_core", Item.Properties().stacksTo(16))
    val COHERENT_QUANTUM_CORE = tip("coherent_quantum_core", Item.Properties().stacksTo(16))
    val WARDEN_PLATING = plain("warden_plating")
    val WARDEN_VISOR: DeferredItem<bpm.world.items.WardenVisorItem> = REG.registerItem("warden_visor", { p -> bpm.world.items.WardenVisorItem(p) }, Item.Properties().stacksTo(1).durability(0))
    val PHASE_GAUNTLET: DeferredItem<bpm.world.items.PhaseGauntletItem> = REG.registerItem("phase_gauntlet", { p -> bpm.world.items.PhaseGauntletItem(p) }, Item.Properties().stacksTo(1))
    val ENTANGLED_COMPASS: DeferredItem<bpm.world.items.EntangledCompassItem> = REG.registerItem("entangled_compass", { p -> bpm.world.items.EntangledCompassItem(p) }, Item.Properties().stacksTo(1))
    val WARDEN_PROGRAM: DeferredItem<bpm.world.items.WardenProgramItem> = REG.registerItem("warden_program", { p -> bpm.world.items.WardenProgramItem(p) }, Item.Properties().stacksTo(1))
    val QUANTUM_TETHER: DeferredItem<bpm.world.items.QuantumTetherItem> = REG.registerItem("quantum_tether", { p -> bpm.world.items.QuantumTetherItem(p) }, Item.Properties().stacksTo(1))

    val blockItems: List<DeferredItem<out Item>> = ContentBlocks.all.map { block -> REG.registerSimpleBlockItem(block) }

    val all: List<DeferredItem<out Item>>
        get() = listOf(
            ENTANGLIUM_SHARD, QUANTUM_ALLOY_BLEND, QUANTUM_ALLOY_INGOT, COHERENCE_LENS, ENTANGLED_CIRCUIT, PHASE_CONDUIT,
            QUANTUM_CORE, REFINED_QUANTUM_CORE, PRISTINE_QUANTUM_CORE, ENTANGLED_QUANTUM_CORE, COHERENT_QUANTUM_CORE, WARDEN_PLATING, WARDEN_VISOR, PHASE_GAUNTLET, ENTANGLED_COMPASS, WARDEN_PROGRAM,
            QUANTUM_TETHER,
        ) + blockItems
}
