package bpm.dev.gametest

import bpm.Bpm
import bpm.world.ContentItems
import bpm.world.DeviceBlocks
import bpm.world.devices.PedestalBlock
import bpm.world.entity.ModEntities
import bpm.world.entity.QuantumWardenEntity
import bpm.world.entity.WardenBoltEntity
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate

/** The Warden's damage rules, its death returning the core, and its bolt — against a real server. */
@GameTestHolder(Bpm.ID)
@PrefixGameTestTemplate(false)
class WardenGameTests {

    @GameTest(template = "empty7t", timeoutTicks = 400)
    fun cageQuartersDamageAndDeathReturnsTheCore(helper: GameTestHelper) {
        val pedestalPos = BlockPos(3, 1, 3)
        helper.setBlock(pedestalPos, DeviceBlocks.CORE_PEDESTAL.get().defaultBlockState().setValue(PedestalBlock.HAS_CORE, false))
        val w = QuantumWardenEntity.spawnAt(helper.level, helper.absolutePos(pedestalPos), null)
        w.setNoAi(true)
        val max = w.maxHealth
        val src = helper.level.damageSources().generic()
        helper.startSequence()
            .thenExecuteAfter(45) {
                w.hurt(src, 20f)
                val lost = max - w.health
                helper.assertTrue(lost in 3f..6f, "a hit on the closed cage took $lost, wanted about a quarter of 20 (after armour)")
            }
            .thenExecuteAfter(12) {
                w.setExposed(true, 100)
                val before = w.health
                w.hurt(src, 20f)
                val lost = before - w.health
                helper.assertTrue(lost in 15f..20f, "a hit on the open core took $lost, wanted about 20 (after armour)")
            }
            .thenExecuteAfter(12) {
                w.hurt(src, 10_000f)
                helper.assertTrue(w.isDeadOrDying, "the Warden survived 10,000 damage")
            }
            .thenExecuteAfter(70) {
                helper.assertTrue(w.isRemoved, "the Warden is still there ${w.deathTime} ticks into its death")
                helper.assertBlockProperty(pedestalPos, PedestalBlock.HAS_CORE, true)
                val drops = helper.level.getEntitiesOfClass(ItemEntity::class.java, AABB(helper.absolutePos(pedestalPos)).inflate(6.0))
                val shards = drops.filter { it.item.`is`(ContentItems.ENTANGLIUM_SHARD.get()) }.sumOf { it.item.count }
                helper.assertTrue(shards >= 8, "$shards shards dropped, wanted at least 8")
            }
            .thenSucceed()
    }

    @GameTest(template = "empty7t", timeoutTicks = 100)
    fun wardenSurvivesPeacefulAndVanishingReturnsTheCore(helper: GameTestHelper) {
        val pedestalPos = BlockPos(3, 1, 3)
        helper.setBlock(pedestalPos, DeviceBlocks.CORE_PEDESTAL.get().defaultBlockState().setValue(PedestalBlock.HAS_CORE, false))
        val w = QuantumWardenEntity.spawnAt(helper.level, helper.absolutePos(pedestalPos), null)
        w.setNoAi(true)
        helper.assertTrue(!w.despawnsInPeaceful(), "the Warden would despawn on Peaceful")
        helper.startSequence()
            .thenExecuteAfter(10) { helper.assertTrue(w.isAlive && !w.isRemoved, "the Warden vanished within 10 ticks") }
            .thenExecute { w.discard() }
            .thenExecuteAfter(2) { helper.assertBlockProperty(pedestalPos, PedestalBlock.HAS_CORE, true) }
            .thenSucceed()
    }

    @GameTest(template = "empty7t", timeoutTicks = 120)
    fun quickPulseNeverDropsTheWarden(helper: GameTestHelper) {
        val pedestalPos = BlockPos(3, 1, 3)
        helper.setBlock(pedestalPos, DeviceBlocks.CORE_PEDESTAL.get().defaultBlockState().setValue(PedestalBlock.HAS_CORE, false))
        val w = QuantumWardenEntity.spawnAt(helper.level, helper.absolutePos(pedestalPos), null)
        w.setNoAi(true)
        fun pulse() {
            val p = bpm.world.entity.LinkerPulseEntity(ModEntities.PULSE.get(), helper.level)
            p.launch(helper.absoluteVec(Vec3(3.5, 4.0, 0.5)), w.getEyePosition(1f).subtract(helper.absoluteVec(Vec3(3.5, 4.0, 0.5))))
            helper.level.addFreshEntity(p)
        }
        helper.startSequence()
            .thenExecuteAfter(45) { pulse() }
            .thenExecuteAfter(20) { helper.assertTrue(!w.disabled && w.isNoGravity, "a pulse on the closed cage downed the Warden") }
            .thenExecute { w.setExposed(true, 200); pulse() }
            .thenExecuteAfter(20) { helper.assertTrue(!w.disabled && w.isNoGravity && w.health < w.maxHealth, "a quick pulse on the open core downed the Warden (disabled=${w.disabled}) or did not sting (${w.health})") }
            .thenSucceed()
    }

