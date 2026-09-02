package bpm.platform.client

/*
 * Everything about drawing ImGui through Blaze3D that differs between bands, and nothing else.
 *
 * The backend itself -- the vertex repacking, the scissor arithmetic, the draw loop -- is one class from
 * 1.21.5 up. What changes underneath it is the GPU vocabulary, twice:
 *
 * - **1.21.5** still passes matrices as LOOSE UNIFORMS. There is no uniform-block snippet, no
 *   `DynamicUniforms`, and a render pass takes the target texture directly. `bindSampler` takes a
 *   `GpuTexture` because `GpuTextureView` does not exist yet, and `drawIndexed` takes only a first index
 *   and a count -- no base vertex, which is why the backend bakes the per-list vertex offset into the
 *   indices on every band rather than passing it here.
 * - **1.21.6** moves to uniform BLOCKS: a projection buffer, a `DynamicTransforms` slice, and views.
 * - **1.21.9** splits the SAMPLER out of the texture and changes how pixels are uploaded.
 *
 * These live in their own file because the backend's arm is already a commented block on every band that
 * does not match it, and a nested directive there would put a comment terminator inside the outer comment
 * and close it early.
 */

//? if >=26.1 {
/*/*
 * The 26.1 GPU backend.
 *
 * Everything Dear ImGui needs is still here -- a vertex buffer, an index buffer, a font atlas, a scissor
 * rect and one pipeline -- but the pipeline is DECLARED differently, so this arm exists rather than a few
 * shims inside the 1.21.9 one.
 *
 * Four changes, and three of them are silent if you get them wrong:
 *
 *  - The uniform/sampler declaration moved out of the builder into a `BindGroupLayout`. Vanilla's
 *    snippets (`MATRICES_PROJECTION_SNIPPET` and friends) are all PRIVATE now, so a mod pipeline states
 *    its own. The names are read off the shader this pipeline uses -- vanilla's own
 *    `core/position_tex_color`, which still declares `Projection`, `DynamicTransforms` and `Sampler0`.
 *  - `withVertexFormat(format, mode)` split into `withVertexBinding(index, format)` and
 *    `withPrimitiveTopology`.
 *  - Blend and depth became state records: `ColorTargetState` and `DepthStencilState`. An EMPTY
 *    depth-stencil is the right answer here, not a disabled one -- the pass has no depth attachment.
 *  - `RenderPass.drawIndexed` was REORDERED to
 *    `(indexCount, instanceCount, firstIndex, vertexOffset, firstInstance)`. Every parameter is an int,
 *    so the old four-argument order plus a zero compiles and draws nothing at all.
 *
 * `CachedOrthoProjectionMatrixBuffer` is gone; `ProjectionMatrixBuffer` plus a `Projection` is the same
 * thing spelled in two objects. And `Minecraft.mainRenderTarget` is `gameRenderer.mainRenderTarget()`,
 * whose colour view has to be reached through its getter -- the field beside it is protected, and Kotlin
 * resolves a same-named field ahead of the method.
 */

/** The font atlas as this band's GPU needs it: a texture, a view, and a sampler of its own. */
internal class ImGuiFont(
    val texture: com.mojang.blaze3d.textures.GpuTexture,
    val view: com.mojang.blaze3d.textures.GpuTextureView,
    val sampler: com.mojang.blaze3d.textures.GpuSampler,
)

internal fun createImGuiFont(
    device: com.mojang.blaze3d.systems.GpuDevice,
    w: Int,
    h: Int,
    pixels: java.nio.ByteBuffer,
): ImGuiFont {
    val texture = device.createTexture(
        "bpm_imgui_font",
        com.mojang.blaze3d.textures.GpuTexture.USAGE_TEXTURE_BINDING or com.mojang.blaze3d.textures.GpuTexture.USAGE_COPY_DST,
        com.mojang.blaze3d.GpuFormat.RGBA8_UNORM,
        w, h, 1, 1,
    )
    // (destination, source, mipLevel, depthOrLayer, destX, destY, width, height). The pixel format is
    // the texture's own now and is no longer passed alongside.
    device.createCommandEncoder().writeToTexture(texture, pixels, 0, 0, 0, 0, w, h)
    val sampler = device.createSampler(
        com.mojang.blaze3d.textures.AddressMode.CLAMP_TO_EDGE,
        com.mojang.blaze3d.textures.AddressMode.CLAMP_TO_EDGE,
        com.mojang.blaze3d.textures.FilterMode.LINEAR,
        com.mojang.blaze3d.textures.FilterMode.LINEAR,
        // Anisotropy, which must be at least 1. A font atlas sampled axis-aligned wants none of it.
        1,
        java.util.OptionalDouble.empty(),
    )
    return ImGuiFont(texture, device.createTextureView(texture), sampler)
}

