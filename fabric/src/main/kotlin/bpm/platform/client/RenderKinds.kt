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

fun useLinesShader(): Unit = notYetOnThisBand("the raw line pass")

fun lineBox(
    pose: com.mojang.blaze3d.vertex.PoseStack,
    builder: com.mojang.blaze3d.vertex.VertexConsumer,
    box: net.minecraft.world.phys.AABB,
    r: Float, g: Float, b: Float, a: Float,
): Unit = notYetOnThisBand("line boxes")

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

fun useLinesShader() {
    com.mojang.blaze3d.systems.RenderSystem.setShader(net.minecraft.client.renderer.CoreShaders.RENDERTYPE_LINES)
}

fun lineBox(
    pose: com.mojang.blaze3d.vertex.PoseStack,
    builder: com.mojang.blaze3d.vertex.VertexConsumer,
    box: net.minecraft.world.phys.AABB,
    r: Float, g: Float, b: Float, a: Float,
) = net.minecraft.client.renderer.ShapeRenderer.renderLineBox(pose, builder, box, r, g, b, a)

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

fun useLinesShader() {
    com.mojang.blaze3d.systems.RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getRendertypeLinesShader)
}

fun lineBox(
    pose: com.mojang.blaze3d.vertex.PoseStack,
    builder: com.mojang.blaze3d.vertex.VertexConsumer,
    box: net.minecraft.world.phys.AABB,
    r: Float, g: Float, b: Float, a: Float,
) = net.minecraft.client.renderer.LevelRenderer.renderLineBox(pose, builder, box, r, g, b, a)

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
