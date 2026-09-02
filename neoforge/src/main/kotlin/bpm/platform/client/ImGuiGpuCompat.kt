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

//? if >=1.21.9 {
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
    // Top-left origin, matching the coordinates ImGui was told to lay out in.
    val ortho = org.joml.Matrix4f().setOrtho(0f, displayWidth, displayHeight, 0f, -1000f, 1000f)
    com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder().createRenderPass(
        colour,
        java.util.OptionalInt.empty(),
    ).use { pass ->
        pass.setPipeline(pipeline)
        pass.setUniform("ModelViewMat", org.joml.Matrix4f())
        pass.setUniform("ProjMat", ortho)
        pass.setUniform("ColorModulator", 1f, 1f, 1f, 1f)
        body(pass)
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
