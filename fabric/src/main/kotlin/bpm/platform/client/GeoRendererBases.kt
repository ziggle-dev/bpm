package bpm.platform.client

import net.minecraft.world.phys.Vec3
import software.bernie.geckolib.animatable.GeoAnimatable
import software.bernie.geckolib.model.GeoModel

/**
 * What a renderer wants from the model's bones, said once.
 *
 * The mod asks two things of GeckoLib's bone walk: where a particular bone ended up, so an effect can
 * hang off it, and please do not draw these ones. Until 5.x both were expressed by overriding
 * `renderRecursively` -- intercepting the recursion, reading `bone.name`, and either capturing the pose
 * or returning early.
 *
 * 5.x deleted that recursion and gave both questions their own answer: `addBonePositionListener`, which
 * hands back the WORLD position directly instead of leaving the caller to combine the local pose with
 * the camera; and a bone updater that can `skipRender` a bone by name. Both are better than what they
 * replace, which is why this seam is shaped like them and the older version is the one doing the work
 * to comply.
 */
interface BoneAccess {

    /** Report where [bone] lands in the world, each frame it is drawn. */
    fun watch(bone: String, onWorldPosition: (Vec3) -> Unit)

    /** Do not draw these bones this frame. */
    fun hide(bones: Set<String>)

    /**
     * Point [bone] at these rotations, in radians, overriding whatever the animation put there.
     *
     * Null leaves an axis to the animation. Until 5.x this was done by reaching into the live GeoBone
     * mid-recursion and calling a setter on it; 5.x made bones immutable shared geometry and moved
     * per-frame transforms into a snapshot, which is the honest model -- one baked model is drawn once
     * per block, and writing to it was only ever safe because the write happened inside the draw.
     */
    fun turn(bone: String, rotX: Float? = null, rotY: Float? = null)
}

/**
 * Somewhere to draw, once the model itself has been drawn.
 *
 * The mod hangs two things off its block models: a line of text over the turret, and a monitor's screen
 * contents. Until 1.21.9 a block entity renderer was handed a `MultiBufferSource` and drew immediately.
 * From 1.21.9 it is handed a collector and SUBMITS -- the draw happens later, in a pass the renderer does
 * not control, against a pose captured now.
 *
 * That difference is why this hands a callback a `Pose` rather than returning a `VertexConsumer`: a
 * consumer can be returned only by the band that draws immediately. Written this way both bands are
 * honest, and the caller reads the same on each.
 */
interface WorldDraw {

    /** Draw geometry of [kind], at the pose currently on [poseStack]. */
    fun into(
        poseStack: com.mojang.blaze3d.vertex.PoseStack,
        kind: bpm.platform.RenderType,
        draw: (com.mojang.blaze3d.vertex.PoseStack.Pose, com.mojang.blaze3d.vertex.VertexConsumer) -> Unit,
    )

    /** Draw [text] at the pose currently on [poseStack], offset by [x] and [y] in its own plane. */
    fun text(
        poseStack: com.mojang.blaze3d.vertex.PoseStack,
        text: net.minecraft.util.FormattedCharSequence,
        x: Float,
        y: Float,
        colour: Int,
        dropShadow: Boolean,
        mode: net.minecraft.client.gui.Font.DisplayMode,
        backgroundColour: Int,
        packedLight: Int,
    )

    /** Draw [stack] as an item model, at the pose currently on [poseStack]. */
    fun item(
        poseStack: com.mojang.blaze3d.vertex.PoseStack,
        stack: net.minecraft.world.item.ItemStack,
        context: net.minecraft.world.item.ItemDisplayContext,
        packedLight: Int,
        packedOverlay: Int,
    )
}

//? if >=1.21.9 {
/*/** A block entity's render state, carrying GeckoLib's per-frame data alongside Minecraft's. */
class BpmBlockRenderState :
    net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState(),
    software.bernie.geckolib.renderer.base.GeoRenderState {
    private val data = HashMap<software.bernie.geckolib.constant.dataticket.DataTicket<*>, Any>()
    override fun getDataMap(): MutableMap<software.bernie.geckolib.constant.dataticket.DataTicket<*>, Any> = data
}

