package bpm.world

import bpm.Bpm
import net.minecraft.core.GlobalPos
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.BucketItem
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.material.MapColor
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.capabilities.Capabilities
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent
import net.neoforged.neoforge.registries.DeferredBlock
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredItem
import net.neoforged.neoforge.registries.DeferredRegister
import java.util.function.Consumer

object ModBlocks {
    val REG: DeferredRegister.Blocks = DeferredRegister.createBlocks(Bpm.ID)

    val CONTROLLER: DeferredBlock<ControllerBlock> = REG.registerBlock(
        "quantum_controller",
        ::ControllerBlock,
        BlockBehaviour.Properties.of()
            .mapColor(MapColor.COLOR_BLUE)
            .strength(3.0f, 6.0f)
            .sound(SoundType.METAL)
            .noOcclusion()
            .lightLevel { state -> if (state.getValue(ControllerBlock.STATUS) == ControllerStatus.RUNNING) 9 else 3 },
    )

    /** Liquid experience in the world — see [ModFluids]. */
    val EXPERIENCE: DeferredBlock<LiquidBlock> = REG.register("experience") { ->
        LiquidBlock(ModFluids.EXPERIENCE.get(), BlockBehaviour.Properties.ofFullCopy(Blocks.WATER).lightLevel { 10 }.noLootTable())
    }
}

object ModItems {
    val REG: DeferredRegister.Items = DeferredRegister.createItems(Bpm.ID)

    val CONTROLLER: DeferredItem<BlockItem> = REG.registerItem("quantum_controller", { p -> ControllerBlockItem(ModBlocks.CONTROLLER.get(), p) }, Item.Properties())
    val LINKER: DeferredItem<LinkerItem> = REG.registerItem("quantum_linker", ::LinkerItem, Item.Properties().stacksTo(1))
    val EXPERIENCE_BUCKET: DeferredItem<BucketItem> = REG.registerItem(
        "experience_bucket",
        { p -> BucketItem(ModFluids.EXPERIENCE.get(), p) },
        Item.Properties().craftRemainder(Items.BUCKET).stacksTo(1),
    )
}

object ModBlockEntities {
    val REG: DeferredRegister<BlockEntityType<*>> = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Bpm.ID)

    val CONTROLLER: DeferredHolder<BlockEntityType<*>, BlockEntityType<ControllerBlockEntity>> = REG.register("quantum_controller") { ->
        @Suppress("DataFlowIssue")
        BlockEntityType.Builder.of(::ControllerBlockEntity, ModBlocks.CONTROLLER.get()).build(null)
    }
}

object ModComponents {
    val REG: DeferredRegister.DataComponents = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, Bpm.ID)

    /** The controller a linker is working for. */
    val SELECTED_CONTROLLER = REG.registerComponentType("selected_controller") { b -> b.persistent(GlobalPos.CODEC).networkSynchronized(GlobalPos.STREAM_CODEC) }

    /** The core tier a controller was built around — see [CoreTier]. */
    val CORE_TIER = REG.registerComponentType("core_tier") { b -> b.persistent(com.mojang.serialization.Codec.STRING).networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8) }

    /** Blinks since the Phase Gauntlet last ate a shard. */
    val BLINKS = REG.registerComponentType("blinks") { b -> b.persistent(com.mojang.serialization.Codec.INT).networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.VAR_INT) }

    /** True while the linker's holder is inside a chamber: the glint, and the pulse. */
    val CHARGED = REG.registerComponentType("charged") { b -> b.persistent(com.mojang.serialization.Codec.BOOL).networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.BOOL) }

    /** Pulses left in the linker before the pedestal must recharge it. */
    val CHARGES = REG.registerComponentType("charges") { b -> b.persistent(com.mojang.serialization.Codec.INT).networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.VAR_INT) }

    /** Game time at which the linker's tracking pulse is ready again. */
    val TRACK_READY_AT = REG.registerComponentType("track_ready_at") { b -> b.persistent(com.mojang.serialization.Codec.LONG).networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.VAR_LONG) }

    /** The controller a tether's bearer has let reach them — see `docs/DESIGN_PLAYER_LINK.md`. */
    val TETHER_CONTROLLER = REG.registerComponentType("tether_controller") { b -> b.persistent(GlobalPos.CODEC).networkSynchronized(GlobalPos.STREAM_CODEC) }

    /** What that controller may do to them: [bpm.world.Grants], comma-joined so it reads in F3+H. */
    val TETHER_GRANTS = REG.registerComponentType("tether_grants") { b -> b.persistent(com.mojang.serialization.Codec.STRING).networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8) }

    /** What the Entangled Compass seeks. */
    val COMPASS_MODE = REG.registerComponentType("compass_mode") { b -> b.persistent(com.mojang.serialization.Codec.STRING).networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8) }
}

object ModCreativeTab {
    val REG: DeferredRegister<CreativeModeTab> = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Bpm.ID)

    val TAB: DeferredHolder<CreativeModeTab, CreativeModeTab> = REG.register("bpm") { ->
        CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.bpm"))
            .icon { ItemStack(ModItems.CONTROLLER.get()) }
            .displayItems { _, out ->
                out.accept(ModItems.CONTROLLER.get())
                out.accept(ModItems.LINKER.get())
                out.accept(ModItems.EXPERIENCE_BUCKET.get())
                for (item in ContentItems.all) out.accept(item.get())
                for (item in DeviceItems.all) out.accept(item.get())
            }
            .build()
    }
}

/** Registers everything above on the mod bus, plus the controller's item capability. */
object BpmRegistries {
    fun install(bus: IEventBus) {
        ModBlocks.REG.register(bus)
        ModItems.REG.register(bus)
        ModFluids.TYPES.register(bus)
        ModFluids.REG.register(bus)
        DeviceBlocks.REG.register(bus)
        DeviceItems.REG.register(bus)
        DeviceBlockEntities.REG.register(bus)
        bpm.world.assembly.ModRecipes.TYPES.register(bus)
        bpm.world.assembly.ModRecipes.SERIALIZERS.register(bus)
        ModAttachments.REG.register(bus)
        bpm.world.entity.ModEntities.REG.register(bus)
        bus.addListener(net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent::class.java, Consumer(bpm.world.entity.ModEntities::attributes))
        ContentBlocks.REG.register(bus)
        ContentItems.REG.register(bus)
        ModBlockEntities.REG.register(bus)
        ModComponents.REG.register(bus)
        ModCreativeTab.REG.register(bus)
        bus.addListener(RegisterCapabilitiesEvent::class.java, Consumer { event ->
            event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModBlockEntities.CONTROLLER.get()) { be, _ -> be.inventory }
            event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, ModBlockEntities.CONTROLLER.get()) { be, _ -> be.tanks }
            event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, ModBlockEntities.CONTROLLER.get()) { be, _ -> be.energy }
            event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, DeviceBlockEntities.ASSEMBLER.get()) { be, _ -> be.items }
            event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, DeviceBlockEntities.ASSEMBLER.get()) { be, _ -> be.tanks }
            event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, DeviceBlockEntities.ASSEMBLER.get()) { be, _ -> be.energy }
            event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, DeviceBlockEntities.PEDESTAL.get()) { be, _ -> bpm.world.devices.PedestalSlot(be) }
        })
    }
}
