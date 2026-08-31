package bpm.client.render

import bpm.Bpm
import bpm.world.DeviceBlockEntities
import bpm.world.DeviceBlockItem
import bpm.world.devices.DeviceBlockEntity
import bpm.world.devices.GateBlockEntity
import bpm.world.devices.PedestalBlockEntity
import bpm.world.devices.PhaseBlockEntity
import bpm.world.devices.SpikeBlockEntity
import bpm.world.devices.TurretBlock
import bpm.world.devices.TurretBlockEntity
import bpm.world.devices.VentBlockEntity
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.AABB
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions
import software.bernie.geckolib.animatable.GeoAnimatable
import software.bernie.geckolib.cache.`object`.GeoBone
import software.bernie.geckolib.renderer.GeoBlockRenderer
import software.bernie.geckolib.renderer.GeoItemRenderer

private fun rl(path: String) = ResourceLocation.fromNamespaceAndPath(Bpm.ID, path)

/** A device's model, animation set and texture by name under `geo/block`, `animations/block`, `textures/block`. */
class DeviceModel<T : GeoAnimatable>(name: String) : PathGeoModel<T>(
    rl("geo/block/$name.geo.json"),
    rl("animations/block/$name.animation.json"),
    rl("textures/block/$name.png"),
)

/** A device block: translucent (columns, halos and bolts carry alpha), glow mask on top, its own render box. */
open class DeviceRenderer<T : DeviceBlockEntity>(name: String, private val box: (T) -> AABB) : GeoBlockRenderer<T>(DeviceModel(name)) {
    init {
        addRenderLayer(GlowLayer(this))
    }

    override fun getRenderBoundingBox(blockEntity: T): AABB = box(blockEntity)

    override fun getRenderType(animatable: T, texture: ResourceLocation, bufferSource: MultiBufferSource?, partialTick: Float): RenderType =
        RenderType.entityTranslucent(texture)
}

/** The gate's plane follows its axis: the model's ring lies in XY, so a Z-axis gate is the model turned a quarter. */
class GateRenderer : DeviceRenderer<GateBlockEntity>("quantum_gate", { be ->
    val b = AABB(be.blockPos)
    if (be.axis == Direction.Axis.X) b.inflate(1.6, 0.0, 0.7).expandTowards(0.0, -3.1, 0.0) else b.inflate(0.7, 0.0, 1.6).expandTowards(0.0, -3.1, 0.0)
}) {
    override fun getFacing(animatable: GateBlockEntity): Direction = animatable.facing
}

/**
 * The turret is modelled standing on a floor; a wall or ceiling mount tips it over about the block's centre.
 * While it tracks, the eye's `yaw` and `pitch` bones are pointed here, from the aim the server sends, eased
 * a little each frame — the animation's own sweep runs when it is not.
 */
class TurretRenderer : DeviceRenderer<TurretBlockEntity>("observer_turret", { be -> AABB(be.blockPos).inflate(0.5) }) {
    override fun getFacing(animatable: TurretBlockEntity): Direction =
        animatable.blockState.takeIf { it.hasProperty(TurretBlock.FACING) }?.getValue(TurretBlock.FACING) ?: Direction.UP

    override fun renderRecursively(
        poseStack: PoseStack, animatable: TurretBlockEntity, bone: GeoBone, renderType: RenderType,
        bufferSource: MultiBufferSource, buffer: com.mojang.blaze3d.vertex.VertexConsumer, isReRender: Boolean, partialTick: Float,
        packedLight: Int, packedOverlay: Int, colour: Int,
    ) {
        if (animatable.tracking) {
            when (bone.name) {
                "yaw" -> {
                    animatable.shownYaw += wrap(animatable.targetYaw - animatable.shownYaw) * EASE
                    bone.setRotY(Math.toRadians(animatable.shownYaw.toDouble()).toFloat())
                }
                "pitch" -> {
                    animatable.shownPitch += (animatable.targetPitch - animatable.shownPitch) * EASE
                    bone.setRotX(Math.toRadians(animatable.shownPitch.toDouble()).toFloat())
                }
            }
        }
        super.renderRecursively(poseStack, animatable, bone, renderType, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, colour)
    }

