package bpm.client.render

import bpm.platform.client.addVertex
import bpm.platform.client.endOfVertex
import bpm.platform.client.setColor

import bpm.world.devices.AssemblerBlockEntity
import bpm.world.devices.PedestalBlockEntity
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LevelRenderer
import bpm.platform.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.BlockPos
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The assembler, and the fabrication happening inside it.
 *
 * The model carries the machine; everything that depends on *what is being made* is drawn here — the feed
 * beams from each loaded pedestal, and the result pulling itself together in the focus. Both are driven by
 * `coherence`, so a job in trouble looks like a job in trouble long before it fails: the beams thin out and
 * go from teal to orchid, and the forming item shakes.
 */
class AssemblerRenderer(ctx: net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider.Context) : DeviceRenderer<AssemblerBlockEntity>(ctx, "quantum_assembler", { be ->
    AABB(be.blockPos).inflate(AssemblerBlockEntity.REACH.toDouble())
}) {

    /**
     * Where the model's focus bone actually is this frame, so the item and the beams sit in the middle of
     * the gyroscope while it bobs rather than at a guessed height. Same trick the controller's core uses —
     * see [BoneAnchors] for why a position is asked for rather than read off the bone.
     */
    override fun onBones(bones: bpm.platform.client.BoneAccess, pos: BlockPos, state: net.minecraft.world.level.block.state.BlockState) {
        bones.watch(FOCUS) { world -> BoneAnchors.capture(pos, FOCUS, world) }
    }

    override fun afterModel(blockEntity: AssemblerBlockEntity, poseStack: PoseStack, draw: bpm.platform.client.WorldDraw, partialTick: Float, packedLight: Int) {
        val animatable = blockEntity
        if (!animatable.running) return
        val level = animatable.level ?: return

        val here = animatable.blockPos
        val focus = BoneAnchors.of(here, FOCUS)?.subtract(here.x.toDouble(), here.y.toDouble(), here.z.toDouble())
            ?: Vec3(0.5, REST_FOCUS, 0.5)

        val time = level.gameTime + partialTick.toDouble()
        val coherence = animatable.coherence.coerceIn(0f, 1f)
        val tint = tintOf(animatable.instability)
        val beat = beatOf(time)

        // A failing job is visibly unsteady: nothing at full coherence shakes at all.
        val shake = (1f - coherence) * 0.06f
        val wob = shake.toDouble()
        val jitter = if (shake <= 0f) Vec3.ZERO else Vec3(
            sin(time * 1.7) * wob,
            sin(time * 2.3 + 1.0) * wob,
            sin(time * 1.3 + 2.0) * wob,
        )
        // How far the result has CLIMBED is how far through the job it is: it starts just clear of the
        // cage and reaches the show point exactly as the job completes, held up by a beam from the machine.
        // Distance reads across a room and costs no dial of its own.
        val rise = RISE_FROM + (SHOW_Y - RISE_FROM) * animatable.progress
        // Inside the cage between beats, up on the beat. The item is never drawn travelling between the
        // two: it blips, which reads as the machine SUMMONING it rather than lifting it.
        val home = if (beat.up) Vec3(0.5, rise, 0.5) else focus
        val at = home.add(jitter)

        // The camera in the same block-local frame the beams are drawn in, so they can turn to face it.
        val eye = bpm.platform.client.cameraPos()
        val camLocal = Vec3(eye.x - here.x, eye.y - here.y, eye.z - here.z)

        if (beat.up) beams(animatable, here, focus, at, camLocal, poseStack, draw, time, coherence, tint, beat.pop)
        forming(animatable, at, poseStack, draw, time, coherence, beat.pop)
    }

    /**
     * One beam per loaded pedestal, drawn as a pair of crossed quads so it reads from any angle.
     *
     * They pulse *along their length* rather than flickering as a whole: a travelling bolt says which way
     * the material is going, which is the same language the controller's transfer beams speak.
     */
    private fun beams(
        be: AssemblerBlockEntity, here: BlockPos, focus: Vec3, target: Vec3, camLocal: Vec3,
        pose: PoseStack, draw: bpm.platform.client.WorldDraw, time: Double, coherence: Float, tint: FloatArray, pop: Float,
    ) = draw.into(pose, BEAM) { p, buffer ->
        // Every beam in one submission: they share a render type, and from 1.21.9 a submission is a unit
        // of deferred work rather than a buffer handed over, so batching them is what keeps them one draw.
        val m = p.pose()

        // The spine: the machine's own core up to the result it is holding. Its LENGTH is the progress bar,
        // and anchoring it to the focus bone means it rides the gyroscope's bob rather than a constant.
        val spine = ((time * 0.06) % 1.0).toFloat()
        beam(m, buffer, focus, target, camLocal, tint, (0.80f + coherence * 0.2f) * pop, HALF_WIDTH * 1.35f, spine)
        for (pedestal in be.pedestals()) {
            if (pedestal.held.isEmpty) continue
            val p = pedestal.blockPos
            // The ingredient itself, at the pedestal's socket — the beam leaves the THING, not the stone.
            val from = Vec3(
                p.x - here.x + 0.5,
                p.y - here.y + BEAM_FROM,
                p.z - here.z + 0.5,
            )
            // The bolt runs pedestal → item, so the machine is visibly drawing FROM the ingredients. The
            // per-pedestal offset keeps eight beams from pulsing in lockstep, which reads as one flash.
            val travel = ((time * 0.09 + (p.x * 0.31 + p.z * 0.17)) % 1.0).toFloat()
            val alpha = (0.72f + coherence * 0.28f) * pop
            beam(m, buffer, from, target, camLocal, tint, alpha, HALF_WIDTH + coherence * 0.02f, travel)

            // The pedestal's own gathering: each prong throws to a point on the axis, and that point throws
            // up into the ingredient. It is the same shape the machine makes at a larger scale — four
            // sources, one focus — so a loaded pedestal reads as a small assembler feeding a big one.
            val foot = Vec3(p.x - here.x + 0.5, (p.y - here.y).toDouble(), p.z - here.z + 0.5)
            val hub = foot.add(0.0, CONVERGE_Y, 0.0)
            val held = foot.add(0.0, PEDESTAL_SOCKET, 0.0)
            for (k in 0 until 4) {
                val a = k * Math.PI / 2
                val tip = foot.add(cos(a) * PRONG_R, PRONG_Y, sin(a) * PRONG_R)
                beam(m, buffer, tip, hub, camLocal, tint, alpha * 0.8f, PRONG_WIDTH, travel)
            }
            beam(m, buffer, hub, held, camLocal, tint, alpha, PRONG_WIDTH * 1.4f, travel)
        }
    }

    /**
     * A beam as ONE ribbon that turns to face the camera, brightest at the travelling head.
     *
     * It used to be two ribbons fixed to the world's own axes — one upright, one flat — on the theory that
     * between them something would always be face-on. It does not work: look along either plane and that
     * ribbon vanishes, and there are angles where both are near enough edge-on that the beam disappears
     * entirely. Turning a single ribbon to face the viewer is both cheaper and correct from everywhere.
     */
    private fun beam(m: org.joml.Matrix4f, buffer: VertexConsumer, from: Vec3, to: Vec3, camLocal: Vec3, rgb: FloatArray, alpha: Float, width: Float, travel: Float) {
        val steps = 8
        val axis = to.subtract(from)
        if (axis.lengthSqr() < 1e-8) return
        val dir = axis.normalize()
        for (i in 0 until steps) {
            val t0 = i / steps.toFloat()
            val t1 = (i + 1) / steps.toFloat()
            // A soft bump that rides the beam; everything else sits at a low base glow.
            val head = 1f - (abs(((t0 + t1) * 0.5f) - travel) * 3f).coerceAtMost(1f)
            val a = alpha * (0.62f + head * 0.38f)
            if (a <= 0.01f) continue
            val w = (width * (0.6f + head * 0.9f)).toDouble()
            val a0 = from.lerp(to, t0.toDouble())
            val a1 = from.lerp(to, t1.toDouble())
            val side = sideways(dir, camLocal.subtract(a0.lerp(a1, 0.5))).scale(w)
            quad(m, buffer, a0.subtract(side), a1.subtract(side), a1.add(side), a0.add(side), rgb, a)
        }
    }

    /**
     * The across-the-ribbon direction: perpendicular to both the beam and the line to the viewer.
     *
     * Looking straight down the beam leaves those two parallel and the cross product zero, which would
     * collapse the ribbon to nothing — so that case falls back to any perpendicular, and the beam becomes a
     * dot facing you, which is what a beam pointed at your eye should look like anyway.
     */
    private fun sideways(dir: Vec3, toCam: Vec3): Vec3 {
        val side = dir.cross(toCam)
        if (side.lengthSqr() > 1e-8) return side.normalize()
        val fallback = if (abs(dir.y) > 0.9) Vec3(1.0, 0.0, 0.0) else Vec3(0.0, 1.0, 0.0)
        return dir.cross(fallback).normalize()
    }

    private fun quad(m: org.joml.Matrix4f, buffer: VertexConsumer, a: Vec3, b: Vec3, c: Vec3, d: Vec3, rgb: FloatArray, alpha: Float) {
        for (v in arrayOf(a, b, c, d)) {
            buffer.addVertex(m, v.x.toFloat(), v.y.toFloat(), v.z.toFloat()).setColor(rgb[0], rgb[1], rgb[2], alpha).endOfVertex()
        }
    }

    /**
     * The result, growing into existence.
     *
     * It starts near nothing and reaches full size at the end, so the bar is not the only way to read how
     * far along a job is — you can tell across the room.
     */
    private fun forming(
        be: AssemblerBlockEntity, at: Vec3, pose: PoseStack, draw: bpm.platform.client.WorldDraw,
        time: Double, coherence: Float, pop: Float,
    ) {
        val stack: ItemStack = be.forming
        if (stack.isEmpty) return
        if (pop <= 0.01f) return
        val level = be.level ?: return
        val grow = (0.35f + be.progress * 0.45f) * pop
        // It settles as it finishes: a nearly done item is nearly steady.
        val wobble = (1f - coherence) * 0.35f + (1f - be.progress) * 0.12f
        pose.pushPose()
        pose.translate(at.x, at.y, at.z)
        pose.mulPose(Axis.YP.rotationDegrees((time * 2.4).toFloat()))
        pose.mulPose(Axis.XP.rotationDegrees(sin(time * 0.09).toFloat() * 18f * wobble))
        pose.scale(grow, grow, grow)
        draw.item(pose, stack, ItemDisplayContext.GROUND, bpm.platform.client.lightAt(level, be.blockPos.above()), OverlayTexture.NO_OVERLAY)
        pose.popPose()
    }

    companion object {
        /** The empty bone in the geo the whole fabrication hangs from. */
        const val FOCUS = "focus"

        /** Where the focus sits when the model was not drawn this frame — the bone's rest height, in blocks. */
        private const val REST_FOCUS = 8.5 / 16.0

        /**
         * Half the beam's thickness, in blocks. The first pass used 0.022, which with a ribbon drawn at
         * ±width is 0.044 blocks across — about half a screen pixel, so the beams were being drawn and were
         * simply too thin to see.
         */
        private const val HALF_WIDTH = 0.03f

        /**
         * The beam's own render type.
         *
         * NOT `bpm.platform.client.lightning()`, which is what this used at first and why the beams never appeared:
         * that one carries `setOutputState(WEATHER_TARGET)`, so it draws into the weather framebuffer for
         * the dedicated lightning pass rather than into the world a block entity renderer is drawing to.
         * It DOES write depth, unlike the rift's. A block entity's buffer is flushed as soon as another
         * render type is asked for, so the beams are drawn early in the block-entity pass — and a primitive
         * that writes no depth is simply painted over by every opaque model drawn after it, which is why the
         * beams kept appearing behind the pedestals even where they pass in front of them. Writing depth
         * makes the later models test against them properly.
         *
         * TRANSLUCENT, not additive. Additive adds the beam to whatever is behind it, so against a bright
         * sky every colour washes out to the same pale blue-white and the fault colours — the whole point of
         * them — became indistinguishable. Ordinary alpha blending keeps a beam the colour it was given
         * whatever it is drawn over.
         */
        /** The assembler beam: blended, unculled, and it MUST write depth so the room occludes it. */
        private val BEAM: RenderType = bpm.platform.client.translucentQuads("bpm_assembler_beam")

        /** The pedestal model's own socket, at model y=22 — where its held item is drawn, inside the ring. */
        const val PEDESTAL_SOCKET = 22.0 / 16.0

        /**
         * Where a beam leaves a pedestal — just clear of the socket ring, not inside it.
         *
         * The ring is a flat halo spanning y 1.356..1.394 and [PEDESTAL_SOCKET] is 1.375, dead centre of it,
         * so a beam starting at the socket had its first stretch buried in the pedestal's own geometry and
         * appeared to come out from under the block. The held item still sits in the ring, which is what the
         * ring is for; only the beam steps above it.
         */
        private const val BEAM_FROM = 1.47

        // The prong tips, worked out from the pedestal model rather than guessed: each pivots at model
        // [0,14,7.07] pointing straight up, is 5 units long, and `variable.has_item` leans it 41.5 degrees
        // inward — so a tip lands 5*cos(41.5) higher and 5*sin(41.5) closer to the axis.
        private const val PRONG_Y = (14.0 + 5.0 * 0.7490) / 16.0
        private const val PRONG_R = (7.07 - 5.0 * 0.6626) / 16.0

        /** Where the four prong beams meet, just above their tips, on the pedestal's axis. */
        private const val CONVERGE_Y = 1.16

        /** The gathering beams are thinner than the one that carries it all to the machine. */
        private const val PRONG_WIDTH = 0.022f


        /**
         * Where the item hangs while it is being shown.
         *
         * Clear of the cage (which tops out at 0.95) AND clear of a transfer's own tear: a link on the
         * machine's top face puts its rift at `0.5 + LinkAnchors.OFF_FACE`, which is 1.55 exactly — so
         * feeding the assembler power or experience from above used to draw the tear straight through the
         * forming item.
         */
        /** Where the result sits at the very start of a job — just clear of the cage, which tops out at 0.95. */
        private const val RISE_FROM = 1.06

        /** Where it has climbed to by the end, and so where a finished job drops it. */
        private const val SHOW_Y = AssemblerBlockEntity.SHOW_HEIGHT

        /** One beat of the blip: up for most of it, back inside the machine for the rest. */
        private const val BEAT_TICKS = 40.0
        private const val UP_AT = 0.10
        private const val DOWN_AT = 0.80
        private const val POP_TICKS = 3.0

        // Palette v4 throughout. Teal is the mod's "working"; amber and red are its two power warnings, the
        // orchid pair its two experience warnings.
        private val TEAL = floatArrayOf(0.098f, 0.761f, 0.651f)
        private val AMBER = floatArrayOf(1.000f, 0.710f, 0.278f)
        private val RED = floatArrayOf(0.776f, 0.157f, 0.157f)
        private val ORCHID_PALE = floatArrayOf(0.941f, 0.639f, 0.839f)
        private val ORCHID = floatArrayOf(0.788f, 0.373f, 0.647f)

        /**
         * The colour of the FAULT, not of how badly it is going.
         *
         * Coherence already says how much trouble the job is in — it drives the shake and the alpha — so
         * spending colour on that as well would waste the one channel that can say what is actually wrong.
         * Starved and flooded need opposite corrections, so they must not look alike; within a resource the
         * two warnings are neighbours, so power trouble reads apart from experience trouble at a glance and
         * which way to turn it takes a second look.
         */
        fun tintOf(fault: AssemblerBlockEntity.Instability): FloatArray = when (fault) {
            AssemblerBlockEntity.Instability.NONE -> TEAL
            AssemblerBlockEntity.Instability.POWER_LOW -> AMBER
            AssemblerBlockEntity.Instability.POWER_HIGH -> RED
            AssemblerBlockEntity.Instability.XP_LOW -> ORCHID_PALE
            AssemblerBlockEntity.Instability.XP_HIGH -> ORCHID
        }

        /** Where in the blip we are, and how far the item has popped in (0 hidden, 1 full). */
        class Beat(val up: Boolean, val pop: Float)

        /**
         * The rhythm, taken from the world clock rather than from the job.
         *
         * Every client agrees on `gameTime`, so the beat needs no packet of its own and stays in step across
         * a multiplayer server — and it keeps running at the same rate whether the job is 200 ticks or 1200.
         */
        fun beatOf(time: Double): Beat {
            val t = ((time % BEAT_TICKS) + BEAT_TICKS) % BEAT_TICKS / BEAT_TICKS
            val pop = POP_TICKS / BEAT_TICKS
            return when {
                t < UP_AT -> Beat(false, 1f)
                t < UP_AT + pop -> Beat(true, ((t - UP_AT) / pop).toFloat())
                t < DOWN_AT -> Beat(true, 1f)
                t < DOWN_AT + pop -> Beat(true, (1.0 - (t - DOWN_AT) / pop).toFloat())
                else -> Beat(false, 1f)
            }
        }
    }
}