    @GameTest(template = "empty7t", timeoutTicks = 200)
    fun trackingPulseFindsAnExposedWarden(helper: GameTestHelper) {
        val pedestalPos = BlockPos(3, 1, 3)
        helper.setBlock(pedestalPos, DeviceBlocks.CORE_PEDESTAL.get().defaultBlockState().setValue(PedestalBlock.HAS_CORE, false))
        val w = QuantumWardenEntity.spawnAt(helper.level, helper.absolutePos(pedestalPos), null)
        w.setNoAi(true)
        helper.startSequence()
            .thenExecuteAfter(45) {
                w.setExposed(true, 300)
                val p = bpm.world.entity.LinkerPulseEntity(ModEntities.PULSE.get(), helper.level)
                p.launch(helper.absoluteVec(Vec3(0.5, 1.6, 0.5)), Vec3(0.0, 1.0, 0.0))
                p.seek(w)
                helper.level.addFreshEntity(p)
            }
            .thenWaitUntil { helper.assertTrue(w.disabled, "the Warden still stands after ${helper.tick} ticks") }
            .thenSucceed()
    }

    @GameTest(template = "empty7t", timeoutTicks = 200)
    fun specialDownsTheClosedCage(helper: GameTestHelper) {
        val pedestalPos = BlockPos(3, 1, 3)
        helper.setBlock(pedestalPos, DeviceBlocks.CORE_PEDESTAL.get().defaultBlockState().setValue(PedestalBlock.HAS_CORE, false))
        val w = QuantumWardenEntity.spawnAt(helper.level, helper.absolutePos(pedestalPos), null)
        w.setNoAi(true)
        val max = w.maxHealth
        helper.startSequence()
            .thenExecuteAfter(45) {
                val p = bpm.world.entity.LinkerPulseEntity(ModEntities.PULSE.get(), helper.level)
                p.launch(helper.absoluteVec(Vec3(0.5, 1.6, 0.5)), Vec3(0.0, 1.0, 0.0))
                p.seek(w)
                helper.level.addFreshEntity(p)
            }
            .thenWaitUntil { helper.assertTrue(w.health < max, "the special did no harm after ${helper.tick} ticks") }
            .thenExecute { helper.assertTrue(w.disabled && !w.isNoGravity && max - w.health in 9f..13f, "the special on the closed cage: disabled=${w.disabled}, took ${max - w.health}, wanted a knockdown and about 12 (after armour)") }
            .thenSucceed()
    }

    @GameTest(template = "empty7t", timeoutTicks = 300)
    fun turretsMendAGroundedWarden(helper: GameTestHelper) {
        val pedestalPos = BlockPos(3, 1, 5)
        helper.setBlock(pedestalPos, DeviceBlocks.CORE_PEDESTAL.get().defaultBlockState().setValue(PedestalBlock.HAS_CORE, false))
        val turret = BlockPos(3, 1, 1)
        helper.setBlock(turret, DeviceBlocks.OBSERVER_TURRET.get().defaultBlockState().setValue(bpm.world.devices.TurretBlock.FACING, net.minecraft.core.Direction.UP))
        // With its AI, so it grounds itself at forty percent; nobody here for the turret to shoot.
        val w = QuantumWardenEntity.spawnAt(helper.level, helper.absolutePos(pedestalPos), null)
        w.health = w.maxHealth * 0.4f
        val start = w.health
        helper.startSequence()
            .thenExecuteAfter(50) { helper.assertTrue(w.grounded, "the Warden at ${w.health} of ${w.maxHealth} is not grounded") }
            .thenWaitUntil { helper.assertTrue(w.health > start + 1f, "the turret has not mended the Warden after ${helper.tick} ticks (${w.health}, tracking ${helper.getBlockEntity<bpm.world.devices.TurretBlockEntity>(turret).tracking})") }
            .thenSucceed()
    }

