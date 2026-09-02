package bpm.world

import bpm.platform.registry.BlockRegistrar
import bpm.platform.registry.ComponentRegistrar
import bpm.platform.registry.ItemRegistrar
import bpm.platform.registry.Registrar
import bpm.platform.registry.RegistryRef
import bpm.platform.registry.Registrars
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
import bpm.platform.ports.Ports
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.material.MapColor
import java.util.function.Consumer

object ModBlocks {
    val REG: BlockRegistrar = Registrars.blocks(Bpm.ID)

    val CONTROLLER: RegistryRef<ControllerBlock> = REG.registerBlock(
        "quantum_controller",
        ::ControllerBlock,
        BlockBehaviour.Properties.of()
            .mapColor(MapColor.COLOR_BLUE)
            .strength(3.0f, 6.0f)
            .sound(SoundType.METAL)
            .noOcclusion()
            .lightLevel { state -> if (state.getValue(ControllerBlock.STATUS) == ControllerStatus.RUNNING) 9 else 3 },
    )

    /**
     * Liquid experience in the world — see [ModFluids].
     *
     * Through `registerBlock` rather than the plain `register`, and it has to be from 1.21.2: a block's
     * `Properties` now carries its own registry key, and `BlockBehaviour`'s constructor reads it while
     * working out the loot table. Building the block from bare `Properties` leaves that unset and throws
     * `NullPointerException: Block id not set` during registration — not at compile time, which is why
     * this survived the port and only surfaced on the first launch. `registerBlock` is the form that
     * sets the key, because the loader owns the name and can stamp it before the block is constructed.
     *
     * `noLootTable` does not excuse it: `effectiveDrops` demands the id before it looks at the value.
     */
    val EXPERIENCE: RegistryRef<LiquidBlock> = REG.registerBlock(
        "experience",
        { p -> LiquidBlock(ModFluids.EXPERIENCE.get(), p) },
        BlockBehaviour.Properties.ofFullCopy(Blocks.WATER).lightLevel { 10 }.noLootTable(),
    )
}

object ModItems {
    val REG: ItemRegistrar = Registrars.items(Bpm.ID)

    val CONTROLLER: RegistryRef<BlockItem> = REG.registerItem("quantum_controller", { p -> ControllerBlockItem(ModBlocks.CONTROLLER.get(), p) }, Item.Properties())
    val LINKER: RegistryRef<LinkerItem> = REG.registerItem("quantum_linker", ::LinkerItem, Item.Properties().stacksTo(1))
    val EXPERIENCE_BUCKET: RegistryRef<BucketItem> = REG.registerItem(
        "experience_bucket",
        { p -> BucketItem(ModFluids.EXPERIENCE.get(), p) },
        Item.Properties().craftRemainder(Items.BUCKET).stacksTo(1),
    )
}

object ModBlockEntities {
    val REG: Registrar<BlockEntityType<*>> = Registrars.of(Registries.BLOCK_ENTITY_TYPE, Bpm.ID)

    val CONTROLLER: RegistryRef<BlockEntityType<ControllerBlockEntity>> = REG.register("quantum_controller") { ->
        bpm.platform.blockEntityType(::ControllerBlockEntity, ModBlocks.CONTROLLER.get())
    }
}

object ModComponents {
    val REG: ComponentRegistrar = Registrars.components(Bpm.ID)

    /** The controller a linker is working for. */
    val SELECTED_CONTROLLER = REG.registerComponentType("selected_controller") { b -> b.persistent(GlobalPos.CODEC).networkSynchronized(bpm.platform.registry.Wire.globalPos) }

    /** The core tier a controller was built around — see [CoreTier]. */
    val CORE_TIER = REG.registerComponentType("core_tier") { b -> b.persistent(com.mojang.serialization.Codec.STRING).networkSynchronized(bpm.platform.registry.Wire.string) }

    /** Blinks since the Phase Gauntlet last ate a shard. */
    val BLINKS = REG.registerComponentType("blinks") { b -> b.persistent(com.mojang.serialization.Codec.INT).networkSynchronized(bpm.platform.registry.Wire.varInt) }

    /** True while the linker's holder is inside a chamber: the glint, and the pulse. */
    val CHARGED = REG.registerComponentType("charged") { b -> b.persistent(com.mojang.serialization.Codec.BOOL).networkSynchronized(bpm.platform.registry.Wire.bool) }

