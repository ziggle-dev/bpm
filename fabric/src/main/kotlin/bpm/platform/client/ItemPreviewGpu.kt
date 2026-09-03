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
 * The 26.1 arm is a third body rather than a variant: it has no `MultiBufferSource` to hand the quads
 * through, builds its pipeline against bind groups, and clears depth to 0.0 because that band is
 * reversed-Z. Its shape follows the other two closely so the three read as one thing.
 */

//? if >=26.1 {
/*/** A quad buffer in the thumbnail's flat format. `VertexFormat.Mode` is `PrimitiveTopology` here. */
internal fun previewBuilder(allocator: com.mojang.blaze3d.vertex.ByteBufferBuilder): com.mojang.blaze3d.vertex.BufferBuilder =
    com.mojang.blaze3d.vertex.BufferBuilder(
        allocator,
        com.mojang.blaze3d.PrimitiveTopology.QUADS,
        com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX_COLOR,
    )

/** Writes a model's quads as POSITION_TEX_COLOR, dropping the attributes a flat draw does not read. */
internal class FlatConsumer(
    private val out: com.mojang.blaze3d.vertex.VertexConsumer,
) : com.mojang.blaze3d.vertex.VertexConsumer {

    override fun addVertex(x: Float, y: Float, z: Float).endOfVertex(): com.mojang.blaze3d.vertex.VertexConsumer {
        out.addVertex(x, y, z).endOfVertex()
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

    override fun setColor(argb: Int): com.mojang.blaze3d.vertex.VertexConsumer = this

    override fun setLineWidth(width: Float): com.mojang.blaze3d.vertex.VertexConsumer = this

    override fun setUv1(u: Int, v: Int): com.mojang.blaze3d.vertex.VertexConsumer = this

    override fun setUv2(u: Int, v: Int): com.mojang.blaze3d.vertex.VertexConsumer = this

    override fun setNormal(x: Float, y: Float, z: Float): com.mojang.blaze3d.vertex.VertexConsumer = this
}

/**
 * One consumer for every render type, as an [ImmediateDraw].
 *
 * The 1.21.9 arm hands `ImmediateCollector` a `MultiBufferSource` that answers every type with the same
 * buffer. That type is gone here, and the collector takes an `ImmediateDraw` instead -- so this is the
 * same one-buffer trick wearing this band's interface. Nothing is batched and nothing is flushed: the
 * caller owns the single builder and builds it itself.
 */
private class OneDraw(
    private val single: com.mojang.blaze3d.vertex.VertexConsumer,
) : ImmediateDraw {

    override fun consumer(kind: bpm.platform.RenderType): com.mojang.blaze3d.vertex.VertexConsumer = single

    override fun into(
        poseStack: com.mojang.blaze3d.vertex.PoseStack,
        kind: bpm.platform.RenderType,
        draw: (com.mojang.blaze3d.vertex.PoseStack.Pose, com.mojang.blaze3d.vertex.VertexConsumer) -> Unit,
    ) = draw(poseStack.last(), single)

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

    override fun flush(kind: bpm.platform.RenderType) = Unit

    override fun flush() = Unit
}

internal fun previewColour(t: com.mojang.blaze3d.pipeline.RenderTarget): com.mojang.blaze3d.textures.GpuTexture? =
    t.getColorTexture()

internal fun previewDepth(t: com.mojang.blaze3d.pipeline.RenderTarget): com.mojang.blaze3d.textures.GpuTexture? =
    t.getDepthTexture()

/** The atlas an item's quads are textured from, through the getter -- the field beside it is protected. */
internal fun blockAtlasTexture(mc: net.minecraft.client.Minecraft): com.mojang.blaze3d.textures.GpuTexture? =
    runCatching {
        mc.textureManager.getTexture(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS).getTexture()
    }.getOrNull()

/** An item's quads, resolved and submitted into the single flat buffer the thumbnail is built in. */
internal fun emitItemQuads(
    mc: net.minecraft.client.Minecraft,
    stack: net.minecraft.world.item.ItemStack,
    pose: com.mojang.blaze3d.vertex.PoseStack,
    into: com.mojang.blaze3d.vertex.VertexConsumer,
) {
    val state = net.minecraft.client.renderer.item.ItemStackRenderState()
    mc.itemModelResolver.updateForTopItem(
        state, stack, net.minecraft.world.item.ItemDisplayContext.GUI, mc.level, null, 0,
    )
    // Full-bright, no overlay: the consumer drops both, and these keep vanilla from tinting.
    state.submit(pose, ImmediateCollector(OneDraw(into)), 0xF000F0, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, 0)
}

/**
 * Flat, textured, depth-tested quads.
 *
 * Depth matters here where it does not for ImGui: a block model is a solid, and without a depth test its
 * back faces paint over its front ones. `withDepthWriting` supplies this band's compare op, which is the
 * REVERSED-Z one -- and the clear below has to agree with it, so depth clears to 0.0 (far) rather than
 * the 1.0 the older arms use.
 */
internal val previewPipeline: com.mojang.blaze3d.pipeline.RenderPipeline by lazy {
    matricesBuilder()
        .withExtraSampler("Sampler0")
        .withLocation("pipeline/bpm_item_preview")
        .withVertexShader("core/position_tex_color")
        .withFragmentShader("core/position_tex_color")
        .withQuads(com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX_COLOR)
        .withBlending(com.mojang.blaze3d.pipeline.BlendFunction.TRANSLUCENT)
        .withCull(true)
        .withDepthWriting(true)
        .build()
}

private val previewProjection = net.minecraft.client.renderer.ProjectionMatrixBuffer("bpm_preview")
private val previewOrtho = net.minecraft.client.renderer.Projection()

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
    val indices = com.mojang.blaze3d.systems.RenderSystem.getSequentialBuffer(mesh.drawState().primitiveTopology())
    val count = mesh.drawState().indexCount()
    val indexBuffer = indices.getBuffer(count)
    com.mojang.blaze3d.systems.RenderSystem.backupProjectionMatrix()
    // (zNear, zFar, width, height, invertY) -- a 16-unit cell counted downwards, as a GUI does.
    previewOrtho.setupOrtho(-1000f, 1000f, 16f, 16f, true)
    com.mojang.blaze3d.systems.RenderSystem.setProjectionMatrix(
        previewProjection.getBuffer(previewOrtho),
        com.mojang.blaze3d.ProjectionType.ORTHOGRAPHIC,
    )
    val transforms = com.mojang.blaze3d.systems.RenderSystem.getDynamicUniforms().writeTransform(
        org.joml.Matrix4f(), org.joml.Vector4f(1f, 1f, 1f, 1f), org.joml.Vector3f(), org.joml.Matrix4f(),
    )
    try {
        val clear = org.joml.Vector4f(0f, 0f, 0f, 0f)
        // 0.0 is FAR on this band; see the note on the pipeline above.
        if (depth != null) encoder.clearColorAndDepthTextures(colour, clear, depth, 0.0) else encoder.clearColorTexture(colour, clear)
        val view = device.createTextureView(colour)
        val depthView = depth?.let { device.createTextureView(it) }
        val pass = if (depthView != null) {
            encoder.createRenderPass(
                { "bpm item preview" }, view, java.util.Optional.empty<org.joml.Vector4fc>(),
                depthView, java.util.OptionalDouble.empty(),
            )
        } else {
            encoder.createRenderPass({ "bpm item preview" }, view, java.util.Optional.empty<org.joml.Vector4fc>())
        }
        pass.use { p ->
            com.mojang.blaze3d.systems.RenderSystem.bindDefaultUniforms(p)
            p.setUniform("DynamicTransforms", transforms)
            p.setPipeline(previewPipeline)
            bindImGuiTexture(p, imguiTextureOf(atlas))
            p.setVertexBuffer(0, vertices.slice())
            p.setIndexBuffer(indexBuffer, indices.type())
            // (indexCount, instanceCount, firstIndex, vertexOffset, firstInstance) -- reordered at 26.1.
            p.drawIndexed(count, 1, 0, 0, 0)
        }
    } finally {
        com.mojang.blaze3d.systems.RenderSystem.restoreProjectionMatrix()
        vertices.close()
    }
}
*///?} elif >=1.21.9 <26.1 {
/*/**
 * Flat, textured, depth-tested quads.
 *
 * Depth matters here where it does not for ImGui: a block model is a solid, and without a depth test its
 * back faces paint over its front ones. The offscreen target carries a depth buffer for this.
 */
