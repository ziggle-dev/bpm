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
    var hidden: Set<String> = emptySet()

    override fun watch(bone: String, onWorldPosition: (Vec3) -> Unit) {
        watches[bone] = onWorldPosition
    }

    override fun hide(bones: Set<String>) {
        hidden = bones
    }

    /** Answers whether this bone should be drawn, reporting its position first if anyone asked. */
    fun visit(boneName: String, isReRender: Boolean, poseStack: com.mojang.blaze3d.vertex.PoseStack): Boolean {
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
        if (!bones.visit(bone.name, isReRender, poseStack)) return
        super.renderRecursively(poseStack, animatable, bone, renderType, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, colour)
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
        if (!bones.visit(bone.name, isReRender, poseStack)) return
        super.renderRecursively(poseStack, animatable, bone, renderType, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, colour)
    }
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
        if (!bones.visit(bone.name, isReRender, poseStack)) return
        super.renderRecursively(poseStack, animatable, bone, renderType, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, colour)
    }
}
//?}
