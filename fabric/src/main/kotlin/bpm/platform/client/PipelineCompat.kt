package bpm.platform.client

/*
 * Building a `RenderPipeline`, which 26.1 re-shaped.
 *
 * From 1.21.5 a custom render type is a pipeline, and this mod builds four of them: two for the rift,
 * one for the assembler beam and one for the energy arc. The BUILDER changed at 26.1 in four ways, and
 * three of them would be silent rather than loud if a call site tried to carry on unchanged -- so they
 * are named here once instead of being fixed four times.
 *
 *  1. The uniform and sampler declaration left the builder for a `BindGroupLayout`. Vanilla's own
 *     snippets, which used to supply it, are PRIVATE at 26.1 -- `MATRICES_PROJECTION_SNIPPET` cannot be
 *     named at all -- so a mod pipeline states its own. The names below are read off the shaders these
 *     pipelines use (vanilla's `core/position_color` and `core/position_tex_color`), both of which still
 *     declare `Projection` and `DynamicTransforms` exactly as before.
 *  2. `withVertexFormat(format, mode)` split into `withVertexBinding(index, format)` and
 *     `withPrimitiveTopology(topology)`.
 *  3. Blend and depth became state records. An EMPTY depth-stencil is what "no depth test and no depth
 *     write" is spelled as now -- not a disabled one.
 *  4. `RenderSetup` no longer takes a buffer size; it works the size out.
 *
 * Everything here is bounded below at 1.21.5, because below that a render type is state shards and none
 * of these types exist.
 */

//? if >=26.1 {
/*/** What `core/position_color` and `core/position_tex_color` declare, minus their samplers. */
private val matricesBindings: com.mojang.blaze3d.pipeline.BindGroupLayout =
    com.mojang.blaze3d.pipeline.BindGroupLayout.builder()
        .withUniform("Projection", com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER)
        .withUniform("DynamicTransforms", com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER)
        .build()

internal fun matricesBuilder(): com.mojang.blaze3d.pipeline.RenderPipeline.Builder =
    com.mojang.blaze3d.pipeline.RenderPipeline.builder().withBindGroupLayout(matricesBindings)

internal fun com.mojang.blaze3d.pipeline.RenderPipeline.Builder.withExtraSampler(
    name: String,
): com.mojang.blaze3d.pipeline.RenderPipeline.Builder =
    withBindGroupLayout(com.mojang.blaze3d.pipeline.BindGroupLayout.builder().withSampler(name).build())

internal fun com.mojang.blaze3d.pipeline.RenderPipeline.Builder.withQuads(
    format: com.mojang.blaze3d.vertex.VertexFormat,
): com.mojang.blaze3d.pipeline.RenderPipeline.Builder =
    withVertexBinding(0, format).withPrimitiveTopology(com.mojang.blaze3d.PrimitiveTopology.QUADS)

internal fun com.mojang.blaze3d.pipeline.RenderPipeline.Builder.withoutDepth(): com.mojang.blaze3d.pipeline.RenderPipeline.Builder =
    withDepthStencilState(java.util.Optional.empty<com.mojang.blaze3d.pipeline.DepthStencilState>())

internal fun quadRenderSetup(
    pipeline: com.mojang.blaze3d.pipeline.RenderPipeline,
): net.minecraft.client.renderer.rendertype.RenderSetup =
    net.minecraft.client.renderer.rendertype.RenderSetup.builder(pipeline).createRenderSetup()

internal fun offscreenColourTarget(width: Int, height: Int): com.mojang.blaze3d.pipeline.TextureTarget =
    com.mojang.blaze3d.pipeline.TextureTarget(
        "bpm_preview", width, height, true, com.mojang.blaze3d.GpuFormat.RGBA8_UNORM,
    )
*///?} elif >=1.21.9 {
/*internal fun matricesBuilder(): com.mojang.blaze3d.pipeline.RenderPipeline.Builder =
    com.mojang.blaze3d.pipeline.RenderPipeline.builder(net.minecraft.client.renderer.RenderPipelines.MATRICES_PROJECTION_SNIPPET)

internal fun com.mojang.blaze3d.pipeline.RenderPipeline.Builder.withExtraSampler(
    name: String,
): com.mojang.blaze3d.pipeline.RenderPipeline.Builder = withSampler(name)

internal fun com.mojang.blaze3d.pipeline.RenderPipeline.Builder.withQuads(
    format: com.mojang.blaze3d.vertex.VertexFormat,
): com.mojang.blaze3d.pipeline.RenderPipeline.Builder =
    withVertexFormat(format, com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS)

internal fun com.mojang.blaze3d.pipeline.RenderPipeline.Builder.withoutDepth(): com.mojang.blaze3d.pipeline.RenderPipeline.Builder =
    withDepthTestFunction(com.mojang.blaze3d.platform.DepthTestFunction.NO_DEPTH_TEST).withDepthWrite(false)

internal fun quadRenderSetup(
    pipeline: com.mojang.blaze3d.pipeline.RenderPipeline,
): net.minecraft.client.renderer.rendertype.RenderSetup =
    net.minecraft.client.renderer.rendertype.RenderSetup.builder(pipeline).bufferSize(256).createRenderSetup()

internal fun offscreenColourTarget(width: Int, height: Int): com.mojang.blaze3d.pipeline.TextureTarget =
    com.mojang.blaze3d.pipeline.TextureTarget(null, width, height, true)
*///?} elif >=1.21.6 {
/*internal fun matricesBuilder(): com.mojang.blaze3d.pipeline.RenderPipeline.Builder =
    com.mojang.blaze3d.pipeline.RenderPipeline.builder(net.minecraft.client.renderer.RenderPipelines.MATRICES_PROJECTION_SNIPPET)

internal fun com.mojang.blaze3d.pipeline.RenderPipeline.Builder.withQuads(
    format: com.mojang.blaze3d.vertex.VertexFormat,
): com.mojang.blaze3d.pipeline.RenderPipeline.Builder =
    withVertexFormat(format, com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS)

internal fun offscreenColourTarget(width: Int, height: Int): com.mojang.blaze3d.pipeline.TextureTarget =
    com.mojang.blaze3d.pipeline.TextureTarget(null, width, height, true)
*///?} elif >=1.21.5 {
/*internal fun matricesBuilder(): com.mojang.blaze3d.pipeline.RenderPipeline.Builder =
    com.mojang.blaze3d.pipeline.RenderPipeline.builder(net.minecraft.client.renderer.RenderPipelines.MATRICES_SNIPPET)

internal fun com.mojang.blaze3d.pipeline.RenderPipeline.Builder.withQuads(
    format: com.mojang.blaze3d.vertex.VertexFormat,
): com.mojang.blaze3d.pipeline.RenderPipeline.Builder =
    withVertexFormat(format, com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS)

internal fun offscreenColourTarget(width: Int, height: Int): com.mojang.blaze3d.pipeline.TextureTarget =
    com.mojang.blaze3d.pipeline.TextureTarget(null, width, height, true)
*///?}
