package bpm.platform.client

import bpm.platform.RenderType
import bpm.platform.ResourceLocation

/**
 * Rendering by EFFECT, not by name.
 *
 * The rule the whole port follows: do not abstract a `RenderType`, abstract what it is FOR. That earns
 * its keep at 1.21.9, where the way a render type is BUILT changed completely -- `RenderStateShard` is
 * gone, and a custom type is now a `RenderPipeline`, which carries blend, cull and write masks, plus a
 * `RenderSetup`, which carries textures, lightmap and buffer size. Nothing above this file noticed,
 * because nothing above this file ever named a shard.
 *
 * Three bodies now, and the shape of the difference is worth stating: 1.21.1 had ready-made types for
 * everything here; 1.21.2 kept the composite-state builder but deleted some of the types; 1.21.9
 * replaced the builder itself. The seam did not move once.
 */


/**
 * A world-space line pass: segments and box outlines.
 *
 * This is the effect, not the machinery, and what it hides is a real three-way difference. Until 1.21.9 a
 * line's WIDTH was global GL state set by `RenderSystem.lineWidth`, drawing through walls meant
 * `RenderSystem.disableDepthTest` around the pass, and the whole thing went into a raw `Tesselator` and
 * out through `BufferUploader`. From 1.21.9 the width is a per-vertex attribute of a new vertex format,
 * depth testing belongs to the pipeline, and `BufferUploader` does not exist.
 *
 * A caller that only ever wanted "a two-pixel box, optionally through walls" should not have to know any
 * of that, and now does not.
 */
interface LinePass {

    /** A segment from [from] to [to]. */
    fun line(
        pose: com.mojang.blaze3d.vertex.PoseStack,
        from: net.minecraft.world.phys.Vec3,
        to: net.minecraft.world.phys.Vec3,
        rgb: FloatArray,
        alpha: Float,
    )

    /** The twelve edges of [box]. */
    fun box(
        pose: com.mojang.blaze3d.vertex.PoseStack,
        box: net.minecraft.world.phys.AABB,
        rgb: FloatArray,
        alpha: Float,
    )
}

/** The twelve edges of a box, as pairs of corners. Shared: a box is a box on every band. */
internal fun boxEdges(box: net.minecraft.world.phys.AABB): List<Pair<net.minecraft.world.phys.Vec3, net.minecraft.world.phys.Vec3>> {
    fun p(x: Double, y: Double, z: Double) = net.minecraft.world.phys.Vec3(x, y, z)
    val a = p(box.minX, box.minY, box.minZ)
    val b = p(box.maxX, box.minY, box.minZ)
    val c = p(box.maxX, box.minY, box.maxZ)
    val d = p(box.minX, box.minY, box.maxZ)
    val e = p(box.minX, box.maxY, box.minZ)
    val f = p(box.maxX, box.maxY, box.minZ)
    val g = p(box.maxX, box.maxY, box.maxZ)
    val h = p(box.minX, box.maxY, box.maxZ)
    return listOf(
        a to b, b to c, c to d, d to a,
        e to f, f to g, g to h, h to e,
        a to e, b to f, c to g, d to h,
    )
}

//? if >=1.21.9 {
/*/**
 * Every pipeline this mod draws with, declared together because they are registered together.
 *
 * A `RenderPipeline` is compiled from the set the game is told about, which is what
 * `RegisterRenderPipelinesEvent` collects -- so these are values built at class-init, and the client
 * entry point hands [BpmPipelines.all] to that event.
 */
object BpmPipelines {

    /**
     * Translucent, lit, and CULLED.
     *
     * Vanilla's own `ENTITY_TRANSLUCENT` pipeline sets `withCull(false)`; this is that recipe with
     * culling left on, which is exactly what the deleted `entityTranslucentCull` was. Its users draw
     * CLOSED shapes -- a mote cube, a monitor panel -- whose far faces would otherwise blend through
     * the near ones and read brighter and muddier than they should.
     */
    val TRANSLUCENT_CULL: com.mojang.blaze3d.pipeline.RenderPipeline =
        com.mojang.blaze3d.pipeline.RenderPipeline.builder(net.minecraft.client.renderer.RenderPipelines.ENTITY_SNIPPET)
            .withLocation("pipeline/bpm_entity_translucent_cull")
            .withShaderDefine("ALPHA_CUTOUT", 0.1f)
            .withShaderDefine("PER_FACE_LIGHTING")
            .withSampler("Sampler1")
            .withBlend(com.mojang.blaze3d.pipeline.BlendFunction.TRANSLUCENT)
            .build()