internal fun bindImGuiFont(pass: com.mojang.blaze3d.systems.RenderPass, font: ImGuiFont) {
    pass.bindTexture("Sampler0", font.view, font.sampler)
}

/** An arbitrary texture ImGui may reference by handle -- a player skin, a rendered thumbnail. */
internal class ImGuiTexture(
    val view: com.mojang.blaze3d.textures.GpuTextureView,
    val sampler: com.mojang.blaze3d.textures.GpuSampler,
)

internal fun imguiTextureOf(texture: com.mojang.blaze3d.textures.GpuTexture): ImGuiTexture =
    ImGuiTexture(
        com.mojang.blaze3d.systems.RenderSystem.getDevice().createTextureView(texture),
        com.mojang.blaze3d.systems.RenderSystem.getDevice().createSampler(
            com.mojang.blaze3d.textures.AddressMode.CLAMP_TO_EDGE,
            com.mojang.blaze3d.textures.AddressMode.CLAMP_TO_EDGE,
            com.mojang.blaze3d.textures.FilterMode.NEAREST,
            com.mojang.blaze3d.textures.FilterMode.NEAREST,
            1,
            java.util.OptionalDouble.empty(),
        ),
    )

internal fun bindImGuiTexture(pass: com.mojang.blaze3d.systems.RenderPass, texture: ImGuiTexture) {
    pass.bindTexture("Sampler0", texture.view, texture.sampler)
}

/** What the shader asks for, stated here because vanilla's snippets are no longer public. */
private val imguiBindings: com.mojang.blaze3d.pipeline.BindGroupLayout =
    com.mojang.blaze3d.pipeline.BindGroupLayout.builder()
        .withUniform("Projection", com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER)
        .withUniform("DynamicTransforms", com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER)
        .withSampler("Sampler0")
        .build()

internal fun imguiPipeline(): com.mojang.blaze3d.pipeline.RenderPipeline =
    com.mojang.blaze3d.pipeline.RenderPipeline.builder()
        .withLocation("pipeline/bpm_imgui")
        .withVertexShader("core/position_tex_color")
        .withFragmentShader("core/position_tex_color")
        .withBindGroupLayout(imguiBindings)
        .withVertexBinding(0, com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX_COLOR)
        .withPrimitiveTopology(com.mojang.blaze3d.PrimitiveTopology.TRIANGLES)
        .withColorTargetState(
            com.mojang.blaze3d.pipeline.ColorTargetState(com.mojang.blaze3d.pipeline.BlendFunction.TRANSLUCENT)
        )
        .withCull(false)
        // Empty, not disabled: the pass below has no depth attachment to test or write.
        .withDepthStencilState(java.util.Optional.empty<com.mojang.blaze3d.pipeline.DepthStencilState>())
        .build()

private val imguiProjection = net.minecraft.client.renderer.ProjectionMatrixBuffer("bpm_imgui")
private val imguiOrtho = net.minecraft.client.renderer.Projection()

internal inline fun imguiRenderPass(
    pipeline: com.mojang.blaze3d.pipeline.RenderPipeline,
    displayWidth: Float,
    displayHeight: Float,
    body: (com.mojang.blaze3d.systems.RenderPass) -> Unit,
): Boolean {
    val target = net.minecraft.client.Minecraft.getInstance().gameRenderer.mainRenderTarget()
    val colour = target.getColorTextureView() ?: return false
    com.mojang.blaze3d.systems.RenderSystem.backupProjectionMatrix()
    // (zNear, zFar, width, height, invertY) -- ImGui's origin is top-left, which is what invertY buys.
    imguiOrtho.setupOrtho(-1000f, 1000f, displayWidth, displayHeight, true)
    com.mojang.blaze3d.systems.RenderSystem.setProjectionMatrix(
        imguiProjection.getBuffer(imguiOrtho),
        com.mojang.blaze3d.ProjectionType.ORTHOGRAPHIC,
    )
    val transforms = com.mojang.blaze3d.systems.RenderSystem.getDynamicUniforms().writeTransform(
        org.joml.Matrix4f(),
        org.joml.Vector4f(1f, 1f, 1f, 1f),
        org.joml.Vector3f(),
        org.joml.Matrix4f(),
    )
    try {
        com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder().createRenderPass(
            { "bpm imgui" },
            colour,
            java.util.Optional.empty<org.joml.Vector4fc>(),
        ).use { pass ->
            com.mojang.blaze3d.systems.RenderSystem.bindDefaultUniforms(pass)
            pass.setUniform("DynamicTransforms", transforms)
            pass.setPipeline(pipeline)
            body(pass)
        }
    } finally {
        com.mojang.blaze3d.systems.RenderSystem.restoreProjectionMatrix()
    }
    return true
}