    @GameTest(template = "empty7t", timeoutTicks = 300)
    fun turretsMendAFlyingWardenWithinReach(helper: GameTestHelper) {
        val pedestalPos = BlockPos(3, 1, 5)
        helper.setBlock(pedestalPos, DeviceBlocks.CORE_PEDESTAL.get().defaultBlockState().setValue(PedestalBlock.HAS_CORE, false))
        val turret = BlockPos(3, 1, 1)
        helper.setBlock(turret, DeviceBlocks.OBSERVER_TURRET.get().defaultBlockState().setValue(bpm.world.devices.TurretBlock.FACING, net.minecraft.core.Direction.UP))
        // Without its AI it hangs where it spawned — flying, four blocks from the turret, nobody to shoot.
        val w = QuantumWardenEntity.spawnAt(helper.level, helper.absolutePos(pedestalPos), null)
        w.isNoAi = true
        w.health = w.maxHealth * 0.8f
        val start = w.health
        helper.startSequence()
            .thenExecuteAfter(10) { helper.assertTrue(!w.grounded && w.isNoGravity, "the Warden at ${w.health} of ${w.maxHealth} is not flying") }
            .thenWaitUntil { helper.assertTrue(w.health > start + 1f, "the turret has not mended the flying Warden after ${helper.tick} ticks (${w.health}, tracking ${helper.getBlockEntity<bpm.world.devices.TurretBlockEntity>(turret).tracking})") }
            .thenSucceed()
    }

    @GameTest(template = "empty7", timeoutTicks = 200)
    fun theWardenRaisesSpikesUnderItsMark(helper: GameTestHelper) {
        for (x in 1..5) for (z in 1..5) helper.setBlock(BlockPos(x, 1, z), bpm.world.ContentBlocks.CHAMBER_FLOOR.get())
        val w = QuantumWardenEntity.spawnAt(helper.level, helper.absolutePos(BlockPos(3, 2, 3)), null)
        w.isNoAi = true
        val player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL)
        val at = helper.absoluteVec(Vec3(3.5, 2.0, 3.5))
        player.setPos(at.x, at.y, at.z)
        val n = w.spikeAttack(helper.level, player)
        helper.assertTrue(n == 5, "spikes rose under $n of 5 cells")
        helper.assertBlockPresent(DeviceBlocks.PHASE_SPIKE.get(), BlockPos(3, 2, 3))
        helper.assertBlockPresent(DeviceBlocks.PHASE_SPIKE.get(), BlockPos(4, 2, 3))
        helper.assertBlockPresent(net.minecraft.world.level.block.Blocks.AIR, BlockPos(4, 2, 4))
        helper.startSequence()
            .thenWaitUntil { helper.assertBlock(BlockPos(3, 2, 3), { it == net.minecraft.world.level.block.Blocks.AIR }, "the spike plates did not vanish") }
            .thenExecute { helper.assertBlockPresent(bpm.world.ContentBlocks.CHAMBER_FLOOR.get(), BlockPos(3, 1, 3)) }
            .thenSucceed()
    }

    @GameTest(template = "empty7", timeoutTicks = 120)
    fun seekerBendsTowardItsMark(helper: GameTestHelper) {
        val pig = helper.spawn(EntityType.PIG, Vec3(4.5, 1.0, 5.5)).also { it.setNoAi(true) }
        val start = pig.health
        val bolt = WardenBoltEntity(ModEntities.BOLT.get(), helper.level)
        bolt.damage = 6f
        // Launched straight along +Z past the pig's side; only the turn brings it round.
        bolt.launch(helper.absoluteVec(Vec3(1.5, 1.6, 1.5)), helper.absoluteVec(Vec3(1.5, 1.6, 6.5)))
        bolt.seek(pig)
        helper.level.addFreshEntity(bolt)
        helper.succeedWhen {
            helper.assertTrue(pig.health < start, "the seeker missed after ${helper.tick} ticks; bolt at ${bolt.position()} removed=${bolt.isRemoved}")
        }
    }

    @GameTest(template = "empty7", timeoutTicks = 100)
    fun boltHurtsWhatItHits(helper: GameTestHelper) {
        val pig = helper.spawn(EntityType.PIG, Vec3(3.5, 1.0, 5.5)).also { it.setNoAi(true) }
        val start = pig.health
        val bolt = WardenBoltEntity(ModEntities.BOLT.get(), helper.level)
        bolt.damage = 6f
        bolt.launch(helper.absoluteVec(Vec3(3.5, 1.6, 1.5)), helper.absoluteVec(Vec3(3.5, 1.6, 5.5)))
        helper.level.addFreshEntity(bolt)
        helper.succeedWhen {
            helper.assertTrue(pig.health < start, "the pig is unhurt after ${helper.tick} ticks; bolt at ${bolt.position()} removed=${bolt.isRemoved}")
        }
    }
}
