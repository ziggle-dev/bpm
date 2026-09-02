package bpm.platform.client

import net.minecraft.world.phys.Vec3
import bpm.platform.GeoAnimatable
import bpm.platform.GeoModel

/*
 * What GeckoLib hands an item renderer alongside the animatable.
 *
 * 5.1 passes the `ItemStack` itself as the renderer's render-data type; 5.2 wrapped it in a `RenderData`
 * record. One accessor's worth of difference, in the middle of an arm that is otherwise identical across
 * both bands -- so it is hoisted here rather than splitting the arm in two.
 *
 * Both arms are bounded above: 1.21.9 has its own body for all of this.
 */
//? if >=1.21.6 <1.21.9 {
/*internal typealias ItemRenderData = software.bernie.geckolib.renderer.GeoItemRenderer.RenderData

internal fun stackOf(data: ItemRenderData): net.minecraft.world.item.ItemStack = data.itemStack()
*///?} elif >=1.21.5 <1.21.6 {
/*internal typealias ItemRenderData = net.minecraft.world.item.ItemStack

internal fun stackOf(data: ItemRenderData): net.minecraft.world.item.ItemStack = data
*///?}

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
/*/**
 * A block entity's render state, carrying GeckoLib's per-frame data alongside Minecraft's.
 *
 * ALL THREE accessors are overridden, not just [getDataMap], and that is not tidiness.
 *
 * GeckoLib mixes `GeoRenderState` into vanilla's `BlockEntityRenderState` and, in that mixin, overrides
 * `addGeckolibData` and `hasGeckolibData` as CONCRETE CLASS METHODS that write straight to its own
 * field -- while leaving `getGeckolibData` as the interface default, which reads through `getDataMap()`.
 * A class method beats an interface default, so a subclass that overrides only `getDataMap()` ends up
 * writing to GeckoLib's field and reading from its own: every ticket comes back null.
 *
 * That is not a subtle wrongness either. `DataTickets.ANIMATABLE_MANAGER` is read back through
 * `Objects.requireNonNull` during extraction, so the first frame a device is on screen takes the client
 * down with a bare NullPointerException four frames deep in GeckoLib. Overriding the writes onto the
 * same map is what makes the two directions agree.
 */
class BpmBlockRenderState :
    net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState(),
    bpm.platform.GeoRenderState {

    private val data = HashMap<bpm.platform.DataTicket<*>, Any>()

    override fun getDataMap(): MutableMap<bpm.platform.DataTicket<*>, Any> = data

    override fun <D : Any> addGeckolibData(ticket: bpm.platform.DataTicket<D>, value: D) {
        data[ticket] = value
    }

    override fun hasGeckolibData(ticket: bpm.platform.DataTicket<*>): Boolean =
        data.containsKey(ticket)
}

/**
 * The same for an entity, plus the one thing this mod needs that vanilla's state does not carry.
 *
 * Only the ID, deliberately. A renderer that wants more looks the entity back up on the client, which
 * costs a map lookup once a frame and keeps this class from growing a field per renderer. The entity
 * itself must not be held here: the whole point of a render state is that the draw pass runs without it.
 *
 * The three accessors are overridden together for the reason given on [BpmBlockRenderState].
 */
class BpmEntityRenderState :
    net.minecraft.client.renderer.entity.state.EntityRenderState(),
    bpm.platform.GeoRenderState {

    var entityId: Int = 0

    private val data = HashMap<bpm.platform.DataTicket<*>, Any>()

    override fun getDataMap(): MutableMap<bpm.platform.DataTicket<*>, Any> = data

    override fun <D : Any> addGeckolibData(ticket: bpm.platform.DataTicket<D>, value: D) {
        data[ticket] = value
    }

    override fun hasGeckolibData(ticket: bpm.platform.DataTicket<*>): Boolean =
        data.containsKey(ticket)
}



