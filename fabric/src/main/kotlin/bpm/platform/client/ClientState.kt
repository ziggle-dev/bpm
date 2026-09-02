package bpm.platform.client

/**
 * The camera, this frame -- where it is and which way it faces.
 *
 * Two changes a band apart, each of which stops a plain property read from working.
 *
 * At 1.21.9 `Camera` grew a record-style `position()` and made the field behind it private. At 26.1
 * `GameRenderer` did the same to `mainCamera`: the field is private and a public `mainCamera()` sits
 * beside it, so from that band even REACHING the camera has to be spelled as a call. Kotlin resolves a
 * same-named field ahead of a method, which is what turns both of these into errors rather than
 * deprecation warnings -- the same trap `AbstractTexture.texture` sprang one band earlier.
 */

private fun camera(): net.minecraft.client.Camera =
    //? if >=26.1 {
    /*net.minecraft.client.Minecraft.getInstance().gameRenderer.mainCamera()
    *///?} else {
    net.minecraft.client.Minecraft.getInstance().gameRenderer.mainCamera
    //?}

fun cameraPos(): net.minecraft.world.phys.Vec3 =
    //? if >=1.21.9 {
    /*camera().position()
    *///?} else {
    camera().position
    //?}

/** Which way the camera faces -- what a world label is turned by so it reads flat to the player. */
fun cameraRotation(): org.joml.Quaternionf = camera().rotation()

/*
 * The current screen, which 26.1 moved off `Minecraft`.
 *
 * `Minecraft.screen` and `Minecraft.setScreen` are gone; the screen belongs to `Gui` now, reached as
 * `gui.screen()` and set through `Minecraft.setScreenAndShow`. And `Gui` repeats the field-beside-method
 * pattern -- a private `screen` field next to a public `screen()` -- so this has to be spelled as a call
 * for the same reason the camera above does.
 *
 * These live here rather than in `GuiTypes` because they are about the client's state, not about a
 * drawing surface, and every caller of them is asking "is a screen open" or "open this one".
 */

/** The screen the client is showing, or null when the player is in the world. */
fun currentScreen(): net.minecraft.client.gui.screens.Screen? =
    //? if >=26.1 {
    /*net.minecraft.client.Minecraft.getInstance().gui.screen()
    *///?} else {
    net.minecraft.client.Minecraft.getInstance().screen
    //?}

/**
 * Show a screen.
 *
 * Not nullable, because 26.1's `setScreenAndShow` is not: closing a screen is `onClose` there rather
 * than a null screen. Nothing in this mod closed one that way, so the narrower signature costs nothing.
 */
fun openScreen(screen: net.minecraft.client.gui.screens.Screen) {
    //? if >=26.1 {
    /*net.minecraft.client.Minecraft.getInstance().setScreenAndShow(screen)
    *///?} else {
    net.minecraft.client.Minecraft.getInstance().setScreen(screen)
    //?}
}

/**
 * Whether the player has hidden the HUD (F1).
 *
 * `Options.hideGui` moved onto the HUD itself at 26.1, which owns the question -- and behind a private
 * field with a public `isHidden()` beside it, the same shape as the camera and the screen above.
 */
fun hudHidden(): Boolean {
    val mc = net.minecraft.client.Minecraft.getInstance()
    //? if >=26.1 {
    /*return mc.gui.hud.isHidden()
    *///?} else {
    return mc.options.hideGui
    //?}
}

/**
 * The camera as a render pass sees it, which moved to `renderer.state.level` at 26.1.
 *
 * Aliased rather than spelled out because it appears only in `submit` signatures -- nothing in this mod
 * reads it, it is passed straight back to `super` -- so the package it lives in is not something the
 * renderers are entitled to know.
 */
//? if >=26.1 {
/*typealias CameraRenderState = net.minecraft.client.renderer.state.level.CameraRenderState
*///?} elif >=1.21.9 {
/*typealias CameraRenderState = net.minecraft.client.renderer.state.CameraRenderState
*///?}

/**
 * Close whatever screen is open.
 *
 * Not `openScreen(null)`: 26.1's `setScreenAndShow` is non-null, and closing a screen is asking the
 * screen to close -- which is what pressing Escape does, and what runs its own teardown.
 */
fun closeScreen() {
    //? if >=26.1 {
    /*currentScreen()?.onClose()
    *///?} else {
    net.minecraft.client.Minecraft.getInstance().setScreen(null)
    //?}
}

/** The frame buffer the game is drawing into, which moved onto the game renderer at 26.1. */
fun mainRenderTarget(): com.mojang.blaze3d.pipeline.RenderTarget {
    val mc = net.minecraft.client.Minecraft.getInstance()
    //? if >=26.1 {
    /*return mc.gameRenderer.mainRenderTarget()
    *///?} else {
    return mc.mainRenderTarget
    //?}
}