    /** Pulses left in the linker before the pedestal must recharge it. */
    val CHARGES = REG.registerComponentType("charges") { b -> b.persistent(com.mojang.serialization.Codec.INT).networkSynchronized(bpm.platform.registry.Wire.varInt) }

    /** Game time at which the linker's tracking pulse is ready again. */
    val TRACK_READY_AT = REG.registerComponentType("track_ready_at") { b -> b.persistent(com.mojang.serialization.Codec.LONG).networkSynchronized(bpm.platform.registry.Wire.varLong) }

    /** The controller a tether's bearer has let reach them — see `docs/DESIGN_PLAYER_LINK.md`. */
    val TETHER_CONTROLLER = REG.registerComponentType("tether_controller") { b -> b.persistent(GlobalPos.CODEC).networkSynchronized(bpm.platform.registry.Wire.globalPos) }

    /** What that controller may do to them: [bpm.world.Grants], comma-joined so it reads in F3+H. */
    val TETHER_GRANTS = REG.registerComponentType("tether_grants") { b -> b.persistent(com.mojang.serialization.Codec.STRING).networkSynchronized(bpm.platform.registry.Wire.string) }

    /** What the Entangled Compass seeks. */
    val COMPASS_MODE = REG.registerComponentType("compass_mode") { b -> b.persistent(com.mojang.serialization.Codec.STRING).networkSynchronized(bpm.platform.registry.Wire.string) }
}

object ModCreativeTab {
    val REG: Registrar<CreativeModeTab> = Registrars.of(Registries.CREATIVE_MODE_TAB, Bpm.ID)

    val TAB: RegistryRef<CreativeModeTab> = REG.register("bpm") { ->
        // The vanilla two-argument form, not NeoForge's no-arg overload: this is shared code and Fabric
        // only has vanilla's. Row and column are what NeoForge's overload passes anyway.
        CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
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
    /**
     * Fluids go on the bus before blocks and items, because both name them at construction:
     * [ModBlocks.EXPERIENCE] builds a `LiquidBlock` from `ModFluids.EXPERIENCE.get()` and
     * [ModItems.EXPERIENCE_BUCKET] a `BucketItem` from the same holder.
     *
     * On NeoForge the order is not load-bearing — `DeferredRegister` holds the factory lambda until
     * its registry event fires, so the `.get()` runs long after everything is registered whatever
     * sequence we call `register` in. It is written this way for the reader, and for the day the
     * same registration list is replayed against a registry that resolves eagerly instead.
     */
    fun install() {
        // Touching each object is what creates its registrar and queues its entries; `installAll` then
        // realises them, in the order they were asked for.
        ModFluids.declare()
        ModBlocks.REG
        ModItems.REG
        DeviceBlocks.REG
        DeviceItems.REG
        DeviceBlockEntities.REG
        bpm.world.assembly.ModRecipes.TYPES
        bpm.world.assembly.ModRecipes.SERIALIZERS

        bpm.world.entity.ModEntities.REG
        bpm.world.entity.ModEntities.attributes()
        ContentBlocks.REG
        ContentItems.REG
        ModBlockEntities.REG
        ModComponents.REG
        ModCreativeTab.REG
        Registrars.installAll()
        LinkerItem.installHooks()
        Ports.providers { sink ->
            sink.items(ModBlockEntities.CONTROLLER.get()) { be, _ -> be.inventory }
            sink.fluids(ModBlockEntities.CONTROLLER.get()) { be, _ -> be.tanks }
            sink.energy(ModBlockEntities.CONTROLLER.get()) { be, _ -> be.energy }
            // The assembler takes its catalyst from anywhere, but is FED from underneath only.
            //
            // The machine is meant to be plumbed, not surrounded: power and experience come up into it from
            // below, which keeps its four sides clear for the pedestals that have to see it and stops a
            // build turning into a cube of pipework. Items are the exception because the catalyst is what a
            // player hands it, and reaching under a block to do that would be miserable.
            sink.items(DeviceBlockEntities.ASSEMBLER.get()) { be, _ -> be.items }
            sink.fluids(DeviceBlockEntities.ASSEMBLER.get()) { be, side ->
                if (side == net.minecraft.core.Direction.DOWN) be.tanks else null
            }
            sink.energy(DeviceBlockEntities.ASSEMBLER.get()) { be, side ->
                if (side == net.minecraft.core.Direction.DOWN) be.energy else null
            }
            sink.items(DeviceBlockEntities.PEDESTAL.get()) { be, _ -> bpm.world.devices.PedestalSlot(be) }
        }
    }
}