internal val previewPipeline: com.mojang.blaze3d.pipeline.RenderPipeline by lazy {
    imguiPipelineBuilder()
        .withLocation("pipeline/bpm_item_preview")
        .withVertexFormat(
            com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX_COLOR,
            com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS,
        )
        .withBlend(com.mojang.blaze3d.pipeline.BlendFunction.TRANSLUCENT)
        .withCull(true)
        .withDepthWrite(true)
        .withDepthTestFunction(com.mojang.blaze3d.platform.DepthTestFunction.LESS_DEPTH_TEST)
        .build()
}

/** A quad buffer in the thumbnail's flat format. */
internal fun previewBuilder(allocator: com.mojang.blaze3d.vertex.ByteBufferBuilder): com.mojang.blaze3d.vertex.BufferBuilder =
    com.mojang.blaze3d.vertex.BufferBuilder(
        allocator,
        com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS,
        com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX_COLOR,
    )

/** The thumbnail's colour and depth attachments, and its size. */
internal fun previewColour(t: com.mojang.blaze3d.pipeline.RenderTarget): com.mojang.blaze3d.textures.GpuTexture? = t.colorTexture

internal fun previewDepth(t: com.mojang.blaze3d.pipeline.RenderTarget): com.mojang.blaze3d.textures.GpuTexture? = t.depthTexture

