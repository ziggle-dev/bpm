package bpm.platform.client

/*
 * Somewhere to draw from a world-render event, where nobody hands you a collector.
 *
 * The mod's own effects -- the rift, the assembler beam, the energy arc, the transfer motes -- are drawn
 * from `RenderLevelStageEvent`, outside the level's own submission. Until 26.1 that meant asking the game
 * for its buffer source and drawing into it. `MultiBufferSource` does not exist at 26.1, and neither the
 * event nor the game renderer will hand out the frame's collector, so on that band the drawing has to be
 * put through a pass of its own.
 *
 * It does not have to reimplement anything, which is the good news. `RenderType.prepare()` returns a
 * `PreparedRenderType` carrying the pipeline, the output target, the dynamic transforms, the scissor state
 * AND the textures, with `drawFromBuffer` to put geometry through all of it. A render type still means
 * exactly what it means everywhere else in the game; only the plumbing to reach it changed.
 */

/**
 * A [WorldDraw] that can also hand back a consumer to write into.
 *
 * [WorldDraw] deliberately does not: a renderer that SUBMITS cannot return one, because the draw happens
 * later in a pass it does not control. A drawer that owns its own buffers can, and all three of these do
 * -- including the 26.1 one, which batches per render type exactly as a buffer source does and puts each
 * batch through a pass when [flush] is called. Batching is not an optimisation there so much as the thing
 * that makes a consumer meaningful at all.
 */
interface ImmediateDraw : WorldDraw {

    /** Somewhere to write geometry of [kind]. Valid until [flush]. */
    fun consumer(kind: bpm.platform.RenderType): com.mojang.blaze3d.vertex.VertexConsumer
}

//? if >=26.1 {
/*/** Batches per render type, then puts each batch through a pass of its own. */
internal class PreparedDraw : ImmediateDraw {

    private val allocator = com.mojang.blaze3d.vertex.ByteBufferBuilder(4096)
    private val builders = LinkedHashMap<bpm.platform.RenderType, com.mojang.blaze3d.vertex.BufferBuilder>()

    override fun consumer(kind: bpm.platform.RenderType): com.mojang.blaze3d.vertex.VertexConsumer =
        builders.getOrPut(kind) {
            com.mojang.blaze3d.vertex.BufferBuilder(allocator, kind.primitiveTopology(), kind.format())
        }

    override fun into(
        poseStack: com.mojang.blaze3d.vertex.PoseStack,
        kind: bpm.platform.RenderType,
        draw: (com.mojang.blaze3d.vertex.PoseStack.Pose, com.mojang.blaze3d.vertex.VertexConsumer) -> Unit,
    ) {
        draw(poseStack.last(), consumer(kind))
    }

    // Text and items are NOT drawn on this band yet, and they no-op rather than throwing because this runs
    // once a frame: a throw would either crash the client or be swallowed into a silent disable, and
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
        seed: Int,
    ) = Unit

    override fun flush() {
        val device = com.mojang.blaze3d.systems.RenderSystem.getDevice()
        for ((kind, builder) in builders) {
            val mesh = builder.build() ?: continue
            mesh.use { built ->
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
        }
        builders.clear()
    }
}
*///?} elif >=1.21.9 {
/*/**
 * The same thing [BufferedDraw] is on the older bands, written out here because that class lives inside
 * the GeckoLib-4 and GeckoLib-5.1 arms and this band has neither. MultiBufferSource is still perfectly
 * present here -- it goes at 26.1 -- so the drawing is unchanged.
 */
internal class SourceDraw(private val bufferSource: net.minecraft.client.renderer.MultiBufferSource) : ImmediateDraw {

    override fun consumer(kind: bpm.platform.RenderType): com.mojang.blaze3d.vertex.VertexConsumer =
        bufferSource.getBuffer(kind)

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
        seed: Int,
    ) {
        val level = net.minecraft.client.Minecraft.getInstance().level ?: return
        drawWorldItem(poseStack, bufferSource, stack, context, packedLight, level, seed)
    }

    override fun flush() {
        (bufferSource as? net.minecraft.client.renderer.MultiBufferSource.BufferSource)?.endBatch()
    }
}
*///?} else {
/** [BufferedDraw] already does all of this; this only adds the consumer. */
internal class SourceDraw(private val bufferSource: net.minecraft.client.renderer.MultiBufferSource) :
    ImmediateDraw, WorldDraw by BufferedDraw(bufferSource) {

    override fun consumer(kind: bpm.platform.RenderType): com.mojang.blaze3d.vertex.VertexConsumer =
        bufferSource.getBuffer(kind)
}
//?}

/**
 * The drawer for world effects on this band.
 *
 * Below 26.1 this is the game's own buffer source, batched and ended as it always was; from 26.1 it is
 * this mod's own batching over prepared render types. Callers hold an [ImmediateDraw] either way and never
 * name a buffer source, which is what lets the same effect code run on both.
 */
fun immediateWorldDraw(): ImmediateDraw =
    //? if >=26.1 {
    /*PreparedDraw()
    *///?} else {
    SourceDraw(net.minecraft.client.Minecraft.getInstance().renderBuffers().bufferSource())
    //?}
