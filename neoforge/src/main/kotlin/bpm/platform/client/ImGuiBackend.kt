package bpm.platform.client

import imgui.ImDrawData

/**
 * Where Dear ImGui's draw lists go.
 *
 * ImGui's output is only ever data: a vertex buffer, an index buffer, and a list of commands each
 * carrying a scissor rectangle and a texture id. Nothing about it is OpenGL. Which is fortunate, because
 * from 1.21.6 Minecraft draws through Blaze3D's `GpuDevice` and a raw GL draw made alongside it does not
 * composite -- the editor was producing perfectly good geometry every frame and none of it landed.
 *
 * So there are two implementations and they draw the same triangles: the bundled `ImGuiImplGl3` on the
 * bands where raw GL still works, and a pass built on `GpuDevice` after that. The second is written from
 * Minecraft's public Blaze3D API and Dear ImGui's own description of its draw data; it is the piece the
 * plan calls S1, and the reason it is worth having beyond 1.21.6 is that the same pass runs on Vulkan.
 */
interface ImGuiBackend {

    /** Called before `ImGui.newFrame`, to build anything the frame will need. */
    fun newFrame()

    /** Draw [data]. Called after `ImGui.render`. */
    fun render(data: ImDrawData)
}

//? if >=1.21.9 {
/*/**
 * ImGui through Blaze3D.
 *
 * The shape follows `GuiRenderer.executeDrawRange`, which is vanilla's own way of putting indexed
 * geometry on the screen: open a pass on the main target, bind the default uniforms, set a pipeline and
 * the two buffers, then scissor and draw per command.
 *
 * Three details are worth stating because they are where this could quietly be wrong.
 *
 * The vertex data is REPACKED rather than uploaded as-is. `ImDrawVert` is 20 bytes -- two floats of
 * position, two of UV, four colour bytes -- and Minecraft has no vertex format with a two-float position.
 * Registering one into the global element registry is possible and is what a GL backend would do;
 * widening to `POSITION_TEX_COLOR` costs four bytes a vertex on a few thousand vertices a frame and
 * needs no global state. The colour bytes are already in the order Minecraft wants.
 *
 * The projection is this mod's own orthographic buffer rather than whatever the frame last set, because
 * this draws outside the GUI's own pass. ImGui is told the display size in GUI units, so its coordinates
 * and the projection agree by construction.
 *
 * The scissor rectangles arrive in those same GUI units and have to be given to the pass in framebuffer
 * pixels with the origin at the bottom -- the conversion vanilla does in `GuiRenderer.enableScissor`.
 */
private class GpuImGuiBackend : ImGuiBackend {

    private val pipeline: com.mojang.blaze3d.pipeline.RenderPipeline =
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

    private val projection = net.minecraft.client.renderer.CachedOrthoProjectionMatrixBuffer("bpm_imgui", -1000f, 1000f, true)

    private var fontTexture: com.mojang.blaze3d.textures.GpuTexture? = null
    private var fontView: com.mojang.blaze3d.textures.GpuTextureView? = null
    private var sampler: com.mojang.blaze3d.textures.GpuSampler? = null

    private var vertexBuffer: com.mojang.blaze3d.buffers.GpuBuffer? = null
    private var indexBuffer: com.mojang.blaze3d.buffers.GpuBuffer? = null

    override fun newFrame() {
        buildFontOnce()
    }

    /**
     * The font atlas, uploaded once.
     *
     * The id handed back to ImGui is [FONT_ID] rather than a GPU handle: this band has no integer handle
     * to give, and every command this mod produces samples the atlas anyway -- the editor's block
     * previews, which are the only other texture it would want, are not available here for the same
     * reason. See [bpm.platform.client.skinHandle].
     */
    private fun buildFontOnce() {
        if (fontTexture != null) return
        val device = com.mojang.blaze3d.systems.RenderSystem.getDevice()
        val atlas = imgui.ImGui.getIO().fonts
        val width = imgui.type.ImInt()
        val height = imgui.type.ImInt()
        val pixels = atlas.getTexDataAsRGBA32(width, height)
        val w = width.get()
        val h = height.get()
        if (w <= 0 || h <= 0) return

        val texture = device.createTexture(
            "bpm_imgui_font",
            com.mojang.blaze3d.textures.GpuTexture.USAGE_TEXTURE_BINDING or com.mojang.blaze3d.textures.GpuTexture.USAGE_COPY_DST,
            com.mojang.blaze3d.textures.TextureFormat.RGBA8,
            w, h, 1, 1,
        )
        device.createCommandEncoder().writeToTexture(
            texture, pixels, com.mojang.blaze3d.platform.NativeImage.Format.RGBA, 0, 0, 0, 0, w, h,
        )
        fontTexture = texture
        fontView = device.createTextureView(texture)
        sampler = device.createSampler(
            com.mojang.blaze3d.textures.AddressMode.CLAMP_TO_EDGE,
            com.mojang.blaze3d.textures.AddressMode.CLAMP_TO_EDGE,
            com.mojang.blaze3d.textures.FilterMode.LINEAR,
            com.mojang.blaze3d.textures.FilterMode.LINEAR,
            // Anisotropy, which must be at least 1. A font atlas sampled axis-aligned wants none of it.
            1,
            java.util.OptionalDouble.empty(),
        )
        atlas.setTexID(FONT_ID)
    }

