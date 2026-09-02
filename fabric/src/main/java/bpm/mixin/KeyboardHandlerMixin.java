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
 *
 * <p>The signature changed at 1.21.9: the four loose key arguments became one {@code KeyEvent} record,
 * and {@code action} moved in front of it. This is the only one of the six mixins whose target moved --
 * every other target method and field has the same shape on 1.21.11 as on 1.21.1, checked with javap.
 *
 * <p>Worth remembering how this presents. A mixin descriptor is not checked by the compiler; it is
 * matched when the mixin is APPLIED, so a drift like this builds cleanly, passes every test, and then
 * kills the client during startup with {@code InvalidInjectionException}. Only running the game finds it.
 */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {

    //? if >=1.21.9 {
    /*@Inject(method = "keyPress", at = @At("HEAD"))
    private void bpm$onKeyPress(long window, int action, net.minecraft.client.input.KeyEvent event, CallbackInfo ci) {
        BpmEvents.INSTANCE.getRawKey().fire(new RawKey(event.key(), event.scancode(), action, event.modifiers()));
    }
    *///?} else {
    @Inject(method = "keyPress", at = @At("HEAD"))
    private void bpm$onKeyPress(long window, int key, int scancode, int action, int modifiers, CallbackInfo ci) {
        BpmEvents.INSTANCE.getRawKey().fire(new RawKey(key, scancode, action, modifiers));
    }
    //?}
}