    private fun wrap(deg: Float): Float {
        var d = deg % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    /** After the model: the seconds it has left dark, as a sign facing the camera above the eye. */
    override fun render(animatable: TurretBlockEntity, partialTick: Float, poseStack: PoseStack, bufferSource: MultiBufferSource, packedLight: Int, packedOverlay: Int) {
        super.render(animatable, partialTick, poseStack, bufferSource, packedLight, packedOverlay)
        if (!animatable.off) return
        val seconds = animatable.secondsDark()
        if (seconds <= 0) return
        val mc = net.minecraft.client.Minecraft.getInstance()
        val font = mc.font
        val text = "$seconds s"
        poseStack.pushPose()
        poseStack.translate(0.5, 1.75, 0.5)
        poseStack.mulPose(mc.entityRenderDispatcher.cameraOrientation())
        poseStack.scale(-0.03f, -0.03f, 0.03f)
        val m = poseStack.last().pose()
        val x = -font.width(text) / 2f
        val bg = (mc.options.getBackgroundOpacity(0.25f) * 255).toInt() shl 24
        font.drawInBatch(text, x, 0f, 0x20FFFFFF, false, m, bufferSource, net.minecraft.client.gui.Font.DisplayMode.SEE_THROUGH, bg, packedLight)
        font.drawInBatch(text, x, 0f, 0xFFF26D6D.toInt(), false, m, bufferSource, net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, packedLight)
        poseStack.popPose()
    }

    companion object {
        const val EASE = 0.2f
    }

    override fun rotateBlock(facing: Direction, poseStack: PoseStack) {
        if (facing == Direction.UP) return
        poseStack.translate(0.0, 0.5, 0.0)
        when (facing) {
            Direction.DOWN -> poseStack.mulPose(Axis.XP.rotationDegrees(180f))
            Direction.SOUTH -> poseStack.mulPose(Axis.XP.rotationDegrees(90f))
            Direction.NORTH -> poseStack.mulPose(Axis.XP.rotationDegrees(-90f))
            Direction.EAST -> poseStack.mulPose(Axis.ZP.rotationDegrees(-90f))
            Direction.WEST -> poseStack.mulPose(Axis.ZP.rotationDegrees(90f))
            else -> {}
        }
        poseStack.translate(0.0, -0.5, 0.0)
    }
}

/** The monitor's texture follows its power state; the glow layer then picks the matching `_glowmask`. */
class MonitorModel : PathGeoModel<bpm.world.devices.MonitorBlockEntity>(
    rl("geo/block/quantum_monitor.geo.json"),
    rl("animations/block/quantum_monitor.animation.json"),
    rl("textures/block/quantum_monitor.png"),
) {
    override fun getTextureResource(animatable: bpm.world.devices.MonitorBlockEntity): ResourceLocation =
        if (animatable.on) ON else super.getTextureResource(animatable)

    companion object {
        private val ON = rl("textures/block/quantum_monitor_on.png")
    }
}

/**
 * A monitor tile shows bezel only along its outer edges. The model carries every piece — four 12 px strips,
 * eight 2 px strip ends and four corners — and the tile's edge flags pick which bones draw: a strip where
 * that side is outer, its end pieces where the neighbouring side is NOT outer (the strip runs on to the
 * tile boundary and the next tile's bezel), a corner where both are.
 */
class MonitorRenderer : GeoBlockRenderer<bpm.world.devices.MonitorBlockEntity>(MonitorModel()) {
    init {
        addRenderLayer(GlowLayer(this))
    }

    /** A wall's content is drawn from its origin tile across every tile, so the origin must not be culled while any of the wall is in view. */
    override fun getRenderBoundingBox(blockEntity: bpm.world.devices.MonitorBlockEntity): AABB =
        if (blockEntity.widgets.isEmpty()) AABB(blockEntity.blockPos) else AABB(blockEntity.blockPos).inflate(bpm.world.devices.MonitorWall.MAX.toDouble())
    override fun getFacing(animatable: bpm.world.devices.MonitorBlockEntity): Direction = animatable.facing

    /** After the panel: the screen's content, for the wall's origin tile. */
    override fun render(animatable: bpm.world.devices.MonitorBlockEntity, partialTick: Float, poseStack: PoseStack, bufferSource: MultiBufferSource, packedLight: Int, packedOverlay: Int) {
        super.render(animatable, partialTick, poseStack, bufferSource, packedLight, packedOverlay)
        MonitorScreenRenderer.draw(animatable, poseStack, bufferSource)
    }