/** One buffer for every render type the model asks for. */
private class OneBuffer(
    private val consumer: com.mojang.blaze3d.vertex.VertexConsumer,
) : net.minecraft.client.renderer.MultiBufferSource {
    override fun getBuffer(type: bpm.platform.RenderType): com.mojang.blaze3d.vertex.VertexConsumer = consumer
}

/** Writes a model's quads as POSITION_TEX_COLOR, dropping the attributes a flat draw does not read. */
internal class FlatConsumer(
    private val out: com.mojang.blaze3d.vertex.VertexConsumer,
) : com.mojang.blaze3d.vertex.VertexConsumer {

    override fun addVertex(x: Float, y: Float, z: Float).endOfVertex(): com.mojang.blaze3d.vertex.VertexConsumer {
        out.addVertex(x, y, z).endOfVertex()
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
    into: com.mojang.blaze3d.vertex.VertexConsumer,
) {
    val state = net.minecraft.client.renderer.item.ItemStackRenderState()
    mc.itemModelResolver.updateForTopItem(
        state, stack, net.minecraft.world.item.ItemDisplayContext.GUI, mc.level, null, 0,
    )
    // Full-bright, no overlay: the consumer drops both, and these keep vanilla from tinting.
    state.submit(pose, ImmediateCollector(OneBuffer(into)), 0xF000F0, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, 0)
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
/*/**
 * Flat, textured, depth-tested quads.
 *
 * Depth matters here where it does not for ImGui: a block model is a solid, and without a depth test its
 * back faces paint over its front ones. The offscreen target carries a depth buffer for this.
 */
internal val previewPipeline: com.mojang.blaze3d.pipeline.RenderPipeline by lazy {
    imguiPipelineBuilder()
        .withLocation("pipeline/bpm_item_preview")
        .withVertexFormat(
            com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX_COLOR,
            com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS,
        )
        .withBlend(com.mojang.blaze3d.pipeline.BlendFunction.TRANSLUCENT)
        .withCull(true)
        .withDepthWrite(true)
        .withDepthTestFunction(com.mojang.blaze3d.platform.DepthTestFunction.LESS_DEPTH_TEST)
        .build()
}

/** A quad buffer in the thumbnail's flat format. */
internal fun previewBuilder(allocator: com.mojang.blaze3d.vertex.ByteBufferBuilder): com.mojang.blaze3d.vertex.BufferBuilder =
    com.mojang.blaze3d.vertex.BufferBuilder(
        allocator,
        com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS,
        com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX_COLOR,
    )

/** The thumbnail's colour and depth attachments, and its size. */
internal fun previewColour(t: com.mojang.blaze3d.pipeline.RenderTarget): com.mojang.blaze3d.textures.GpuTexture? = t.colorTexture

internal fun previewDepth(t: com.mojang.blaze3d.pipeline.RenderTarget): com.mojang.blaze3d.textures.GpuTexture? = t.depthTexture

/** One buffer for every render type the model asks for. */
private class OneBuffer(
    private val consumer: com.mojang.blaze3d.vertex.VertexConsumer,
) : net.minecraft.client.renderer.MultiBufferSource {
    override fun getBuffer(type: bpm.platform.RenderType): com.mojang.blaze3d.vertex.VertexConsumer = consumer
}

/** Writes a model's quads as POSITION_TEX_COLOR, dropping the attributes a flat draw does not read. */
internal class FlatConsumer(
    private val out: com.mojang.blaze3d.vertex.VertexConsumer,
) : com.mojang.blaze3d.vertex.VertexConsumer {

    override fun addVertex(x: Float, y: Float, z: Float).endOfVertex(): com.mojang.blaze3d.vertex.VertexConsumer {
        out.addVertex(x, y, z).endOfVertex()
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
    into: com.mojang.blaze3d.vertex.VertexConsumer,
) {
    mc.itemRenderer.renderStatic(
        stack,
        net.minecraft.world.item.ItemDisplayContext.GUI,
        // Full-bright, no overlay: the consumer drops both, and these keep vanilla from tinting.
        0xF000F0,
        net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,
        pose,
        OneBuffer(into),
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