    override fun render(data: ImDrawData) {
        val lists = data.cmdListsCount
        if (lists == 0) return
        val view = fontView ?: return
        val texSampler = sampler ?: return
        val device = com.mojang.blaze3d.systems.RenderSystem.getDevice()

        val totalVertices = data.totalVtxCount
        val totalIndices = data.totalIdxCount
        if (totalVertices == 0 || totalIndices == 0) return

        // ---- pack every list into one vertex and one index buffer ------------------------------------
        val vertices = org.lwjgl.system.MemoryUtil.memAlloc(totalVertices * OUT_VERTEX_BYTES)
        val indices = org.lwjgl.system.MemoryUtil.memAlloc(totalIndices * 2)
        val baseVertexOf = IntArray(lists)
        val baseIndexOf = IntArray(lists)
        var vertexCount = 0
        var indexCount = 0
        try {
            for (list in 0 until lists) {
                baseVertexOf[list] = vertexCount
                baseIndexOf[list] = indexCount

                val src = data.getCmdListVtxBufferData(list)
                val listVertices = data.getCmdListVtxBufferSize(list)
                for (v in 0 until listVertices) {
                    val at = v * IN_VERTEX_BYTES
                    vertices.putFloat(src.getFloat(at))          // x
                    vertices.putFloat(src.getFloat(at + 4))      // y
                    vertices.putFloat(0f)                        // z: ImGui is two-dimensional
                    vertices.putFloat(src.getFloat(at + 8))      // u
                    vertices.putFloat(src.getFloat(at + 12))     // v
                    vertices.putInt(src.getInt(at + 16))         // rgba, already byte-ordered
                }
                vertexCount += listVertices

                val srcIndices = data.getCmdListIdxBufferData(list)
                val listIndices = data.getCmdListIdxBufferSize(list)
                for (i in 0 until listIndices) indices.putShort(srcIndices.getShort(i * 2))
                indexCount += listIndices
            }
            vertices.flip()
            indices.flip()

            vertexBuffer?.close()
            indexBuffer?.close()
            vertexBuffer = device.createBuffer({ "bpm_imgui_vertices" }, com.mojang.blaze3d.buffers.GpuBuffer.USAGE_VERTEX, vertices)
            indexBuffer = device.createBuffer({ "bpm_imgui_indices" }, com.mojang.blaze3d.buffers.GpuBuffer.USAGE_INDEX, indices)
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(vertices)
            org.lwjgl.system.MemoryUtil.memFree(indices)
        }

        val vbo = vertexBuffer ?: return
        val ibo = indexBuffer ?: return

        // ---- one pass, one draw per command ------------------------------------------------------------
        val minecraft = net.minecraft.client.Minecraft.getInstance()
        val window = minecraft.window
        val target = minecraft.mainRenderTarget
        // Null until the target has buffers; nothing to draw into before then.
        val colour = target.colorTextureView ?: return

        /*
         * The projection comes from ImGui's OWN display size, not from the window.
         *
         * ImGui is told to lay out in window coordinates, and the editor has been designed against those
         * since it was written. Building the projection from `guiScaledWidth` instead -- which is the
         * window divided by the player's GUI scale, so perhaps a third of it -- projects coordinates that
         * run to 1920 through a space 640 wide: the editor comes out several times too large and anchored
         * to the top-left corner, which is exactly what it did.
         *
         * Taking both the projection and the scissor conversion from the draw data means they cannot
         * disagree with each other or with what ImGui was told, whatever the window is doing.
         */
        val displayWidth = data.displaySizeX
        val displayHeight = data.displaySizeY
        if (displayWidth <= 0f || displayHeight <= 0f) return

        com.mojang.blaze3d.systems.RenderSystem.backupProjectionMatrix()
        com.mojang.blaze3d.systems.RenderSystem.setProjectionMatrix(
            projection.getBuffer(displayWidth, displayHeight),
            com.mojang.blaze3d.ProjectionType.ORTHOGRAPHIC,
        )
        val transforms = com.mojang.blaze3d.systems.RenderSystem.getDynamicUniforms().writeTransform(
            org.joml.Matrix4f(),
            org.joml.Vector4f(1f, 1f, 1f, 1f),
            org.joml.Vector3f(),
            org.joml.Matrix4f(),
        )
        try {
            device.createCommandEncoder().createRenderPass(
                { "bpm imgui" },
                colour,
                java.util.OptionalInt.empty(),
            ).use { pass ->
                com.mojang.blaze3d.systems.RenderSystem.bindDefaultUniforms(pass)
                pass.setUniform("DynamicTransforms", transforms)
                pass.setPipeline(pipeline)
                pass.setVertexBuffer(0, vbo)
                pass.setIndexBuffer(ibo, com.mojang.blaze3d.vertex.VertexFormat.IndexType.SHORT)
                pass.bindTexture("Sampler0", view, texSampler)

                for (list in 0 until lists) {
                    for (command in 0 until data.getCmdListCmdBufferSize(list)) {
                        val elements = data.getCmdListCmdBufferElemCount(list, command)
                        if (elements == 0) continue
                        val clip = data.getCmdListCmdBufferClipRect(list, command)
                        if (clip.z <= clip.x || clip.w <= clip.y) continue
                        scissor(pass, data, window.height, clip.x, clip.y, clip.z, clip.w)
                        pass.drawIndexed(
                            baseVertexOf[list] + data.getCmdListCmdBufferVtxOffset(list, command),
                            baseIndexOf[list] + data.getCmdListCmdBufferIdxOffset(list, command),
                            elements,
                            1,
                        )
                    }
                }
                pass.disableScissor()
            }
        } finally {
            com.mojang.blaze3d.systems.RenderSystem.restoreProjectionMatrix()
        }
    }

