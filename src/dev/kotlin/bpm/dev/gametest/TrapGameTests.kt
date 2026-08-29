package bpm.dev.gametest

import bpm.Bpm
import bpm.world.DeviceBlocks
import bpm.world.devices.PhaseBlock
import bpm.world.devices.PhaseBlockEntity
import bpm.world.devices.TrapBlockEntity
import bpm.world.devices.TrapMode
import bpm.world.devices.TurretBlock
import bpm.world.devices.TurretBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Mob
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate

/** The traps against a real server: spikes and vents on their cycle, a turret hunting, a superposition block phasing. */
@GameTestHolder(Bpm.ID)
@PrefixGameTestTemplate(false)
class TrapGameTests {

    private fun still(helper: GameTestHelper, type: EntityType<out Mob>, at: Vec3): Mob = helper.spawn(type, at).also { it.setNoAi(true) }

    @GameTest(template = "empty7", timeoutTicks = 200)
    fun spikesHurtWhatStandsOnThem(helper: GameTestHelper) {
        val spike = BlockPos(3, 1, 3)
        helper.setBlock(spike, DeviceBlocks.PHASE_SPIKE.get())
        helper.getBlockEntity<TrapBlockEntity>(spike).mode = TrapMode.CYCLE
        val pig = still(helper, EntityType.PIG, Vec3(3.5, 2.0, 3.5))
        val start = pig.health
        helper.succeedWhen {
            helper.assertTrue(pig.health < start, "the pig is unhurt after ${helper.tick} ticks (spike ${helper.getBlockEntity<TrapBlockEntity>(spike).phase})")
        }
    }

    @GameTest(template = "empty7", timeoutTicks = 200)
    fun ventsHurtAndLift(helper: GameTestHelper) {
        val vent = BlockPos(3, 1, 3)
        helper.setBlock(vent, DeviceBlocks.DECOHERENCE_VENT.get())
        helper.getBlockEntity<TrapBlockEntity>(vent).mode = TrapMode.CYCLE
        val pig = still(helper, EntityType.PIG, Vec3(3.5, 2.0, 3.5))
        val start = pig.health
        helper.succeedWhen {
            helper.assertTrue(pig.health < start && pig.hasEffect(MobEffects.LEVITATION), "the pig is unhurt or grounded after ${helper.tick} ticks (vent ${helper.getBlockEntity<TrapBlockEntity>(vent).phase})")
        }
    }

    @GameTest(template = "empty7", timeoutTicks = 100)
    fun ventsThrowWhatStepsOnThemToAPeer(helper: GameTestHelper) {
        val a = BlockPos(1, 1, 1)
        val b = BlockPos(5, 1, 5)
        helper.setBlock(a, DeviceBlocks.DECOHERENCE_VENT.get())
        helper.setBlock(b, DeviceBlocks.DECOHERENCE_VENT.get())
        val va = helper.getBlockEntity<bpm.world.devices.VentBlockEntity>(a)
        val vb = helper.getBlockEntity<bpm.world.devices.VentBlockEntity>(b)
        va.mode = TrapMode.LINKED
        vb.mode = TrapMode.LINKED
        va.peers += helper.absolutePos(b)
        vb.peers += helper.absolutePos(a)
        // With its AI (a still mob never moves, so never steps): the first tick on the grate throws it.
        val pig = helper.spawn(EntityType.PIG, Vec3(1.5, 2.0, 1.5))
        val there = helper.absolutePos(b)
        helper.succeedWhen {
            helper.assertTrue(pig.blockPosition().distManhattan(there) <= 2, "the pig is ${pig.blockPosition().distManhattan(there)} from the other vent after ${helper.tick} ticks")
        }
    }

