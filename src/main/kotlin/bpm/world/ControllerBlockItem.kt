package bpm.world

import net.minecraft.world.item.BlockItem
import net.minecraft.world.level.block.Block
import software.bernie.geckolib.animatable.GeoItem
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache
import software.bernie.geckolib.animation.AnimatableManager
import software.bernie.geckolib.animation.AnimationController
import software.bernie.geckolib.util.GeckoLibUtil
import java.util.function.Consumer

/** The controller in item form, drawn with the same GeckoLib model (dormant pose) in hand and in the inventory. */
class ControllerBlockItem(block: Block, properties: Properties) : BlockItem(block, properties), GeoItem {

    private val animCache: AnimatableInstanceCache = GeckoLibUtil.createInstanceCache(this)

    override fun registerControllers(controllers: AnimatableManager.ControllerRegistrar) {
        controllers.add(AnimationController(this, "status", 0) { state -> state.setAndContinue(ControllerBlockEntity.OFF) })
    }

    override fun getAnimatableInstanceCache(): AnimatableInstanceCache = animCache

    override fun appendHoverText(stack: net.minecraft.world.item.ItemStack, context: TooltipContext, tooltip: MutableList<net.minecraft.network.chat.Component>, flag: net.minecraft.world.item.TooltipFlag) {
        val tier = CoreTier.of(stack)
        tooltip.add(net.minecraft.network.chat.Component.literal("${tier.label} core · reach ${tier.rangeText} · ${tier.maxLinks} links · ${tier.maxPlayerLinks} presence"))
    }

}