/**
 * A pedestal, and whatever is resting on it.
 *
 * The model is a bare plinth, so without this an assembler's ingredients would be invisible and laying out
 * a recipe would be guesswork.
 */
class PedestalRenderer(ctx: net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider.Context) : DeviceRenderer<PedestalBlockEntity>(ctx, "core_pedestal", { be ->
    AABB(be.blockPos).inflate(0.3, 0.0, 0.3).expandTowards(0.0, 1.2, 0.0)
}) {

    /**
     * The socket ring is never drawn.
     *
     * It is a halo for a core to hang in, and it reads as a collar around anything else — it fought the
     * four prongs that are already pointing at the item. Hidden rather than deleted from the model so the
     * chamber's altar geometry and its animations are untouched and it can come back by removing one line.
     */
    override fun onBones(bones: bpm.platform.client.BoneAccess, pos: BlockPos, state: net.minecraft.world.level.block.state.BlockState) {
        bones.hide(setOf(SOCKET_RING))
    }

    override fun afterModel(blockEntity: PedestalBlockEntity, poseStack: PoseStack, draw: bpm.platform.client.WorldDraw, partialTick: Float, packedLight: Int) {
        val animatable = blockEntity
        val stack = animatable.held
        if (stack.isEmpty) return
        val level = animatable.level ?: return
        val time = level.gameTime + partialTick.toDouble()
        poseStack.pushPose()
        poseStack.translate(0.5, AssemblerRenderer.PEDESTAL_SOCKET + sin(time * 0.05) * 0.03, 0.5)
        poseStack.mulPose(Axis.YP.rotationDegrees((time * 1.6).toFloat()))
        poseStack.scale(0.5f, 0.5f, 0.5f)
        draw.item(poseStack, stack, ItemDisplayContext.GROUND, bpm.platform.client.lightAt(level, animatable.blockPos.above()), OverlayTexture.NO_OVERLAY)
        poseStack.popPose()
    }
}

private const val SOCKET_RING = "socket_ring"