/** [BoneAccess] over a render pass: listeners for the watches, one updater for the hides. */
private class PassBones<R : bpm.platform.GeoRenderState>(
    private val pass: bpm.platform.RenderPassInfo<R>,
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
internal class CollectorDraw(private val collector: net.minecraft.client.renderer.SubmitNodeCollector) : WorldDraw {

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
    bpm.platform.GeoBlockRendererOf<T, BpmBlockRenderState>(model)
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
        pass: bpm.platform.RenderPassInfo<BpmBlockRenderState>,
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
     * Turn the model for a block that is not standing the usual way up, and say whether you did.
     *
     * False means "not mine", and the stock rotation runs. The method that used to be overridden --
     * `rotateBlock(Direction, PoseStack)` -- is gone, replaced by one that reads the facing off the
     * render pass instead of being handed it, so the facing is fetched here and the caller keeps the
     * question it was actually asking.
     */
    protected open fun rotateFor(facing: net.minecraft.core.Direction, poseStack: com.mojang.blaze3d.vertex.PoseStack): Boolean = false

    override fun tryRotateByBlockstate(
        pass: bpm.platform.RenderPassInfo<BpmBlockRenderState>,
        poseStack: com.mojang.blaze3d.vertex.PoseStack,
    ) {
        val facing = pass.renderState()
            .getOrDefaultGeckolibData(bpm.platform.DataTickets.BLOCK_FACING, net.minecraft.core.Direction.NORTH)
        if (facing != null && rotateFor(facing, poseStack)) return
        super.tryRotateByBlockstate(pass, poseStack)
    }

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
) : bpm.platform.GeoEntityRendererOf<T, BpmEntityRenderState>(context, model)
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
        pass: bpm.platform.RenderPassInfo<BpmEntityRenderState>,
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
    bpm.platform.GeoItemRendererOf<T>(model)
    where T : net.minecraft.world.item.Item, T : GeoAnimatable {

    protected open fun onBones(bones: BoneAccess) {}

    override fun preRenderPass(
        pass: bpm.platform.RenderPassInfo<bpm.platform.GeoRenderState>,
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

    override fun getRenderType(state: bpm.platform.GeoRenderState, texture: bpm.platform.ResourceLocation): bpm.platform.RenderType =
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
        renderData: bpm.platform.GeoItemRenderData,
        state: bpm.platform.GeoRenderState,
        partialTick: Float,
    ) {
        super.captureDefaultRenderState(animatable, renderData, state, partialTick)
        state.addGeckolibData(ITEM_STACK, renderData.itemStack())
    }

}
*///?} elif >=1.21.5 {
/*// GeckoLib 5 on a band that still draws with a MultiBufferSource.
//
// This is the third renderer shape in the ladder, and it is genuinely between the other two rather than
// a step towards either. It has 5.x's RENDER STATES -- a renderer is typed on one, `renderRecursively`
// leads with it and no longer carries the animatable, and `getRenderType` takes it instead of a buffer
// source -- but none of 1.21.9's submit model. There is no RenderPassInfo either, so the bone hooks stay
// where 4.x had them, inside the recursion, and read what they need off the render state's data tickets
// instead of off the animatable that used to be handed to them.

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

    // Answers whether this bone should be drawn, turning it and reporting its position first.
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

internal class BufferedDraw(private val bufferSource: net.minecraft.client.renderer.MultiBufferSource) : WorldDraw {

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

// The entity renderer is typed on its render state from 5.x, and that state has to be both an
// EntityRenderState and a GeoRenderState. All three data accessors are overridden onto ONE map for the
// reason recorded on the 1.21.9 arm: GeckoLib's mixin turns two of them into class methods over its own
// field while the third stays an interface default reading this one, and splitting them leaves reads and
// writes looking at different maps.
class BpmEntityRenderState :
    net.minecraft.client.renderer.entity.state.EntityRenderState(),
    bpm.platform.GeoRenderState {

    var entityId: Int = 0

    private val data = HashMap<bpm.platform.DataTicket<*>, Any>()

    override fun getDataMap(): MutableMap<bpm.platform.DataTicket<*>, Any> = data

    // FOUR accessors on this band, not three: `getGeckolibData` is still abstract here, where 5.4 made
    // it an interface default over `getDataMap`. The value is nullable in both directions, so a null
    // write is a removal rather than a stored null -- the map itself cannot hold one.
    override fun <D : Any> addGeckolibData(ticket: bpm.platform.DataTicket<D>, value: D?) {
        if (value == null) data.remove(ticket) else data[ticket] = value
    }

    @Suppress("UNCHECKED_CAST")
    override fun <D : Any> getGeckolibData(ticket: bpm.platform.DataTicket<D>): D? =
        data[ticket] as D?

    override fun hasGeckolibData(ticket: bpm.platform.DataTicket<*>): Boolean =
        data.containsKey(ticket)
}

abstract class GeoBlockRendererBase<T>(model: GeoModel<T>) :
    OffScreenAwareBlockRenderer<T>(model)
    where T : net.minecraft.world.level.block.entity.BlockEntity, T : GeoAnimatable {

    private val bones = RecursionBones()

    /** Declare the bones this renderer watches or hides, for the block at [pos] in [state]. */
    protected open fun onBones(bones: BoneAccess, pos: net.minecraft.core.BlockPos, state: net.minecraft.world.level.block.state.BlockState) {}

    override fun renderRecursively(
        renderState: bpm.platform.GeoRenderState,
        poseStack: com.mojang.blaze3d.vertex.PoseStack,
        bone: bpm.platform.GeoBone,
        renderType: bpm.platform.RenderType,
        bufferSource: net.minecraft.client.renderer.MultiBufferSource,
        buffer: com.mojang.blaze3d.vertex.VertexConsumer,
        isReRender: Boolean,
        packedLight: Int,
        packedOverlay: Int,
        colour: Int,
    ) {
        // The block's position and state used to arrive as the animatable itself. GeckoLib captures both
        // into the render state on this band, so they are read back rather than asked for -- and read
        // with a default, because a renderer whose model never asked for them would otherwise throw.
        val pos = renderState.getOrDefaultGeckolibData(
            bpm.platform.DataTickets.BLOCKPOS, net.minecraft.core.BlockPos.ZERO,
        ) ?: net.minecraft.core.BlockPos.ZERO
        val state = renderState.getOrDefaultGeckolibData(
            bpm.platform.DataTickets.BLOCKSTATE,
            net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),
        ) ?: net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
        onBones(bones, pos, state)
        if (!bones.visit(bone, isReRender, poseStack)) return
        super.renderRecursively(renderState, poseStack, bone, renderType, bufferSource, buffer, isReRender, packedLight, packedOverlay, colour)
    }

    /** Attach the glow layer. See [BpmGlowLayer] for why the class itself is not shared. */
    protected fun addGlow() {
        addRenderLayer(BpmGlowLayer(this))
    }

    protected open fun renderTypeFor(texture: bpm.platform.ResourceLocation): bpm.platform.RenderType =
        entityTranslucent(texture)

    override fun getRenderType(
        renderState: bpm.platform.GeoRenderState,
        texture: bpm.platform.ResourceLocation,
    ): bpm.platform.RenderType = renderTypeFor(texture)

    // `alwaysRender` and the override it feeds live on OffScreenAwareBlockRenderer; see that file.

    protected open fun facingOf(blockEntity: T): net.minecraft.core.Direction =
        net.minecraft.core.Direction.NORTH

    override fun getFacing(animatable: T): net.minecraft.core.Direction = facingOf(animatable)

    /** Turn the model for a block that is not standing the usual way up; false means "not mine". */
    protected open fun rotateFor(facing: net.minecraft.core.Direction, poseStack: com.mojang.blaze3d.vertex.PoseStack): Boolean = false

    override fun rotateBlock(facing: net.minecraft.core.Direction, poseStack: com.mojang.blaze3d.vertex.PoseStack) {
        if (rotateFor(facing, poseStack)) return
        super.rotateBlock(facing, poseStack)
    }

    /** Draw whatever this renderer hangs off the model, once the model itself is drawn. */
    protected open fun afterModel(blockEntity: T, poseStack: com.mojang.blaze3d.vertex.PoseStack, draw: WorldDraw, partialTick: Float, packedLight: Int) {}

    override fun render(
        animatable: T,
        partialTick: Float,
        poseStack: com.mojang.blaze3d.vertex.PoseStack,
        bufferSource: net.minecraft.client.renderer.MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int,
        cameraPos: Vec3,
    ) {
        super.render(animatable, partialTick, poseStack, bufferSource, packedLight, packedOverlay, cameraPos)
        afterModel(animatable, poseStack, BufferedDraw(bufferSource), partialTick, packedLight)
    }
}

