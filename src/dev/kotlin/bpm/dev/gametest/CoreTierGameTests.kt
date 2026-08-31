package bpm.dev.gametest

import bpm.Bpm
import bpm.world.ControllerBlockEntity
import bpm.world.CoreTier
import bpm.world.ModBlocks
import bpm.world.ModComponents
import bpm.world.ModItems
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.phys.AABB
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate

/** The core tier rides the controller: into the block, out again when it breaks. */
@GameTestHolder(Bpm.ID)
@PrefixGameTestTemplate(false)
class CoreTierGameTests {

    @GameTest(template = "empty7", timeoutTicks = 100)
    fun coreTierSurvivesPlacingAndBreaking(helper: GameTestHelper) {
        val pos = BlockPos(3, 1, 3)
        helper.setBlock(pos, ModBlocks.CONTROLLER.get())
        val be = helper.getBlockEntity<ControllerBlockEntity>(pos)
        be.coreTier = CoreTier.PRISTINE
        be.setChanged()
        helper.assertTrue(be.linkRange == 64.0, "a pristine controller reaches ${be.linkRange}, wanted 64")
        helper.assertTrue(be.maxLinks == 32, "a pristine controller holds ${be.maxLinks} links, wanted 32")
        // The item form carries the tier back through the block entity's components.
        val stack = be.collectComponents().get(ModComponents.CORE_TIER.get())
        helper.assertTrue(stack == "pristine", "the block entity's components say '$stack'")
        helper.level.destroyBlock(helper.absolutePos(pos), true)
        helper.succeedWhen {
            val drops = helper.level.getEntitiesOfClass(ItemEntity::class.java, AABB(helper.absolutePos(pos)).inflate(2.0)).filter { it.item.`is`(ModItems.CONTROLLER.get()) }
            helper.assertTrue(drops.isNotEmpty(), "no controller dropped")
            val tier = CoreTier.of(drops.first().item)
            helper.assertTrue(tier == CoreTier.PRISTINE, "the dropped controller is ${tier.key}, wanted pristine")
        }
    }
}