    /** Flat colour added onto what is behind it, writing no depth: the energy arc. */
    val ADDITIVE_QUADS: com.mojang.blaze3d.pipeline.RenderPipeline =
        colourQuadPipeline("bpm_additive_quads", additive = true, depthWrite = false)

    /** Flat colour, blended, WRITING depth: the assembler beam, which must occlude what is behind it. */
    val TRANSLUCENT_QUADS: com.mojang.blaze3d.pipeline.RenderPipeline =
        colourQuadPipeline("bpm_translucent_quads", additive = false, depthWrite = true)

    val all: List<com.mojang.blaze3d.pipeline.RenderPipeline>
        get() = listOf(TRANSLUCENT_CULL, ADDITIVE_QUADS, TRANSLUCENT_QUADS)
}

/**
 * A position+colour quad pipeline.
 *
 * Modelled on vanilla's `DEBUG_FILLED_SNIPPET`, the stock "flat colour, no texture, no lighting"
 * recipe: projection matrices, `core/position_color` for both stages, POSITION_COLOR quads. Only the
 * blend and the depth write differ between the two this mod needs. `BlendFunction.LIGHTNING` is
 * `SRC_ALPHA, ONE` -- the same additive the old `ADDITIVE_TRANSPARENCY` shard was, and the same thing
 * the rift shader's JSON asked for in words.
 */
private fun colourQuadPipeline(name: String, additive: Boolean, depthWrite: Boolean): com.mojang.blaze3d.pipeline.RenderPipeline =
    com.mojang.blaze3d.pipeline.RenderPipeline.builder(net.minecraft.client.renderer.RenderPipelines.MATRICES_PROJECTION_SNIPPET)
        .withLocation("pipeline/" + name)
        .withVertexShader("core/position_color")
        .withFragmentShader("core/position_color")
        .withVertexFormat(com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR, com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS)
        .withBlend(
            if (additive) com.mojang.blaze3d.pipeline.BlendFunction.LIGHTNING
            else com.mojang.blaze3d.pipeline.BlendFunction.TRANSLUCENT,
        )
        .withCull(false)
        .withDepthWrite(depthWrite)
        .build()

private val translucentCullCache = HashMap<ResourceLocation, RenderType>()

fun translucentCull(texture: ResourceLocation): RenderType = translucentCullCache.getOrPut(texture) {
    net.minecraft.client.renderer.rendertype.RenderType.create(
        "bpm_entity_translucent_cull",
        net.minecraft.client.renderer.rendertype.RenderSetup.builder(BpmPipelines.TRANSLUCENT_CULL)
            .withTexture("Sampler0", texture)
            .useLightmap()
            .useOverlay()
            .sortOnUpload()
            .createRenderSetup(),
    )
}

private val colourQuadCache = HashMap<String, RenderType>()

fun additiveQuads(name: String): RenderType = colourQuadCache.getOrPut(name) {
    net.minecraft.client.renderer.rendertype.RenderType.create(
        name,
        net.minecraft.client.renderer.rendertype.RenderSetup.builder(BpmPipelines.ADDITIVE_QUADS)
            .bufferSize(256)
            .createRenderSetup(),
    )
}

fun translucentQuads(name: String): RenderType = colourQuadCache.getOrPut(name) {
    net.minecraft.client.renderer.rendertype.RenderType.create(
        name,
        net.minecraft.client.renderer.rendertype.RenderSetup.builder(BpmPipelines.TRANSLUCENT_QUADS)
            .bufferSize(256)
            .createRenderSetup(),
    )
}

/**
 * The GUI half of this seam, which this band has NOT had done yet.
 *
 * These are the offscreen-target and projection helpers the block preview and the ImGui host use, and
 * here they need the Blaze3D rewrite in earnest: `RenderTarget.clear` is gone, `setProjectionMatrix`
 * takes a `GpuBufferSlice` you upload yourself, `renderLineBox` was deleted outright, and the chunk and
 * GUI render types moved out of `RenderTypes` entirely.
 *
 * They THROW rather than returning something plausible. A silent no-op would draw nothing and read as a
 * content bug; this names the band and the missing piece at the moment it is first needed.
 */