abstract class GeoEntityRendererBase<T>(
    context: net.minecraft.client.renderer.entity.EntityRendererProvider.Context,
    model: GeoModel<T>,
) : software.bernie.geckolib.renderer.GeoEntityRenderer<T, BpmEntityRenderState>(context, model)
    where T : net.minecraft.world.entity.Entity, T : GeoAnimatable {

    private val bones = RecursionBones()

    /** Declare the bones this renderer watches or hides, for the entity with [entityId]. */
    protected open fun onBones(bones: BoneAccess, entityId: Int) {}

    override fun createRenderState(): BpmEntityRenderState = BpmEntityRenderState()

    // The entity id is not one of GeckoLib's own tickets, so it is stashed while the rest of the state
    // is being captured -- the one hook on this band that runs with the animatable still in hand.
    override fun captureDefaultRenderState(
        animatable: T,
        relatedObject: Void?,
        renderState: BpmEntityRenderState,
        partialTick: Float,
    ): BpmEntityRenderState {
        val captured = super.captureDefaultRenderState(animatable, relatedObject, renderState, partialTick)
        captured.entityId = animatable.id
        return captured
    }

    override fun renderRecursively(
        renderState: BpmEntityRenderState,
        poseStack: com.mojang.blaze3d.vertex.PoseStack,
        bone: bpm.platform.GeoBone,
        renderType: bpm.platform.RenderType,
        bufferSource: net.minecraft.client.renderer.MultiBufferSource,
        buffer: com.mojang.blaze3d.vertex.VertexConsumer,
        isReRender: Boolean,
        packedLight: Int,
        packedOverlay: Int,
        colour: Int,
    ) {
        onBones(bones, renderState.entityId)
        if (!bones.visit(bone, isReRender, poseStack)) return
        super.renderRecursively(renderState, poseStack, bone, renderType, bufferSource, buffer, isReRender, packedLight, packedOverlay, colour)
    }

    protected fun addGlow() {
        addRenderLayer(BpmGlowLayer(this))
    }

    protected open fun renderTypeFor(texture: bpm.platform.ResourceLocation): bpm.platform.RenderType =
        entityTranslucent(texture)

    override fun getRenderType(
        renderState: BpmEntityRenderState,
        texture: bpm.platform.ResourceLocation,
    ): bpm.platform.RenderType = renderTypeFor(texture)
}

