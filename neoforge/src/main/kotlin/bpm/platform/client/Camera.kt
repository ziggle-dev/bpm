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