private fun notYetOnThisBand(what: String): Nothing =
    error("bpm: " + what + " is not implemented on 1.21.9+ yet -- the Blaze3D GPU-buffer rewrite is outstanding")

private val lineTypeCache = HashMap<Boolean, RenderType>()

/**
 * Vanilla's own LINES pipeline, with the depth test turned off for the through-walls variant.
 *
 * The width is deliberately absent: from 1.21.9 it rides on each vertex, so one type serves every width.
 */
private fun linesType(throughWalls: Boolean): RenderType = lineTypeCache.getOrPut(throughWalls) {
    val pipeline =
        if (!throughWalls) {
            net.minecraft.client.renderer.RenderPipelines.LINES
        } else {
            com.mojang.blaze3d.pipeline.RenderPipeline.builder(net.minecraft.client.renderer.RenderPipelines.LINES_SNIPPET)
                .withLocation("pipeline/bpm_lines_through_walls")
                .withDepthTestFunction(com.mojang.blaze3d.platform.DepthTestFunction.NO_DEPTH_TEST)
                .withDepthWrite(false)
                .build()
        }
    net.minecraft.client.renderer.rendertype.RenderType.create(
        if (throughWalls) "bpm_lines_through_walls" else "bpm_lines",
        net.minecraft.client.renderer.rendertype.RenderSetup.builder(pipeline)
            .setLayeringTransform(net.minecraft.client.renderer.rendertype.LayeringTransform.VIEW_OFFSET_Z_LAYERING)
            .setOutputTarget(net.minecraft.client.renderer.rendertype.OutputTarget.ITEM_ENTITY_TARGET)
            .createRenderSetup(),
    )
}

fun worldLines(throughWalls: Boolean, width: Float, draw: (LinePass) -> Unit) {
    val buffers = net.minecraft.client.Minecraft.getInstance().renderBuffers().bufferSource()
    val type = linesType(throughWalls)
    draw(BufferLinePass(buffers.getBuffer(type), width))
    buffers.endBatch(type)
}

private class BufferLinePass(
    private val consumer: com.mojang.blaze3d.vertex.VertexConsumer,
    private val width: Float,
) : LinePass {

    override fun line(
        pose: com.mojang.blaze3d.vertex.PoseStack,
        from: net.minecraft.world.phys.Vec3,
        to: net.minecraft.world.phys.Vec3,
        rgb: FloatArray,
        alpha: Float,
    ) {
        val m = pose.last()
        val delta = to.subtract(from)
        val d = if (delta.lengthSqr() < 1e-12) net.minecraft.world.phys.Vec3(0.0, 1.0, 0.0) else delta.normalize()
        consumer.addVertex(m, from.x.toFloat(), from.y.toFloat(), from.z.toFloat())
            .setColor(rgb[0], rgb[1], rgb[2], alpha)
            .setNormal(m, d.x.toFloat(), d.y.toFloat(), d.z.toFloat())
            .setLineWidth(width)
        consumer.addVertex(m, to.x.toFloat(), to.y.toFloat(), to.z.toFloat())
            .setColor(rgb[0], rgb[1], rgb[2], alpha)
            .setNormal(m, d.x.toFloat(), d.y.toFloat(), d.z.toFloat())
            .setLineWidth(width)
    }

    override fun box(
        pose: com.mojang.blaze3d.vertex.PoseStack,
        box: net.minecraft.world.phys.AABB,
        rgb: FloatArray,
        alpha: Float,
    ) {
        for ((from, to) in boxEdges(box)) line(pose, from, to, rgb, alpha)
    }
}

fun setGuiProjection(matrix: org.joml.Matrix4f): Unit = notYetOnThisBand("the offscreen GUI projection")

@Suppress("unused")
fun applyModelView() = Unit

fun clearTarget(target: com.mojang.blaze3d.pipeline.RenderTarget): Unit = notYetOnThisBand("clearing a render target")

fun offscreenTarget(width: Int, height: Int): com.mojang.blaze3d.pipeline.TextureTarget =
    com.mojang.blaze3d.pipeline.TextureTarget(null, width, height, true)
