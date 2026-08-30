package bpm.client.fx

import bpm.client.render.BoneAnchors
import bpm.client.render.ControllerRenderer
import bpm.client.render.RiftRenderer
import bpm.client.render.RiftShader
import bpm.net.EffectKind
import bpm.net.EffectOp
import bpm.net.EffectPayload
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions
import org.joml.Vector3f
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.min

/**
 * The world drawing what the controllers do. Fed by [EffectPayload]s; ticks with the client; draws after
 * the translucent blocks.
 *
 * A **transfer** shows the things themselves: an item sprite that leaves the origin's face and shrinks away
 * from it, then appears out in front of the target's face and grows into it (fluid, energy and experience
 * travel as coloured dust instead). An **action** shows the tool at work in front of the block, swinging on
 * every blow. Anything whose spot is inside a solid block is skipped: nobody could see it. The controller's
 * own end hangs above its core. (The rift model is not drawn for now.)
 */
object EffectManager {
    /**
     * Where an effect's end sits: [near] is on the block's face (for the controller, its core) and [far] a
     * little way out from it (for the controller, above it). What is pulled out travels near → far, what is
     * pushed in far → near; the tool is held between them. [cell] is where a viewer sees it, for the light
     * and the solid check.
     */
    private class Anchor(private val restNear: Vec3, val far: Vec3, val facing: Vec3, private val core: BlockPos? = null) {
        /**
         * The controller's end follows its `core` bone rather than a constant. The core BOBS — the idle
         * animation moves it, faster while the graph is running — so a fixed height was wrong by a few
         * pixels on most frames by construction. [restNear] is the fallback for a frame on which the
         * controller's model was not drawn at all (culled, or the chunk not yet rendered).
         */
        val near: Vec3 get() = core?.let { BoneAnchors.of(it, ControllerRenderer.CORE) } ?: restNear
        val cell: BlockPos = BlockPos.containing(far)
    }

    private class Flyer(val stack: ItemStack, val from: Vec3, val to: Vec3, val shrink: Boolean, var delay: Int) {
        var t = 0
        val seed = (Math.random() * 1000).toInt()
        val done: Boolean get() = delay <= 0 && t >= LEG_TICKS
    }

    private abstract class Effect {
        var closeIn = -1
        var closed = false

        /**
         * Ticks since the server last said anything about this stream.
         *
         * `closeIn` only ever leaves -1 when an [EffectOp.END] arrives, so an effect whose END never comes —
         * the graph stopped, the controller unloaded, the chunk went out of range, the stream key changed —
         * is IMMORTAL: never closed, never done, never removed. Before this it sat there rendering a rift
         * for the rest of the session, which is the stationary portal that would not fade.
         */
        var idle = 0
        abstract fun tick(level: Level)
        abstract fun render(level: Level, pose: PoseStack, buffers: MultiBufferSource, cam: Vec3, partial: Float)
        abstract val done: Boolean
    }

    private class Transfer(val kind: EffectKind, val from: Anchor, val to: Anchor, var item: String) : Effect() {
        val flyers = ArrayList<Flyer>()

        /** The portal at each end: the origin pulls things in, the target pushes them out. */
        private val fromRift = Rift(inward = true)
        private val toRift = Rift(inward = false)

        override val done: Boolean get() = closed && flyers.isEmpty() && fromRift.done && toRift.done

        fun pulse(level: Level, amount: Int) {
            // How hard the vortices spin follows how much is moving, so a trickle and a flood do not look alike.
            val flow = (0.6 + amount / 32.0).coerceAtMost(2.5)
            fromRift.flowSpeed = flow
            toRift.flowSpeed = flow
            fromRift.pulse()
            toRift.pulse()
            val tint = colourOf(kind, item)
            motes(level, from, inward = true, colour = tint)
            motes(level, to, inward = false, colour = tint)
            val stack = stackOf(item)
            if ((kind == EffectKind.ITEMS || kind == EffectKind.DROP) && !stack.isEmpty) {
                val n = min(MAX_PER_PULSE, max(1, (amount + 7) / 8))
                for (i in 0 until n) {
                    if (flyerCount() >= MAX_FLYERS) break
                    val stagger = i * 2
                    flyers += Flyer(stack, from.near, from.far, shrink = true, delay = stagger)
                    // A drop has no DRAWN arrival. The thing that comes out of the target rift is the real
                    // entity, spawned by the server to emerge on the same tick this leg would have started.
                    // Drawing one as well is the same item twice, and no amount of timing hides that the
                    // drawn one is a different size from the real one it hands over to.
                    if (kind != EffectKind.DROP) {
                        flyers += Flyer(stack, to.far, to.near, shrink = false, delay = stagger + LEG_TICKS + VOID_TICKS)
                    }
                }
            } else {
                val colour = colourOf(kind, item)
                dust(level, from.near, from.far, colour)
                dust(level, to.far, to.near, colour)
            }
        }