    override fun renderRecursively(
        poseStack: PoseStack, animatable: bpm.world.devices.MonitorBlockEntity, bone: GeoBone, renderType: RenderType,
        bufferSource: MultiBufferSource, buffer: com.mojang.blaze3d.vertex.VertexConsumer, isReRender: Boolean, partialTick: Float,
        packedLight: Int, packedOverlay: Int, colour: Int,
    ) {
        val s = animatable.blockState
        val up = s.getValue(bpm.world.devices.MonitorBlock.UP)
        val down = s.getValue(bpm.world.devices.MonitorBlock.DOWN)
        val left = s.getValue(bpm.world.devices.MonitorBlock.LEFT)
        val right = s.getValue(bpm.world.devices.MonitorBlock.RIGHT)
        val show = when (bone.name) {
            "bezel_up" -> up
            "bezel_down" -> down
            "bezel_left" -> left
            "bezel_right" -> right
            "bezel_up_l" -> up && !left
            "bezel_up_r" -> up && !right
            "bezel_down_l" -> down && !left
            "bezel_down_r" -> down && !right
            "bezel_left_u" -> left && !up
            "bezel_left_d" -> left && !down
            "bezel_right_u" -> right && !up
            "bezel_right_d" -> right && !down
            "corner_ul" -> up && left
            "corner_ur" -> up && right
            "corner_dl" -> down && left
            "corner_dr" -> down && right
            else -> true
        }
        if (!show) return
        super.renderRecursively(poseStack, animatable, bone, renderType, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, colour)
    }
}

/** Device items drawn with their block's model at rest, one renderer per model, made on first use. */
object DeviceItemExtensions {
    private val cache = HashMap<String, IClientItemExtensions>()

    /** Bones a model leaves out when drawn as an item (world-only parts that would dwarf the block). */
    private val HIDDEN_IN_ITEM: Map<String, Set<String>> = mapOf(
        "quantum_gate" to setOf("gate", "clamp_l", "clamp_r"),
        // a lone tile has every edge outer: corners draw, the strip ends would only overlap them
        "quantum_monitor" to setOf("bezel_up_l", "bezel_up_r", "bezel_down_l", "bezel_down_r", "bezel_left_u", "bezel_left_d", "bezel_right_u", "bezel_right_d"),
    )

    fun of(model: String): IClientItemExtensions = cache.getOrPut(model) {
        object : IClientItemExtensions {
            private val renderer by lazy {
                object : GeoItemRenderer<DeviceBlockItem>(DeviceModel(model)) {
                    init {
                        addRenderLayer(GlowLayer(this))
                    }

                    override fun getRenderType(animatable: DeviceBlockItem, texture: ResourceLocation, bufferSource: MultiBufferSource?, partialTick: Float): RenderType =
                        RenderType.entityTranslucent(texture)

                    /** As an item the gate is just its projector: the rift, its rim and the clamps only exist in the world. */
                    override fun renderRecursively(
                        poseStack: PoseStack, animatable: DeviceBlockItem, bone: GeoBone, renderType: RenderType,
                        bufferSource: MultiBufferSource, buffer: com.mojang.blaze3d.vertex.VertexConsumer, isReRender: Boolean, partialTick: Float,
                        packedLight: Int, packedOverlay: Int, colour: Int,
                    ) {
                        if (bone.name in HIDDEN_IN_ITEM[model].orEmpty()) return
                        super.renderRecursively(poseStack, animatable, bone, renderType, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, colour)
                    }
                }
            }

            override fun getCustomRenderer(): BlockEntityWithoutLevelRenderer = renderer
        }
    }
}

object DeviceRenderers {
    fun register(event: EntityRenderersEvent.RegisterRenderers) {
        event.registerBlockEntityRenderer(DeviceBlockEntities.GATE.get()) { GateRenderer() }
        event.registerBlockEntityRenderer(DeviceBlockEntities.PEDESTAL.get()) { DeviceRenderer<PedestalBlockEntity>("core_pedestal") { be -> AABB(be.blockPos).inflate(0.3, 0.0, 0.3).expandTowards(0.0, 1.2, 0.0) } }
        event.registerBlockEntityRenderer(DeviceBlockEntities.SPIKE.get()) { DeviceRenderer<SpikeBlockEntity>("phase_spike") { be -> AABB(be.blockPos).expandTowards(0.0, 1.2, 0.0) } }
        event.registerBlockEntityRenderer(DeviceBlockEntities.VENT.get()) { DeviceRenderer<VentBlockEntity>("decoherence_vent") { be -> AABB(be.blockPos).expandTowards(0.0, 1.7, 0.0) } }
        event.registerBlockEntityRenderer(DeviceBlockEntities.TURRET.get()) { TurretRenderer() }
        event.registerBlockEntityRenderer(DeviceBlockEntities.PHASE.get()) { DeviceRenderer<PhaseBlockEntity>("phase_block") { be -> AABB(be.blockPos) } }
        event.registerBlockEntityRenderer(DeviceBlockEntities.MONITOR.get()) { MonitorRenderer() }
    }
}
