package bpm.platform.client

/*
 * The GPU half of an item thumbnail: the pipeline, the atlas, the quads and the offscreen draw.
 *
 * These were inside the ImGui backend's 1.21.6 arm, which is why previews stopped at 1.21.8 -- not
 * because the band could not do it, but because the code was in a place 1.21.9 could not see. They live
 * here now, with arms of their own.
 *
 * The two arms are the same body apart from two functions. `blockAtlasTexture` reaches the atlas through
 * a getter from 1.21.9, and `emitItemQuads` submits rather than renders. Everything else -- the pipeline,
 * the projection, the pass -- is identical, and is EXTRACTED from the working 1.21.6 code rather than
 * retyped, so the two cannot drift by transcription.
 *
 * Bounded below 26.1: that band has no `MultiBufferSource` to hand the quads through and rebuilds the
 * pipeline against bind groups, so it needs a third body that does not exist yet. Previews fall back to
 * labels there, as they did everywhere before this.
 */

//? if >=1.21.9 <26.1 {
/*/** Writes a model's quads as POSITION_TEX_COLOR, dropping the attributes a flat draw does not read. */
internal class FlatConsumer(
    private val out: com.mojang.blaze3d.vertex.VertexConsumer,
) : com.mojang.blaze3d.vertex.VertexConsumer {

    override fun addVertex(x: Float, y: Float, z: Float): com.mojang.blaze3d.vertex.VertexConsumer {
        out.addVertex(x, y, z)
        return this
    }

    override fun setColor(r: Int, g: Int, b: Int, a: Int): com.mojang.blaze3d.vertex.VertexConsumer {
        out.setColor(r, g, b, a)
        return this
    }

    override fun setUv(u: Float, v: Float): com.mojang.blaze3d.vertex.VertexConsumer {
        out.setUv(u, v)
        return this
    }

    // Overlay, lightmap and normal: accepted and dropped. See the note at the top of the file.
    override fun setUv1(u: Int, v: Int): com.mojang.blaze3d.vertex.VertexConsumer = this

    override fun setUv2(u: Int, v: Int): com.mojang.blaze3d.vertex.VertexConsumer = this

    override fun setNormal(x: Float, y: Float, z: Float): com.mojang.blaze3d.vertex.VertexConsumer = this

    // Abstract from 1.21.9, and dropped for the same reason as the rest: a flat thumbnail reads neither
    // a packed colour nor a line width.
    override fun setColor(argb: Int): com.mojang.blaze3d.vertex.VertexConsumer = this

    override fun setLineWidth(width: Float): com.mojang.blaze3d.vertex.VertexConsumer = this
}

internal fun imguiPipelineBuilder(): com.mojang.blaze3d.pipeline.RenderPipeline.Builder =
    com.mojang.blaze3d.pipeline.RenderPipeline.builder(net.minecraft.client.renderer.RenderPipelines.MATRICES_PROJECTION_SNIPPET)
        .withVertexShader("core/position_tex_color")
        .withFragmentShader("core/position_tex_color")
        .withSampler("Sampler0")

// ---- item preview ----------------------------------------------------------------------------------

/**
 * The atlas an item's quads are textured from.
 *
 * Reached through the GETTER here, not the field. From 1.21.9 `AbstractTexture.texture` is protected with
 * a public `getTexture()` beside it, and Kotlin resolves the same-named field first -- which is why the
 * access widener that serves the older bands is bounded below 1.21.9 and is not needed here.
 */
internal fun blockAtlasTexture(mc: net.minecraft.client.Minecraft): com.mojang.blaze3d.textures.GpuTexture? =
    runCatching {
        mc.textureManager.getTexture(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS).getTexture()
    }.getOrNull()

/**
 * An item's quads, by resolving and submitting it.
 *
 * `ItemRenderer.renderStatic` is gone from 1.21.9: an item is resolved to an `ItemStackRenderState` and
 * SUBMITTED, and the only exit from a resolved layer takes a collector. [ImmediateCollector] is that
 * collector -- it catches the submission and draws it straight into the consumer it was given, which
 * here is the single flat buffer the thumbnail is built in.
 */