internal fun imguiDrawIndexed(pass: com.mojang.blaze3d.systems.RenderPass, firstIndex: Int, count: Int) {
    // (indexCount, instanceCount, firstIndex, vertexOffset, firstInstance)
    pass.drawIndexed(count, 1, firstIndex, 0, 0)
}

internal fun createImGuiVertexBuffer(device: com.mojang.blaze3d.systems.GpuDevice, bytes: java.nio.ByteBuffer): com.mojang.blaze3d.buffers.GpuBuffer =
    device.createBuffer({ "bpm_imgui_vertices" }, com.mojang.blaze3d.buffers.GpuBuffer.USAGE_VERTEX, bytes)

internal fun createImGuiIndexBuffer(device: com.mojang.blaze3d.systems.GpuDevice, bytes: java.nio.ByteBuffer): com.mojang.blaze3d.buffers.GpuBuffer =
    device.createBuffer({ "bpm_imgui_indices" }, com.mojang.blaze3d.buffers.GpuBuffer.USAGE_INDEX, bytes)
*///?} elif >=1.21.9 {
/*/** The font atlas as this band's GPU needs it: a texture, a view, and a sampler of its own. */
internal class ImGuiFont(
    val texture: com.mojang.blaze3d.textures.GpuTexture,
    val view: com.mojang.blaze3d.textures.GpuTextureView,
    val sampler: com.mojang.blaze3d.textures.GpuSampler,
)

internal fun createImGuiFont(
    device: com.mojang.blaze3d.systems.GpuDevice,
    w: Int,
    h: Int,
    pixels: java.nio.ByteBuffer,
): ImGuiFont {
    val texture = device.createTexture(
        "bpm_imgui_font",
        com.mojang.blaze3d.textures.GpuTexture.USAGE_TEXTURE_BINDING or com.mojang.blaze3d.textures.GpuTexture.USAGE_COPY_DST,
        com.mojang.blaze3d.textures.TextureFormat.RGBA8,
        w, h, 1, 1,
    )
    device.createCommandEncoder().writeToTexture(
        texture, pixels, com.mojang.blaze3d.platform.NativeImage.Format.RGBA, 0, 0, 0, 0, w, h,
    )
    val sampler = device.createSampler(
        com.mojang.blaze3d.textures.AddressMode.CLAMP_TO_EDGE,
        com.mojang.blaze3d.textures.AddressMode.CLAMP_TO_EDGE,
        com.mojang.blaze3d.textures.FilterMode.LINEAR,
        com.mojang.blaze3d.textures.FilterMode.LINEAR,
        // Anisotropy, which must be at least 1. A font atlas sampled axis-aligned wants none of it.
        1,
        java.util.OptionalDouble.empty(),
    )
    return ImGuiFont(texture, device.createTextureView(texture), sampler)
}

internal fun bindImGuiFont(pass: com.mojang.blaze3d.systems.RenderPass, font: ImGuiFont) {
    pass.bindTexture("Sampler0", font.view, font.sampler)
}

/** An arbitrary texture ImGui may reference by handle -- a player skin, a rendered thumbnail. */
internal class ImGuiTexture(
    val view: com.mojang.blaze3d.textures.GpuTextureView,
    val sampler: com.mojang.blaze3d.textures.GpuSampler,
)

