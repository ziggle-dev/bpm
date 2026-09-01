package bpm.mixin;

import bpm.platform.events.BpmEvents;
import bpm.platform.events.ClickPhase;
import bpm.platform.events.LeftClickBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The HOLD phase of a left click, which is the one the monitor's slider drag depends on.
 *
 * Fabric's {@code AttackBlockCallback} fires on START only. A slider is dragged by holding the button
 * down and moving, so without this the monitor's sliders would move once and then stop — the behaviour
 * would look broken rather than missing, which is why this is a required mixin and not a nicety.
 *
 * {@code stopDestroyBlock} gives the ABORT phase. STOP is not injected: vanilla has no "finished
 * destroying" moment distinct from the block actually breaking, and the block-break hook already covers
 * that.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {

    @Inject(method = "continueDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void bpm$onContinueDestroy(BlockPos pos, Direction face, CallbackInfoReturnable<Boolean> cir) {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        if (!BpmEvents.INSTANCE.getLeftClickBlock().fire(new LeftClickBlock(player, pos, face, ClickPhase.HOLD))) {
            // Refused: report "still going" so the game does not treat this as a finished break, but do
            // not let the vanilla mining progress advance.
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "stopDestroyBlock", at = @At("HEAD"))
    private void bpm$onStopDestroy(CallbackInfo ci) {
        var player = Minecraft.getInstance().player;
        if (player != null) {
            BpmEvents.INSTANCE.getLeftClickBlock().fire(
                new LeftClickBlock(player, BlockPos.ZERO, null, ClickPhase.ABORT));
        }
    }
}
