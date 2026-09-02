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
import net.minecraft.client.renderer.MultiBufferSource
import bpm.platform.RenderType
import net.minecraft.core.Direction
import bpm.platform.ResourceLocation
import net.minecraft.world.phys.AABB
import software.bernie.geckolib.animatable.GeoAnimatable
import bpm.platform.GeoBone

private fun rl(path: String) = ResourceLocation.fromNamespaceAndPath(Bpm.ID, path)

/** A device's model, animation set and texture by name under `geo/block`, `animations/block`, `textures/block`. */
class DeviceModel<T : GeoAnimatable>(name: String) : PathGeoModel<T>(
    rl("geo/block/$name.geo.json"),
    rl("animations/block/$name.animation.json"),
    rl("textures/block/$name.png"),
)

/** A device block: translucent (columns, halos and bolts carry alpha), glow mask on top, its own render box. */
open class DeviceRenderer<T : DeviceBlockEntity>(name: String, private val box: (T) -> AABB) : bpm.platform.client.GeoBlockRendererBase<T>(DeviceModel(name)) {
    init {
        addGlow()
    }

    /*
     * Vanilla's `shouldRenderOffScreen`, not NeoForge's `getRenderBoundingBox`.
     *
     * Both exist to stop a model being culled when the block it belongs to leaves the frustum but the
     * model still pokes into view. NeoForge lets you name the real box; vanilla only lets you opt out of
     * the check, and vanilla is what both loaders have. The cost of opting out is one skipped frustum
     * test for three block-entity types that are almost always on screen when their chunk is; the cost of
     * the precise box was a method that does not exist on Fabric.
     */
    override fun alwaysRender(): Boolean = true

    override fun renderTypeFor(texture: ResourceLocation): RenderType =
        bpm.platform.client.entityTranslucent(texture)
}