/**
 * The same for an entity, plus the one thing this mod needs that vanilla's state does not carry.
 *
 * Only the ID, deliberately. A renderer that wants more looks the entity back up on the client, which
 * costs a map lookup once a frame and keeps this class from growing a field per renderer. The entity
 * itself must not be held here: the whole point of a render state is that the draw pass runs without it.
 */
class BpmEntityRenderState :
    net.minecraft.client.renderer.entity.state.EntityRenderState(),
    software.bernie.geckolib.renderer.base.GeoRenderState {
    var entityId: Int = 0
    private val data = HashMap<software.bernie.geckolib.constant.dataticket.DataTicket<*>, Any>()
    override fun getDataMap(): MutableMap<software.bernie.geckolib.constant.dataticket.DataTicket<*>, Any> = data
}



/** [BoneAccess] over a render pass: listeners for the watches, one updater for the hides. */
private class PassBones<R : software.bernie.geckolib.renderer.base.GeoRenderState>(
    private val pass: software.bernie.geckolib.renderer.base.RenderPassInfo<R>,
) : BoneAccess {

    override fun watch(bone: String, onWorldPosition: (Vec3) -> Unit) {
        pass.addBonePositionListener(bone) { world, _, _ -> world?.let(onWorldPosition) }
    }

    override fun hide(bones: Set<String>) {
        if (bones.isEmpty()) return
        pass.addBoneUpdater { _, snapshots -> bones.forEach { name -> snapshots.ifPresent(name) { it.skipRender(true) } } }
    }

    override fun turn(bone: String, rotX: Float?, rotY: Float?) {
        if (rotX == null && rotY == null) return
        pass.addBoneUpdater { _, snapshots ->
            snapshots.ifPresent(bone) { snapshot ->
                rotX?.let { snapshot.setRotX(it) }
                rotY?.let { snapshot.setRotY(it) }
            }
        }
    }
}

/** [WorldDraw] over a submit collector: everything is deferred, against the pose captured now. */
private class CollectorDraw(private val collector: net.minecraft.client.renderer.SubmitNodeCollector) : WorldDraw {

    override fun into(
        poseStack: com.mojang.blaze3d.vertex.PoseStack,
        kind: bpm.platform.RenderType,
        draw: (com.mojang.blaze3d.vertex.PoseStack.Pose, com.mojang.blaze3d.vertex.VertexConsumer) -> Unit,
    ) {
        collector.submitCustomGeometry(poseStack, kind) { pose, buffer -> draw(pose, buffer) }
    }

    override fun text(
        poseStack: com.mojang.blaze3d.vertex.PoseStack,
        text: net.minecraft.util.FormattedCharSequence,
        x: Float,
        y: Float,
        colour: Int,
        dropShadow: Boolean,
        mode: net.minecraft.client.gui.Font.DisplayMode,
        backgroundColour: Int,
        packedLight: Int,
    ) {
        collector.submitText(poseStack, x, y, text, dropShadow, mode, packedLight, colour, backgroundColour, 0)
    }

    /**
     * An item, resolved to a render state and submitted.
     *
     * `ItemRenderer.renderStatic` is gone: an item is now resolved to a stack of layers first and drawn
     * from those. A fresh state per call is a small allocation on a path that draws a handful of items a
     * frame; if that ever matters, the state is exactly the kind of thing that would be cached per widget.
     */
    override fun item(
        poseStack: com.mojang.blaze3d.vertex.PoseStack,
        stack: net.minecraft.world.item.ItemStack,
        context: net.minecraft.world.item.ItemDisplayContext,
        packedLight: Int,
        packedOverlay: Int,
    ) {
        val mc = net.minecraft.client.Minecraft.getInstance()
        val state = net.minecraft.client.renderer.item.ItemStackRenderState()
        mc.itemModelResolver.updateForTopItem(state, stack, context, mc.level, null, 0)
        state.submit(poseStack, collector, packedLight, packedOverlay, 0)
    }
}