internal fun imguiTextureOf(texture: com.mojang.blaze3d.textures.GpuTexture): ImGuiTexture =
    ImGuiTexture(
        com.mojang.blaze3d.systems.RenderSystem.getDevice().createTextureView(texture),
        com.mojang.blaze3d.systems.RenderSystem.getDevice().createSampler(
            com.mojang.blaze3d.textures.AddressMode.CLAMP_TO_EDGE,
            com.mojang.blaze3d.textures.AddressMode.CLAMP_TO_EDGE,
            com.mojang.blaze3d.textures.FilterMode.NEAREST,
            com.mojang.blaze3d.textures.FilterMode.NEAREST,
            1,
            java.util.OptionalDouble.empty(),
        ),
    )

internal fun bindImGuiTexture(pass: com.mojang.blaze3d.systems.RenderPass, texture: ImGuiTexture) {
    pass.bindTexture("Sampler0", texture.view, texture.sampler)
}

internal fun imguiPipeline(): com.mojang.blaze3d.pipeline.RenderPipeline =
    com.mojang.blaze3d.pipeline.RenderPipeline.builder(net.minecraft.client.renderer.RenderPipelines.MATRICES_PROJECTION_SNIPPET)
        .withLocation("pipeline/bpm_imgui")
        .withVertexShader("core/position_tex_color")
        .withFragmentShader("core/position_tex_color")
        .withSampler("Sampler0")
        .withVertexFormat(
            com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX_COLOR,
            com.mojang.blaze3d.vertex.VertexFormat.Mode.TRIANGLES,
        )
        .withBlend(com.mojang.blaze3d.pipeline.BlendFunction.TRANSLUCENT)
        .withCull(false)
        .withDepthWrite(false)
        .withDepthTestFunction(com.mojang.blaze3d.platform.DepthTestFunction.NO_DEPTH_TEST)
        .build()

private val imguiProjection = net.minecraft.client.renderer.CachedOrthoProjectionMatrixBuffer("bpm_imgui", -1000f, 1000f, true)

internal inline fun imguiRenderPass(
    pipeline: com.mojang.blaze3d.pipeline.RenderPipeline,
    displayWidth: Float,
    displayHeight: Float,
    body: (com.mojang.blaze3d.systems.RenderPass) -> Unit,
): Boolean {
    val colour = net.minecraft.client.Minecraft.getInstance().mainRenderTarget.colorTextureView ?: return false
    com.mojang.blaze3d.systems.RenderSystem.backupProjectionMatrix()
    com.mojang.blaze3d.systems.RenderSystem.setProjectionMatrix(
        imguiProjection.getBuffer(displayWidth, displayHeight),
        com.mojang.blaze3d.ProjectionType.ORTHOGRAPHIC,
    )
    val transforms = com.mojang.blaze3d.systems.RenderSystem.getDynamicUniforms().writeTransform(
        org.joml.Matrix4f(),
        org.joml.Vector4f(1f, 1f, 1f, 1f),
        org.joml.Vector3f(),
        org.joml.Matrix4f(),
    )
    try {
        com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder().createRenderPass(
            { "bpm imgui" },
            colour,
            java.util.OptionalInt.empty(),
        ).use { pass ->
            com.mojang.blaze3d.systems.RenderSystem.bindDefaultUniforms(pass)
            pass.setUniform("DynamicTransforms", transforms)
            pass.setPipeline(pipeline)
            body(pass)
        }
    } finally {
        com.mojang.blaze3d.systems.RenderSystem.restoreProjectionMatrix()
    }
    return true
}

internal fun imguiDrawIndexed(pass: com.mojang.blaze3d.systems.RenderPass, firstIndex: Int, count: Int) {
    pass.drawIndexed(0, firstIndex, count, 1)
}

internal fun createImGuiVertexBuffer(device: com.mojang.blaze3d.systems.GpuDevice, bytes: java.nio.ByteBuffer): com.mojang.blaze3d.buffers.GpuBuffer =
    device.createBuffer({ "bpm_imgui_vertices" }, com.mojang.blaze3d.buffers.GpuBuffer.USAGE_VERTEX, bytes)

internal fun createImGuiIndexBuffer(device: com.mojang.blaze3d.systems.GpuDevice, bytes: java.nio.ByteBuffer): com.mojang.blaze3d.buffers.GpuBuffer =
    device.createBuffer({ "bpm_imgui_indices" }, com.mojang.blaze3d.buffers.GpuBuffer.USAGE_INDEX, bytes)
