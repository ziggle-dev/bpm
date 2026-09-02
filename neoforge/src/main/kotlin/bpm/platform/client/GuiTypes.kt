package bpm.platform.client

/*
 * The GUI types 26.1 renamed.
 *
 * `GuiGraphics` became `GuiGraphicsExtractor`, and the name is the honest one: from the 1.21.6 rework a
 * screen has not been drawing, it has been describing what to draw, and 26.1 finished the thought by
 * renaming the surface after what it does. The API is otherwise the one this mod already uses -- `pose()`,
 * `fill`, `blit`, `enableScissor` -- so an alias carries the whole of it.
 *
 * `renderItem` is NOT aliased here, because it is not a rename: an item goes through a picture-in-picture
 * render state now. `drawString`/`drawCenteredString` ARE renames -- to `text`/`centeredText`, same
 * arguments -- so they get extensions below rather than a seam of their own.
 */
/*
 * The light constants did NOT come across as a rename, and an earlier note here said they did.
 *
 * `LightTexture` was two things at once: the lightmap TEXTURE, and the home of the packed-light constants
 * and helpers everything else used. 26.1 split them. The texture became `Lightmap` -- which is why an
 * alias looked right -- but it carries none of the constants; those went to `LightCoordsUtil`, together
 * with `LevelRenderer.getLightColor`, under the name `getLightCoords`. So the seam is a value and a
 * function, not a type. Do not put the alias back.
 */

//? if >=26.1 {
/*typealias GuiGraphics = net.minecraft.client.gui.GuiGraphicsExtractor

/** Full block light and full sky light, packed -- what a self-lit thing is drawn at. */
val FULL_BRIGHT: Int = net.minecraft.util.LightCoordsUtil.FULL_BRIGHT

/** The packed light at a position, for something drawn in the world at that position. */
fun lightAt(level: net.minecraft.world.level.BlockAndLightGetter, pos: net.minecraft.core.BlockPos): Int =
    net.minecraft.util.LightCoordsUtil.getLightCoords(level, pos)

fun GuiGraphics.drawText(font: net.minecraft.client.gui.Font, s: String, x: Int, y: Int, colour: Int, shadow: Boolean) =
    text(font, s, x, y, colour, shadow)

fun GuiGraphics.drawText(font: net.minecraft.client.gui.Font, s: net.minecraft.network.chat.Component, x: Int, y: Int, colour: Int, shadow: Boolean) =
    text(font, s, x, y, colour, shadow)

fun GuiGraphics.drawText(font: net.minecraft.client.gui.Font, s: net.minecraft.util.FormattedCharSequence, x: Int, y: Int, colour: Int, shadow: Boolean) =
    text(font, s, x, y, colour, shadow)

fun GuiGraphics.drawCenteredText(font: net.minecraft.client.gui.Font, s: String, x: Int, y: Int, colour: Int) =
    centeredText(font, s, x, y, colour)

fun GuiGraphics.drawCenteredText(font: net.minecraft.client.gui.Font, s: net.minecraft.network.chat.Component, x: Int, y: Int, colour: Int) =
    centeredText(font, s, x, y, colour)

fun GuiGraphics.drawCenteredText(font: net.minecraft.client.gui.Font, s: net.minecraft.util.FormattedCharSequence, x: Int, y: Int, colour: Int) =
    centeredText(font, s, x, y, colour)

/** `renderItem`/`renderItemDecorations` lost their `render` prefix; the arguments are unchanged. */
fun GuiGraphics.drawItem(stack: net.minecraft.world.item.ItemStack, x: Int, y: Int) = item(stack, x, y)

fun GuiGraphics.drawItemDecorations(font: net.minecraft.client.gui.Font, stack: net.minecraft.world.item.ItemStack, x: Int, y: Int) =
    itemDecorations(font, stack, x, y)

fun GuiGraphics.drawItemDecorations(font: net.minecraft.client.gui.Font, stack: net.minecraft.world.item.ItemStack, x: Int, y: Int, text: String) =
    itemDecorations(font, stack, x, y, text)
*///?} else {
typealias GuiGraphics = net.minecraft.client.gui.GuiGraphics

val FULL_BRIGHT: Int = net.minecraft.client.renderer.LightTexture.FULL_BRIGHT

fun lightAt(level: net.minecraft.world.level.BlockAndTintGetter, pos: net.minecraft.core.BlockPos): Int =
    net.minecraft.client.renderer.LevelRenderer.getLightColor(level, pos)

fun GuiGraphics.drawText(font: net.minecraft.client.gui.Font, s: String, x: Int, y: Int, colour: Int, shadow: Boolean) =
    drawString(font, s, x, y, colour, shadow)

fun GuiGraphics.drawText(font: net.minecraft.client.gui.Font, s: net.minecraft.network.chat.Component, x: Int, y: Int, colour: Int, shadow: Boolean) =
    drawString(font, s, x, y, colour, shadow)

fun GuiGraphics.drawText(font: net.minecraft.client.gui.Font, s: net.minecraft.util.FormattedCharSequence, x: Int, y: Int, colour: Int, shadow: Boolean) =
    drawString(font, s, x, y, colour, shadow)

fun GuiGraphics.drawCenteredText(font: net.minecraft.client.gui.Font, s: String, x: Int, y: Int, colour: Int) =
    drawCenteredString(font, s, x, y, colour)

fun GuiGraphics.drawCenteredText(font: net.minecraft.client.gui.Font, s: net.minecraft.network.chat.Component, x: Int, y: Int, colour: Int) =
    drawCenteredString(font, s, x, y, colour)

fun GuiGraphics.drawCenteredText(font: net.minecraft.client.gui.Font, s: net.minecraft.util.FormattedCharSequence, x: Int, y: Int, colour: Int) =
    drawCenteredString(font, s, x, y, colour)

fun GuiGraphics.drawItem(stack: net.minecraft.world.item.ItemStack, x: Int, y: Int) = renderItem(stack, x, y)

fun GuiGraphics.drawItemDecorations(font: net.minecraft.client.gui.Font, stack: net.minecraft.world.item.ItemStack, x: Int, y: Int) =
    renderItemDecorations(font, stack, x, y)

fun GuiGraphics.drawItemDecorations(font: net.minecraft.client.gui.Font, stack: net.minecraft.world.item.ItemStack, x: Int, y: Int, text: String) =
    renderItemDecorations(font, stack, x, y, text)
//?}
