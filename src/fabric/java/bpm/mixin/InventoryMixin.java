package bpm.mixin;

import bpm.platform.events.BpmEvents;
import bpm.platform.events.Drops;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * What a player drops on death, so the chamber can keep it.
 *
 * NeoForge fires {@code LivingDropsEvent} and lets a listener cancel it. Vanilla has no such moment: an
 * inventory simply empties itself into the world here. So the veto is asked BEFORE any of that happens,
 * and a "no" cancels the whole method — which is the same outcome cancelling the NeoForge event has.
 *
 * The list handed to the veto is a snapshot of what is about to be dropped, taken while the inventory is
 * still full, because that is what the seam promises and what a listener needs to decide.
 */
@Mixin(Inventory.class)
public abstract class InventoryMixin {

    @Inject(method = "dropAll", at = @At("HEAD"), cancellable = true)
    private void bpm$onDropAll(CallbackInfo ci) {
        Inventory self = (Inventory) (Object) this;
        if (!(self.player instanceof ServerPlayer player)) {
            return;
        }
        List<ItemStack> stacks = new ArrayList<>();
        for (int i = 0; i < self.getContainerSize(); i++) {
            ItemStack stack = self.getItem(i);
            if (!stack.isEmpty()) {
                stacks.add(stack);
            }
        }
        if (stacks.isEmpty()) {
            return;
        }
        if (!BpmEvents.INSTANCE.getPlayerDrops().fire(new Drops(player, stacks))) {
            ci.cancel();
        }
    }
}