        override fun tick(level: Level) {
            fromRift.tick()
            toRift.tick()
            // The transfer ending is what closes the rifts; `done` then waits for the close to play out.
            if (closed) {
                fromRift.close()
                toRift.close()
            }
            flyers.removeAll { f ->
                if (f.delay > 0) f.delay-- else f.t++
                f.done
            }
        }

        override fun render(level: Level, pose: PoseStack, buffers: MultiBufferSource, cam: Vec3, partial: Float) {
            drawRift(level, fromRift, from, pose, buffers, cam, partial)
            drawRift(level, toRift, to, pose, buffers, cam, partial)
            for (f in flyers) {
                if (f.delay > 0) continue
                val anchor = if (f.shrink) from else to
                if (hidden(level, anchor)) continue
                val p = (f.t + partial) / LEG_TICKS
                val at = f.from.lerp(f.to, p.toDouble())
                // Out of the face and away: gone by the end of the leg; the other end reversed.
                val scale = if (f.shrink) 1f - p * 0.85f else 0.15f + p * 0.85f
                pose.pushPose()
                pose.translate(at.x - cam.x, at.y - cam.y, at.z - cam.z)
                pose.mulPose(Axis.YP.rotationDegrees((f.t + partial) * 24f + f.seed))
                pose.scale(scale * 0.55f, scale * 0.55f, scale * 0.55f)
                Minecraft.getInstance().itemRenderer.renderStatic(f.stack, ItemDisplayContext.GROUND, LevelRenderer.getLightColor(level, anchor.cell), OverlayTexture.NO_OVERLAY, pose, buffers, level, f.seed)
                pose.popPose()
            }
        }
    }

    private class Action(val kind: EffectKind, val at: Anchor, var item: String) : Effect() {
        private var swing = 99

        override val done: Boolean get() = closed && swing >= SWING_TICKS

        fun blow() {
            swing = 0
        }

        override fun tick(level: Level) {
            swing++
            // Mining is one blow after another; the server only says when it starts and stops.
            if (kind == EffectKind.MINE && !closed && swing >= MINE_SWING_EVERY) blow()
        }

        override fun render(level: Level, pose: PoseStack, buffers: MultiBufferSource, cam: Vec3, partial: Float) {
            val stack = stackOf(item)
            if (stack.isEmpty || hidden(level, at)) return
            if (closed && swing >= SWING_TICKS) return
            // The tool, just off the block, pointing at it; a blow is a quick pitch down and back.
            val s = swing + partial
            val angle = if (s < SWING_TICKS / 2f) -75f * (s / (SWING_TICKS / 2f)) else if (s < SWING_TICKS) -75f * ((SWING_TICKS - s) / (SWING_TICKS / 2f)) else 0f
            val hold = at.near.lerp(at.far, bpm.world.LinkAnchors.HAND_ALONG)
            pose.pushPose()
            pose.translate(hold.x - cam.x, hold.y - cam.y, hold.z - cam.z)
            pose.mulPose(Axis.YP.rotationDegrees(yawOf(at.facing) + 180f))
            pose.mulPose(Axis.XP.rotationDegrees(angle - 20f))
            pose.scale(0.7f, 0.7f, 0.7f)
            Minecraft.getInstance().itemRenderer.renderStatic(stack, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, LevelRenderer.getLightColor(level, at.cell), OverlayTexture.NO_OVERLAY, pose, buffers, level, 0)
            pose.popPose()
        }
    }

    private val effects = LinkedHashMap<String, Effect>()

    fun clear() {
        effects.clear()
        BoneAnchors.clear()
    }

    fun onPayload(p: EffectPayload) {
        val level = Minecraft.getInstance().level ?: return
        when (p.kind) {
            EffectKind.MINE, EffectKind.USE, EffectKind.STRIKE -> action(level, p)
            else -> transfer(level, p)
        }
    }

    private fun transfer(level: Level, p: EffectPayload) {
        val key = "T|${p.controller.asLong()}|${p.stream}"
        when (p.op) {
            EffectOp.BEGIN, EffectOp.PULSE -> {
                val t = (effects[key] as? Transfer)?.takeIf { !it.closed } ?: run {
                    if (effects.size >= MAX_EFFECTS) return
                    val from = anchor(p.controller, p.origin, p.originFace, p.target)
                    val to = anchor(p.controller, p.target, p.targetFace, p.origin)
                    Transfer(p.kind, from, to, p.item).also { effects[key] = it }
                }
                if (p.item.isNotEmpty()) t.item = p.item
                t.closeIn = -1
                t.idle = 0
                t.pulse(level, p.amount)
            }
            EffectOp.END -> effects[key]?.closeIn = LINGER_TICKS
        }
    }