    /**
     * A clip rectangle in ImGui's coordinates, as a scissor box in framebuffer pixels from the bottom.
     *
     * The scale is the draw data's own framebuffer scale rather than the GUI scale, for the reason given
     * above: these rectangles are in the same space as the vertices, and the projection was built from
     * that space too. The Y flip is the one thing here that fails quietly rather than loudly -- it clips
     * the wrong band of the screen instead of throwing -- so it is spelled out.
     */
    private fun scissor(
        pass: com.mojang.blaze3d.systems.RenderPass,
        data: ImDrawData,
        framebufferHeight: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) {
        val originX = data.displayPosX
        val originY = data.displayPosY
        val scaleX = data.framebufferScaleX.takeIf { it > 0f } ?: 1f
        val scaleY = data.framebufferScaleY.takeIf { it > 0f } ?: 1f
        val x = ((left - originX) * scaleX).toInt()
        val y = (framebufferHeight - (bottom - originY) * scaleY).toInt()
        val width = ((right - left) * scaleX).toInt()
        val height = ((bottom - top) * scaleY).toInt()
        pass.enableScissor(x, y, maxOf(0, width), maxOf(0, height))
    }

    private companion object {
        /** `ImDrawVert`: two floats of position, two of UV, four colour bytes. */
        const val IN_VERTEX_BYTES = 20

        /** `POSITION_TEX_COLOR`: three floats of position, two of UV, four colour bytes. */
        const val OUT_VERTEX_BYTES = 24

        /** Any stable non-zero value; this band has no GPU handle to hand ImGui, and one texture is used. */
        const val FONT_ID = 1L
    }
}
*///?} else {
/**
 * ImGui through raw OpenGL, which is how it has always worked and still does on these bands.
 *
 * The backend is rebuilt whenever the GL capabilities change, because its VAO, shader and font texture
 * all belong to the context that went away with them.
 */
private class Gl3ImGuiBackend : ImGuiBackend {

    private var gl: imgui.gl3.ImGuiImplGl3? = null
    private var caps: Any? = null

    override fun newFrame() {
        val now = runCatching { org.lwjgl.opengl.GL.getCapabilities() }.getOrNull()
        if (gl == null || (now != null && now !== caps)) {
            gl = imgui.gl3.ImGuiImplGl3().also { it.init("#version 150") }
            caps = now
        }
        gl?.newFrame()
    }

    override fun render(data: ImDrawData) {
        gl?.renderDrawData(data)
        // The backend bound its own buffers behind Minecraft's back, and on 1.21.1 it also left the
        // model-view matrix where it found it rather than where the game expects it. `BufferUploader`
        // itself is gone from 1.21.5, so the correction goes through the seam that already knows which
        // bands still have a cached binding to put right.
        resetVertexBuffers()
        applyModelView()
    }
}
//?}

/** The backend for this band, made once. */
private val backend: ImGuiBackend by lazy {
    //? if >=1.21.9 {
    /*GpuImGuiBackend()
    *///?} else {
    Gl3ImGuiBackend()
    //?}
}

/** The backend for this band. */
fun imguiBackend(): ImGuiBackend = backend