abstract class GeoBlockRendererBase<T>(model: GeoModel<T>) :
    software.bernie.geckolib.renderer.GeoBlockRenderer<T, BpmBlockRenderState>(model)
    where T : net.minecraft.world.level.block.entity.BlockEntity, T : GeoAnimatable {

    override fun createRenderState(): BpmBlockRenderState = BpmBlockRenderState()

    /**
     * Declare the bones this renderer watches or hides, for the block at [pos] in [state].
     *
     * The position and the state are given rather than looked up because on this band the animatable is
     * long gone by render time -- the render state is all there is, and it happens to carry both.
     */
    protected open fun onBones(bones: BoneAccess, pos: net.minecraft.core.BlockPos, state: net.minecraft.world.level.block.state.BlockState) {}

    override fun preRenderPass(
        pass: software.bernie.geckolib.renderer.base.RenderPassInfo<BpmBlockRenderState>,
        collector: net.minecraft.client.renderer.SubmitNodeCollector,
    ) {
        onBones(PassBones(pass), pass.renderState().blockPos, pass.renderState().blockState)
        super.preRenderPass(pass, collector)
    }

    /** Attach the glow layer. See [BpmGlowLayer] for why the class itself is not shared. */
    protected fun addGlow() {
        withRenderLayer(BpmGlowLayer(this))
    }

    /** The render type this renderer draws its texture with. */
    protected open fun renderTypeFor(texture: bpm.platform.ResourceLocation): bpm.platform.RenderType =
        entityTranslucent(texture)

    override fun getRenderType(state: BpmBlockRenderState, texture: bpm.platform.ResourceLocation): bpm.platform.RenderType =
        renderTypeFor(texture)

    /** Whether to skip the frustum test. `shouldRenderOffScreen` lost its argument here. */
    protected open fun alwaysRender(): Boolean = false

    override fun shouldRenderOffScreen(): Boolean = alwaysRender()

    /** Which way the block faces; `getFacing` became `getBlockStateDirection`. */
    protected open fun facingOf(blockEntity: T): net.minecraft.core.Direction =
        net.minecraft.core.Direction.NORTH

    override fun getBlockStateDirection(blockEntity: T): net.minecraft.core.Direction = facingOf(blockEntity)

    /**
     * Draw whatever this renderer hangs off the model, once the model itself is submitted.
     *
     * The block entity is looked back up here rather than carried on the render state. A render state
     * exists so the draw pass can run without touching the world, and putting a block entity on one
     * would defeat that; a client-side lookup by position costs a map read once a frame.
     */
    protected open fun afterModel(blockEntity: T, poseStack: com.mojang.blaze3d.vertex.PoseStack, draw: WorldDraw, partialTick: Float, packedLight: Int) {}

    override fun submit(
        state: BpmBlockRenderState,
        poseStack: com.mojang.blaze3d.vertex.PoseStack,
        collector: net.minecraft.client.renderer.SubmitNodeCollector,
        cameraState: net.minecraft.client.renderer.state.CameraRenderState,
    ) {
        super.submit(state, poseStack, collector, cameraState)
        @Suppress("UNCHECKED_CAST")
        val blockEntity = net.minecraft.client.Minecraft.getInstance().level?.getBlockEntity(state.blockPos) as? T ?: return
        afterModel(blockEntity, poseStack, CollectorDraw(collector), state.partialTick, state.packedLight)
    }


}

