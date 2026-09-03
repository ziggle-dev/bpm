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

/*
 * The matrix uniforms a pipeline declares, which is the whole of the 1.21.5-vs-1.21.6 difference here.
 *
 * 1.21.5 still passes matrices as LOOSE uniforms -- its `UniformType` has INT, VEC3, MATRIX4X4 and no
 * `UNIFORM_BUFFER` at all -- and vanilla's recipe for "just the matrices" is `MATRICES_SNIPPET`. 1.21.6
 * moved them into std140 blocks, and the snippet that declares those is `MATRICES_PROJECTION_SNIPPET`.
 * Same pipelines either way; only the name of the recipe they start from differs.
 *
 * This also decides which GLSL a shader has to be written in, which is why the rift's sources are split
 * at the same version rather than at 1.21.9.
 */

/**
 * `TextureStateShard` dropped its TriState blur argument at 1.21.6, and is gone entirely at 1.21.9 --
 * where a texture is named on the RenderSetup instead. Both arms are bounded above for that reason:
 * an open-ended `>=1.21.6` would declare this on a band that has no such class.
 */
//? if >=1.21.6 <1.21.9 {
/*private fun textureShard(texture: ResourceLocation): net.minecraft.client.renderer.RenderStateShard.EmptyTextureStateShard =
    net.minecraft.client.renderer.RenderStateShard.TextureStateShard(texture, false)
*///?} elif >=1.21.5 <1.21.6 {
/*private fun textureShard(texture: ResourceLocation): net.minecraft.client.renderer.RenderStateShard.EmptyTextureStateShard =
    net.minecraft.client.renderer.RenderStateShard.TextureStateShard(texture, net.minecraft.util.TriState.FALSE, false)
*///?}

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
            .withExtraSampler("Sampler1")
            .withBlending(com.mojang.blaze3d.pipeline.BlendFunction.TRANSLUCENT)
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
    matricesBuilder()
        .withLocation("pipeline/" + name)
        .withVertexShader("core/position_color")
        .withFragmentShader("core/position_color")
        .withQuads(com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR)
        .withBlending(
            if (additive) com.mojang.blaze3d.pipeline.BlendFunction.LIGHTNING
            else com.mojang.blaze3d.pipeline.BlendFunction.TRANSLUCENT,
        )
        .withCull(false)
        .withDepthWriting(depthWrite)
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
        quadRenderSetup(BpmPipelines.ADDITIVE_QUADS),
    )
}

fun translucentQuads(name: String): RenderType = colourQuadCache.getOrPut(name) {
    net.minecraft.client.renderer.rendertype.RenderType.create(
        name,
        quadRenderSetup(BpmPipelines.TRANSLUCENT_QUADS),
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
                .withoutDepth()
                .build()
        }
    net.minecraft.client.renderer.rendertype.RenderType.create(
        if (throughWalls) "bpm_lines_through_walls" else "bpm_lines",
        net.minecraft.client.renderer.rendertype.RenderSetup.builder(pipeline)
            .setLayeringTransform(net.minecraft.client.renderer.rendertype.LayeringTransform.VIEW_OFFSET_Z_LAYERING)
            /*
             * The MAIN target, not the item-entity one.
             *
             * Vanilla's own `lines` type draws into `ITEM_ENTITY_TARGET`, and so did this until the linker
             * outlines stopped appearing on 1.21.11. That target is composited at a fixed point in the
             * frame graph, and `RenderLevelStageEvent` -- which is where these are drawn from -- fires
             * after it. Geometry written there afterwards is simply never picked up. The main target is
             * still being drawn into at that moment, which is the whole reason the event is useful.
             */
            .setOutputTarget(net.minecraft.client.renderer.rendertype.OutputTarget.MAIN_TARGET)
            .createRenderSetup(),
    )
}

