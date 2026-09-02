package bpm.client.mc

import bpm.catalog.values.RegistryIds
import bpm.client.editor.FluidLooks
import bpm.world.ControllerStores
import bpm.world.ModFluids
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.TextureAtlas

/**
 * How the buffer panel colours and names a fluid: the fluid type's tint when it has one (water, liquid
 * experience), else the average of its still texture (lava, most modded fluids) — sampled once per fluid.
 */
object FluidLookProvider : FluidLooks {
    private val colours = HashMap<String, Int?>()

    override fun colour(fluidId: String): Int? = colours.getOrPut(fluidId) { compute(fluidId) }

    override fun labelOf(fluidId: String): String? = RegistryIds.fluid(fluidId)?.let { bpm.platform.world.Fluids.displayName(it).string }

    override fun describe(fluidId: String, amountMb: Int): String? =
        if (fluidId == ModFluids.ID) "${amountMb / ControllerStores.XP_MB_PER_POINT} experience points" else null

    private fun compute(fluidId: String): Int? {
        val fluid = RegistryIds.fluid(fluidId) ?: return null
        val look = bpm.platform.client.FluidVisuals.of(fluid)
        val tint = look.tint
        if ((tint and 0xFFFFFF) != 0xFFFFFF) return tint or (0xFF shl 24)
        return runCatching {
            val contents = bpm.platform.client.blockSprite(look.still).contents()
            val w = contents.width()
            val h = contents.height()
            // Sampled from the texture on disk rather than from the sprite. The sprite's own image is not
            // reachable on every version -- it is private before 1.21.9 and, from 1.21.9, `originalImage`
            // is gone entirely in favour of a package-private mip array -- and widening a field to read it
            // is a build-level dependency on one version's internals. These are the same pixels: the atlas
            // is stitched from this file, and reading only the first w*h of it takes the first frame of an
            // animated fluid exactly as the sprite's own frame size already does.
            val png = look.still.withPath { "textures/$it.png" }
            Minecraft.getInstance().resourceManager.open(png).use { stream ->
                com.mojang.blaze3d.platform.NativeImage.read(stream).use { image ->
                    var r = 0L
                    var g = 0L
                    var b = 0L
                    var n = 0L
                    for (y in 0 until h) for (x in 0 until w) {
                        val abgr = bpm.platform.pixel(image, x, y)
                        if ((abgr ushr 24) == 0) continue
                        r += abgr and 0xFF
                        g += (abgr shr 8) and 0xFF
                        b += (abgr shr 16) and 0xFF
                        n++
                    }
                    if (n == 0L) null else (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
                }
            }
        }.getOrNull()
    }

    fun clear() = colours.clear()
}
