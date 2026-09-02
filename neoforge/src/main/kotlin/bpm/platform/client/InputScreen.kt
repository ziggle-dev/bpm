package bpm.platform.client

import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * A screen whose input arrives the same way on every band.
 *
 * 1.21.9 wrapped the loose arguments of the four input callbacks in records: `keyPressed(int, int, int)`
 * became `keyPressed(KeyEvent)`, `charTyped(char, int)` became `charTyped(CharacterEvent)`, and
 * `mouseClicked(double, double, int)` became `mouseClicked(MouseButtonEvent, boolean)` -- the extra flag
 * saying whether this is a double click, which nothing here has ever wanted to know.
 *
 * The arguments are the same facts either way, so the seam unwraps them and the screens keep the bodies
 * they had. Each hook answers "did I handle it"; false hands the event on to vanilla, which is what the
 * old `return super.keyPressed(...)` tails were saying.
 *
 * `mouseScrolled` is here for symmetry only -- its signature has not changed since 1.21.2 and it is not
 * switched.
 */
abstract class InputScreen(title: Component) : Screen(title) {

    /** A mouse button went down at [x], [y]. */
    protected open fun onMouseDown(x: Double, y: Double, button: Int): Boolean = false

    /** The wheel moved. [dy] is the usual vertical notch count. */
    protected open fun onScroll(x: Double, y: Double, dx: Double, dy: Double): Boolean = false

    /** A key went down. */
    protected open fun onKeyDown(key: Int, scan: Int, modifiers: Int): Boolean = false

    /** A character was typed. */
    protected open fun onCharTyped(codePoint: Char, modifiers: Int): Boolean = false

    /** A mouse button came up. */
    protected open fun onMouseUp(x: Double, y: Double, button: Int): Boolean = false

    /** The mouse moved with a button held. */
    protected open fun onMouseDrag(x: Double, y: Double, button: Int, dx: Double, dy: Double): Boolean = false

    /** A key came up. */
    protected open fun onKeyUp(key: Int, scan: Int, modifiers: Int): Boolean = false

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean =
        onScroll(mouseX, mouseY, scrollX, scrollY) || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)

    //? if >=1.21.9 {
    /*override fun mouseClicked(event: net.minecraft.client.input.MouseButtonEvent, doubleClick: Boolean): Boolean =
        onMouseDown(event.x(), event.y(), event.button()) || super.mouseClicked(event, doubleClick)

    override fun mouseReleased(event: net.minecraft.client.input.MouseButtonEvent): Boolean =
        onMouseUp(event.x(), event.y(), event.button()) || super.mouseReleased(event)

    override fun mouseDragged(event: net.minecraft.client.input.MouseButtonEvent, dragX: Double, dragY: Double): Boolean =
        onMouseDrag(event.x(), event.y(), event.button(), dragX, dragY) || super.mouseDragged(event, dragX, dragY)

    override fun keyPressed(event: net.minecraft.client.input.KeyEvent): Boolean =
        onKeyDown(event.key(), event.scancode(), event.modifiers()) || super.keyPressed(event)

    override fun keyReleased(event: net.minecraft.client.input.KeyEvent): Boolean =
        onKeyUp(event.key(), event.scancode(), event.modifiers()) || super.keyReleased(event)

    override fun charTyped(event: net.minecraft.client.input.CharacterEvent): Boolean =
        onCharTyped(event.codepoint().toChar(), event.modifiers()) || super.charTyped(event)
    *///?} else {
    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean =
        onMouseDown(mouseX, mouseY, button) || super.mouseClicked(mouseX, mouseY, button)

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean =
        onMouseUp(mouseX, mouseY, button) || super.mouseReleased(mouseX, mouseY, button)

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean =
        onMouseDrag(mouseX, mouseY, button, dragX, dragY) || super.mouseDragged(mouseX, mouseY, button, dragX, dragY)

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean =
        onKeyDown(keyCode, scanCode, modifiers) || super.keyPressed(keyCode, scanCode, modifiers)

    override fun keyReleased(keyCode: Int, scanCode: Int, modifiers: Int): Boolean =
        onKeyUp(keyCode, scanCode, modifiers) || super.keyReleased(keyCode, scanCode, modifiers)

    override fun charTyped(codePoint: Char, modifiers: Int): Boolean =
        onCharTyped(codePoint, modifiers) || super.charTyped(codePoint, modifiers)
    //?}
}

/**
 * A key binding in this mod's own category.
 *
 * The category was a translation key until 1.21.9 and is now a `KeyMapping.Category` record registered
 * by id, which is the same string said in a type. The registration is idempotent per id, so building
 * the mapping is the only moment either band needs.
 */
fun bpmKeyMapping(name: String, key: Int): net.minecraft.client.KeyMapping =
    //? if >=1.21.9 {
    /*net.minecraft.client.KeyMapping(
        name,
        com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM,
        key,
        net.minecraft.client.KeyMapping.Category.register(bpm.platform.ResourceLocation.fromNamespaceAndPath(bpm.Bpm.ID, "main")),
    )
    *///?} else {
    net.minecraft.client.KeyMapping(name, com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM, key, "key.categories.bpm")
    //?}

/** Whether [key] is held on the game window. `isKeyDown` takes the window itself rather than its handle. */
fun isKeyHeld(key: Int): Boolean {
    val window = net.minecraft.client.Minecraft.getInstance().window
    //? if >=1.21.9 {
    /*return com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, key)
    *///?} else {
    return com.mojang.blaze3d.platform.InputConstants.isKeyDown(window.window, key)
    //?}
}
