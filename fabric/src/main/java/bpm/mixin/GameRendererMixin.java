package bpm.mixin;

import bpm.platform.client.InputScreenKt;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The end of the frame, after the GUI has been painted.
 *
 * <p>From 1.21.6 the GUI is recorded during {@code Screen#render} and painted afterwards, so anything
 * that draws immediately — which is what an ImGui backend does — is covered by it. The editor draws
 * every frame and is painted over every frame, which looks like a blur sitting on top of it.
 * {@link bpm.platform.client.InputScreenKt#deferGuiDraw} stashes the frame instead, and something has to
 * run it once the GUI is done. NeoForge offers that moment as {@code RenderFrameEvent.Post}; Fabric has
 * no equivalent, so this is it.
 *
 * <p>{@code GameRenderer.render(DeltaTracker, boolean)} is the anchor because it is the same method on
 * every band this mod builds for — checked with javap against 1.21.8 and 1.21.11 — and its TAIL is after
 * the GUI and before the buffer swap. Anchoring on {@code Minecraft.runTick} instead would land after
 * the swap, which draws into the next frame.
 *
 * <p>Not version-gated, and it does not need to be: below 1.21.6 {@code deferGuiDraw} draws immediately
 * and never stashes anything, so this finds nothing pending and returns.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(method = "render", at = @At("TAIL"))
    private void bpm$drawDeferredGui(CallbackInfo ci) {
        InputScreenKt.drawDeferredGui();
    }
}