    @GameTest(template = "empty7", timeoutTicks = 200)
    fun linkedTrapsOnlyFireWhenTold(helper: GameTestHelper) {
        val spike = BlockPos(3, 1, 3)
        helper.setBlock(spike, DeviceBlocks.PHASE_SPIKE.get())
        val be = helper.getBlockEntity<TrapBlockEntity>(spike)
        be.mode = TrapMode.LINKED
        val pig = still(helper, EntityType.PIG, Vec3(3.5, 2.0, 3.5))
        val start = pig.health
        helper.startSequence()
            .thenExecuteAfter(70) { helper.assertTrue(pig.health == start, "a trap on demand went off by itself") }
            .thenExecute { helper.assertTrue(be.fire(), "fire() refused") }
            .thenExecuteAfter(30) { helper.assertTrue(pig.health < start, "the fired trap did no harm (${be.phase})") }
            .thenSucceed()
    }

    @GameTest(template = "empty7", timeoutTicks = 200)
    fun turretHuntsHostiles(helper: GameTestHelper) {
        val turret = BlockPos(3, 1, 1)
        helper.setBlock(turret, DeviceBlocks.OBSERVER_TURRET.get().defaultBlockState().setValue(TurretBlock.FACING, Direction.UP))
        val creeper = still(helper, EntityType.CREEPER, Vec3(3.5, 1.0, 5.5))
        val start = creeper.health
        helper.succeedWhen {
            val be = helper.getBlockEntity<TurretBlockEntity>(turret)
            helper.assertTrue(be.hasTarget, "the turret found nothing to shoot after ${helper.tick} ticks")
            helper.assertTrue(creeper.health < start, "the creeper is unhurt after ${helper.tick} ticks (tracking ${be.tracking})")
        }
    }

    @GameTest(template = "empty7", timeoutTicks = 100)
    fun pulseDarkensATurret(helper: GameTestHelper) {
        val turret = BlockPos(3, 1, 1)
        helper.setBlock(turret, DeviceBlocks.OBSERVER_TURRET.get().defaultBlockState().setValue(TurretBlock.FACING, Direction.UP))
        val be = helper.getBlockEntity<TurretBlockEntity>(turret)
        val p = bpm.world.entity.LinkerPulseEntity(bpm.world.entity.ModEntities.PULSE.get(), helper.level)
        val from = helper.absoluteVec(Vec3(3.5, 1.5, 5.5))
        p.launch(from, helper.absoluteVec(Vec3(3.5, 1.5, 1.5)).subtract(from))
        helper.level.addFreshEntity(p)
        helper.succeedWhen { helper.assertTrue(be.disabled && be.secondsDark() in 13..15, "the turret is still lit after ${helper.tick} ticks (pulse at ${p.position()} removed=${p.isRemoved}, dark ${be.secondsDark()} s)") }
    }

