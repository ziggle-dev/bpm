package bpm.platform.client

/*
 * Item thumbnails for the editor's picker, on the bands where a render target stopped being something
 * you can bind and draw into.
 *
 * Until 1.21.5 this was four calls: clear the target, bind it, let `GuiGraphics.renderItem` draw, unbind.
 * `RenderTarget` has none of those any more -- no `bindWrite`, no `clear`, no `setClearColor` -- because
 * a target is now written by opening a render pass on its texture. So the item's geometry has to be got
 * into a pass of our own, which is what this file does.
 *
 * The trick is that `MultiBufferSource` is a ONE-METHOD interface. Vanilla's item renderer will happily
 * emit a model's quads into whatever consumer it is handed, so it is handed one that writes them all
 * into a single buffer regardless of which render type asked. That skips the whole question of
 * reproducing vanilla's render types against a different output target, which is the part that has no
 * public answer.
 *
 * Lighting is skipped on purpose rather than by omission. The consumer accepts the light and overlay
 * attributes and drops them, writing POSITION_TEX_COLOR, so the mesh draws with the same flat pipeline
 * the ImGui backend uses -- no lightmap texture to bind, no entity shader, no `Sampler2`. A GUI item is
 * lit by a fixed rig anyway; flat and correct beats lit and unreachable.
 */

//? if >=1.21.6 {
/*/**
 * Draw [stack] into [target] as a thumbnail, in a 16-unit space looked at the way the GUI looks at an
 * item.
 *
 * False when the atlas is not ready, which is normal for the first frames after a resource reload; the
 * slot simply stays unrendered and is asked for again.
 */
fun renderItemPreview(
    mc: net.minecraft.client.Minecraft,
    target: com.mojang.blaze3d.pipeline.RenderTarget,
    stack: net.minecraft.world.item.ItemStack,
): Boolean {
    val colour = previewColour(target) ?: return false
    val depth = previewDepth(target)
    val atlas = blockAtlasTexture(mc) ?: return false

    val allocator = com.mojang.blaze3d.vertex.ByteBufferBuilder(4096)
    try {
        val builder = previewBuilder(allocator)

        // The transform vanilla's GuiGraphics.renderItem applies: centre the item in its 16-unit cell and
        // scale it up, with Y flipped because the model is built Y-up and a GUI counts downwards.
        val pose = com.mojang.blaze3d.vertex.PoseStack()
        pose.translate(8.0, 8.0, 0.0)
        pose.scale(16f, -16f, 16f)
        emitItemQuads(mc, stack, pose, FlatConsumer(builder))

        val mesh = builder.build() ?: return false
        mesh.use { drawPreviewMesh(it, colour, depth, atlas, target.width, target.height) }
    } finally {
        allocator.close()
    }
    return true
}
*///?}