/** The gate's plane follows its axis: the model's ring lies in XY, so a Z-axis gate is the model turned a quarter. */
class GateRenderer : DeviceRenderer<GateBlockEntity>("quantum_gate", { be ->
    val b = AABB(be.blockPos)
    if (be.axis == Direction.Axis.X) b.inflate(1.6, 0.0, 0.7).expandTowards(0.0, -3.1, 0.0) else b.inflate(0.7, 0.0, 1.6).expandTowards(0.0, -3.1, 0.0)
}) {
    override fun facingOf(blockEntity: GateBlockEntity): Direction = blockEntity.facing
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
    override fun texturePath(animatable: bpm.world.devices.MonitorBlockEntity): ResourceLocation =
        if (animatable.on) ON else super.texturePath(animatable)

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
class MonitorRenderer : bpm.platform.client.GeoBlockRendererBase<bpm.world.devices.MonitorBlockEntity>(MonitorModel()) {
    init {
        addGlow()
    }

    /**
     * A wall's content is drawn from its origin tile across every tile, so the origin must not be culled
     * while any of the wall is in view. See the note above on why this is `shouldRenderOffScreen` rather
     * than a bounding box: only one of the two exists on both loaders. A monitor with no widgets draws
     * nothing anyway, so opting out of the frustum test costs nothing there either.
     */
    override fun alwaysRender(): Boolean = true
    override fun facingOf(blockEntity: bpm.world.devices.MonitorBlockEntity): Direction = blockEntity.facing

    /** After the panel: the screen's content, for the wall's origin tile. */
    override fun render(animatable: bpm.world.devices.MonitorBlockEntity, partialTick: Float, poseStack: PoseStack, bufferSource: MultiBufferSource, packedLight: Int, packedOverlay: Int) {
        super.render(animatable, partialTick, poseStack, bufferSource, packedLight, packedOverlay)
        MonitorScreenRenderer.draw(animatable, poseStack, bufferSource)
    }

    /**
     * A monitor tile only draws the bezel edges that face outward, so a wall of them reads as one panel.
     *
     * The block state says which neighbours are present; every bone the state says to omit is named here
     * once per pass, rather than the model being walked and each bone asked as it comes up.
     */
    override fun onBones(bones: bpm.platform.client.BoneAccess, pos: net.minecraft.core.BlockPos, state: net.minecraft.world.level.block.state.BlockState) {
        val up = state.getValue(bpm.world.devices.MonitorBlock.UP)
        val down = state.getValue(bpm.world.devices.MonitorBlock.DOWN)
        val left = state.getValue(bpm.world.devices.MonitorBlock.LEFT)
        val right = state.getValue(bpm.world.devices.MonitorBlock.RIGHT)
        val shown = mapOf(
            "bezel_up" to up,
            "bezel_down" to down,
            "bezel_left" to left,
            "bezel_right" to right,
            "bezel_up_l" to (up && !left),
            "bezel_up_r" to (up && !right),
            "bezel_down_l" to (down && !left),
            "bezel_down_r" to (down && !right),
            "bezel_left_u" to (left && !up),
            "bezel_left_d" to (left && !down),
            "bezel_right_u" to (right && !up),
            "bezel_right_d" to (right && !down),
            "corner_ul" to (up && left),
            "corner_ur" to (up && right),
            "corner_dl" to (down && left),
            "corner_dr" to (down && right),
        )
        bones.hide(shown.filterValues { !it }.keys)
    }
}

/** Device items drawn with their block's model at rest, one renderer per model, made on first use. */
object DeviceItemExtensions {
    private val cache = HashMap<String, bpm.platform.client.GeoItemRendererBase<DeviceBlockItem>>()

    /** Bones a model leaves out when drawn as an item (world-only parts that would dwarf the block). */
    private val HIDDEN_IN_ITEM: Map<String, Set<String>> = mapOf(
        "quantum_gate" to setOf("gate", "clamp_l", "clamp_r"),
        // a lone tile has every edge outer: corners draw, the strip ends would only overlap them
        "quantum_monitor" to setOf("bezel_up_l", "bezel_up_r", "bezel_down_l", "bezel_down_r", "bezel_left_u", "bezel_left_d", "bezel_right_u", "bezel_right_d"),
    )

    fun of(model: String): bpm.platform.client.GeoItemRendererBase<DeviceBlockItem> = cache.getOrPut(model) {
        run {
            run {
                object : bpm.platform.client.GeoItemRendererBase<DeviceBlockItem>(DeviceModel(model)) {
                    init {
                        addGlow()
                    }

                    override fun renderTypeFor(texture: ResourceLocation): RenderType =
        bpm.platform.client.entityTranslucent(texture)

                    /** As an item the gate is just its projector: the rift, its rim and the clamps only exist in the world. */
                    override fun onBones(bones: bpm.platform.client.BoneAccess) {
                        bones.hide(HIDDEN_IN_ITEM[model].orEmpty())
                    }
                }
            }
        }
    }
}

object DeviceRenderers {
    fun register(sink: bpm.platform.client.RendererSink) {
        sink.blockEntity(DeviceBlockEntities.GATE.get()) { GateRenderer() }
        sink.blockEntity(DeviceBlockEntities.PEDESTAL.get()) { PedestalRenderer() }
        sink.blockEntity(DeviceBlockEntities.ASSEMBLER.get()) { AssemblerRenderer() }
        sink.blockEntity(DeviceBlockEntities.SPIKE.get()) { DeviceRenderer<SpikeBlockEntity>("phase_spike") { be -> AABB(be.blockPos).expandTowards(0.0, 1.2, 0.0) } }
        sink.blockEntity(DeviceBlockEntities.VENT.get()) { DeviceRenderer<VentBlockEntity>("decoherence_vent") { be -> AABB(be.blockPos).expandTowards(0.0, 1.7, 0.0) } }
        sink.blockEntity(DeviceBlockEntities.TURRET.get()) { TurretRenderer() }
        sink.blockEntity(DeviceBlockEntities.PHASE.get()) { DeviceRenderer<PhaseBlockEntity>("phase_block") { be -> AABB(be.blockPos) } }
        sink.blockEntity(DeviceBlockEntities.MONITOR.get()) { MonitorRenderer() }
    }
}
