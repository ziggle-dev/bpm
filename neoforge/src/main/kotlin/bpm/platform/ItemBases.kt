package bpm.platform

import net.minecraft.network.chat.Component
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block

/**
 * The two hooks this mod's items use that vanilla re-cut at 1.21.5.
 *
 * `appendHoverText` was handed a `MutableList<Component>` to add to; it is now handed a `Consumer` plus a
 * `TooltipDisplay` describing which of the stack's own component tooltips are being shown. Every caller
 * here only ever appended lines, so [lore] takes the appending function and nothing else.
 *
 * `inventoryTick` took a `Level`, a slot index and a `selected` flag; it now takes a `ServerLevel` and a
 * NULLABLE `EquipmentSlot` -- null for an ordinary inventory slot, MAINHAND when it is the selected one.
 * Two of the three things this mod did with the old arguments were "is this on the server" and "is this
 * in a hand", and the third (the slot index) was never read. So [carriedTick] answers both and drops it.
 *
 * The band boundary is 1.21.5 for both. That is the first version of a band with no node in this build
 * yet, so it is a claim rather than a measurement -- and a wrong one fails loudly the day that node is
 * added, which is the cheapest kind of wrong to be.
 */
open class BpmItem(properties: Properties) : Item(properties) {
    //? if <1.21 {
    /*/**
     * Forge asks the item for its client extensions; the provider this mod builds is one.
     *
     * This is on the base rather than on `GeoRenderedItem` because it overrides a method the class
     * already has from `Item`, and a Kotlin interface bringing a second implementation of it would make
     * every item ambiguous. An item that draws itself normally is not a [bpm.platform.GeoRenderedItem]
     * and is left alone.
     */
    override fun initializeClient(consumer: java.util.function.Consumer<net.minecraftforge.client.extensions.common.IClientItemExtensions>) {
        val provider = (this as? bpm.platform.GeoRenderedItem)?.geoRenderProvider() ?: return
        consumer.accept(provider)
    }
    *///?}


    /** Add this item's own tooltip lines. */
    protected open fun lore(stack: ItemStack, add: (Component) -> Unit) {}

    /** One server tick while this stack is carried. [inHand] is true while it is in either hand. */
    protected open fun carriedTick(
        stack: ItemStack,
        level: net.minecraft.server.level.ServerLevel,
        entity: net.minecraft.world.entity.Entity,
        inHand: Boolean,
    ) {}

    //? if >=1.21.5 {
    /*final override fun appendHoverText(
        stack: ItemStack,
        context: TooltipContext,
        display: net.minecraft.world.item.component.TooltipDisplay,
        add: java.util.function.Consumer<Component>,
        flag: net.minecraft.world.item.TooltipFlag,
    ) {
        super.appendHoverText(stack, context, display, add, flag)
        lore(stack) { add.accept(it) }
    }

    final override fun inventoryTick(
        stack: ItemStack,
        level: net.minecraft.server.level.ServerLevel,
        entity: net.minecraft.world.entity.Entity,
        slot: net.minecraft.world.entity.EquipmentSlot?,
    ) {
        super.inventoryTick(stack, level, entity, slot)
        carriedTick(stack, level, entity, slot != null)
    }
    *///?} elif >=1.20.5 {
    final override fun appendHoverText(
        stack: ItemStack,
        context: TooltipContext,
        tooltip: MutableList<Component>,
        flag: net.minecraft.world.item.TooltipFlag,
    ) {
        super.appendHoverText(stack, context, tooltip, flag)
        lore(stack) { tooltip.add(it) }
    }

    final override fun inventoryTick(
        stack: ItemStack,
        level: net.minecraft.world.level.Level,
        entity: net.minecraft.world.entity.Entity,
        slot: Int,
        selected: Boolean,
    ) {
        super.inventoryTick(stack, level, entity, slot, selected)
        val server = level as? net.minecraft.server.level.ServerLevel ?: return
        val offhand = (entity as? net.minecraft.world.entity.LivingEntity)?.offhandItem === stack
        carriedTick(stack, server, entity, selected || offhand)
    }
    //?} else {
    /*/**
     * A nullable `Level` rather than a context object: the tooltip was handed the world it was drawn in,
     * and `TooltipContext` -- which carries the registries a component-aware tooltip needs -- did not
     * exist yet because neither did the components.
     */
    final override fun appendHoverText(
        stack: ItemStack,
        level: net.minecraft.world.level.Level?,
        tooltip: MutableList<Component>,
        flag: net.minecraft.world.item.TooltipFlag,
    ) {
        super.appendHoverText(stack, level, tooltip, flag)
        lore(stack) { tooltip.add(it) }
    }

    final override fun inventoryTick(
        stack: ItemStack,
        level: net.minecraft.world.level.Level,
        entity: net.minecraft.world.entity.Entity,
        slot: Int,
        selected: Boolean,
    ) {
        super.inventoryTick(stack, level, entity, slot, selected)
        val server = level as? net.minecraft.server.level.ServerLevel ?: return
        val offhand = (entity as? net.minecraft.world.entity.LivingEntity)?.offhandItem === stack
        carriedTick(stack, server, entity, selected || offhand)
    }
    *///?}
}

/** The same tooltip hook, for an item that places a block and so cannot extend [BpmItem]. */
open class BpmBlockItem(block: Block, properties: Properties) : BlockItem(block, properties) {
    //? if <1.21 {
    /*/**
     * Forge asks the item for its client extensions; the provider this mod builds is one.
     *
     * This is on the base rather than on `GeoRenderedItem` because it overrides a method the class
     * already has from `Item`, and a Kotlin interface bringing a second implementation of it would make
     * every item ambiguous. An item that draws itself normally is not a [bpm.platform.GeoRenderedItem]
     * and is left alone.
     */
    override fun initializeClient(consumer: java.util.function.Consumer<net.minecraftforge.client.extensions.common.IClientItemExtensions>) {
        val provider = (this as? bpm.platform.GeoRenderedItem)?.geoRenderProvider() ?: return
        consumer.accept(provider)
    }
    *///?}


    protected open fun lore(stack: ItemStack, add: (Component) -> Unit) {}

    //? if >=1.21.5 {
    /*final override fun appendHoverText(
        stack: ItemStack,
        context: TooltipContext,
        display: net.minecraft.world.item.component.TooltipDisplay,
        add: java.util.function.Consumer<Component>,
        flag: net.minecraft.world.item.TooltipFlag,
    ) {
        super.appendHoverText(stack, context, display, add, flag)
        lore(stack) { add.accept(it) }
    }
    *///?} elif >=1.20.5 {
    final override fun appendHoverText(
        stack: ItemStack,
        context: TooltipContext,
        tooltip: MutableList<Component>,
        flag: net.minecraft.world.item.TooltipFlag,
    ) {
        super.appendHoverText(stack, context, tooltip, flag)
        lore(stack) { tooltip.add(it) }
    }
    //?} else {
    /*/**
     * A nullable `Level` rather than a context object: the tooltip was handed the world it was drawn in,
     * and `TooltipContext` -- which carries the registries a component-aware tooltip needs -- did not
     * exist yet because neither did the components.
     */
    final override fun appendHoverText(
        stack: ItemStack,
        level: net.minecraft.world.level.Level?,
        tooltip: MutableList<Component>,
        flag: net.minecraft.world.item.TooltipFlag,
    ) {
        super.appendHoverText(stack, level, tooltip, flag)
        lore(stack) { tooltip.add(it) }
    }
    *///?}
}
