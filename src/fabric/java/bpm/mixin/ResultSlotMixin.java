package bpm.mixin;

import bpm.platform.events.BpmEvents;
import bpm.platform.events.Crafted;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Something was taken out of a crafting result slot.
 *
 * NeoForge fires {@code PlayerEvent.ItemCraftedEvent}; vanilla only has this method. HEAD rather than
 * RETURN, because the seam promises the grid is still full when a listener sees it — by the time this
 * returns, the ingredients have been consumed and the thing a core-tier upgrade wants to inspect is gone.
 */
@Mixin(ResultSlot.class)
public abstract class ResultSlotMixin {

    @Shadow
    @Final
    private Player player;

    @Shadow
    @Final
    private net.minecraft.world.inventory.CraftingContainer craftSlots;

    @Inject(method = "onTake", at = @At("HEAD"))
    private void bpm$onTake(Player taker, ItemStack stack, CallbackInfo ci) {
        BpmEvents.INSTANCE.getItemCrafted().fire(new Crafted(this.player, stack, (Container) this.craftSlots));
    }
}
