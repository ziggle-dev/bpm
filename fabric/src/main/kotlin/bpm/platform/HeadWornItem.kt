package bpm.platform

import bpm.world.TooltipItem
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.Item.Properties

/**
 * An item worn on the head, and right-clicked to put on.
 *
 * This is not a signature change but a change of KIND. Until 1.21.2 "equippable" was an interface an
 * item class implemented, `Equipable`, whose `swapWithEquipmentSlot` the item called from `use`. From
 * 1.21.2 it is a DATA COMPONENT on the stack, `DataComponents.EQUIPPABLE`, and vanilla's own `Item.use`
 * does the swap when it finds one -- so the newer form is the absence of code rather than different
 * code, and the item properties carry what the interface used to.
 *
 * Both spellings answer "wear this on your head", which is why they can share a name at all.
 */
//? if >=1.21.2 {
/*abstract class HeadWornItem(properties: Properties) : TooltipItem(
    properties.component(
        net.minecraft.core.component.DataComponents.EQUIPPABLE,
        net.minecraft.world.item.equipment.Equippable.builder(EquipmentSlot.HEAD).build(),
    ),
)
*///?} else {
abstract class HeadWornItem(properties: Properties) : TooltipItem(properties), net.minecraft.world.item.Equipable {
    override fun getEquipmentSlot(): EquipmentSlot = EquipmentSlot.HEAD

    override fun use(
        level: net.minecraft.world.level.Level,
        player: net.minecraft.world.entity.player.Player,
        hand: net.minecraft.world.InteractionHand,
    ): UseResult = swapWithEquipmentSlot(this, level, player, hand)
}
//?}