    private fun action(level: Level, p: EffectPayload) {
        val key = "A|${p.controller.asLong()}|${p.kind}|${p.origin.asLong()}|${p.originFace}"
        when (p.op) {
            EffectOp.BEGIN, EffectOp.PULSE -> {
                val a = (effects[key] as? Action)?.takeIf { !it.closed } ?: run {
                    if (effects.size >= MAX_EFFECTS) return
                    Action(p.kind, anchor(p.controller, p.origin, p.originFace, null), p.item).also { effects[key] = it }
                }
                if (p.item.isNotEmpty()) a.item = p.item
                a.closeIn = -1
                a.idle = 0
                a.blow()
            }
            EffectOp.END -> effects[key]?.closeIn = LINGER_TICKS
        }
    }

    fun tick() {
        val level = Minecraft.getInstance().level ?: run { effects.clear(); return }
        val it = effects.entries.iterator()
        while (it.hasNext()) {
            val e = it.next().value
            e.tick(level)
            if (e.closeIn > 0 && --e.closeIn == 0) e.closed = true
            // The safety net: nothing has pulsed this in a while, so shut it whatever the server did or
            // did not send. A live-but-slow transfer simply reopens on its next batch, which reads better
            // than one rift held open across the gap anyway.
            if (++e.idle > IDLE_TICKS) e.closed = true
            if (e.done) it.remove()
        }
    }

    fun render(event: RenderLevelStageEvent) {
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return
        // Block entities have all drawn by this stage, so the bone positions they published are this frame's.
        BoneAnchors.endFrame()
        if (effects.isEmpty()) return
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val buffers = mc.renderBuffers().bufferSource()
        val cam = event.camera.position
        val partial = event.partialTick.getGameTimeDeltaPartialTick(false)
        for (e in effects.values) e.render(level, event.poseStack, buffers, cam, partial)
        buffers.endBatch()
    }

    // ---- placing things ------------------------------------------------------------------------------

    /**
     * Where an effect's end for [pos] sits. Linked with a face: on that face and a little way out from it,
     * facing outward. The controller itself: its core, and a spot above it, facing [other]. No face: as if
     * the top were linked.
     */
    private fun anchor(controller: BlockPos, pos: BlockPos, face: Int, other: BlockPos?): Anchor {
        val centre = Vec3.atCenterOf(pos)
        if (pos == controller && face < 0) {
            val hang = centre.add(0.0, SELF_HEIGHT, 0.0)
            val toward = other?.let { Vec3.atCenterOf(it).subtract(hang) }?.let { Vec3(it.x, 0.0, it.z) }?.takeIf { it.lengthSqr() > 1e-4 }?.normalize() ?: Vec3(0.0, 0.0, -1.0)
            return Anchor(centre.add(0.0, SELF_CORE, 0.0), hang, toward, core = pos)
        }
        val d = if (face in 0..5) Direction.from3DDataValue(face) else Direction.UP
        val n = Vec3.atLowerCornerOf(d.normal)
        return Anchor(centre.add(n.scale(0.5)), centre.add(n.scale(OFF_FACE)), n)
    }

    private fun hidden(level: Level, a: Anchor): Boolean = level.getBlockState(a.cell).isSolidRender(level, a.cell)

    /**
     * Off for the rest of the session once the rift has thrown once.
     *
     * The rift is decoration for a transfer that has already happened — it is never worth a crash, and it
     * draws from inside a `RenderLevelStageEvent` listener, where a throw is not caught by anything before
     * it reaches `Minecraft.run` and takes the client down. That is not hypothetical: a buffer swapped
     * during the glow layer's re-render pass crashed on the first frame a rift existed, which on a world
     * with a running graph is the moment you log in. The flyers and the dust do not go through here, so a
     * disabled rift leaves the transfer still legible.
     */
    private var riftsBroken = false

    /** The rift sits where things vanish and appear — [Anchor.far]. It is billboarded, so it has no facing. */
    private fun drawRift(level: Level, rift: Rift, a: Anchor, pose: PoseStack, buffers: MultiBufferSource, cam: Vec3, partial: Float) {
        if (riftsBroken || !RiftShader.ready || hidden(level, a)) return
        try {
            RiftRenderer.draw(rift, a.far.subtract(cam), a.cell, a.facing, RIFT_SCALE, buffers, pose, partial)
        } catch (t: Throwable) {
            riftsBroken = true
            bpm.Bpm.LOGGER.error("rift rendering failed; rifts are off for this session (transfers still draw their items)", t)
        }
    }

