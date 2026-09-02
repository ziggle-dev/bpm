package bpm.platform.client

/**
 * Where the camera is, this frame.
 *
 * `Camera` grew a record-style `position()` accessor at 1.21.9 and made the field behind it private, so
 * the Kotlin property that used to read `getPosition()` now resolves to something it may not touch. One
 * line either way, and the caller says what it wants rather than how to get it.
 *
 * Everything else this mod asks the camera -- `rotation()` in particular -- is spelled the same on every
 * band and is called directly.
 */
fun cameraPos(): net.minecraft.world.phys.Vec3 =
    //? if >=1.21.9 {
    /*net.minecraft.client.Minecraft.getInstance().gameRenderer.mainCamera.position()
    *///?} else {
    net.minecraft.client.Minecraft.getInstance().gameRenderer.mainCamera.position
    //?}
