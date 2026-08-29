package bpm.client.mc

import bpm.catalog.values.RegistryIds
import bpm.client.editor.FluidLooks
import bpm.world.ControllerStores
import bpm.world.ModFluids
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.TextureAtlas
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions

/**
 * How the buffer panel colours and names a fluid: the fluid type's tint when it has one (water, liquid
 * experience), else the average of its still texture (lava, most modded fluids) — sampled once per fluid.
 */
object FluidLookProvider : FluidLooks {
    private val colours = HashMap<String, Int?>()

    override fun colour(fluidId: String): Int? = colours.getOrPut(fluidId) { compute(fluidId) }

    override fun labelOf(fluidId: String): String? = RegistryIds.fluid(fluidId)?.fluidType?.description?.string

    override fun describe(fluidId: String, amountMb: Int): String? =
        if (fluidId == ModFluids.ID) "${amountMb / ControllerStores.XP_MB_PER_POINT} experience points" else null

    private fun compute(fluidId: String): Int? {
        val fluid = RegistryIds.fluid(fluidId) ?: return null
        val ext = IClientFluidTypeExtensions.of(fluid)
        val tint = ext.tintColor
        if ((tint and 0xFFFFFF) != 0xFFFFFF) return tint or (0xFF shl 24)
        return runCatching {
            val sprite = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(ext.stillTexture)
            val image = sprite.contents().originalImage
            val w = sprite.contents().width()
            val h = sprite.contents().height()
            var r = 0L
            var g = 0L
            var b = 0L
            var n = 0L
            for (y in 0 until h) for (x in 0 until w) {
                val abgr = image.getPixelRGBA(x, y)
                if ((abgr ushr 24) == 0) continue
                r += abgr and 0xFF
                g += (abgr shr 8) and 0xFF
                b += (abgr shr 16) and 0xFF
                n++
            }
            if (n == 0L) null else (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
        }.getOrNull()
    }

    fun clear() = colours.clear()
}
