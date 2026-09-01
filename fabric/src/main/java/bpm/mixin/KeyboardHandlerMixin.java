package bpm.mixin;

import bpm.platform.events.BpmEvents;
import bpm.platform.events.RawKey;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Raw key presses, before any key binding sees them.
 *
 * NeoForge fires {@code InputEvent.Key}, which it documents as uncancellable — so the seam's veto has
 * never meant "stop the press reaching the game" on that loader either; {@code Keys} clears the mapping's
 * own down-state instead. This mixin keeps that contract rather than improving on it: firing the hook and
 * ignoring the answer means the editor behaves identically on both loaders, which is worth more than
 * Fabric being able to do slightly better here.
 */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {

    @Inject(method = "keyPress", at = @At("HEAD"))
    private void bpm$onKeyPress(long window, int key, int scancode, int action, int modifiers, CallbackInfo ci) {
        BpmEvents.INSTANCE.getRawKey().fire(new RawKey(key, scancode, action, modifiers));
    }
}
