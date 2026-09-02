package bpm.mixin;

import bpm.platform.events.BpmEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Three client moments Fabric has no callback for.
 *
 * <ul>
 *   <li>{@code setScreen} — NeoForge's {@code ScreenEvent.Init.Post}. The editor uses it to know a screen
 *       opened, and the smoke-run harness uses it to notice the title screen. Called
 *       {@code setScreenAndShow} from 26.1, and a mixin whose target has moved is not a warning: the
 *       game refuses to start with "could not find any targets matching".</li>
 *   <li>{@code startAttack} — NeoForge's {@code PlayerInteractEvent.LeftClickEmpty}. A left click at
 *       nothing, which is how the linker fires at range.</li>
 *   <li>{@code startUseItem} — NeoForge's {@code InteractionKeyMappingTriggered}, vetoable. Ctrl+use on a
 *       link opens the rename box instead of the wand's own use, and a veto must suppress the arm swing
 *       too or the arm waves for a use that never reached the server.</li>
 * </ul>
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    @Shadow
    public LocalPlayer player;

    @Shadow
    public HitResult hitResult;

    //? if >=26.1 {
    /*@Inject(method = "setScreenAndShow", at = @At("RETURN"))
    private void bpm$onSetScreen(Screen screen, CallbackInfo ci) {
        if (screen != null) {
            BpmEvents.INSTANCE.getScreenOpened().fire(screen);
        }
    }
    *///?} else {
    @Inject(method = "setScreen", at = @At("RETURN"))
    private void bpm$onSetScreen(Screen screen, CallbackInfo ci) {
        if (screen != null) {
            BpmEvents.INSTANCE.getScreenOpened().fire(screen);
        }
    }
    //?}

    @Inject(method = "startAttack", at = @At("HEAD"))
    private void bpm$onStartAttack(CallbackInfoReturnable<Boolean> cir) {
        // "Empty" means the click hit nothing: a block or entity hit goes down the normal paths, which
        // AttackBlockCallback and AttackEntityCallback already cover.
        if (this.player != null && (this.hitResult == null || this.hitResult.getType() == HitResult.Type.MISS)) {
            BpmEvents.INSTANCE.getLeftClickEmpty().fire(this.player);
        }
    }

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void bpm$onStartUseItem(CallbackInfo ci) {
        if (this.player != null && !BpmEvents.INSTANCE.getUseItemPressed().fire(this.player)) {
            // Cancelling here stops both the interaction and the swing, which is what the veto means.
            ci.cancel();
        }
    }
}