*///?} elif >=1.21.2 {
/*private val translucentCullCache = HashMap<ResourceLocation, RenderType>()

fun translucentCull(texture: ResourceLocation): RenderType = translucentCullCache.getOrPut(texture) {
    net.minecraft.client.renderer.RenderType.create(
        "bpm_entity_translucent_cull",
        com.mojang.blaze3d.vertex.DefaultVertexFormat.NEW_ENTITY,
        com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS,
        1536,
        true,
        true,
        net.minecraft.client.renderer.RenderType.CompositeState.builder()
            .setShaderState(net.minecraft.client.renderer.RenderStateShard.RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
            .setTextureState(net.minecraft.client.renderer.RenderStateShard.TextureStateShard(texture, net.minecraft.util.TriState.FALSE, false))
            .setTransparencyState(net.minecraft.client.renderer.RenderStateShard.TRANSLUCENT_TRANSPARENCY)
            .setLightmapState(net.minecraft.client.renderer.RenderStateShard.LIGHTMAP)
            .setOverlayState(net.minecraft.client.renderer.RenderStateShard.OVERLAY)
            .createCompositeState(true),
    )
}

private fun positionColorShader(): net.minecraft.client.renderer.RenderStateShard.ShaderStateShard =
    net.minecraft.client.renderer.RenderStateShard.ShaderStateShard(net.minecraft.client.renderer.CoreShaders.POSITION_COLOR)

private val lineTypeCache = HashMap<Pair<Boolean, Float>, RenderType>()

/**
 * Built rather than taken from `RenderType.lines()`, for two reasons.
 *
 * The width is part of the TYPE on this band -- a `LineStateShard`, which is global GL state under the
 * covers -- and the through-walls variant needs the depth test off, which the stock one does not offer.
 */
private fun linesType(throughWalls: Boolean, width: Float): RenderType = lineTypeCache.getOrPut(throughWalls to width) {
    net.minecraft.client.renderer.RenderType.create(
        "bpm_lines_" + (if (throughWalls) "xray_" else "") + width,
        com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR_NORMAL,
        com.mojang.blaze3d.vertex.VertexFormat.Mode.LINES,
        1536,
        false,
        false,
        net.minecraft.client.renderer.RenderType.CompositeState.builder()
            .setShaderState(net.minecraft.client.renderer.RenderStateShard.RENDERTYPE_LINES_SHADER)
            .setLineState(net.minecraft.client.renderer.RenderStateShard.LineStateShard(java.util.OptionalDouble.of(width.toDouble())))
            .setLayeringState(net.minecraft.client.renderer.RenderStateShard.VIEW_OFFSET_Z_LAYERING)
            .setTransparencyState(net.minecraft.client.renderer.RenderStateShard.TRANSLUCENT_TRANSPARENCY)
            .setOutputState(net.minecraft.client.renderer.RenderStateShard.ITEM_ENTITY_TARGET)
            .setWriteMaskState(net.minecraft.client.renderer.RenderStateShard.COLOR_DEPTH_WRITE)
            .setCullState(net.minecraft.client.renderer.RenderStateShard.NO_CULL)
            .setDepthTestState(
                if (throughWalls) net.minecraft.client.renderer.RenderStateShard.NO_DEPTH_TEST
                else net.minecraft.client.renderer.RenderStateShard.LEQUAL_DEPTH_TEST,
            )
            .createCompositeState(false),
    )
}

fun worldLines(throughWalls: Boolean, width: Float, draw: (LinePass) -> Unit) {
    val buffers = net.minecraft.client.Minecraft.getInstance().renderBuffers().bufferSource()
    val type = linesType(throughWalls, width)
    draw(BufferLinePass(buffers.getBuffer(type)))
    buffers.endBatch(type)
}

private class BufferLinePass(private val consumer: com.mojang.blaze3d.vertex.VertexConsumer) : LinePass {

