package bpm.client.render

import bpm.Bpm
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import bpm.platform.ResourceLocation
import software.bernie.geckolib.animatable.GeoAnimatable
import bpm.platform.GeoRenderer

/**
 * GeckoLib's glow layer, minus its one sharp edge: a `_glowmask` with no visible pixel (an "off" texture with
 * nothing lit) makes [AutoGlowingGeoLayer] throw while registering the emissive texture and crashes the client
 * the first time the model is drawn — including in the creative tab. This layer looks at the mask first and
 * skips the glow when there is nothing to glow; a missing mask is left to GeckoLib (it may still carry glow
 * sections in the base texture's mcmeta).
 */
class GlowLayer<T : GeoAnimatable>(renderer: GeoRenderer<T>) : bpm.platform.client.GlowLayerBase<T>(renderer) {
    override fun glows(animatable: T): Boolean = Glowmasks.glows(getTextureResource(animatable))
}

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