abstract class GeoEntityRendererBase<T>(
    context: net.minecraft.client.renderer.entity.EntityRendererProvider.Context,
    model: GeoModel<T>,
) : software.bernie.geckolib.renderer.GeoEntityRenderer<T, BpmEntityRenderState>(context, model)
    where T : net.minecraft.world.entity.Entity, T : GeoAnimatable {

    /**
     * The no-argument `createRenderState` is final here; this two-argument one is the hook GeckoLib
     * documents for "a different subclass of EntityRenderState", which is exactly what this is.
     */
    override fun createRenderState(animatable: T, relatedObject: Void?): BpmEntityRenderState = BpmEntityRenderState()

    override fun extractRenderState(entity: T, state: BpmEntityRenderState, partialTick: Float) {
        super.extractRenderState(entity, state, partialTick)
        state.entityId = entity.id
    }

    /** Declare the bones this renderer watches or hides, for the entity with [entityId]. */
    protected open fun onBones(bones: BoneAccess, entityId: Int) {}

    override fun preRenderPass(
        pass: software.bernie.geckolib.renderer.base.RenderPassInfo<BpmEntityRenderState>,
        collector: net.minecraft.client.renderer.SubmitNodeCollector,
    ) {
        onBones(PassBones(pass), pass.renderState().entityId)
        super.preRenderPass(pass, collector)
    }

    protected fun addGlow() {
        withRenderLayer(BpmGlowLayer(this))
    }

    protected open fun renderTypeFor(texture: bpm.platform.ResourceLocation): bpm.platform.RenderType =
        entityTranslucent(texture)

    override fun getRenderType(state: BpmEntityRenderState, texture: bpm.platform.ResourceLocation): bpm.platform.RenderType =
        renderTypeFor(texture)

}

abstract class GeoItemRendererBase<T>(model: GeoModel<T>) :
    software.bernie.geckolib.renderer.GeoItemRenderer<T>(model)
    where T : net.minecraft.world.item.Item, T : GeoAnimatable {

    protected open fun onBones(bones: BoneAccess) {}

    override fun preRenderPass(
        pass: software.bernie.geckolib.renderer.base.RenderPassInfo<software.bernie.geckolib.renderer.base.GeoRenderState>,
        collector: net.minecraft.client.renderer.SubmitNodeCollector,
    ) {
        onBones(PassBones(pass))
        super.preRenderPass(pass, collector)
    }

    protected fun addGlow() {
        withRenderLayer(BpmGlowLayer(this))
    }

    protected open fun renderTypeFor(texture: bpm.platform.ResourceLocation): bpm.platform.RenderType =
        entityTranslucent(texture)

    override fun getRenderType(state: software.bernie.geckolib.renderer.base.GeoRenderState, texture: bpm.platform.ResourceLocation): bpm.platform.RenderType =
        renderTypeFor(texture)

    /**
     * Put the stack being drawn on the render state, so [actorStack] can find it.
     *
     * GeckoLib hands the stack to the renderer as render data and no longer publishes it as a ticket of
     * its own; the Molang variables that ask what is bound to a linker need it, and this is the last
     * point at which anyone has it.
     */
    override fun captureDefaultRenderState(
        animatable: T,
        renderData: software.bernie.geckolib.renderer.GeoItemRenderer.RenderData,
        state: software.bernie.geckolib.renderer.base.GeoRenderState,
        partialTick: Float,
    ) {
        super.captureDefaultRenderState(animatable, renderData, state, partialTick)
        state.addGeckolibData(ITEM_STACK, renderData.itemStack())
    }

}
*///?} else {


/**
 * [BoneAccess] over the old recursion.
 *
 * The watches and hides are collected once and then consulted per bone as `renderRecursively` walks the
 * model, which is what every call site used to do by hand. The world position is the local pose
 * combined with the camera -- the arithmetic 5.x now does for us.
 */
private class RecursionBones : BoneAccess {
    val watches = HashMap<String, (Vec3) -> Unit>()
    val turns = HashMap<String, Pair<Float?, Float?>>()
    var hidden: Set<String> = emptySet()

    override fun watch(bone: String, onWorldPosition: (Vec3) -> Unit) {
        watches[bone] = onWorldPosition
    }

    override fun hide(bones: Set<String>) {
        hidden = bones
    }

    override fun turn(bone: String, rotX: Float?, rotY: Float?) {
        if (rotX == null && rotY == null) return
        turns[bone] = rotX to rotY
    }

    /** Answers whether this bone should be drawn, turning it and reporting its position first. */
    fun visit(bone: bpm.platform.GeoBone, isReRender: Boolean, poseStack: com.mojang.blaze3d.vertex.PoseStack): Boolean {
        val boneName = bone.name
        turns[boneName]?.let { (rotX, rotY) ->
            rotX?.let { bone.setRotX(it) }
            rotY?.let { bone.setRotY(it) }
        }
        if (!isReRender) {
            watches[boneName]?.let { report ->
                val local = poseStack.last().pose().transformPosition(org.joml.Vector3f(0f, 0f, 0f))
                val cam = net.minecraft.client.Minecraft.getInstance().gameRenderer.mainCamera.position
                report(Vec3(local.x + cam.x, local.y + cam.y, local.z + cam.z))
            }
        }
        return boneName !in hidden
    }
}

