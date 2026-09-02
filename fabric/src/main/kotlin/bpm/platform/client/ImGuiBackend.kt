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

//? if >=1.21.5 {
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

    private val pipeline: com.mojang.blaze3d.pipeline.RenderPipeline = imguiPipeline()

    private var font: ImGuiFont? = null

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
        if (font != null) return
        val device = com.mojang.blaze3d.systems.RenderSystem.getDevice()
        val atlas = imgui.ImGui.getIO().fonts
        val width = imgui.type.ImInt()
        val height = imgui.type.ImInt()
        val pixels = atlas.getTexDataAsRGBA32(width, height)
        val w = width.get()
        val h = height.get()
        if (w <= 0 || h <= 0) return

        // Creating and configuring the atlas differs by band; see ImGuiGpuCompat.
        font = createImGuiFont(device, w, h, pixels)
        atlas.setTexID(FONT_ID)
    }

    override fun render(data: ImDrawData) {
        val lists = data.cmdListsCount
        if (lists == 0) return
        val atlasFont = font ?: return
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

                /*
                 * The list's vertex offset is folded into its index VALUES, rather than passed to the
                 * draw call as a base vertex. 1.21.5 has no base-vertex parameter -- `drawIndexed` takes
                 * a first index and a count and nothing else -- and doing it here is arithmetically the
                 * same thing on every band, so all of them share one path.
                 */
                val srcIndices = data.getCmdListIdxBufferData(list)
                val listIndices = data.getCmdListIdxBufferSize(list)
                val base = vertexCount - listVertices
                for (i in 0 until listIndices) {
                    indices.putShort(((srcIndices.getShort(i * 2).toInt() and 0xFFFF) + base).toShort())
                }
                indexCount += listIndices
            }
            vertices.flip()
            indices.flip()

            vertexBuffer?.close()
            indexBuffer?.close()
            vertexBuffer = createImGuiVertexBuffer(device, vertices)
            indexBuffer = createImGuiIndexBuffer(device, indices)
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(vertices)
            org.lwjgl.system.MemoryUtil.memFree(indices)
        }

        val vbo = vertexBuffer ?: return
        val ibo = indexBuffer ?: return

        // ---- one pass, one draw per command ------------------------------------------------------------
        val minecraft = net.minecraft.client.Minecraft.getInstance()
        val window = minecraft.window

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

        // Opening the pass, the projection and the uniforms all differ by band; see ImGuiGpuCompat.
        imguiRenderPass(pipeline, displayWidth, displayHeight) { pass ->
            run {
                // 26.1 takes a SLICE of the vertex buffer rather than the buffer, and moved
                // `IndexType` out of `VertexFormat` to the top of blaze3d.
                //? if >=26.1 {
                /*pass.setVertexBuffer(0, vbo.slice())
                pass.setIndexBuffer(ibo, com.mojang.blaze3d.IndexType.SHORT)
                *///?} else {
                pass.setVertexBuffer(0, vbo)
                pass.setIndexBuffer(ibo, com.mojang.blaze3d.vertex.VertexFormat.IndexType.SHORT)
                //?}
                // Whatever the last command bound; the font is by far the common case, so it is bound
                // once here and only re-bound when a command asks for something else.
                bindImGuiFont(pass, atlasFont)
                var bound = FONT_ID

                for (list in 0 until lists) {
                    for (command in 0 until data.getCmdListCmdBufferSize(list)) {
                        val elements = data.getCmdListCmdBufferElemCount(list, command)
                        if (elements == 0) continue
                        val clip = data.getCmdListCmdBufferClipRect(list, command)
                        if (clip.z <= clip.x || clip.w <= clip.y) continue

                        /*
                         * The texture this command wants. A command referring to a handle nobody
                         * registered is SKIPPED rather than drawn with the font: drawing it would paint a
                         * slab of font atlas where an icon belongs, which reads as corruption, where
                         * skipping reads as "not ready yet" -- which is what it is.
                         */
                        val wanted = data.getCmdListCmdBufferTextureId(list, command)
                        if (wanted != bound) {
                            if (wanted == FONT_ID) {
                                bindImGuiFont(pass, atlasFont)
                            } else {
                                val texture = ImGuiTextures.lookup(wanted) ?: continue
                                bindImGuiTexture(pass, texture)
                            }
                            bound = wanted
                        }
                        scissor(pass, data, window.height, clip.x, clip.y, clip.z, clip.w)
                        // The vertex offset is already in the index values -- see the upload above.
                        imguiDrawIndexed(
                            pass,
                            baseIndexOf[list] + data.getCmdListCmdBufferIdxOffset(list, command),
                            elements,
                        )
                    }
                }
                pass.disableScissor()
            }
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

/**
 * The handles ImGui draws with, on a band that has no integer texture names.
 *
 * Before 1.21.5 a handle WAS the OpenGL name: the game handed one out, ImGui stored it in a draw
 * command, and the backend bound it. From 1.21.5 there are no names anywhere, so the mod issues its own
 * and keeps the mapping. `IconRegion.texture` is a `Long` for exactly this reason -- it was widened in
 * vscript ahead of this, and on 1.21.1 the number really is a GL name.
 *
 * Handles are stable per texture: a slot re-rendered every few seconds keeps its handle, and the icon
 * strip does not churn. Nothing is evicted, because the things registered here are a fixed set -- one
 * per preview slot, one per player skin on screen -- and they outlive any individual frame.
 */
internal object ImGuiTextures {

    private val byHandle = HashMap<Long, ImGuiTexture>()
    private val handles = HashMap<com.mojang.blaze3d.textures.GpuTexture, Long>()

    /** 1 is the font; everything else counts up from 2. */
    private var next = 2L

    fun handleFor(texture: com.mojang.blaze3d.textures.GpuTexture): Long =
        handles.getOrPut(texture) {
            val handle = next++
            byHandle[handle] = imguiTextureOf(texture)
            handle
        }

    fun lookup(handle: Long): ImGuiTexture? = byHandle[handle]

    /** Forget a texture that has been closed, so a recycled GpuTexture cannot answer to a stale handle. */
    fun forget(texture: com.mojang.blaze3d.textures.GpuTexture) {
        handles.remove(texture)?.let { byHandle.remove(it) }
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
    //? if >=1.21.5 {
    /*GpuImGuiBackend()
    *///?} else {
    Gl3ImGuiBackend()
    //?}
}

/** The backend for this band. */
fun imguiBackend(): ImGuiBackend = backend