    override fun line(
        pose: com.mojang.blaze3d.vertex.PoseStack,
        from: net.minecraft.world.phys.Vec3,
        to: net.minecraft.world.phys.Vec3,
        rgb: FloatArray,
        alpha: Float,
    ) {
        val m = pose.last()
        val delta = to.subtract(from)
        val d = if (delta.lengthSqr() < 1e-12) net.minecraft.world.phys.Vec3(0.0, 1.0, 0.0) else delta.normalize()
        consumer.addVertex(m, from.x.toFloat(), from.y.toFloat(), from.z.toFloat())
            .setColor(rgb[0], rgb[1], rgb[2], alpha)
            .setNormal(m, d.x.toFloat(), d.y.toFloat(), d.z.toFloat())
        consumer.addVertex(m, to.x.toFloat(), to.y.toFloat(), to.z.toFloat())
            .setColor(rgb[0], rgb[1], rgb[2], alpha)
            .setNormal(m, d.x.toFloat(), d.y.toFloat(), d.z.toFloat())
    }

    override fun box(
        pose: com.mojang.blaze3d.vertex.PoseStack,
        box: net.minecraft.world.phys.AABB,
        rgb: FloatArray,
        alpha: Float,
    ) {
        for ((from, to) in boxEdges(box)) line(pose, from, to, rgb, alpha)
    }
}

fun setGuiProjection(matrix: org.joml.Matrix4f) =
    com.mojang.blaze3d.systems.RenderSystem.setProjectionMatrix(matrix, com.mojang.blaze3d.ProjectionType.ORTHOGRAPHIC)

@Suppress("unused")
fun applyModelView() = Unit

fun clearTarget(target: com.mojang.blaze3d.pipeline.RenderTarget) = target.clear()

fun offscreenTarget(width: Int, height: Int): com.mojang.blaze3d.pipeline.TextureTarget =
    com.mojang.blaze3d.pipeline.TextureTarget(width, height, true)

private val colourQuadCache = HashMap<String, RenderType>()

fun additiveQuads(name: String): RenderType = colourQuads(name, additive = true, depthWrite = false)

fun translucentQuads(name: String): RenderType = colourQuads(name, additive = false, depthWrite = true)

private fun colourQuads(name: String, additive: Boolean, depthWrite: Boolean): RenderType = colourQuadCache.getOrPut(name) {
    net.minecraft.client.renderer.RenderType.create(
        name,
        com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR,
        com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS,
        256,
        false,
        false,
        net.minecraft.client.renderer.RenderType.CompositeState.builder()
            .setShaderState(positionColorShader())
            .setTextureState(net.minecraft.client.renderer.RenderStateShard.NO_TEXTURE)
            .setTransparencyState(
                if (additive) net.minecraft.client.renderer.RenderStateShard.ADDITIVE_TRANSPARENCY
                else net.minecraft.client.renderer.RenderStateShard.TRANSLUCENT_TRANSPARENCY,
            )
            .setCullState(net.minecraft.client.renderer.RenderStateShard.NO_CULL)
            .setWriteMaskState(
                if (depthWrite) net.minecraft.client.renderer.RenderStateShard.COLOR_DEPTH_WRITE
                else net.minecraft.client.renderer.RenderStateShard.COLOR_WRITE,
            )
            .createCompositeState(false),
    )
}
*///?} else {
private val translucentCullCache = HashMap<ResourceLocation, RenderType>()

fun translucentCull(texture: ResourceLocation): RenderType = translucentCullCache.getOrPut(texture) {
    net.minecraft.client.renderer.RenderType.entityTranslucentCull(texture)
}

private fun positionColorShader(): net.minecraft.client.renderer.RenderStateShard.ShaderStateShard =
    net.minecraft.client.renderer.RenderStateShard.ShaderStateShard(net.minecraft.client.renderer.GameRenderer::getPositionColorShader)

private val lineTypeCache = HashMap<Pair<Boolean, Float>, RenderType>()

/**
 * Built rather than taken from `RenderType.lines()`, for two reasons.
 *
 * The width is part of the TYPE on this band -- a `LineStateShard`, which is global GL state under the
 * covers -- and the through-walls variant needs the depth test off, which the stock one does not offer.
 */
