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

/** Blending is one field of the colour target's state now, rather than a builder call of its own. */
internal fun com.mojang.blaze3d.pipeline.RenderPipeline.Builder.withBlending(
    blend: com.mojang.blaze3d.pipeline.BlendFunction,
): com.mojang.blaze3d.pipeline.RenderPipeline.Builder =
    withColorTargetState(com.mojang.blaze3d.pipeline.ColorTargetState(blend))

/**
 * Depth writing, which is the other half of the same state record.
 *
 * `withDepthWrite` is gone with `withBlend`, and the compare op has to be supplied with it.
 *
 * GREATER_THAN_OR_EQUAL, because 26.x uses REVERSED-Z: near is 1.0, far is 0.0, and the buffer clears to
 * far. That is what `DepthStencilState.DEFAULT` is built with. An earlier version of this said
 * LESS_THAN_OR_EQUAL and claimed it was vanilla's default; it is the exact opposite, and the effect was
 * that every world effect drawn through here vanished against the sky while still appearing in front of
 * a block -- depth 0.7 <= 0.0 fails against cleared sky, but 0.7 <= 0.8 passes against a nearer block.
 *
 * Read it off `DepthStencilState.DEFAULT` rather than assuming, if this ever needs revisiting.
 */
internal fun com.mojang.blaze3d.pipeline.RenderPipeline.Builder.withDepthWriting(
    write: Boolean,
): com.mojang.blaze3d.pipeline.RenderPipeline.Builder =
    withDepthStencilState(
        com.mojang.blaze3d.pipeline.DepthStencilState(com.mojang.blaze3d.platform.CompareOp.GREATER_THAN_OR_EQUAL, write)
    )

/**
 * Declare the `Globals` block, for a shader that reads `GameTime` or the screen size.
 *
 * Vanilla's own pipelines get this from `GLOBALS_SNIPPET`, which is private at 26.1 along with the rest
 * of them -- so a mod pipeline whose GLSL says `#moj_import <minecraft:globals.glsl>` has to declare the
 * block itself or the uniform is simply never bound. That failure is SILENT: the pipeline registers, the
 * geometry is submitted, and nothing appears. It is what stopped the rift drawing while the transfer
 * items beside it drew fine.
 *
 * Separate from [matricesBuilder] rather than folded into it, because the beam and the arc use vanilla's
 * `core/position_color` and read no globals; declaring a block they never sample would be describing
 * their shaders wrongly.
 */
internal fun com.mojang.blaze3d.pipeline.RenderPipeline.Builder.withGlobals(): com.mojang.blaze3d.pipeline.RenderPipeline.Builder =
    withBindGroupLayout(
        com.mojang.blaze3d.pipeline.BindGroupLayout.builder()
            .withUniform("Globals", com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER)
            .build()
    )

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

internal fun com.mojang.blaze3d.pipeline.RenderPipeline.Builder.withBlending(
    blend: com.mojang.blaze3d.pipeline.BlendFunction,
): com.mojang.blaze3d.pipeline.RenderPipeline.Builder = withBlend(blend)

internal fun com.mojang.blaze3d.pipeline.RenderPipeline.Builder.withDepthWriting(
    write: Boolean,
): com.mojang.blaze3d.pipeline.RenderPipeline.Builder = withDepthWrite(write)

/** Vanilla's snippets already declare the globals on this band, so there is nothing to add. */
internal fun com.mojang.blaze3d.pipeline.RenderPipeline.Builder.withGlobals(): com.mojang.blaze3d.pipeline.RenderPipeline.Builder = this

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

internal fun com.mojang.blaze3d.pipeline.RenderPipeline.Builder.withBlending(
    blend: com.mojang.blaze3d.pipeline.BlendFunction,
): com.mojang.blaze3d.pipeline.RenderPipeline.Builder = withBlend(blend)

internal fun com.mojang.blaze3d.pipeline.RenderPipeline.Builder.withDepthWriting(
    write: Boolean,
): com.mojang.blaze3d.pipeline.RenderPipeline.Builder = withDepthWrite(write)

/** Vanilla's snippets already declare the globals on this band, so there is nothing to add. */
internal fun com.mojang.blaze3d.pipeline.RenderPipeline.Builder.withGlobals(): com.mojang.blaze3d.pipeline.RenderPipeline.Builder = this

internal fun offscreenColourTarget(width: Int, height: Int): com.mojang.blaze3d.pipeline.TextureTarget =
    com.mojang.blaze3d.pipeline.TextureTarget(null, width, height, true)
*///?} elif >=1.21.5 {
/*internal fun matricesBuilder(): com.mojang.blaze3d.pipeline.RenderPipeline.Builder =
    com.mojang.blaze3d.pipeline.RenderPipeline.builder(net.minecraft.client.renderer.RenderPipelines.MATRICES_SNIPPET)

internal fun com.mojang.blaze3d.pipeline.RenderPipeline.Builder.withQuads(
    format: com.mojang.blaze3d.vertex.VertexFormat,
): com.mojang.blaze3d.pipeline.RenderPipeline.Builder =
    withVertexFormat(format, com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS)

internal fun com.mojang.blaze3d.pipeline.RenderPipeline.Builder.withBlending(
    blend: com.mojang.blaze3d.pipeline.BlendFunction,
): com.mojang.blaze3d.pipeline.RenderPipeline.Builder = withBlend(blend)

internal fun com.mojang.blaze3d.pipeline.RenderPipeline.Builder.withDepthWriting(
    write: Boolean,
): com.mojang.blaze3d.pipeline.RenderPipeline.Builder = withDepthWrite(write)

/** Vanilla's snippets already declare the globals on this band, so there is nothing to add. */
internal fun com.mojang.blaze3d.pipeline.RenderPipeline.Builder.withGlobals(): com.mojang.blaze3d.pipeline.RenderPipeline.Builder = this

internal fun offscreenColourTarget(width: Int, height: Int): com.mojang.blaze3d.pipeline.TextureTarget =
    com.mojang.blaze3d.pipeline.TextureTarget(null, width, height, true)
*///?}
