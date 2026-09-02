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

/**
 * Whether a control key is held.
 *
 * `Screen.hasControlDown()` is gone at 1.21.9, and it was the wrong thing to ask anyway: this is a WORLD
 * interaction, not a screen one, and there is no screen open when it is asked. Reading the two keys is
 * the same answer with none of that borrowed context.
 */
fun ctrlHeld(): Boolean =
    isKeyHeld(org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL) || isKeyHeld(org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_CONTROL)

/**
 * Translate and scale the GUI, draw, and put it back.
 *
 * The GUI's transform stack stopped being a `PoseStack` at 1.21.9 and became a JOML `Matrix3x2fStack` --
 * two dimensions, because a GUI never had a third -- so `pushPose`/`popPose` became
 * `pushMatrix`/`popMatrix` and the translate and scale lost their z.
 *
 * A shared wrapper over the two stacks would be a lie (the world's stack really is three-dimensional and
 * still a PoseStack), which is why the seam is this instead: the only two things the panel ever does to
 * that stack, named as one operation. Twenty lines against a type the caller then never mentions.
 */
inline fun guiScaled(g: net.minecraft.client.gui.GuiGraphics, x: Float, y: Float, scale: Float, body: () -> Unit) {
    val pose = g.pose()
    //? if >=1.21.6 {
    /*pose.pushMatrix()
    pose.translate(x, y)
    pose.scale(scale, scale)
    body()
    pose.popMatrix()
    *///?} else {
    pose.pushPose()
    pose.translate(x, y, 0f)
    pose.scale(scale, scale, 1f)
    body()
    pose.popPose()
    //?}
}

/**
 * The GLFW handle of the game window.
 *
 * `Window.getWindow()` became the record-style `handle()` at 1.21.9. Same long, and the ImGui host needs
 * it to ask GLFW about modifier keys directly.
 */
fun windowHandle(): Long {
    val window = net.minecraft.client.Minecraft.getInstance().window
    //? if >=1.21.9 {
    /*return window.handle()
    *///?} else {
    return window.window
    //?}
}

/**
 * Push out whatever Minecraft has batched for the GUI, so a raw draw lands on top of it rather than under.
 *
 * `GuiGraphics.flush()` is gone from 1.21.9, and there is nothing to replace it with: the GUI became an
 * EXTRACT phase that records draws into a `GuiRenderState` and renders them later, so there is no batch
 * in flight to flush at the moment a screen draws. Correct on that band is to do nothing.
 */
fun flushGui(g: net.minecraft.client.gui.GuiGraphics) {
    // From 1.21.9 there is nothing in flight to flush, so this is deliberately empty there.
    //? if <1.21.5 {
    g.flush()
    //?}
}

/**
 * Put the shared vertex buffer back to a known state after a foreign renderer has drawn.
 *
 * `BufferUploader.reset()` existed because the ImGui backend binds its own VAO and buffers behind
 * Minecraft's back, and vanilla cached which one it thought was bound. From 1.21.5 there is no such
 * cache and no such class -- Blaze3D owns its buffers through a `GpuDevice` and does not assume.
 */
fun resetVertexBuffers() {
    // From 1.21.5 there is no cached binding to correct, so this is deliberately empty there.
    //? if <1.21.5 {
    com.mojang.blaze3d.vertex.BufferUploader.reset()
    //?}
}

/**
 * Draw an item model in the world, into a buffer source.
 *
 * From 1.21.9 an item is drawn by resolving it to an `ItemStackRenderState` and SUBMITTING that to a
 * collector, and the level-render event this is called from is handed a `MultiBufferSource` and no
 * collector. The way through is [ImmediateCollector]: rather than find a collector, be one, and draw
 * what it is handed.
 *
 * Returns whether it drew, which is true on every band this mod builds for.
 */
fun drawWorldItem(
    pose: com.mojang.blaze3d.vertex.PoseStack,
    buffers: net.minecraft.client.renderer.MultiBufferSource,
    stack: net.minecraft.world.item.ItemStack,
    context: net.minecraft.world.item.ItemDisplayContext,
    light: Int,
    level: net.minecraft.world.level.Level,
    seed: Int,
): Boolean {
    //? if >=1.21.9 {
    /*val mc = net.minecraft.client.Minecraft.getInstance()
    val state = net.minecraft.client.renderer.item.ItemStackRenderState()
    mc.itemModelResolver.updateForTopItem(state, stack, context, level as? net.minecraft.client.multiplayer.ClientLevel, null, seed)
    state.submit(pose, ImmediateCollector(buffers), light, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, 0)
    return true
    *///?} else {
    net.minecraft.client.Minecraft.getInstance().itemRenderer.renderStatic(
        stack,
        context,
        light,
        net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,
        pose,
        buffers,
        level as? net.minecraft.client.multiplayer.ClientLevel,
        seed,
    )
    return true
    //?}
}

/**
 * Whether a screen still has to draw its own background.
 *
 * Until the 1.21.6 GUI rework `Screen.render` was called with nothing behind it and a screen that wanted
 * the world dimmed called `renderBackground` itself. From 1.21.6 the caller does it first --
 * `renderWithTooltipAndSubtitles` calls `renderBackground`, then `render` -- and the blur behind it may
 * only be requested ONCE a frame: a second call is an IllegalStateException, not a second blur.
 *
 * So the question is no longer "do I want a background" but "has one already happened". A screen that
 * does NOT want one overrides `renderBackground` to do nothing, which works on every band.
 */
val screenRendersOwnBackground: Boolean
    //? if >=1.21.6 {
    /*get() = false
    *///?} else {
    get() = true
    //?}

/**
 * Draw now, or at the very end of the frame.
 *
 * From 1.21.9 a `Screen` does not draw: it EXTRACTS. `Screen.render` records commands into a
 * `GuiRenderState` which is rendered afterwards, so a raw OpenGL draw made from inside `render` -- which
 * is what an ImGui backend does -- happens BEFORE the GUI is painted and is covered by it. The editor
 * was drawing every frame and being painted over every frame.
 *
 * So from 1.21.6 the frame is stashed and run after everything else. That
 * is the same move the plan calls for -- "move the ImGui frame out of Screen#render to the end of
 * Minecraft's frame" -- and NeoForge offers the moment as an event, so it costs no mixin.
 *
 * One closure at a time, deliberately: a screen renders once a frame, and a stale one left over from a
 * frame that did not run is a stale one to draw.
 */
private var pendingFrame: (() -> Unit)? = null

fun deferGuiDraw(draw: () -> Unit) {
    //? if >=1.21.6 {
    /*pendingFrame = draw
    *///?} else {
    draw()
    //?}
}


/** Draw whatever the last screen frame stashed, if anything. Called at the end of the frame. */
fun drawDeferredGui() {
    val frame = pendingFrame ?: return
    pendingFrame = null
    frame()
}