private fun linesType(throughWalls: Boolean, width: Float): RenderType = lineTypeCache.getOrPut(throughWalls to width) {
    net.minecraft.client.renderer.RenderType.create(
        "bpm_lines_" + (if (throughWalls) "xray_" else "") + width,
        com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR_NORMAL,
        com.mojang.blaze3d.vertex.VertexFormat.Mode.LINES,
        1536,
        false,
        false,
        net.minecraft.client.renderer.RenderType.CompositeState.builder()
            .setShaderState(net.minecraft.client.renderer.RenderStateShard.RENDERTYPE_LINES_SHADER)
            .setLineState(net.minecraft.client.renderer.RenderStateShard.LineStateShard(java.util.OptionalDouble.of(width.toDouble())))
            .setLayeringState(net.minecraft.client.renderer.RenderStateShard.VIEW_OFFSET_Z_LAYERING)
            .setTransparencyState(net.minecraft.client.renderer.RenderStateShard.TRANSLUCENT_TRANSPARENCY)
            .setOutputState(net.minecraft.client.renderer.RenderStateShard.ITEM_ENTITY_TARGET)
            .setWriteMaskState(net.minecraft.client.renderer.RenderStateShard.COLOR_DEPTH_WRITE)
            .setCullState(net.minecraft.client.renderer.RenderStateShard.NO_CULL)
            .setDepthTestState(
                if (throughWalls) net.minecraft.client.renderer.RenderStateShard.NO_DEPTH_TEST
                else net.minecraft.client.renderer.RenderStateShard.LEQUAL_DEPTH_TEST,
            )
            .createCompositeState(false),
    )
}

fun worldLines(throughWalls: Boolean, width: Float, draw: (LinePass) -> Unit) {
    val buffers = net.minecraft.client.Minecraft.getInstance().renderBuffers().bufferSource()
    val type = linesType(throughWalls, width)
    draw(BufferLinePass(buffers.getBuffer(type)))
    buffers.endBatch(type)
}

private class BufferLinePass(private val consumer: com.mojang.blaze3d.vertex.VertexConsumer) : LinePass {

    override fun line(
        pose: com.mojang.blaze3d.vertex.PoseStack,
        from: net.minecraft.world.phys.Vec3,
        to: net.minecraft.world.phys.Vec3,
        rgb: FloatArray,
        alpha: Float,
    ) {
        val m = pose.last()
        val delta = to.subtract(from)
        val d = if (delta.lengthSqr() < 1e-12) net.minecraft.world.phys.Vec3(0.0, 1.0, 0.0) else delta.normalize()
        consumer.addVertex(m, from.x.toFloat(), from.y.toFloat(), from.z.toFloat())
            .setColor(rgb[0], rgb[1], rgb[2], alpha)
            .setNormal(m, d.x.toFloat(), d.y.toFloat(), d.z.toFloat())
        consumer.addVertex(m, to.x.toFloat(), to.y.toFloat(), to.z.toFloat())
            .setColor(rgb[0], rgb[1], rgb[2], alpha)
            .setNormal(m, d.x.toFloat(), d.y.toFloat(), d.z.toFloat())
    }

    override fun box(
        pose: com.mojang.blaze3d.vertex.PoseStack,
        box: net.minecraft.world.phys.AABB,
        rgb: FloatArray,
        alpha: Float,
    ) {
        for ((from, to) in boxEdges(box)) line(pose, from, to, rgb, alpha)
    }
}

fun setGuiProjection(matrix: org.joml.Matrix4f) =
    com.mojang.blaze3d.systems.RenderSystem.setProjectionMatrix(matrix, com.mojang.blaze3d.vertex.VertexSorting.ORTHOGRAPHIC_Z)

fun applyModelView() = com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix()

fun clearTarget(target: com.mojang.blaze3d.pipeline.RenderTarget) =
    target.clear(net.minecraft.client.Minecraft.ON_OSX)

fun offscreenTarget(width: Int, height: Int): com.mojang.blaze3d.pipeline.TextureTarget =
    com.mojang.blaze3d.pipeline.TextureTarget(width, height, true, net.minecraft.client.Minecraft.ON_OSX)

private val colourQuadCache = HashMap<String, RenderType>()

fun additiveQuads(name: String): RenderType = colourQuads(name, additive = true, depthWrite = false)

fun translucentQuads(name: String): RenderType = colourQuads(name, additive = false, depthWrite = true)