    /**
     * Motes thrown around a rift's mouth, spiralling in or out.
     *
     * The velocity is tangential PLUS a pull along the radius, which is what makes them orbit inward rather
     * than fall straight at the middle — the same shape the shader's arms have, so the particles and the
     * surface tell the same story. They carry the transfer's own colour, so a fluid's motes are its tint
     * and experience comes off green.
     */
    private fun motes(level: Level, a: Anchor, inward: Boolean, colour: Int) {
        val axis = a.facing
        // Any two vectors spanning the rift's plane; the seed only has to not be parallel to the axis.
        val seed = if (abs(axis.y) > 0.9) Vec3(1.0, 0.0, 0.0) else Vec3(0.0, 1.0, 0.0)
        val u = seed.cross(axis).normalize()
        val v = axis.cross(u)
        val options = DustParticleOptions(Vector3f(((colour shr 16) and 0xFF) / 255f, ((colour shr 8) and 0xFF) / 255f, (colour and 0xFF) / 255f), 0.7f)
        repeat(MOTES_PER_PULSE) {
            val ang = Math.random() * Math.PI * 2
            val rad = RIFT_SCALE * (0.75 + Math.random() * 0.4)
            val at = a.far.add(u.scale(cos(ang) * rad)).add(v.scale(sin(ang) * rad))
            val tangent = u.scale(-sin(ang)).add(v.scale(cos(ang))).scale(0.055)
            val pull = a.far.subtract(at).normalize().scale(if (inward) 0.045 else -0.045)
            val vel = tangent.add(pull)
            level.addParticle(options, at.x, at.y, at.z, vel.x, vel.y, vel.z)
        }
    }

    private fun yawOf(facing: Vec3): Float = Math.toDegrees(atan2(-facing.x, -facing.z)).toFloat()

    private fun dust(level: Level, from: Vec3, to: Vec3, colour: Int) {
        val options = DustParticleOptions(Vector3f(((colour shr 16) and 0xFF) / 255f, ((colour shr 8) and 0xFF) / 255f, (colour and 0xFF) / 255f), 0.8f)
        val v = to.subtract(from).scale(1.0 / LEG_TICKS)
        repeat(4) {
            val p = from.add((Math.random() - 0.5) * 0.2, (Math.random() - 0.5) * 0.2, (Math.random() - 0.5) * 0.2)
            level.addParticle(options, p.x, p.y, p.z, v.x, v.y, v.z)
        }
    }

    private fun colourOf(kind: EffectKind, item: String): Int = when (kind) {
        EffectKind.ENERGY -> 0x4DFFD8
        EffectKind.XP -> 0xA8F04A
        EffectKind.FLUID -> ResourceLocation.tryParse(item)?.let { BuiltInRegistries.FLUID.get(it) }?.let { IClientFluidTypeExtensions.of(it).tintColor and 0xFFFFFF } ?: 0x3F76E4
        else -> 0x8AB4F8
    }

    private fun stackOf(item: String): ItemStack =
        ResourceLocation.tryParse(item)?.let { BuiltInRegistries.ITEM.getOptional(it).orElse(null) }?.let { ItemStack(it) } ?: ItemStack.EMPTY

    private fun flyerCount(): Int = effects.values.sumOf { (it as? Transfer)?.flyers?.size ?: 0 }

    private fun Vec3.lerp(to: Vec3, t: Double): Vec3 = Vec3(x + (to.x - x) * t, y + (to.y - y) * t, z + (to.z - z) * t)

    // The reach geometry lives in bpm.world.LinkAnchors so the SERVER can put real entities at the same
    // points these effects draw to — see `items.drop`, which spawns its stack exactly where the hand is.
    private const val OFF_FACE = bpm.world.LinkAnchors.OFF_FACE
    private const val SELF_HEIGHT = bpm.world.LinkAnchors.SELF_HEIGHT
    private const val SELF_CORE = bpm.world.LinkAnchors.SELF_CORE

    /** Half-width of the rift quad, in blocks; it grows to this as the tear opens. */
    private const val RIFT_SCALE = 0.42f

    /** Motes flung around each mouth per pulse. Both ends get a set, so this is doubled per transfer. */
    private const val MOTES_PER_PULSE = 5
    // Shared with the server, which has to hold a real entity back for exactly as long as the drawn one
    // takes to arrive — see bpm.world.EffectTiming and `items.drop`.
    private const val LEG_TICKS = bpm.world.EffectTiming.LEG_TICKS
    private const val VOID_TICKS = bpm.world.EffectTiming.VOID_TICKS
    private const val LINGER_TICKS = 10

    /** How long an effect may go unmentioned before the client closes it itself. */
    private const val IDLE_TICKS = 40
    private const val SWING_TICKS = 8
    private const val MINE_SWING_EVERY = 7
    private const val MAX_EFFECTS = 32
    private const val MAX_FLYERS = 200
    private const val MAX_PER_PULSE = 4
}