abstract class GeoItemRendererBase<T>(model: GeoModel<T>) :
    software.bernie.geckolib.renderer.GeoItemRenderer<T>(model)
    where T : net.minecraft.world.item.Item, T : GeoAnimatable {

    private val bones = RecursionBones()

    protected open fun onBones(bones: BoneAccess) {}

    // 5.x dropped GeckoLib's own ITEMSTACK ticket and hands the stack to the item renderer as render
    // data instead, so the mod carries it the last leg itself. See `actorStack`.
    override fun captureDefaultRenderState(
        animatable: T,
        renderData: ItemRenderData,
        state: bpm.platform.GeoRenderState,
        partialTick: Float,
    ): bpm.platform.GeoRenderState {
        val captured = super.captureDefaultRenderState(animatable, renderData, state, partialTick)
        captured.addGeckolibData(ITEM_STACK, stackOf(renderData))
        return captured
    }

    override fun renderRecursively(
        renderState: bpm.platform.GeoRenderState,
        poseStack: com.mojang.blaze3d.vertex.PoseStack,
        bone: bpm.platform.GeoBone,
        renderType: bpm.platform.RenderType,
        bufferSource: net.minecraft.client.renderer.MultiBufferSource,
        buffer: com.mojang.blaze3d.vertex.VertexConsumer,
        isReRender: Boolean,
        packedLight: Int,
        packedOverlay: Int,
        colour: Int,
    ) {
        onBones(bones)
        if (!bones.visit(bone, isReRender, poseStack)) return
        super.renderRecursively(renderState, poseStack, bone, renderType, bufferSource, buffer, isReRender, packedLight, packedOverlay, colour)
    }

    protected fun addGlow() {
        addRenderLayer(BpmGlowLayer(this))
    }

    protected open fun renderTypeFor(texture: bpm.platform.ResourceLocation): bpm.platform.RenderType =
        entityTranslucent(texture)

    override fun getRenderType(
        renderState: bpm.platform.GeoRenderState,
        texture: bpm.platform.ResourceLocation,
    ): bpm.platform.RenderType = renderTypeFor(texture)
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
internal class BufferedDraw(private val bufferSource: net.minecraft.client.renderer.MultiBufferSource) : WorldDraw {

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

    /** Turn the model for a block that is not standing the usual way up; false means "not mine". */
    protected open fun rotateFor(facing: net.minecraft.core.Direction, poseStack: com.mojang.blaze3d.vertex.PoseStack): Boolean = false

    override fun rotateBlock(facing: net.minecraft.core.Direction, poseStack: com.mojang.blaze3d.vertex.PoseStack) {
        if (rotateFor(facing, poseStack)) return
        super.rotateBlock(facing, poseStack)
    }

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