/** [WorldDraw] over a buffer source: everything happens now, which is what this band's draw call means. */
private class BufferedDraw(private val bufferSource: net.minecraft.client.renderer.MultiBufferSource) : WorldDraw {

    override fun into(
        poseStack: com.mojang.blaze3d.vertex.PoseStack,
        kind: bpm.platform.RenderType,
        draw: (com.mojang.blaze3d.vertex.PoseStack.Pose, com.mojang.blaze3d.vertex.VertexConsumer) -> Unit,
    ) {
        draw(poseStack.last(), bufferSource.getBuffer(kind))
    }

    override fun text(
        poseStack: com.mojang.blaze3d.vertex.PoseStack,
        text: net.minecraft.util.FormattedCharSequence,
        x: Float,
        y: Float,
        colour: Int,
        dropShadow: Boolean,
        mode: net.minecraft.client.gui.Font.DisplayMode,
        backgroundColour: Int,
        packedLight: Int,
    ) {
        net.minecraft.client.Minecraft.getInstance().font.drawInBatch(
            text, x, y, colour, dropShadow, poseStack.last().pose(), bufferSource, mode, backgroundColour, packedLight,
        )
    }

    override fun item(
        poseStack: com.mojang.blaze3d.vertex.PoseStack,
        stack: net.minecraft.world.item.ItemStack,
        context: net.minecraft.world.item.ItemDisplayContext,
        packedLight: Int,
        packedOverlay: Int,
    ) {
        val mc = net.minecraft.client.Minecraft.getInstance()
        mc.itemRenderer.renderStatic(stack, context, packedLight, packedOverlay, poseStack, bufferSource, mc.level, 0)
    }
}

abstract class GeoBlockRendererBase<T>(model: GeoModel<T>) :
    software.bernie.geckolib.renderer.GeoBlockRenderer<T>(model)
    where T : net.minecraft.world.level.block.entity.BlockEntity, T : GeoAnimatable {

    private val bones = RecursionBones()

    /** Declare the bones this renderer watches or hides, for the block at [pos] in [state]. */
    protected open fun onBones(bones: BoneAccess, pos: net.minecraft.core.BlockPos, state: net.minecraft.world.level.block.state.BlockState) {}

    override fun renderRecursively(
        poseStack: com.mojang.blaze3d.vertex.PoseStack,
        animatable: T,
        bone: bpm.platform.GeoBone,
        renderType: bpm.platform.RenderType,
        bufferSource: net.minecraft.client.renderer.MultiBufferSource,
        buffer: com.mojang.blaze3d.vertex.VertexConsumer,
        isReRender: Boolean,
        partialTick: Float,
        packedLight: Int,
        packedOverlay: Int,
        colour: Int,
    ) {
        onBones(bones, animatable.blockPos, animatable.blockState)
        if (!bones.visit(bone, isReRender, poseStack)) return
        super.renderRecursively(poseStack, animatable, bone, renderType, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, colour)
    }

    /** Attach the glow layer. See [BpmGlowLayer] for why the class itself is not shared. */
    protected fun addGlow() {
        addRenderLayer(BpmGlowLayer(this))
    }

    protected open fun renderTypeFor(texture: bpm.platform.ResourceLocation): bpm.platform.RenderType =
        entityTranslucent(texture)

    override fun getRenderType(
        animatable: T,
        texture: bpm.platform.ResourceLocation,
        bufferSource: net.minecraft.client.renderer.MultiBufferSource?,
        partialTick: Float,
    ): bpm.platform.RenderType = renderTypeFor(texture)

    protected open fun alwaysRender(): Boolean = false

    override fun shouldRenderOffScreen(blockEntity: T): Boolean = alwaysRender()

    protected open fun facingOf(blockEntity: T): net.minecraft.core.Direction =
        net.minecraft.core.Direction.NORTH

    override fun getFacing(animatable: T): net.minecraft.core.Direction = facingOf(animatable)

    /** Draw whatever this renderer hangs off the model, once the model itself is drawn. */
    protected open fun afterModel(blockEntity: T, poseStack: com.mojang.blaze3d.vertex.PoseStack, draw: WorldDraw, partialTick: Float, packedLight: Int) {}

    override fun render(
        animatable: T,
        partialTick: Float,
        poseStack: com.mojang.blaze3d.vertex.PoseStack,
        bufferSource: net.minecraft.client.renderer.MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int,
    ) {
        super.render(animatable, partialTick, poseStack, bufferSource, packedLight, packedOverlay)
        afterModel(animatable, poseStack, BufferedDraw(bufferSource), partialTick, packedLight)
    }


}

