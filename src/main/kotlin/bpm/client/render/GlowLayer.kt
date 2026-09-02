package bpm.client.render

import bpm.Bpm
import bpm.platform.ResourceLocation
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft

/*
 * The glow LAYER now lives in `bpm.platform.client`, per loader and per band.
 *
 * Its generic arity changed at GeckoLib 5 -- `GeoRenderLayer<T>` became `GeoRenderLayer<T, O, R>` -- and
 * a shared file cannot name a type whose parameter count depends on the version. What stays here is the
 * part that never changed and is the whole reason the layer is subclassed at all: the survey below.
 */

/** Which textures have a glowmask worth drawing; answered once per texture until resources reload. */
object Glowmasks {
    private val cache = HashMap<ResourceLocation, Boolean>()

    fun glows(texture: ResourceLocation): Boolean = cache.getOrPut(texture) { inspect(texture) }

    /** Forget everything — the masks may have changed. */
    fun invalidate() = cache.clear()

    private fun inspect(texture: ResourceLocation): Boolean {
        val mask = texture.withPath { it.replace(".png", "_glowmask.png") }
        val resource = Minecraft.getInstance().resourceManager.getResource(mask).orElse(null) ?: return true
        return try {
            resource.open().use { NativeImage.read(it) }.use { hasVisiblePixel(it) }.also {
                if (!it) Bpm.LOGGER.info("glowmask {} has no visible pixel; the glow layer is skipped for {}", mask, texture)
            }
        } catch (e: Exception) {
            Bpm.LOGGER.warn("glowmask {} could not be read; the glow layer is skipped for {}", mask, texture, e)
            false
        }
    }

    fun hasVisiblePixel(image: NativeImage): Boolean {
        for (y in 0 until image.height) for (x in 0 until image.width) if (bpm.platform.pixel(image, x, y) ushr 24 != 0) return true
        return false
    }
}