    @GameTest(template = "empty7", timeoutTicks = 60)
    fun linkerChargesCountAndThePedestalRefillsThem(helper: GameTestHelper) {
        val linker = bpm.world.ModItems.LINKER.get()
        val stack = net.minecraft.world.item.ItemStack(linker)
        helper.assertTrue(linker.charges(stack) == bpm.world.LinkerItem.MAX_CHARGES, "a fresh linker has ${linker.charges(stack)} charges")
        stack.set(bpm.world.ModComponents.CHARGES.get(), 0)
        helper.assertTrue(linker.charges(stack) == 0, "an emptied linker still reads ${linker.charges(stack)}")
        val player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL)
        linker.recharge(stack, player)
        helper.assertTrue(linker.charges(stack) == bpm.world.LinkerItem.MAX_CHARGES, "the pedestal's recharge left ${linker.charges(stack)}")
        helper.succeed()
    }

    @GameTest(template = "empty7", timeoutTicks = 60)
    fun quickPulseIntoTheFloorThrowsTheShooterUp(helper: GameTestHelper) {
        val player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL)
        val at = helper.absoluteVec(Vec3(3.5, 1.0, 3.5))
        player.setPos(at.x, at.y, at.z)
        val p = bpm.world.entity.LinkerPulseEntity(bpm.world.entity.ModEntities.PULSE.get(), helper.level)
        p.owner = player
        p.launch(at.add(0.0, 0.9, 0.0), Vec3(0.0, -1.0, 0.0))
        helper.level.addFreshEntity(p)
        helper.succeedWhen { helper.assertTrue(player.deltaMovement.y >= 0.95, "the shooter was not thrown (dy ${player.deltaMovement.y}) after ${helper.tick} ticks; pulse removed=${p.isRemoved}") }
    }

    @GameTest(template = "empty7", timeoutTicks = 200)
    fun decoheredFloorBitesAndHeals(helper: GameTestHelper) {
        val tile = BlockPos(3, 1, 3)
        helper.setBlock(tile, bpm.world.ContentBlocks.CHAMBER_FLOOR.get())
        helper.assertTrue(bpm.world.devices.PhaseBlockEntity.decohere(helper.level, helper.absolutePos(tile), 100), "the floor would not decohere")
        val be = helper.getBlockEntity<PhaseBlockEntity>(tile)
        helper.assertTrue(be.trail, "not a trail tile")
        // A live pig (a still one never moves, so never checks the blocks it is inside), dropped into the ghost.
        var pig: net.minecraft.world.entity.animal.Pig? = null
        helper.startSequence()
            .thenWaitUntil { helper.assertBlockProperty(tile, PhaseBlock.SOLID, false) }
            .thenExecute { pig = helper.spawn(EntityType.PIG, Vec3(3.5, 1.2, 3.5)) }
            .thenWaitUntil { helper.assertTrue(pig!!.health < pig!!.maxHealth, "the pig stood in the ghost unhurt (${pig!!.health})") }
            .thenWaitUntil { helper.assertBlock(tile, { it == bpm.world.ContentBlocks.CHAMBER_FLOOR.get() }, "the floor did not heal back") }
            .thenSucceed()
    }

    @GameTest(template = "empty7t", timeoutTicks = 200)
    fun turretOnAPillarHitsWhatIsBelowIt(helper: GameTestHelper) {
        for (y in 1..4) helper.setBlock(BlockPos(3, y, 3), bpm.world.ContentBlocks.CHAMBER_PILLAR.get())
        val turret = BlockPos(3, 5, 3)
        helper.setBlock(turret, DeviceBlocks.OBSERVER_TURRET.get().defaultBlockState().setValue(TurretBlock.FACING, Direction.UP))
        // Two blocks out, four down: a ray from the eye would have entered the turret's own block.
        val creeper = still(helper, EntityType.CREEPER, Vec3(3.5, 1.0, 5.5))
        val start = creeper.health
        helper.succeedWhen {
            val be = helper.getBlockEntity<TurretBlockEntity>(turret)
            helper.assertTrue(be.hasTarget, "the turret found nothing below it after ${helper.tick} ticks")
            helper.assertTrue(creeper.health < start, "the creeper below is unhurt after ${helper.tick} ticks")
        }
    }

    @GameTest(template = "empty7", timeoutTicks = 200)
    fun conjuredSpikesBiteAndSinkBack(helper: GameTestHelper) {
        val tile = BlockPos(3, 1, 3)
        helper.setBlock(tile, bpm.world.ContentBlocks.CHAMBER_FLOOR.get())
        val pig = still(helper, EntityType.PIG, Vec3(3.5, 2.0, 3.5))
        val start = pig.health
        helper.assertTrue(bpm.world.devices.SpikeBlockEntity.conjure(helper.level, helper.absolutePos(tile), 60), "the floor would not spike")
        helper.assertBlockPresent(DeviceBlocks.PHASE_SPIKE.get(), tile.above())
        helper.startSequence()
            .thenWaitUntil { helper.assertTrue(pig.health < start, "the pig on the conjured spike is unhurt (${pig.health})") }
            .thenWaitUntil { helper.assertBlock(tile.above(), { it == net.minecraft.world.level.block.Blocks.AIR }, "the spike plate did not vanish") }
            .thenExecute { helper.assertBlockPresent(bpm.world.ContentBlocks.CHAMBER_FLOOR.get(), tile) }
            .thenSucceed()
    }

    @GameTest(template = "empty7t", timeoutTicks = 60)
    fun aTurretHopsToAnotherPerch(helper: GameTestHelper) {
        for (y in 1..2) {
            helper.setBlock(BlockPos(1, y, 3), bpm.world.ContentBlocks.CHAMBER_PILLAR.get())
            helper.setBlock(BlockPos(5, y, 3), bpm.world.ContentBlocks.CHAMBER_PILLAR.get())
        }
        val from = BlockPos(1, 3, 3)
        val to = BlockPos(5, 3, 3)
        helper.setBlock(from, DeviceBlocks.OBSERVER_TURRET.get().defaultBlockState().setValue(TurretBlock.FACING, Direction.UP))
        helper.getBlockEntity<TurretBlockEntity>(from).active = false
        val moved = TurretBlockEntity.hop(helper.level, helper.absolutePos(from), helper.absolutePos(to))
        helper.assertTrue(moved != null, "the turret did not hop")
        helper.assertBlockPresent(DeviceBlocks.OBSERVER_TURRET.get(), to)
        helper.assertBlockPresent(net.minecraft.world.level.block.Blocks.AIR, from)
        helper.assertTrue(!moved!!.active, "the hop forgot the turret was switched off")
        helper.assertTrue(TurretBlockEntity.hop(helper.level, helper.absolutePos(to), helper.absolutePos(BlockPos(3, 5, 3))) == null, "a perch in mid-air was accepted")
        helper.succeed()
    }

    @GameTest(template = "empty7t", timeoutTicks = 120)
    fun turretsFaceTheWardenEvenWithNothingToShoot(helper: GameTestHelper) {
        val turret = BlockPos(1, 1, 3)
        helper.setBlock(turret, DeviceBlocks.OBSERVER_TURRET.get().defaultBlockState().setValue(TurretBlock.FACING, Direction.UP))
        // A whole Warden is nothing to shoot and nothing to mend; the turret should still turn to it, and only turn.
        val w = bpm.world.entity.QuantumWardenEntity.spawnAt(helper.level, helper.absolutePos(BlockPos(5, 1, 3)), null)
        w.isNoAi = true
        helper.startSequence()
            .thenExecuteAfter(30) {
                val be = helper.getBlockEntity<TurretBlockEntity>(turret)
                helper.assertTrue(be.tracking && !be.hasTarget, "the turret is not watching (tracking=${be.tracking}, target=${be.hasTarget})")
                // An up-facing mount reads yaw as atan2(-x, -z) from its eye; whichever way the plot is turned, the aim must point at the Warden.
                val d = w.getEyePosition(1f).subtract(Vec3.atCenterOf(helper.absolutePos(turret)).add(0.0, 0.5, 0.0))
                val want = Math.toDegrees(kotlin.math.atan2(-d.x, -d.z)).toFloat()
                var diff = (be.targetYaw - want) % 360f
                if (diff > 180f) diff -= 360f
                if (diff < -180f) diff += 360f
                helper.assertTrue(kotlin.math.abs(diff) < 10f, "the turret aims at ${be.targetYaw}°, the Warden is at $want°")
                helper.assertTrue(w.health >= w.maxHealth, "the turret fired at a Warden it had no cause to shoot (${w.health})")
            }
            .thenSucceed()
    }

    @GameTest(template = "empty7", timeoutTicks = 300)
    fun superpositionBlockCycles(helper: GameTestHelper) {
        val block = BlockPos(3, 1, 3)
        helper.setBlock(block, DeviceBlocks.PHASE_BLOCK.get())
        helper.getBlockEntity<PhaseBlockEntity>(block).mode = TrapMode.CYCLE
        helper.startSequence()
            .thenWaitUntil { helper.assertBlockProperty(block, PhaseBlock.SOLID, false) }
            .thenWaitUntil { helper.assertBlockProperty(block, PhaseBlock.SOLID, true) }
            .thenSucceed()
    }
}
