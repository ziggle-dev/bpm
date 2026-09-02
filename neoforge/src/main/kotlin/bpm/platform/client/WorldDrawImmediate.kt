package bpm.platform.client

/*
 * Somewhere to draw from a world-render event, where nobody hands you a collector.
 *
 * The mod's own effects -- the rift, the assembler beam, the energy arc, the transfer motes, the linker's
 * lines -- are drawn from `RenderLevelStageEvent`, outside the level's own submission. Until 26.1 that
 * meant asking the game for its buffer source and drawing into it. `MultiBufferSource` does not exist at
 * 26.1, and neither the event nor the game renderer will hand out the frame's collector, so on that band
 * the drawing has to open its own pass.
 *
 * It does not have to reimplement anything, though, which is the good news. `RenderType.prepare()` returns
 * a `PreparedRenderType` carrying the pipeline, the output target, the dynamic transforms, the scissor
 * state AND the textures, with `drawFromBuffer` to put geometry through all of it. So a render type still
 * means exactly what it means everywhere else in the game; only the plumbing to reach it changed.
 */

//? if >=26.1 {
/*/** [WorldDraw] that opens a pass per draw, because there is no buffer source to batch into. */
internal class PreparedDraw : WorldDraw {

    override fun into(
        poseStack: com.mojang.blaze3d.vertex.PoseStack,
        kind: bpm.platform.RenderType,
        draw: (com.mojang.blaze3d.vertex.PoseStack.Pose, com.mojang.blaze3d.vertex.VertexConsumer) -> Unit,
    ) {
        val allocator = com.mojang.blaze3d.vertex.ByteBufferBuilder(1536)
        try {
            val builder = com.mojang.blaze3d.vertex.BufferBuilder(allocator, kind.primitiveTopology(), kind.format())
            draw(poseStack.last(), builder)
            val mesh = builder.build() ?: return
            mesh.use { built ->
                val device = com.mojang.blaze3d.systems.RenderSystem.getDevice()
                val vertices = device.createBuffer(
                    { "bpm_world_draw" },
                    com.mojang.blaze3d.buffers.GpuBuffer.USAGE_VERTEX,
                    built.vertexBuffer(),
                )
                try {
                    val indices = com.mojang.blaze3d.systems.RenderSystem.getSequentialBuffer(kind.primitiveTopology())
                    val count = built.drawState().indexCount()
                    kind.prepare().drawFromBuffer(vertices, indices.getBuffer(count), indices.type(), 0, count, 1)
                } finally {
                    vertices.close()
                }
            }
        } finally {
            allocator.close()
        }
    }

    // Text and items are NOT drawn on this band yet, and they no-op rather than throwing because this runs
    // once a frame: a throw here would either crash the client or be swallowed into a silent disable, and
    // neither says more than nothing drawn does. Text wants `Font.prepareText` and a prepared-text state;
    // an item wants its quads routed the way `ItemPreview` routes them. Both are outstanding.
    override fun text(
        poseStack: com.mojang.blaze3d.vertex.PoseStack,
        text: net.minecraft.util.FormattedCharSequence,
        x: Float,
        y: Float,
        colour: Int,
        dropShadow: Boolean,
        mode: net.minecraft.client.gui.Font.DisplayMode,
        backgroundColour: Int,
        packedLight: Int,
    ) = Unit

    override fun item(
        poseStack: com.mojang.blaze3d.vertex.PoseStack,
        stack: net.minecraft.world.item.ItemStack,
        context: net.minecraft.world.item.ItemDisplayContext,
        packedLight: Int,
        packedOverlay: Int,
    ) = Unit

    override fun flush() = Unit
}
*///?}

/**
 * The drawer for world effects on this band.
 *
 * Below 26.1 this is the game's own buffer source, batched and ended as it always was; from 26.1 it is a
 * pass per draw. Callers hold a [WorldDraw] either way and never name a buffer source, which is what lets
 * the same effect code run on both.
 */
//? if >=1.21.9 <26.1 {
/*/**
 * The same thing [BufferedDraw] is on the older bands, written out here because that class lives inside
 * the GeckoLib-4 and GeckoLib-5.1 arms and this band has neither. MultiBufferSource is still perfectly
 * present here -- it goes at 26.1 -- so the drawing is unchanged.
 */
private class SourceDraw(private val bufferSource: net.minecraft.client.renderer.MultiBufferSource) : WorldDraw {

    override fun into(
        poseStack: com.mojang.blaze3d.vertex.PoseStack,
        kind: bpm.platform.RenderType,
        draw: (com.mojang.blaze3d.vertex.PoseStack.Pose, com.mojang.blaze3d.vertex.VertexConsumer) -> Unit,
    ) {
        draw(poseStack.last(), bufferSource.getBuffer(kind))
    }

    override fun text(
        poseStack: com.mojang.blaze3d.vertex.PoseStack,
        text: net.minecraft.util.FormattedCharSequence,
        x: Float,
        y: Float,
        colour: Int,
        dropShadow: Boolean,
        mode: net.minecraft.client.gui.Font.DisplayMode,
        backgroundColour: Int,
        packedLight: Int,
    ) {
        net.minecraft.client.Minecraft.getInstance().font.drawInBatch(
            text, x, y, colour, dropShadow, poseStack.last().pose(), bufferSource, mode, backgroundColour, packedLight,
        )
    }

    override fun item(
        poseStack: com.mojang.blaze3d.vertex.PoseStack,
        stack: net.minecraft.world.item.ItemStack,
        context: net.minecraft.world.item.ItemDisplayContext,
        packedLight: Int,
        packedOverlay: Int,
    ) {
        val mc = net.minecraft.client.Minecraft.getInstance()
        val level = mc.level ?: return
        drawWorldItem(poseStack, bufferSource, stack, context, packedLight, level, 0)
    }

    override fun flush() {
        (bufferSource as? net.minecraft.client.renderer.MultiBufferSource.BufferSource)?.endBatch()
    }
}
*///?}

fun immediateWorldDraw(): WorldDraw =
    //? if >=26.1 {
    /*PreparedDraw()
    *///?} elif >=1.21.9 {
    /*SourceDraw(net.minecraft.client.Minecraft.getInstance().renderBuffers().bufferSource())
    *///?} else {
    BufferedDraw(net.minecraft.client.Minecraft.getInstance().renderBuffers().bufferSource())
    //?}
