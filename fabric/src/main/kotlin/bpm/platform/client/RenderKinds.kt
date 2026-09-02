package bpm.platform.client

import net.minecraft.client.renderer.RenderStateShard
import bpm.platform.RenderType
import bpm.platform.ResourceLocation

/**
 * Rendering by EFFECT, not by name.
 *
 * The rule the whole port follows here is the one in the compatibility plan: do not abstract a
 * `RenderType`, abstract what it is FOR. These are the four things the mod actually asks the renderer,
 * and each is spelled differently -- or has to be built by hand -- on the far side of 1.21.2.
 */

/**
 * A translucent entity surface that culls its back faces.
 *
 * `RenderType.entityTranslucentCull` was deleted at 1.21.2, along with its core shader; the surviving
 * `entityTranslucent` explicitly sets `NO_CULL`, so it is not a rename and substituting it would change
 * what is drawn. Every user of this draws a CLOSED shape -- a mote cube, a monitor panel -- where the
 * far faces are behind the near ones; with culling off they blend through and the thing reads brighter
 * and muddier than it should.
 *
 * So on the newer band the type is rebuilt here, from vanilla's own 1.21.1 recipe: same vertex format,
 * same buffer size, same transparency, lightmap and overlay, and no `NO_CULL`. The shader is the plain
 * entity-translucent one, which is what the deleted pair always shared -- culling is a state, not a
 * program. Memoized per texture, because a `RenderType` is compared by identity when the buffer source
 * sorts its layers, and building one per frame would defeat that.
 */
//? if >=1.21.2 {
/*private val translucentCullCache = HashMap<ResourceLocation, RenderType>()

fun translucentCull(texture: ResourceLocation): RenderType = translucentCullCache.getOrPut(texture) {
    RenderType.create(
        "bpm_entity_translucent_cull",
        com.mojang.blaze3d.vertex.DefaultVertexFormat.NEW_ENTITY,
        com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS,
        1536,
        true,
        true,
        net.minecraft.client.renderer.RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
            .setTextureState(RenderStateShard.TextureStateShard(texture, net.minecraft.util.TriState.FALSE, false))
            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
            .setLightmapState(RenderStateShard.LIGHTMAP)
            .setOverlayState(RenderStateShard.OVERLAY)
            .createCompositeState(true),
    )
}

/** The plain position+colour program, as a shard for a hand-built type. */
fun positionColorShader(): RenderStateShard.ShaderStateShard =
    RenderStateShard.ShaderStateShard(net.minecraft.client.renderer.CoreShaders.POSITION_COLOR)

/** Bind the lines program for a raw `RenderSystem` pass. */
fun useLinesShader() {
    com.mojang.blaze3d.systems.RenderSystem.setShader(net.minecraft.client.renderer.CoreShaders.RENDERTYPE_LINES)
}

/** `LevelRenderer.renderLineBox` moved to `ShapeRenderer` wholesale. */
fun lineBox(
    pose: com.mojang.blaze3d.vertex.PoseStack,
    builder: com.mojang.blaze3d.vertex.VertexConsumer,
    box: net.minecraft.world.phys.AABB,
    r: Float, g: Float, b: Float, a: Float,
) = net.minecraft.client.renderer.ShapeRenderer.renderLineBox(pose, builder, box, r, g, b, a)

/**
 * An orthographic projection for drawing into an offscreen GUI-space target.
 *
 * The sort order used to be named by a `VertexSorting`; it is a `ProjectionType` now, which says the
 * same thing about an orthographic pass and also tells the renderer how to order translucency.
 */
fun setGuiProjection(matrix: org.joml.Matrix4f) =
    com.mojang.blaze3d.systems.RenderSystem.setProjectionMatrix(matrix, com.mojang.blaze3d.ProjectionType.ORTHOGRAPHIC)

/**
 * Push the model-view stack through to the GL state.
 *
 * A no-op from 1.21.2: the stack is read where it is used rather than mirrored into a uniform, so there
 * is nothing left to apply. Kept as a call so the sites that legitimately changed the stack still read
 * as a matched pair.
 */
@Suppress("unused")
fun applyModelView() = Unit

/** `RenderTarget.clear` lost its "on macOS" argument. */
fun clearTarget(target: com.mojang.blaze3d.pipeline.RenderTarget) = target.clear()

/**
 * An offscreen colour+depth target to draw an item into.
 *
 * `TextureTarget`'s fourth argument used to be "are we on macOS" -- a driver workaround -- and is
 * `useStencil` from 1.21.2. Same arity, different MEANING, which is the one shape of change that
 * compiles silently and does the wrong thing: on NeoForge, which keeps a four-argument overload, passing
 * the old value asks for a stencil buffer on exactly the machines the workaround was for. Fabric has
 * only the three-argument form and refused it, which is how this was caught at all.
 */
fun offscreenTarget(width: Int, height: Int): com.mojang.blaze3d.pipeline.TextureTarget =
    com.mojang.blaze3d.pipeline.TextureTarget(width, height, true)
*///?} else {
private val translucentCullCache = HashMap<ResourceLocation, RenderType>()

fun translucentCull(texture: ResourceLocation): RenderType = translucentCullCache.getOrPut(texture) {
    RenderType.entityTranslucentCull(texture)
}

fun positionColorShader(): RenderStateShard.ShaderStateShard =
    RenderStateShard.ShaderStateShard(net.minecraft.client.renderer.GameRenderer::getPositionColorShader)

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
//?}

/**
 * The stock render types this mod asks for.
 *
 * 1.21.9 moved `RenderType` into its own package AND split the factories out into a separate
 * `RenderTypes`. The type is aliased in [bpm.platform.RenderType]; these are the calls, and there are
 * only four of them because everything else the mod draws is one of its own composites.
 */
//? if >=1.21.9 {
/*fun entityTranslucent(texture: ResourceLocation): RenderType =
    net.minecraft.client.renderer.rendertype.RenderTypes.entityTranslucent(texture)

fun lightning(): RenderType = net.minecraft.client.renderer.rendertype.RenderTypes.lightning()

fun translucent(): RenderType = net.minecraft.client.renderer.rendertype.RenderTypes.translucent()

fun gui(): RenderType = net.minecraft.client.renderer.rendertype.RenderTypes.gui()
*///?} else {
fun entityTranslucent(texture: ResourceLocation): RenderType =
    net.minecraft.client.renderer.RenderType.entityTranslucent(texture)

fun lightning(): RenderType = net.minecraft.client.renderer.RenderType.lightning()

fun translucent(): RenderType = net.minecraft.client.renderer.RenderType.translucent()

fun gui(): RenderType = net.minecraft.client.renderer.RenderType.gui()
//?}
