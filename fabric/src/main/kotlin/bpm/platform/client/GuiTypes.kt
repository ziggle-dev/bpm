package bpm.platform.client

/*
 * The GUI types 26.1 renamed.
 *
 * `GuiGraphics` became `GuiGraphicsExtractor`, and the name is the honest one: from the 1.21.6 rework a
 * screen has not been drawing, it has been describing what to draw, and 26.1 finished the thought by
 * renaming the surface after what it does. The API is otherwise the one this mod already uses -- `pose()`,
 * `fill`, `blit`, `enableScissor` -- so an alias carries the whole of it.
 *
 * Two things did NOT come across and are not aliased here, because they are not renames: `drawString` and
 * `renderItem` are gone from the surface entirely. Text goes through `Font.prepareText` and a submitted
 * prepared-text state, and an item through a picture-in-picture render state. Those have their own seams.
 */
//? if >=26.1 {
/*typealias GuiGraphics = net.minecraft.client.gui.GuiGraphicsExtractor

/** `LightTexture` became `Lightmap`; same constants, same job. */
typealias LightTexture = net.minecraft.client.renderer.Lightmap
*///?} else {
typealias GuiGraphics = net.minecraft.client.gui.GuiGraphics

typealias LightTexture = net.minecraft.client.renderer.LightTexture
//?}