internal fun emitItemQuads(
    mc: net.minecraft.client.Minecraft,
    stack: net.minecraft.world.item.ItemStack,
    pose: com.mojang.blaze3d.vertex.PoseStack,
    into: net.minecraft.client.renderer.MultiBufferSource,
) {
    val state = net.minecraft.client.renderer.item.ItemStackRenderState()
    mc.itemModelResolver.updateForTopItem(
        state, stack, net.minecraft.world.item.ItemDisplayContext.GUI, mc.level, null, 0,
    )
    // Full-bright, no overlay: the consumer drops both, and these keep vanilla from tinting.
    state.submit(pose, ImmediateCollector(into), 0xF000F0, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, 0)
}

internal fun drawPreviewMesh(
    mesh: com.mojang.blaze3d.vertex.MeshData,
    colour: com.mojang.blaze3d.textures.GpuTexture,
    depth: com.mojang.blaze3d.textures.GpuTexture?,
    atlas: com.mojang.blaze3d.textures.GpuTexture,
    width: Int,
    height: Int,
) {
    val device = com.mojang.blaze3d.systems.RenderSystem.getDevice()
    val encoder = device.createCommandEncoder()
    val vertices = device.createBuffer({ "bpm_preview_vertices" }, com.mojang.blaze3d.buffers.GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer())
    val indices = com.mojang.blaze3d.systems.RenderSystem.getSequentialBuffer(mesh.drawState().mode())
    val indexBuffer = indices.getBuffer(mesh.drawState().indexCount())
    com.mojang.blaze3d.systems.RenderSystem.backupProjectionMatrix()
    com.mojang.blaze3d.systems.RenderSystem.setProjectionMatrix(
        previewProjection.getBuffer(16f, 16f),
        com.mojang.blaze3d.ProjectionType.ORTHOGRAPHIC,
    )
    val transforms = com.mojang.blaze3d.systems.RenderSystem.getDynamicUniforms().writeTransform(
        org.joml.Matrix4f(), org.joml.Vector4f(1f, 1f, 1f, 1f), org.joml.Vector3f(), org.joml.Matrix4f(),
    )
    try {
        if (depth != null) encoder.clearColorAndDepthTextures(colour, 0, depth, 1.0) else encoder.clearColorTexture(colour, 0)
        val view = device.createTextureView(colour)
        val depthView = depth?.let { device.createTextureView(it) }
        val pass = if (depthView != null) {
            encoder.createRenderPass({ "bpm item preview" }, view, java.util.OptionalInt.empty(), depthView, java.util.OptionalDouble.empty())
        } else {
            encoder.createRenderPass({ "bpm item preview" }, view, java.util.OptionalInt.empty())
        }
        pass.use { p ->
            com.mojang.blaze3d.systems.RenderSystem.bindDefaultUniforms(p)
            p.setUniform("DynamicTransforms", transforms)
            p.setPipeline(previewPipeline)
            bindImGuiTexture(p, imguiTextureOf(atlas))
            p.setVertexBuffer(0, vertices)
            p.setIndexBuffer(indexBuffer, indices.type())
            p.drawIndexed(0, 0, mesh.drawState().indexCount(), 1)
        }
    } finally {
        com.mojang.blaze3d.systems.RenderSystem.restoreProjectionMatrix()
        vertices.close()
    }
}

private val previewProjection = net.minecraft.client.renderer.CachedOrthoProjectionMatrixBuffer("bpm_preview", -1000f, 1000f, true)
*///?} elif >=1.21.6 <1.21.9 {
/*/** Writes a model's quads as POSITION_TEX_COLOR, dropping the attributes a flat draw does not read. */
internal class FlatConsumer(
    private val out: com.mojang.blaze3d.vertex.VertexConsumer,
) : com.mojang.blaze3d.vertex.VertexConsumer {

    override fun addVertex(x: Float, y: Float, z: Float): com.mojang.blaze3d.vertex.VertexConsumer {
        out.addVertex(x, y, z)
        return this
    }

    override fun setColor(r: Int, g: Int, b: Int, a: Int): com.mojang.blaze3d.vertex.VertexConsumer {
        out.setColor(r, g, b, a)
        return this
    }

    override fun setUv(u: Float, v: Float): com.mojang.blaze3d.vertex.VertexConsumer {
        out.setUv(u, v)
        return this
    }

    // Overlay, lightmap and normal: accepted and dropped. See the note at the top of the file.
    override fun setUv1(u: Int, v: Int): com.mojang.blaze3d.vertex.VertexConsumer = this

    override fun setUv2(u: Int, v: Int): com.mojang.blaze3d.vertex.VertexConsumer = this

    override fun setNormal(x: Float, y: Float, z: Float): com.mojang.blaze3d.vertex.VertexConsumer = this
}