fun worldLines(throughWalls: Boolean, width: Float, draw: (LinePass) -> Unit) {
    val into = immediateWorldDraw()
    val type = linesType(throughWalls)
    draw(BufferLinePass(into.consumer(type), width))
    into.flush(type)
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
    offscreenColourTarget(width, height)
*///?} elif >=1.21.5 {
/*// 1.21.5 is where a render type stopped carrying its own GL state.
//
// The shader, the blend, the cull, the depth test and the write mask all moved into a RenderPipeline --
// a value built once and reused -- and what is left in a CompositeState is the texture, the lightmap
// and overlay, the layering and the output target: the things that are still per-DRAW rather than
// per-program. The construction call changed to match, and lost the vertex format with it, because the
// pipeline names that too: create(name, bufferSize, affectsCrumbling, sortOnUpload, pipeline, state).
//
// So this arm is the 1.21.2 one with its state shards deleted, holding the same pipelines the 1.21.9
// arm builds. The two bands differ only in how a RenderType is made FROM a pipeline: RenderSetup and
// the move into the `rendertype` package are both 1.21.9 changes, not 1.21.5 ones.

object BpmPipelines {

    // Translucent, lit, and CULLED: vanilla's ENTITY_TRANSLUCENT recipe with culling left on, which is
    // exactly what the deleted `entityTranslucentCull` was. Its users draw CLOSED shapes -- a mote cube,
    // a monitor panel -- whose far faces would otherwise blend through the near ones.
    val TRANSLUCENT_CULL: com.mojang.blaze3d.pipeline.RenderPipeline =
        com.mojang.blaze3d.pipeline.RenderPipeline.builder(net.minecraft.client.renderer.RenderPipelines.ENTITY_SNIPPET)
            .withLocation("pipeline/bpm_entity_translucent_cull")
            .withShaderDefine("ALPHA_CUTOUT", 0.1f)
            .withSampler("Sampler1")
            .withBlending(com.mojang.blaze3d.pipeline.BlendFunction.TRANSLUCENT)
            .build()

    // Flat colour added onto what is behind it, writing no depth: the energy arc.
    val ADDITIVE_QUADS: com.mojang.blaze3d.pipeline.RenderPipeline =
        colourQuadPipeline("bpm_additive_quads", additive = true, depthWrite = false)

    // Flat colour, blended, WRITING depth: the assembler beam, which must occlude what is behind it.
    val TRANSLUCENT_QUADS: com.mojang.blaze3d.pipeline.RenderPipeline =
        colourQuadPipeline("bpm_translucent_quads", additive = false, depthWrite = true)

    // Vanilla's LINES with the depth test turned off, for the warden-visor through-walls pass.
    val LINES_THROUGH_WALLS: com.mojang.blaze3d.pipeline.RenderPipeline =
        com.mojang.blaze3d.pipeline.RenderPipeline.builder(net.minecraft.client.renderer.RenderPipelines.LINES_SNIPPET)
            .withLocation("pipeline/bpm_lines_through_walls")
            .withDepthTestFunction(com.mojang.blaze3d.platform.DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .build()

    val all: List<com.mojang.blaze3d.pipeline.RenderPipeline>
        get() = listOf(TRANSLUCENT_CULL, ADDITIVE_QUADS, TRANSLUCENT_QUADS, LINES_THROUGH_WALLS)
}

// Modelled on vanilla's DEBUG_FILLED_SNIPPET, the stock "flat colour, no texture, no lighting" recipe.
// Only the blend and the depth write differ between the two this mod needs, and BlendFunction.LIGHTNING
// is SRC_ALPHA, ONE -- the same additive the old ADDITIVE_TRANSPARENCY shard was.
private fun colourQuadPipeline(name: String, additive: Boolean, depthWrite: Boolean): com.mojang.blaze3d.pipeline.RenderPipeline =
    matricesBuilder()
        .withLocation("pipeline/" + name)
        .withVertexShader("core/position_color")
        .withFragmentShader("core/position_color")
        .withQuads(com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR)
        .withBlending(
            if (additive) com.mojang.blaze3d.pipeline.BlendFunction.LIGHTNING
            else com.mojang.blaze3d.pipeline.BlendFunction.TRANSLUCENT,
        )
        .withCull(false)
        .withDepthWriting(depthWrite)
        .build()

private val translucentCullCache = HashMap<ResourceLocation, RenderType>()

fun translucentCull(texture: ResourceLocation): RenderType = translucentCullCache.getOrPut(texture) {
    net.minecraft.client.renderer.RenderType.create(
        "bpm_entity_translucent_cull",
        1536,
        true,
        true,
        BpmPipelines.TRANSLUCENT_CULL,
        net.minecraft.client.renderer.RenderType.CompositeState.builder()
            .setTextureState(textureShard(texture))
            .setLightmapState(net.minecraft.client.renderer.RenderStateShard.LIGHTMAP)
            .setOverlayState(net.minecraft.client.renderer.RenderStateShard.OVERLAY)
            .createCompositeState(true),
    )
}

private val colourQuadCache = HashMap<String, RenderType>()

fun additiveQuads(name: String): RenderType = colourQuads(name, BpmPipelines.ADDITIVE_QUADS)

fun translucentQuads(name: String): RenderType = colourQuads(name, BpmPipelines.TRANSLUCENT_QUADS)

private fun colourQuads(name: String, pipeline: com.mojang.blaze3d.pipeline.RenderPipeline): RenderType =
    colourQuadCache.getOrPut(name) {
        net.minecraft.client.renderer.RenderType.create(
            name,
            256,
            false,
            false,
            pipeline,
            net.minecraft.client.renderer.RenderType.CompositeState.builder().createCompositeState(false),
        )
    }

private val lineTypeCache = HashMap<Pair<Boolean, Float>, RenderType>()

// The width is still part of the TYPE on this band. Per-vertex line width is a 1.21.9 addition, so a
// LineStateShard carries it here and the cache is keyed by width as well as by the depth test, exactly
// as on 1.21.2.
private fun linesType(throughWalls: Boolean, width: Float): RenderType = lineTypeCache.getOrPut(throughWalls to width) {
    net.minecraft.client.renderer.RenderType.create(
        "bpm_lines_" + (if (throughWalls) "xray_" else "") + width,
        1536,
        false,
        false,
        if (throughWalls) BpmPipelines.LINES_THROUGH_WALLS else net.minecraft.client.renderer.RenderPipelines.LINES,
        net.minecraft.client.renderer.RenderType.CompositeState.builder()
            .setLineState(net.minecraft.client.renderer.RenderStateShard.LineStateShard(java.util.OptionalDouble.of(width.toDouble())))
            .setLayeringState(net.minecraft.client.renderer.RenderStateShard.VIEW_OFFSET_Z_LAYERING)
            // The ITEM-ENTITY target, as on the band below, not the main one the 1.21.9 arm names.
            // Drawing into it stopped working at 1.21.11, where the frame graph composites that target
            // before RenderLevelStageEvent fires. That really is a 1.21.9 change: VERIFIED in game on
            // 1.21.8, where the linker's lines and the transfer motes both draw correctly through this
            // target. Do not "fix" this to MAIN_TARGET without a symptom to go with it.
            .setOutputState(net.minecraft.client.renderer.RenderStateShard.ITEM_ENTITY_TARGET)
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

// The same gap the 1.21.9 arm has, and for the same reason: the offscreen-target and projection
// helpers behind the editor's block previews need the GPU-buffer rewrite, which is outstanding. They
// THROW rather than no-op, so the missing piece names itself the moment it is first needed instead of
// silently drawing nothing.
private fun notYetOnThisBand(what: String): Nothing =
    error("bpm: " + what + " is not implemented on 1.21.5+ yet -- the Blaze3D GPU-buffer rewrite is outstanding")

fun setGuiProjection(matrix: org.joml.Matrix4f): Unit = notYetOnThisBand("the offscreen GUI projection")

@Suppress("unused")
fun applyModelView() = Unit

fun clearTarget(target: com.mojang.blaze3d.pipeline.RenderTarget): Unit = notYetOnThisBand("clearing a render target")

fun offscreenTarget(width: Int, height: Int): com.mojang.blaze3d.pipeline.TextureTarget =
    offscreenColourTarget(width, height)
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
*///?} elif >=1.21.5 {
/*fun entityTranslucent(texture: ResourceLocation): RenderType =
    net.minecraft.client.renderer.RenderType.entityTranslucent(texture)

fun lightning(): RenderType = net.minecraft.client.renderer.RenderType.lightning()

// Both of these go before the package move does. `gui()` is deleted at 1.21.5 and the chunk layers
// leave RenderType at 1.21.6, so naming either here would only work on one of this arm's two bands --
// and nothing calls them: their last shared-tree caller went when the monitor screen stopped asking for
// a GUI render type. They answer the same way the 1.21.9 arm does rather than pretending otherwise.
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
 * `RenderType.translucent()` was one of the chunk layers until 1.21.9, when the layers became their own
 * `ChunkSectionLayer` enum -- which is the honest shape, since a chunk layer was never a render type in
 * the sense the rest of this file uses the word. The seam is the instruction rather than the value.
 */
fun drawFluidTranslucent(fluid: net.minecraft.world.level.material.Fluid) {
    // From 26.1 there is nothing to say: a fluid's layer is part of its `FluidModel`, and
    // `FluidModel.Unbaked.bake` derives it from the transparency of the sprites themselves. Deliberately
    // empty there rather than renamed -- `ItemBlockRenderTypes` is gone from the game, not moved.
    //? if >=1.21.6 <26.1 {
    /*net.minecraft.client.renderer.ItemBlockRenderTypes.setRenderLayer(fluid, net.minecraft.client.renderer.chunk.ChunkSectionLayer.TRANSLUCENT)
    *///?} elif <1.21.6 {
    net.minecraft.client.renderer.ItemBlockRenderTypes.setRenderLayer(fluid, net.minecraft.client.renderer.RenderType.translucent())
    //?}
}

//? if >=1.21.5 {
/*/**
 * A handle ImGui can draw a loaded texture with.
 *
 * `AbstractTexture.texture` reads as a field on 1.21.5-1.21.8, where the access widener makes it
 * reachable, and as `getTexture()` from 1.21.9, where it is public -- Kotlin spells both the same way,
 * so one line covers all three bands.
 *
 * Null when the texture has not been uploaded yet, which is normal for a skin still being fetched: the
 * caller falls back to a label and asks again next frame.
 */
private fun textureHandle(id: ResourceLocation): Long? = runCatching {
    val loaded = net.minecraft.client.Minecraft.getInstance().textureManager.getTexture(id)
    //-? the accessor differs; see below
    gpuTextureOf(loaded)?.let { ImGuiTextures.handleFor(it) }
}.getOrNull()
*///?}

//? if >=1.21.9 {
/*private fun gpuTextureOf(t: net.minecraft.client.renderer.texture.AbstractTexture): com.mojang.blaze3d.textures.GpuTexture? =
    t.getTexture()
*///?} elif >=1.21.5 {
/*// The widened field. Kotlin will not synthesise a property here because the field itself is in scope.
private fun gpuTextureOf(t: net.minecraft.client.renderer.texture.AbstractTexture): com.mojang.blaze3d.textures.GpuTexture? =
    t.texture
*///?}

/**
 * The three things the editor's icon strip asks of a texture, and the one band that cannot answer.
 *
 * The skin's own type moved package at 1.21.9 as well, which is why the whole signature is switched
 * here rather than just the body. ImGui identifies a texture by an opaque handle, and until 1.21.6 that
 * handle was simply the OpenGL
 * name -- which the game handed out freely, and which is why [dev.ziggle.vscript.editor.IconRegion]
 * takes a `Long`. From 1.21.9 there are no GL names anywhere in the client: a texture is a `GpuTexture`
 * behind a `GpuTextureView`, and a render target's colour attachment is the same. Nothing can be
 * flattened to a long without the ImGui backend that consumes a view directly, which is a piece of work
 * in its own right (the RenderPipeline backend the plan calls S1) and is not done.
 *
 * So on that band these answer "no", and the picker falls back to the labels it already shows while a
 * preview is still rendering. The strip is poorer; nothing else in the editor is affected, and nothing
 * pretends to have drawn something it did not.
 */
/**
 * The texture handle for a player's head, by uuid.
 *
 * The skin comes from the player when the client can see them and from the uuid's default skin when it
 * cannot, so a row for someone across the world or logged out still shows a face rather than a hole.
 *
 * `PlayerSkin` -- an object with the skin's several parts -- arrived at 1.20.5; before it a player simply
 * had a texture id. The whole lookup lives here rather than at the call site because the type of "a
 * skin" is what changed, and a shared caller cannot name two of them.
 */
//? if >=1.21.9 {
/*fun playerHeadHandle(uuid: java.util.UUID): Long? {
    val skin = net.minecraft.client.Minecraft.getInstance().level?.players()?.firstOrNull { it.uuid == uuid }?.skin
        ?: net.minecraft.client.resources.DefaultPlayerSkin.get(uuid)
    return textureHandle(skin.body().texturePath())
}
*///?} elif >=1.21.5 {
/*fun playerHeadHandle(uuid: java.util.UUID): Long? {
    val skin = net.minecraft.client.Minecraft.getInstance().level?.players()?.firstOrNull { it.uuid == uuid }?.skin
        ?: net.minecraft.client.resources.DefaultPlayerSkin.get(uuid)
    return textureHandle(skin.texture())
}
*///?} elif >=1.20.5 {
fun playerHeadHandle(uuid: java.util.UUID): Long? {
    val skin = net.minecraft.client.Minecraft.getInstance().level?.players()?.firstOrNull { it.uuid == uuid }?.skin
        ?: net.minecraft.client.resources.DefaultPlayerSkin.get(uuid)
    return net.minecraft.client.Minecraft.getInstance().textureManager.getTexture(skin.texture()).id.toLong()
}
//?} else {
/*fun playerHeadHandle(uuid: java.util.UUID): Long? {
    val player = net.minecraft.client.Minecraft.getInstance().level?.players()?.firstOrNull { it.uuid == uuid }
    val texture = (player as? net.minecraft.client.player.AbstractClientPlayer)?.skinTextureLocation
        ?: net.minecraft.client.resources.DefaultPlayerSkin.getDefaultSkin(uuid)
    return net.minecraft.client.Minecraft.getInstance().textureManager.getTexture(texture).id.toLong()
}
*///?}

/** The handle of an offscreen target's colour attachment. See [playerHeadHandle] for why this can be null. */
fun targetHandle(target: com.mojang.blaze3d.pipeline.RenderTarget): Long? {
    //? if >=1.21.5 {
    /*return target.colorTexture?.let { ImGuiTextures.handleFor(it) }
    *///?} else {
    return target.colorTextureId.toLong()
    //?}
}

/**
 * Draw [stack] into [target] as a thumbnail, in a 16-unit GUI space against an identity model-view.
 *
 * One body here, and from 1.21.6 up NONE: those bands live in [bpm.platform.client] `ItemPreview`,
 * because getting an item's quads into a pass of our own is a page of code rather than a branch. The
 * arms are wrapped around the whole function so those bands declare nothing at all and the other file
 * owns the name.
 *
 * 1.21.5 is the ONLY band with no thumbnail, for the reason on its arm below; the picker falls back to a
 * label there. Everything from 1.21.6 up draws one, 26.1 included -- an earlier version of this note said
 * 1.21.9 answered false, which stopped being true when that band got its own body in `ItemPreviewGpu`.
 */
//? if >=1.21.5 <1.21.6 {
/*// 1.21.5 answers false for now. Its pipelines take loose uniforms and its render pass takes the target
// texture rather than a view, so the drawing half needs a third body; the quad routing above it would be
// identical. The picker falls back to labels here, as it did everywhere before this.
fun renderItemPreview(
    mc: net.minecraft.client.Minecraft,
    target: com.mojang.blaze3d.pipeline.RenderTarget,
    stack: net.minecraft.world.item.ItemStack,
): Boolean = false
*///?} elif <1.21.5 {
/**
 * Our own buffer source rather than the game's shared one.
 *
 * `mc.renderBuffers().bufferSource()` may already hold geometry another mod queued this frame; flushing
 * it here would draw that into this 96x96 texture, and leave ours to be flushed into theirs later. This
 * used to live in `BlockPreviewRenderer` and be passed in, until `MultiBufferSource` stopped existing on
 * every band -- the reason for it is unchanged, only which side of the seam it sits on.
 */
private val previewBuffers: net.minecraft.client.renderer.MultiBufferSource.BufferSource by lazy {
    //? if >=1.20.5 {
    net.minecraft.client.renderer.MultiBufferSource.immediate(com.mojang.blaze3d.vertex.ByteBufferBuilder(1536))
    //?} else {
    /*// The growable byte buffer behind a buffer source was still a BufferBuilder here; the split into a
    // raw ByteBufferBuilder and the builder that writes into it came later.
    net.minecraft.client.renderer.MultiBufferSource.immediate(com.mojang.blaze3d.vertex.BufferBuilder(1536))
    *///?}
}

fun renderItemPreview(
    mc: net.minecraft.client.Minecraft,
    target: com.mojang.blaze3d.pipeline.RenderTarget,
    stack: net.minecraft.world.item.ItemStack,
): Boolean {
    target.setClearColor(0f, 0f, 0f, 0f)
    clearTarget(target)
    target.bindWrite(true)
    com.mojang.blaze3d.systems.RenderSystem.backupProjectionMatrix()
    val modelView = com.mojang.blaze3d.systems.RenderSystem.getModelViewStack()
    //? if >=1.20.5 {
    modelView.pushMatrix()
    modelView.identity()
    //?} else {
    /*// A PoseStack rather than a Matrix4fStack: same stack, older spelling.
    modelView.pushPose()
    modelView.setIdentity()
    *///?}
    applyModelView()
    /*
     * A 16-unit GUI space against an IDENTITY model-view, both set here.
     *
     * This used to set only the projection, with a 1000..21000 depth range chosen to match the -11000 Z
     * push the vanilla GUI model-view happens to carry -- that is, it borrowed whatever model-view the
     * frame had left lying around. It works in a plain client and fails wherever another mod has set a
     * different one, because then the item lands outside the depth range and clips away silently.
     */
    setGuiProjection(org.joml.Matrix4f().setOrtho(0f, 16f, 16f, 0f, -1000f, 1000f))
    val graphics = net.minecraft.client.gui.GuiGraphics(mc, previewBuffers)
    try {
        graphics.renderItem(stack, 0, 0)
        graphics.flush()
    } finally {
        //? if >=1.20.5 {
        modelView.popMatrix()
        //?} else {
        /*modelView.popPose()
        *///?}
        applyModelView()
        com.mojang.blaze3d.systems.RenderSystem.restoreProjectionMatrix()
        target.unbindWrite()
        mc.mainRenderTarget.bindWrite(true)
    }
    return true
}
//?}

/**
 * Grab the main render target as an image.
 *
 * `Screenshot.takeScreenshot` returned the image until 1.21.9 and now hands it to a consumer instead --
 * the read back off the GPU is asynchronous there, so it cannot return one. The dev harness wants a
 * value, so this waits for it; a screenshot command has nothing better to do meanwhile.
 */
fun grabScreenshot(target: com.mojang.blaze3d.pipeline.RenderTarget): com.mojang.blaze3d.platform.NativeImage {
    //? if >=1.21.5 {
    /*val held = java.util.concurrent.CompletableFuture<com.mojang.blaze3d.platform.NativeImage>()
    net.minecraft.client.Screenshot.takeScreenshot(target) { held.complete(it) }
    return held.get(10, java.util.concurrent.TimeUnit.SECONDS)
    *///?} else {
    return net.minecraft.client.Screenshot.takeScreenshot(target)
    //?}
}