*///?} elif >=1.21.6 {
/*// No GpuSampler on this band: the filtering and address mode belong to the texture itself, and a pass
// binds the view alone through `bindSampler`.
internal class ImGuiFont(
    val texture: com.mojang.blaze3d.textures.GpuTexture,
    val view: com.mojang.blaze3d.textures.GpuTextureView,
)

internal fun createImGuiFont(
    device: com.mojang.blaze3d.systems.GpuDevice,
    w: Int,
    h: Int,
    pixels: java.nio.ByteBuffer,
): ImGuiFont {
    val texture = device.createTexture(
        "bpm_imgui_font",
        com.mojang.blaze3d.textures.GpuTexture.USAGE_TEXTURE_BINDING or com.mojang.blaze3d.textures.GpuTexture.USAGE_COPY_DST,
        com.mojang.blaze3d.textures.TextureFormat.RGBA8,
        w, h, 1, 1,
    )
    texture.setAddressMode(com.mojang.blaze3d.textures.AddressMode.CLAMP_TO_EDGE)
    // The `false` is mipmapping, which a GUI atlas drawn at one scale has no use for.
    texture.setTextureFilter(
        com.mojang.blaze3d.textures.FilterMode.LINEAR,
        com.mojang.blaze3d.textures.FilterMode.LINEAR,
        false,
    )
    // Same RGBA8 bytes, read as ints here. ImGui hands back a ByteBuffer either way.
    device.createCommandEncoder().writeToTexture(
        texture, pixels.asIntBuffer(), com.mojang.blaze3d.platform.NativeImage.Format.RGBA, 0, 0, 0, 0, w, h,
    )
    return ImGuiFont(texture, device.createTextureView(texture))
}

internal fun bindImGuiFont(pass: com.mojang.blaze3d.systems.RenderPass, font: ImGuiFont) {
    pass.bindSampler("Sampler0", font.view)
}

// The filter is NEAREST here where the font's is LINEAR: these are item thumbnails and player faces,
// drawn at or near their own size, and smoothing them only makes them muddy.
internal class ImGuiTexture(val view: com.mojang.blaze3d.textures.GpuTextureView)

internal fun imguiTextureOf(texture: com.mojang.blaze3d.textures.GpuTexture): ImGuiTexture {
    texture.setAddressMode(com.mojang.blaze3d.textures.AddressMode.CLAMP_TO_EDGE)
    texture.setTextureFilter(
        com.mojang.blaze3d.textures.FilterMode.NEAREST,
        com.mojang.blaze3d.textures.FilterMode.NEAREST,
        false,
    )
    return ImGuiTexture(com.mojang.blaze3d.systems.RenderSystem.getDevice().createTextureView(texture))
}

internal fun bindImGuiTexture(pass: com.mojang.blaze3d.systems.RenderPass, texture: ImGuiTexture) {
    pass.bindSampler("Sampler0", texture.view)
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

internal fun imguiPipeline(): com.mojang.blaze3d.pipeline.RenderPipeline =
    com.mojang.blaze3d.pipeline.RenderPipeline.builder(net.minecraft.client.renderer.RenderPipelines.MATRICES_PROJECTION_SNIPPET)
        .withLocation("pipeline/bpm_imgui")
        .withVertexShader("core/position_tex_color")
        .withFragmentShader("core/position_tex_color")
        .withSampler("Sampler0")
        .withVertexFormat(
            com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX_COLOR,
            com.mojang.blaze3d.vertex.VertexFormat.Mode.TRIANGLES,
        )
        .withBlend(com.mojang.blaze3d.pipeline.BlendFunction.TRANSLUCENT)
        .withCull(false)
        .withDepthWrite(false)
        .withDepthTestFunction(com.mojang.blaze3d.platform.DepthTestFunction.NO_DEPTH_TEST)
        .build()

private val imguiProjection = net.minecraft.client.renderer.CachedOrthoProjectionMatrixBuffer("bpm_imgui", -1000f, 1000f, true)

internal inline fun imguiRenderPass(
    pipeline: com.mojang.blaze3d.pipeline.RenderPipeline,
    displayWidth: Float,
    displayHeight: Float,
    body: (com.mojang.blaze3d.systems.RenderPass) -> Unit,
): Boolean {
    val colour = net.minecraft.client.Minecraft.getInstance().mainRenderTarget.colorTextureView ?: return false
    com.mojang.blaze3d.systems.RenderSystem.backupProjectionMatrix()
    com.mojang.blaze3d.systems.RenderSystem.setProjectionMatrix(
        imguiProjection.getBuffer(displayWidth, displayHeight),
        com.mojang.blaze3d.ProjectionType.ORTHOGRAPHIC,
    )
    // The trailing float is the line width, which 1.21.9 dropped. ImGui draws triangles, so it is never
    // read; 1f is what vanilla passes for anything that is not a line.
    val transforms = com.mojang.blaze3d.systems.RenderSystem.getDynamicUniforms().writeTransform(
        org.joml.Matrix4f(),
        org.joml.Vector4f(1f, 1f, 1f, 1f),
        org.joml.Vector3f(),
        org.joml.Matrix4f(),
        1f,
    )
    try {
        com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder().createRenderPass(
            { "bpm imgui" },
            colour,
            java.util.OptionalInt.empty(),
        ).use { pass ->
            com.mojang.blaze3d.systems.RenderSystem.bindDefaultUniforms(pass)
            pass.setUniform("DynamicTransforms", transforms)
            pass.setPipeline(pipeline)
            body(pass)
        }
    } finally {
        com.mojang.blaze3d.systems.RenderSystem.restoreProjectionMatrix()
    }
    return true
}

internal fun imguiDrawIndexed(pass: com.mojang.blaze3d.systems.RenderPass, firstIndex: Int, count: Int) {
    pass.drawIndexed(0, firstIndex, count, 1)
}

internal fun createImGuiVertexBuffer(device: com.mojang.blaze3d.systems.GpuDevice, bytes: java.nio.ByteBuffer): com.mojang.blaze3d.buffers.GpuBuffer =
    device.createBuffer({ "bpm_imgui_vertices" }, com.mojang.blaze3d.buffers.GpuBuffer.USAGE_VERTEX, bytes)

internal fun createImGuiIndexBuffer(device: com.mojang.blaze3d.systems.GpuDevice, bytes: java.nio.ByteBuffer): com.mojang.blaze3d.buffers.GpuBuffer =
    device.createBuffer({ "bpm_imgui_indices" }, com.mojang.blaze3d.buffers.GpuBuffer.USAGE_INDEX, bytes)
*///?} elif >=1.21.5 {
/*// The loose-uniform band. No views, no uniform blocks, no dynamic-transform buffer: a pass takes the
// target texture, `bindSampler` takes the font texture, and the matrices are set by name.
internal class ImGuiFont(val texture: com.mojang.blaze3d.textures.GpuTexture)

internal fun createImGuiFont(
    device: com.mojang.blaze3d.systems.GpuDevice,
    w: Int,
    h: Int,
    pixels: java.nio.ByteBuffer,
): ImGuiFont {
    // No usage flags and no mip count on this band -- createTexture takes the format and the size.
    val texture = device.createTexture("bpm_imgui_font", com.mojang.blaze3d.textures.TextureFormat.RGBA8, w, h, 1)
    texture.setAddressMode(com.mojang.blaze3d.textures.AddressMode.CLAMP_TO_EDGE)
    texture.setTextureFilter(
        com.mojang.blaze3d.textures.FilterMode.LINEAR,
        com.mojang.blaze3d.textures.FilterMode.LINEAR,
        false,
    )
    device.createCommandEncoder().writeToTexture(
        texture, pixels.asIntBuffer(), com.mojang.blaze3d.platform.NativeImage.Format.RGBA, 0, 0, 0, w, h,
    )
    return ImGuiFont(texture)
}

internal fun bindImGuiFont(pass: com.mojang.blaze3d.systems.RenderPass, font: ImGuiFont) {
    pass.bindSampler("Sampler0", font.texture)
}

internal class ImGuiTexture(val texture: com.mojang.blaze3d.textures.GpuTexture)

internal fun imguiTextureOf(texture: com.mojang.blaze3d.textures.GpuTexture): ImGuiTexture {
    texture.setAddressMode(com.mojang.blaze3d.textures.AddressMode.CLAMP_TO_EDGE)
    texture.setTextureFilter(
        com.mojang.blaze3d.textures.FilterMode.NEAREST,
        com.mojang.blaze3d.textures.FilterMode.NEAREST,
        false,
    )
    return ImGuiTexture(texture)
}

internal fun bindImGuiTexture(pass: com.mojang.blaze3d.systems.RenderPass, texture: ImGuiTexture) {
    pass.bindSampler("Sampler0", texture.texture)
}

// Built from GUI_TEXTURED_SNIPPET rather than assembled by hand: that is vanilla's own recipe for
// "textured, colour-modulated, matrices by name", so the shader and the uniform names come as a set and
// there is nothing to guess. Only the blend, the cull and the depth behaviour are this mod's business.
internal fun imguiPipeline(): com.mojang.blaze3d.pipeline.RenderPipeline =
    com.mojang.blaze3d.pipeline.RenderPipeline.builder(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED_SNIPPET)
        .withLocation("pipeline/bpm_imgui")
        .withVertexFormat(
            com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX_COLOR,
            com.mojang.blaze3d.vertex.VertexFormat.Mode.TRIANGLES,
        )
        .withBlend(com.mojang.blaze3d.pipeline.BlendFunction.TRANSLUCENT)
        .withCull(false)
        .withDepthWrite(false)
        .withDepthTestFunction(com.mojang.blaze3d.platform.DepthTestFunction.NO_DEPTH_TEST)
        .build()

internal inline fun imguiRenderPass(
    pipeline: com.mojang.blaze3d.pipeline.RenderPipeline,
    displayWidth: Float,
    displayHeight: Float,
    body: (com.mojang.blaze3d.systems.RenderPass) -> Unit,
): Boolean {
    val colour = net.minecraft.client.Minecraft.getInstance().mainRenderTarget.colorTexture ?: return false

    // The matrices are RenderSystem's on this band, not the pass's. Setting ProjMat and ModelViewMat as
    // pass uniforms looks right and does nothing: the shader reads the managed state, and what is in it
    // when a screen draws is the GUI's own -- an ortho over the GUI-SCALED size, and a model-view
    // carrying the GUI scale. ImGui lays out in window pixels, so the editor came out scaled by exactly
    // the GUI scale factor and anchored to the top-left corner. Which is what it did.
    //
    // So both are set the way vanilla sets them, and both are put back afterwards.
    com.mojang.blaze3d.systems.RenderSystem.backupProjectionMatrix()
    com.mojang.blaze3d.systems.RenderSystem.setProjectionMatrix(
        // Top-left origin, matching the coordinates ImGui was told to lay out in.
        org.joml.Matrix4f().setOrtho(0f, displayWidth, displayHeight, 0f, -1000f, 1000f),
        com.mojang.blaze3d.ProjectionType.ORTHOGRAPHIC,
    )
    val modelView = com.mojang.blaze3d.systems.RenderSystem.getModelViewStack()
    modelView.pushMatrix()
    modelView.identity()
    val colour0 = com.mojang.blaze3d.systems.RenderSystem.getShaderColor().clone()
    com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
    try {
        com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder().createRenderPass(
            colour,
            java.util.OptionalInt.empty(),
        ).use { pass ->
            pass.setPipeline(pipeline)
            body(pass)
        }
    } finally {
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(colour0[0], colour0[1], colour0[2], colour0[3])
        modelView.popMatrix()
        com.mojang.blaze3d.systems.RenderSystem.restoreProjectionMatrix()
    }
    return true
}

// No base vertex on this band, which is why the backend folds the per-list vertex offset into the index
// values before upload -- see the index loop in ImGuiBackend.
internal fun imguiDrawIndexed(pass: com.mojang.blaze3d.systems.RenderPass, firstIndex: Int, count: Int) {
    pass.drawIndexed(firstIndex, count)
}

// A buffer is a TYPE and a USAGE here, where later bands fold both into one usage bitmask. The contents
// are rebuilt every frame, so the usage is the streaming one.
internal fun createImGuiVertexBuffer(device: com.mojang.blaze3d.systems.GpuDevice, bytes: java.nio.ByteBuffer): com.mojang.blaze3d.buffers.GpuBuffer =
    device.createBuffer(
        { "bpm_imgui_vertices" },
        com.mojang.blaze3d.buffers.BufferType.VERTICES,
        com.mojang.blaze3d.buffers.BufferUsage.STREAM_WRITE,
        bytes,
    )

internal fun createImGuiIndexBuffer(device: com.mojang.blaze3d.systems.GpuDevice, bytes: java.nio.ByteBuffer): com.mojang.blaze3d.buffers.GpuBuffer =
    device.createBuffer(
        { "bpm_imgui_indices" },
        com.mojang.blaze3d.buffers.BufferType.INDICES,
        com.mojang.blaze3d.buffers.BufferUsage.STREAM_WRITE,
        bytes,
    )
*///?}