private fun colourQuads(name: String, additive: Boolean, depthWrite: Boolean): RenderType = colourQuadCache.getOrPut(name) {
    net.minecraft.client.renderer.RenderType.create(
        name,
        com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR,
        com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS,
        256,
        false,
        false,
        net.minecraft.client.renderer.RenderType.CompositeState.builder()
            .setShaderState(positionColorShader())
            .setTextureState(net.minecraft.client.renderer.RenderStateShard.NO_TEXTURE)
            .setTransparencyState(
                if (additive) net.minecraft.client.renderer.RenderStateShard.ADDITIVE_TRANSPARENCY
                else net.minecraft.client.renderer.RenderStateShard.TRANSLUCENT_TRANSPARENCY,
            )
            .setCullState(net.minecraft.client.renderer.RenderStateShard.NO_CULL)
            .setWriteMaskState(
                if (depthWrite) net.minecraft.client.renderer.RenderStateShard.COLOR_DEPTH_WRITE
                else net.minecraft.client.renderer.RenderStateShard.COLOR_WRITE,
            )
            .createCompositeState(false),
    )
}
//?}

/**
 * The stock render types this mod asks for.
 *
 * 1.21.9 moved `RenderType` into its own package AND split the factories out into a separate
 * `RenderTypes`. The type is aliased in [bpm.platform.RenderType]; these are the calls.
 *
 * `translucent` and `gui` do not survive to that band at all -- the chunk layers and the GUI renderer
 * became separate worlds -- so they are named here only for the versions that have them, and their two
 * call sites are what the next pass has to move.
 */
//? if >=1.21.9 {
/*fun entityTranslucent(texture: ResourceLocation): RenderType =
    net.minecraft.client.renderer.rendertype.RenderTypes.entityTranslucent(texture)

fun lightning(): RenderType = net.minecraft.client.renderer.rendertype.RenderTypes.lightning()

fun translucent(): RenderType = notYetOnThisBand("the translucent block layer")

fun gui(): RenderType = notYetOnThisBand("the GUI render type")
*///?} elif >=1.21.2 {
/*fun entityTranslucent(texture: ResourceLocation): RenderType =
    net.minecraft.client.renderer.RenderType.entityTranslucent(texture)

fun lightning(): RenderType = net.minecraft.client.renderer.RenderType.lightning()

fun translucent(): RenderType = net.minecraft.client.renderer.RenderType.translucent()

fun gui(): RenderType = net.minecraft.client.renderer.RenderType.gui()
*///?} else {
fun entityTranslucent(texture: ResourceLocation): RenderType =
    net.minecraft.client.renderer.RenderType.entityTranslucent(texture)

fun lightning(): RenderType = net.minecraft.client.renderer.RenderType.lightning()

fun translucent(): RenderType = net.minecraft.client.renderer.RenderType.translucent()

fun gui(): RenderType = net.minecraft.client.renderer.RenderType.gui()
//?}

/**
 * One sprite off the block atlas, by name.
 *
 * `Minecraft.getTextureAtlas` handed back a `Function<Identifier, TextureAtlasSprite>` -- the atlas as a
 * lookup. 1.21.9 gave the atlases their own `AtlasManager` and the atlas its own `getSprite`, so the
 * lookup is now two named steps instead of a function value. Same sprite either way.
 */
//? if >=1.21.9 {
/*fun blockSprite(texture: ResourceLocation): net.minecraft.client.renderer.texture.TextureAtlasSprite =
    net.minecraft.client.Minecraft.getInstance()
        .atlasManager
        .getAtlasOrThrow(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS)
        .getSprite(texture)
*///?} else {
fun blockSprite(texture: ResourceLocation): net.minecraft.client.renderer.texture.TextureAtlasSprite =
    net.minecraft.client.Minecraft.getInstance()
        .getTextureAtlas(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS)
        .apply(texture)
//?}

/**
 * Draw this fluid in the translucent chunk layer.
 *
 * The map is Fabric's own here; NeoForge puts the same method on `ItemBlockRenderTypes`. And
 * `RenderType.translucent()` was one of the chunk layers until 1.21.9, when the layers became their own
 * `ChunkSectionLayer` enum -- which is the honest shape, since a chunk layer was never a render type in
 * the sense the rest of this file uses the word. The seam is the instruction rather than the value.
 */
fun drawFluidTranslucent(fluid: net.minecraft.world.level.material.Fluid) {
    //? if >=1.21.9 {
    /*net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap.putFluid(fluid, net.minecraft.client.renderer.chunk.ChunkSectionLayer.TRANSLUCENT)
    *///?} else {
    net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap.INSTANCE.putFluid(fluid, net.minecraft.client.renderer.RenderType.translucent())
    //?}
}
