package bpm.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reach {@code attackStrengthTicker}, which vanilla keeps private.
 *
 * A controller's pseudo-player is never ticked, so its attack-strength counter never recovers on its own
 * and every swing would land as a weak one. NeoForge widens the field with an access transformer; here it
 * takes an accessor mixin.
 *
 * This replaces reflection, which is what the shared code used to do and what the {@code WorldActor} seam
 * exists to have removed: reflection on a private field is a bet on the running mappings, and it lost that
 * bet on any loader that is not Mojmap.
 */
@Mixin(LivingEntity.class)
public interface LivingEntityAccessor {
    @Accessor("attackStrengthTicker")
    void bpm$setAttackStrengthTicker(int value);
}