internal fun imguiPipelineBuilder(): com.mojang.blaze3d.pipeline.RenderPipeline.Builder =
    com.mojang.blaze3d.pipeline.RenderPipeline.builder(net.minecraft.client.renderer.RenderPipelines.MATRICES_PROJECTION_SNIPPET)
        .withVertexShader("core/position_tex_color")
        .withFragmentShader("core/position_tex_color")
        .withSampler("Sampler0")

// ---- item preview ----------------------------------------------------------------------------------

/** The atlas an item's quads are textured from. Needs the widener; see the build scripts. */
internal fun blockAtlasTexture(mc: net.minecraft.client.Minecraft): com.mojang.blaze3d.textures.GpuTexture? =
    runCatching {
        mc.textureManager.getTexture(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS).texture
    }.getOrNull()

/** Vanilla's own item renderer, emitting into whatever consumer it is handed. */
internal fun emitItemQuads(
    mc: net.minecraft.client.Minecraft,
    stack: net.minecraft.world.item.ItemStack,
    pose: com.mojang.blaze3d.vertex.PoseStack,
    into: net.minecraft.client.renderer.MultiBufferSource,
) {
    mc.itemRenderer.renderStatic(
        stack,
        net.minecraft.world.item.ItemDisplayContext.GUI,
        // Full-bright, no overlay: the consumer drops both, and these keep vanilla from tinting.
        0xF000F0,
        net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,
        pose,
        into,
        mc.level,
        0,
    )
}

internal fun drawPreviewMesh(
    mesh: com.mojang.blaze3d.vertex.MeshData,
    colour: com.mojang.blaze3d.textures.GpuTexture,
    depth: com.mojang.blaze3d.textures.GpuTexture?,
    atlas: com.mojang.blaze3d.textures.GpuTexture,
    width: Int,
    height: Int,
) {
    val device = com.mojang.blaze3d.systems.RenderSystem.getDevice()
    val encoder = device.createCommandEncoder()
    val vertices = device.createBuffer({ "bpm_preview_vertices" }, com.mojang.blaze3d.buffers.GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer())
    val indices = com.mojang.blaze3d.systems.RenderSystem.getSequentialBuffer(mesh.drawState().mode())
    val indexBuffer = indices.getBuffer(mesh.drawState().indexCount())
    com.mojang.blaze3d.systems.RenderSystem.backupProjectionMatrix()
    com.mojang.blaze3d.systems.RenderSystem.setProjectionMatrix(
        previewProjection.getBuffer(16f, 16f),
        com.mojang.blaze3d.ProjectionType.ORTHOGRAPHIC,
    )
    val transforms = com.mojang.blaze3d.systems.RenderSystem.getDynamicUniforms().writeTransform(
        org.joml.Matrix4f(), org.joml.Vector4f(1f, 1f, 1f, 1f), org.joml.Vector3f(), org.joml.Matrix4f(), 1f,
    )
    try {
        if (depth != null) encoder.clearColorAndDepthTextures(colour, 0, depth, 1.0) else encoder.clearColorTexture(colour, 0)
        val view = device.createTextureView(colour)
        val depthView = depth?.let { device.createTextureView(it) }
        val pass = if (depthView != null) {
            encoder.createRenderPass({ "bpm item preview" }, view, java.util.OptionalInt.empty(), depthView, java.util.OptionalDouble.empty())
        } else {
            encoder.createRenderPass({ "bpm item preview" }, view, java.util.OptionalInt.empty())
        }
        pass.use { p ->
            com.mojang.blaze3d.systems.RenderSystem.bindDefaultUniforms(p)
            p.setUniform("DynamicTransforms", transforms)
            p.setPipeline(previewPipeline)
            p.bindSampler("Sampler0", device.createTextureView(atlas))
            p.setVertexBuffer(0, vertices)
            p.setIndexBuffer(indexBuffer, indices.type())
            p.drawIndexed(0, 0, mesh.drawState().indexCount(), 1)
        }
    } finally {
        com.mojang.blaze3d.systems.RenderSystem.restoreProjectionMatrix()
        vertices.close()
    }
}

private val previewProjection = net.minecraft.client.renderer.CachedOrthoProjectionMatrixBuffer("bpm_preview", -1000f, 1000f, true)
*///?}
