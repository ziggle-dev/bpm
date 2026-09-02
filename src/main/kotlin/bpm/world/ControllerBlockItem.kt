package bpm.world

import net.minecraft.world.item.BlockItem
import net.minecraft.world.level.block.Block
import software.bernie.geckolib.animatable.GeoItem
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache
import bpm.platform.AnimatableManager
import software.bernie.geckolib.animation.AnimationController
import software.bernie.geckolib.util.GeckoLibUtil
import java.util.function.Consumer

/** The controller in item form, drawn with the same GeckoLib model (dormant pose) in hand and in the inventory. */
class ControllerBlockItem(block: Block, properties: Properties) : bpm.platform.BpmBlockItem(block, properties), GeoItem {

    private val animCache: AnimatableInstanceCache = GeckoLibUtil.createInstanceCache(this)

    override fun registerControllers(controllers: bpm.platform.ControllerRegistrar) {
        controllers.add(bpm.platform.animController(this, "status", 0) { state -> state.setAndContinue(ControllerBlockEntity.OFF) })
    }

    override fun getAnimatableInstanceCache(): AnimatableInstanceCache = animCache

    /**
     * Which renderer draws this item, asked once by GeckoLib and cached by it.
     *
     * The answer lives in one client-side table, not here -- see [bpm.client.render.BpmItemRenderers].
     * This override exists because GeckoLib asks the item and nothing else can answer for it, and it is
     * the one route that works on both loaders and on both sides of 1.21.4's
     * `BlockEntityWithoutLevelRenderer` deletion.
     */
    override fun createGeoRenderer(consumer: Consumer<software.bernie.geckolib.animatable.client.GeoRenderProvider>) =
        bpm.client.render.geoItemRenderer(this, consumer)

    override fun lore(stack: net.minecraft.world.item.ItemStack, add: (net.minecraft.network.chat.Component) -> Unit) {
        val tier = CoreTier.of(stack)
        add(net.minecraft.network.chat.Component.literal("${tier.label} core · reach ${tier.rangeText} · ${tier.maxLinks} links · ${tier.maxPlayerLinks} presence"))
    }

}
