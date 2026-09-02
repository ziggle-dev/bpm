package bpm.platform.registry

import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.state.BlockBehaviour

/**
 * Stamping a block's or item's own registry key onto the `Properties` it is built from.
 *
 * From 1.21.2 a `Properties` carries its key, and the constructor READS it -- `BlockBehaviour` resolves
 * its loot table from it and `Item` its description id and model -- so building either from bare
 * `Properties` throws `NullPointerException: Block id not set` at registration.
 *
 * NeoForge hides this: its `DeferredRegister.registerBlock` stamps the key before calling the factory,
 * so that branch never sees it. This loader registers into the vanilla registry directly, so it has to
 * do the stamping itself, and this is where.
 *
 * Before 1.21.2 there is no key to set and these are the identity. Kept as calls rather than a
 * directive at each site so the four registration paths read the same on both versions.
 */
//? if >=1.21.2 {
/*fun blockProps(props: BlockBehaviour.Properties, id: ResourceLocation): BlockBehaviour.Properties =
    props.setId(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BLOCK, id))

fun itemProps(props: Item.Properties, id: ResourceLocation): Item.Properties =
    props.setId(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ITEM, id))
*///?} else {
fun blockProps(props: BlockBehaviour.Properties, id: ResourceLocation): BlockBehaviour.Properties = props

fun itemProps(props: Item.Properties, id: ResourceLocation): Item.Properties = props
//?}