abstract class GeoEntityRendererBase<T>(
    context: net.minecraft.client.renderer.entity.EntityRendererProvider.Context,
    model: GeoModel<T>,
) : software.bernie.geckolib.renderer.GeoEntityRenderer<T>(context, model)
    where T : net.minecraft.world.entity.Entity, T : GeoAnimatable {

    private val bones = RecursionBones()

    /** Declare the bones this renderer watches or hides, for the entity with [entityId]. */
    protected open fun onBones(bones: BoneAccess, entityId: Int) {}

    override fun renderRecursively(
        poseStack: com.mojang.blaze3d.vertex.PoseStack,
        animatable: T,
        bone: bpm.platform.GeoBone,
        renderType: bpm.platform.RenderType,
        bufferSource: net.minecraft.client.renderer.MultiBufferSource,
        buffer: com.mojang.blaze3d.vertex.VertexConsumer,
        isReRender: Boolean,
        partialTick: Float,
        packedLight: Int,
        packedOverlay: Int,
        colour: Int,
    ) {
        onBones(bones, animatable.id)
        if (!bones.visit(bone, isReRender, poseStack)) return
        super.renderRecursively(poseStack, animatable, bone, renderType, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, colour)
    }

    protected fun addGlow() {
        addRenderLayer(BpmGlowLayer(this))
    }

    protected open fun renderTypeFor(texture: bpm.platform.ResourceLocation): bpm.platform.RenderType =
        entityTranslucent(texture)

    override fun getRenderType(
        animatable: T,
        texture: bpm.platform.ResourceLocation,
        bufferSource: net.minecraft.client.renderer.MultiBufferSource?,
        partialTick: Float,
    ): bpm.platform.RenderType = renderTypeFor(texture)

}

abstract class GeoItemRendererBase<T>(model: GeoModel<T>) :
    software.bernie.geckolib.renderer.GeoItemRenderer<T>(model)
    where T : net.minecraft.world.item.Item, T : GeoAnimatable {

    private val bones = RecursionBones()

    protected open fun onBones(bones: BoneAccess) {}

    override fun renderRecursively(
        poseStack: com.mojang.blaze3d.vertex.PoseStack,
        animatable: T,
        bone: bpm.platform.GeoBone,
        renderType: bpm.platform.RenderType,
        bufferSource: net.minecraft.client.renderer.MultiBufferSource,
        buffer: com.mojang.blaze3d.vertex.VertexConsumer,
        isReRender: Boolean,
        partialTick: Float,
        packedLight: Int,
        packedOverlay: Int,
        colour: Int,
    ) {
        onBones(bones)
        if (!bones.visit(bone, isReRender, poseStack)) return
        super.renderRecursively(poseStack, animatable, bone, renderType, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, colour)
    }

    protected fun addGlow() {
        addRenderLayer(BpmGlowLayer(this))
    }

    protected open fun renderTypeFor(texture: bpm.platform.ResourceLocation): bpm.platform.RenderType =
        entityTranslucent(texture)

    override fun getRenderType(
        animatable: T,
        texture: bpm.platform.ResourceLocation,
        bufferSource: net.minecraft.client.renderer.MultiBufferSource?,
        partialTick: Float,
    ): bpm.platform.RenderType = renderTypeFor(texture)

}
//?}
