package bpm.platform.client

/*
 * The three GPU calls the ImGui backend makes that changed shape at 1.21.9, and nothing else.
 *
 * 1.21.9 split the SAMPLER out of the texture. Before it, filtering and address mode are properties you
 * set ON a `GpuTexture` and a render pass binds a view alone; after, a `GpuSampler` is its own object,
 * created from the device and handed to the pass beside the view. The pixel upload moved too --
 * `writeToTexture` takes an `IntBuffer` on the earlier band and a `ByteBuffer` on the later one, for the
 * same RGBA8 bytes.
 *
 * That is the WHOLE difference between the two bands' backends: everything else -- the pipeline, the
 * vertex repacking, the projection, the scissor arithmetic, the draw loop -- is identical. So the
 * backend stays one class and the three calls live here, in a file whose directives are top-level and
 * therefore safe to switch. A nested arm inside the backend's own arm would close its comment early.
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

/** The identity transform block every ImGui draw shares. */
internal fun imguiTransforms(): com.mojang.blaze3d.buffers.GpuBufferSlice =
    com.mojang.blaze3d.systems.RenderSystem.getDynamicUniforms().writeTransform(
        org.joml.Matrix4f(),
        org.joml.Vector4f(1f, 1f, 1f, 1f),
        org.joml.Vector3f(),
        org.joml.Matrix4f(),
    )
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

// The trailing float is the line width, which 1.21.9 dropped from this call. ImGui draws triangles, so
// it is never read; 1f is what vanilla passes for anything that is not a line.
internal fun imguiTransforms(): com.mojang.blaze3d.buffers.GpuBufferSlice =
    com.mojang.blaze3d.systems.RenderSystem.getDynamicUniforms().writeTransform(
        org.joml.Matrix4f(),
        org.joml.Vector4f(1f, 1f, 1f, 1f),
        org.joml.Vector3f(),
        org.joml.Matrix4f(),
        1f,
    )
*///?}
