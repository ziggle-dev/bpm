package bpm.client.fx

import bpm.client.render.BoneAnchors
import bpm.client.render.ControllerRenderer
import bpm.client.render.RiftRenderer
import bpm.net.EffectKind
import bpm.net.EffectOp
import bpm.net.EffectPayload
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.MultiBufferSource
import bpm.platform.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.registries.BuiltInRegistries
import bpm.platform.ResourceLocation
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
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
    private class Anchor(
        private val restNear: Vec3,
        private val restFar: Vec3,
        private val restFacing: Vec3,
        private val core: BlockPos? = null,
        private val entity: Int = 0,
    ) {
/**
         * The person this end rides, or null when it is not a person or they are not loaded here.
         *
         * Everything about the geometry has to be recomputed per frame for these: they are the only end that
         * can be somewhere else by the next one.
         */
        private fun person(): Entity? = if (entity == 0) null else Minecraft.getInstance().level?.getEntity(entity)

        /**
         * The controller's end follows its `core` bone rather than a constant. The core BOBS — the idle
         * animation moves it, faster while the graph is running — so a fixed height was wrong by a few
         * pixels on most frames by construction. [restNear] is the fallback for a frame on which the
         * controller's model was not drawn at all (culled, or the chunk not yet rendered), and for a person
         * the client cannot see.
         */
        /**
         * A person's items come and go at the TOP OF THEIR HEAD, not their chest.
         *
         * Chest height is eye height, and the owner of the tether is usually in first person: items rendered
         * there fly through the camera. Above the head they stay out of their own bearer's face and still
         * read correctly to everyone watching from outside.
         */
        fun near(partial: Float = 1f): Vec3 =
            person()?.let { it.getPosition(partial).add(0.0, it.bbHeight.toDouble(), 0.0) }
                ?: core?.let { BoneAnchors.of(it, ControllerRenderer.CORE) } ?: restNear

        /**
         * A person's rift hangs over their head, not out in front of them.
         *
         * It was offset along [facing] at first, the way a linked block's is offset off its face — but a
         * block's face is a fixed thing to stand off from and a person's is not, so the rift slid around them
         * as whatever they were trading with moved. Overhead is the same answer the controller's own end
         * gives, it never depends on where the other end is, and it leaves the person visible under it.
         */
        fun far(partial: Float = 1f): Vec3 =
            person()?.let { it.getPosition(partial).add(0.0, it.bbHeight + HEAD_CLEARANCE, 0.0) } ?: restFar


        /**
         * Which way the disc turns to be seen — not where it sits, and never a function of the other end.
         *
         * It did turn to face whatever was being traded with, which meant a person's tear swung about them
         * as the far end moved. A person's is [DOWN]: flat overhead, looking at them.
         */
        val facing: Vec3 get() = restFacing

        fun cell(partial: Float = 1f): BlockPos = BlockPos.containing(far(partial))
    }

    /**
     * [anchor] rather than two points: the endpoints were always just the anchor's near and far taken in one
     * order or the other, and copying them at spawn is what left items flying out of the spot a player had
     * already walked away from.
     */
    private class Flyer(val stack: ItemStack, val fluid: net.minecraft.world.level.material.Fluid?, val anchor: Anchor, val shrink: Boolean, var delay: Int) {
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

        /** Drawn in the second pass, over everything else — see [EffectManager.render]. */
        open val drawsLast: Boolean get() = false

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

        override val drawsLast: Boolean get() = kind == EffectKind.ENERGY

        /** So two arcs in one room never writhe in step. */
        private val seed: Int = System.identityHashCode(this) and 0xFFFF

        /** The last pulse's flow, kept so the energy arc can writhe harder for a bigger current. */
        private var lastFlow: Double = 1.0

        fun flow(): Double = lastFlow

        fun pulse(level: Level, amount: Int) {
            // How hard the vortices spin follows how much is moving, so a trickle and a flood do not look alike.
            val flow = (0.6 + amount / 32.0).coerceAtMost(2.5)
            lastFlow = flow
            fromRift.flowSpeed = flow
            toRift.flowSpeed = flow
            fromRift.pulse()
            toRift.pulse()
            val tint = colourOf(kind, item)
            // Particles around the tear itself are off: at one transfer a tick they are a constant haze
            // around the mouth that hides the tear rather than dressing it. The shader carries the rift on
            // its own. Uncomment both to bring them back.
            // motes(level, from, inward = true, colour = tint)
            // motes(level, to, inward = false, colour = tint)
            val stack = stackOf(item)
            val fluid = if (kind == EffectKind.FLUID) fluidOf(item) else null
            val carries = (kind == EffectKind.ITEMS || kind == EffectKind.DROP) && !stack.isEmpty
            if (carries || fluid != null) {
                // Fluid runs as a chain of drips rather than one blob: more of them, closer together, so a
                // millibucket-a-tick trickle and a full bucket look as different as they should.
                val n = if (fluid != null) {
                    min(MAX_DRIPS, max(2, amount / 120 + 2))
                } else {
                    min(MAX_PER_PULSE, max(1, (amount + 7) / 8))
                }
                for (i in 0 until n) {
                    if (flyerCount() >= MAX_FLYERS) break
                    val stagger = if (fluid != null) i else i * 2
                    flyers += Flyer(stack, fluid, from, shrink = true, delay = stagger)
                    // A drop has no DRAWN arrival. The thing that comes out of the target rift is the real
                    // entity, spawned by the server to emerge on the same tick this leg would have started.
                    // Drawing one as well is the same item twice, and no amount of timing hides that the
                    // drawn one is a different size from the real one it hands over to.
                    if (kind != EffectKind.DROP) {
                        flyers += Flyer(stack, fluid, to, shrink = false, delay = stagger + LEG_TICKS + VOID_TICKS)
                    }
                }
            } else if (kind != EffectKind.ENERGY) {
                // Energy is drawn as a live arc in `render` instead: a current is a continuous thing, and
                // spitting particles at it made a cable look like a conveyor belt.
                val colour = colourOf(kind, item)
                dust(level, from.near(), from.far(), colour)
                dust(level, to.far(), to.near(), colour)
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
            if (kind == EffectKind.FLUID) {
                // The stream first, the drips over it: a transfer of a bucket a tick is a running column,
                // not a shower, and drips alone made every rate look like a leak.
                fluidOf(item)?.let { fluid ->
                    val t = level.gameTime + partial.toDouble()
                    // Only while liquid is actually MOVING — `running`, not `openness`.
                    //
                    // A Transfer outlives a `wait`, so anything drawn unconditionally from it hangs in the
                    // air during the gap; that was the lens of lava. Openness fixed the worst of it but not
                    // all, because the mouth is meant to hold open for a beat after each batch and the
                    // column was inheriting that hold as a thin shape sitting in the tear.
                    val liveFrom = fromRift.running(partial)
                    val liveTo = toRift.running(partial)
                    if (liveFrom > 0.02f) {
                        val a = from.near(partial)
                        val b = from.far(partial)
                        stream(fluid, a, a.lerp(b, 1.0 - MOUTH_GAP), cam, pose, buffers, level, from.cell(partial), flow(), t, liveFrom)
                    }
                    if (liveTo > 0.02f) {
                        val c = to.far(partial)
                        val d = to.near(partial)
                        stream(fluid, c.lerp(d, MOUTH_GAP), d, cam, pose, buffers, level, to.cell(partial), flow(), t, liveTo)
                    }
                }
            }
            if (kind == EffectKind.ENERGY) {
                // TWO short arcs, one per end — the same spans the items fly and the dust used to take.
                // A single arc strung between the two mouths drew a cable across the room, which is exactly
                // what the rifts exist to avoid: the current goes into the tear here and comes out of the
                // tear there, and the only visible run is the hop between a mouth and its own block.
                val t = level.gameTime + partial.toDouble()
                arc(from.near(partial), from.far(partial), cam, pose, buffers, t, seed, flow())
                arc(to.far(partial), to.near(partial), cam, pose, buffers, t, seed + 977, flow())
            }
            for (f in flyers) {
                if (f.delay > 0) continue
                if (hidden(level, f.anchor, partial)) continue
                val p = (f.t + partial) / LEG_TICKS
                val a = if (f.shrink) f.anchor.near(partial) else f.anchor.far(partial)
                val b = if (f.shrink) f.anchor.far(partial) else f.anchor.near(partial)
                val at = a.lerp(b, p.toDouble())
                // Out of the face and away: gone by the end of the leg; the other end reversed.
                //
                // A drop of liquid goes ALL the way to nothing, where an item stops at a fifteenth of its
                // size: an item is swallowed by the tear and you want to see it go, but a drop left at the
                // mouth just sits on top of the disc, which does not write depth to hide it.
                val fade = if (f.fluid != null) 1f else 0.85f
                val scale = if (f.shrink) 1f - p * fade else (1f - fade) + p * fade
                val light = LevelRenderer.getLightColor(level, f.anchor.cell(partial))
                pose.pushPose()
                pose.translate(at.x - cam.x, at.y - cam.y, at.z - cam.z)
                val fluid = f.fluid
                if (fluid != null) {
                    drip(fluid, pose, buffers, scale * 0.16f, light, f.seed, f.t + partial)
                } else {
                    pose.mulPose(Axis.YP.rotationDegrees((f.t + partial) * 24f + f.seed))
                    pose.scale(scale * 0.55f, scale * 0.55f, scale * 0.55f)
                    Minecraft.getInstance().itemRenderer.renderStatic(f.stack, ItemDisplayContext.GROUND, light, OverlayTexture.NO_OVERLAY, pose, buffers, level, f.seed)
                }
                pose.popPose()
            }

            // The mouths LAST, so everything that goes through one is already on the screen behind it.
            //
            // The tear is translucent and now writes depth, which means two things at once: what has
            // already been drawn shows THROUGH it, and nothing drawn afterwards can paint over it. Drawing
            // it first gave the opposite of both — the fluid landed on top of the disc and looked stuck to
            // the front of it. A shared buffer source flushes whenever the render type changes, so asking
            // for these last is what actually puts them last on the GPU.
            drawRift(level, fromRift, from, pose, buffers, cam, partial)
            drawRift(level, toRift, to, pose, buffers, cam, partial)
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
            if (stack.isEmpty || hidden(level, at, partial)) return
            if (closed && swing >= SWING_TICKS) return
            // The tool, just off the block, pointing at it; a blow is a quick pitch down and back.
            val s = swing + partial
            val angle = if (s < SWING_TICKS / 2f) -75f * (s / (SWING_TICKS / 2f)) else if (s < SWING_TICKS) -75f * ((SWING_TICKS - s) / (SWING_TICKS / 2f)) else 0f
            val hold = at.near(partial).lerp(at.far(partial), bpm.world.LinkAnchors.HAND_ALONG)
            pose.pushPose()
            pose.translate(hold.x - cam.x, hold.y - cam.y, hold.z - cam.z)
            pose.mulPose(Axis.YP.rotationDegrees(yawOf(at.facing) + 180f))
            pose.mulPose(Axis.XP.rotationDegrees(angle - 20f))
            pose.scale(0.7f, 0.7f, 0.7f)
            Minecraft.getInstance().itemRenderer.renderStatic(stack, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, LevelRenderer.getLightColor(level, at.cell(partial)), OverlayTexture.NO_OVERLAY, pose, buffers, level, 0)
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
                    val from = anchor(p.controller, p.origin, p.originFace, p.originEntity)
                    val to = anchor(p.controller, p.target, p.targetFace, p.targetEntity)
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
                    Action(p.kind, anchor(p.controller, p.origin, p.originFace, p.originEntity), p.item).also { effects[key] = it }
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

    fun render(event: bpm.platform.events.WorldRender) {
        // Block entities have all drawn by this stage, so the bone positions they published are this frame's.
        BoneAnchors.endFrame()
        if (effects.isEmpty()) return
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val buffers = mc.renderBuffers().bufferSource()
        val cam = event.eye
        val partial = event.delta.getGameTimeDeltaPartialTick(false)
        // Two passes, energy second.
        //
        // The liquid writes depth, as it should — it is a solid column and a wall ought to hide it. The arc
        // does not, so at the same link the two sit at effectively the same depth and whichever is drawn
        // last blends over the other. Left to the map's insertion order that was a coin toss; energy going
        // second makes the current read over the column it runs beside.
        for (e in effects.values) if (!e.drawsLast) e.render(level, event.pose, buffers, cam, partial)
        for (e in effects.values) if (e.drawsLast) e.render(level, event.pose, buffers, cam, partial)
        buffers.endBatch()
    }

    // ---- placing things ------------------------------------------------------------------------------

    /**
     * Where an effect's end for [pos] sits. Linked with a face: on that face and a little way out from it,
     * facing outward. A person: the top of their head, and a tear flat above it. The controller itself: its
     * core, and a tear flat above that. No face: as if the top were linked.
     *
     * Only a linked face still turns a tear sideways, because there a face is genuinely what it stands off
     * from. The two ends that HANG over something — a person and the controller — both look down at what
     * they belong to rather than at each other.
     */
    private fun anchor(controller: BlockPos, pos: BlockPos, face: Int, entity: Int): Anchor {
        val centre = Vec3.atCenterOf(pos)
        if (entity != 0) {
            // The rest positions are only for a client that cannot see them: chest and overhead of the block
            // they were last reported in. Once the entity resolves, neither is read again — DOWN always is.
            val head = centre.add(0.0, PERSON_REST_HEAD, 0.0)
            return Anchor(head, centre.add(0.0, PERSON_REST_OVERHEAD, 0.0), DOWN, entity = entity)
        }
        if (pos == controller && face < 0) {
            return Anchor(centre.add(0.0, SELF_CORE, 0.0), centre.add(0.0, SELF_HEIGHT, 0.0), DOWN, core = pos)
        }
        val d = if (face in 0..5) Direction.from3DDataValue(face) else Direction.UP
        val n = Vec3.atLowerCornerOf(bpm.platform.unitVector(d))
        return Anchor(centre.add(n.scale(0.5)), centre.add(n.scale(OFF_FACE)), n)
    }

    private fun hidden(level: Level, a: Anchor, partial: Float = 1f): Boolean =
        a.cell(partial).let { bpm.platform.solidRender(level.getBlockState(it), level, it) }

    /**
     * Off for the rest of the session once the rift has thrown once.
     *
     * The rift is decoration for a transfer that has already happened — it is never worth a crash, and it
     * draws from inside the world-render hook, where a throw is not caught by anything before
     * it reaches `Minecraft.run` and takes the client down. That is not hypothetical: a buffer swapped
     * during the glow layer's re-render pass crashed on the first frame a rift existed, which on a world
     * with a running graph is the moment you log in. The flyers and the dust do not go through here, so a
     * disabled rift leaves the transfer still legible.
     */
    private var riftsBroken = false

    /** The rift sits where things vanish and appear — [Anchor.far]. It is billboarded, so it has no facing. */
    private fun drawRift(level: Level, rift: Rift, a: Anchor, pose: PoseStack, buffers: MultiBufferSource, cam: Vec3, partial: Float) {
        if (riftsBroken || !bpm.platform.client.RiftLooks.ready || hidden(level, a, partial)) return
        try {
            RiftRenderer.draw(rift, a.far(partial).subtract(cam), a.cell(partial), a.facing, RIFT_SCALE, buffers, pose, partial)
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
        val options = bpm.platform.dust(colour, 0.7f)
        repeat(MOTES_PER_PULSE) {
            val ang = Math.random() * Math.PI * 2
            val rad = RIFT_SCALE * (0.75 + Math.random() * 0.4)
            val far = a.far()
            val at = far.add(u.scale(cos(ang) * rad)).add(v.scale(sin(ang) * rad))
            val tangent = u.scale(-sin(ang)).add(v.scale(cos(ang))).scale(0.055)
            val pull = far.subtract(at).normalize().scale(if (inward) 0.045 else -0.045)
            val vel = tangent.add(pull)
            level.addParticle(options, at.x, at.y, at.z, vel.x, vel.y, vel.z)
        }
    }

    private fun yawOf(facing: Vec3): Float = Math.toDegrees(atan2(-facing.x, -facing.z)).toFloat()

    private fun dust(level: Level, from: Vec3, to: Vec3, colour: Int) {
        val options = bpm.platform.dust(colour, 0.8f)
        val v = to.subtract(from).scale(1.0 / LEG_TICKS)
        repeat(4) {
            val p = from.add((Math.random() - 0.5) * 0.2, (Math.random() - 0.5) * 0.2, (Math.random() - 0.5) * 0.2)
            level.addParticle(options, p.x, p.y, p.z, v.x, v.y, v.z)
        }
    }

    /**
     * One drop of the real liquid, as a SOLID.
     *
     * It was a camera-facing quad, and a billboard is exactly what a drop of water is not: it has no
     * thickness, it turns to follow you, and every drop in a stream shows you the same face at the same
     * angle, which is what made a transfer read as stickers rather than liquid. This is a small box with
     * all six faces textured, tumbling on two axes, so it catches the light differently as it turns and the
     * drops in a stream are visibly at different attitudes.
     *
     * Taller than it is wide, because a falling drop is.
     */
    private fun drip(
        fluid: net.minecraft.world.level.material.Fluid,
        pose: PoseStack,
        buffers: MultiBufferSource,
        size: Float,
        light: Int,
        seed: Int,
        age: Float,
    ) {
        if (size <= 0.001f) return
        val look = bpm.platform.client.FluidVisuals.of(fluid)
        val sprite = Minecraft.getInstance()
            .getTextureAtlas(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS)
            .apply(look.still)
        val tint = look.tint
        val r = ((tint shr 16) and 0xFF) / 255f
        val g = ((tint shr 8) and 0xFF) / 255f
        val b = (tint and 0xFF) / 255f
        val a = ((tint ushr 24) and 0xFF).let { if (it == 0) 0.95f else it / 255f }

        // Tumbling on two axes at rates that do not divide: no drop ever repeats a neighbour's attitude.
        pose.mulPose(Axis.YP.rotationDegrees(age * 4.1f + seed))
        pose.mulPose(Axis.XP.rotationDegrees(age * 2.7f + seed * 0.61f))

        val last = pose.last()
        val buffer = buffers.getBuffer(bpm.platform.client.translucentCull(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS))
        solid(last, buffer, size, size * 1.35f, size, sprite, r, g, b, a, light)
    }

    /**
     * A textured box, six faces, in the pose's own frame.
     *
     * The sprite is sampled from well inside its own patch of the atlas: fluid sprites sit shoulder to
     * shoulder there, and taking the very edge of one bleeds the next one in.
     */
    private fun solid(
        last: PoseStack.Pose,
        buffer: com.mojang.blaze3d.vertex.VertexConsumer,
        hx: Float,
        hy: Float,
        hz: Float,
        sprite: net.minecraft.client.renderer.texture.TextureAtlasSprite,
        r: Float,
        g: Float,
        b: Float,
        alpha: Float,
        light: Int,
    ) {
        val m = last.pose()
        val u0 = sprite.getU(0.2f)
        val u1 = sprite.getU(0.8f)
        val v0 = sprite.getV(0.2f)
        val v1 = sprite.getV(0.8f)

        fun face(nx: Float, ny: Float, nz: Float, corners: Array<FloatArray>) {
            val uv = arrayOf(floatArrayOf(u0, v1), floatArrayOf(u1, v1), floatArrayOf(u1, v0), floatArrayOf(u0, v0))
            for (i in 0 until 4) {
                val c = corners[i]
                buffer.addVertex(m, c[0], c[1], c[2])
                    .setColor(r, g, b, alpha)
                    .setUv(uv[i][0], uv[i][1])
                    .setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(light)
                    .setNormal(last, nx, ny, nz)
            }
        }

        face(0f, 0f, 1f, arrayOf(
            floatArrayOf(-hx, -hy, hz), floatArrayOf(hx, -hy, hz), floatArrayOf(hx, hy, hz), floatArrayOf(-hx, hy, hz)))
        face(0f, 0f, -1f, arrayOf(
            floatArrayOf(hx, -hy, -hz), floatArrayOf(-hx, -hy, -hz), floatArrayOf(-hx, hy, -hz), floatArrayOf(hx, hy, -hz)))
        face(1f, 0f, 0f, arrayOf(
            floatArrayOf(hx, -hy, hz), floatArrayOf(hx, -hy, -hz), floatArrayOf(hx, hy, -hz), floatArrayOf(hx, hy, hz)))
        face(-1f, 0f, 0f, arrayOf(
            floatArrayOf(-hx, -hy, -hz), floatArrayOf(-hx, -hy, hz), floatArrayOf(-hx, hy, hz), floatArrayOf(-hx, hy, -hz)))
        face(0f, 1f, 0f, arrayOf(
            floatArrayOf(-hx, hy, hz), floatArrayOf(hx, hy, hz), floatArrayOf(hx, hy, -hz), floatArrayOf(-hx, hy, -hz)))
        face(0f, -1f, 0f, arrayOf(
            floatArrayOf(-hx, -hy, -hz), floatArrayOf(hx, -hy, -hz), floatArrayOf(hx, -hy, hz), floatArrayOf(-hx, -hy, hz)))
    }

    /**
     * A running column of the real liquid, from a mouth to its own block — a TUBE, not a ribbon.
     *
     * Square section, extruded along the run in the span's own frame, so it has real thickness and does not
     * turn to follow the viewer. A flat strip is the same lie a billboard is: from the side it vanishes to a
     * line, and a stream that disappears when you walk round it is worse than no stream at all.
     *
     * The motion is free. The flowing sprite already animates with directional movement, and Minecraft plays
     * it by uploading the live frame into the sprite's own patch of the atlas, so mapping it down the length
     * once and sampling every frame makes the liquid visibly run. Scrolling the UVs by hand would mean
     * wrapping inside the sprite's region and a seam wherever a quad straddled the wrap.
     *
     * Width and opacity follow the flow, so a trickle is a thread and a bucket a tick is a proper column.
     */
    private fun stream(
        fluid: net.minecraft.world.level.material.Fluid,
        from: Vec3,
        to: Vec3,
        cam: Vec3,
        pose: PoseStack,
        buffers: MultiBufferSource,
        level: Level,
        cell: BlockPos,
        flow: Double,
        time: Double,
        life: Float,
    ) {
        val span = to.subtract(from)
        if (span.lengthSqr() < 1e-8) return
        val dir = span.normalize()
        val u = perpendicular(dir)
        val v = dir.cross(u).normalize()
        // Liquid falls; it does not fork. Barely any sway, and slow.
        val amp = 0.02 * span.length()

        val centres = Array(STREAM_STEPS + 1) { i ->
            val t = i / STREAM_STEPS.toDouble()
            val taper = sin(t * Math.PI)
            val a = sin(time * 0.18 + t * 2.2)
            val b = cos(time * 0.14 + t * 1.7)
            from.add(span.scale(t)).add(u.scale(a * amp * taper)).add(v.scale(b * amp * taper)).subtract(cam)
        }

        val look = bpm.platform.client.FluidVisuals.of(fluid)
        val sprite = Minecraft.getInstance()
            .getTextureAtlas(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS)
            .apply(look.flowing)
        val tint = look.tint
        val r = ((tint shr 16) and 0xFF) / 255f
        val g = ((tint shr 8) and 0xFF) / 255f
        val b = (tint and 0xFF) / 255f
        val alpha = (0.6f + 0.15f * flow.toFloat()).coerceAtMost(0.92f) * life.coerceIn(0f, 1f)
        val half = STREAM_WIDTH * (0.55 + 0.5 * flow) * life.coerceIn(0f, 1f)
        val light = LevelRenderer.getLightColor(level, cell)

        val buffer = buffers.getBuffer(bpm.platform.client.translucentCull(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS))
        val last = pose.last()
        val m = last.pose()
        val u0 = sprite.getU(0.18f)
        val u1 = sprite.getU(0.82f)

        fun width(i: Int): Double {
            val t = i / STREAM_STEPS.toDouble()
            return half * (0.45 + 0.55 * sin(t * Math.PI))
        }
        // The four corners of the section at ring i, in the span's own frame.
        fun corner(i: Int, k: Int): Vec3 {
            val w = width(i)
            val su = if (k == 0 || k == 3) -w else w
            val sv = if (k < 2) -w else w
            return centres[i].add(u.scale(su)).add(v.scale(sv))
        }

        for (i in 0 until STREAM_STEPS) {
            val vA = sprite.getV(0.05f + 0.9f * (i / STREAM_STEPS.toFloat()))
            val vB = sprite.getV(0.05f + 0.9f * ((i + 1) / STREAM_STEPS.toFloat()))
            for (k in 0 until 4) {
                val k2 = (k + 1) % 4
                val n = corner(i, k2).subtract(corner(i, k)).cross(dir).let {
                    if (it.lengthSqr() < 1e-9) u else it.normalize()
                }
                val quad = arrayOf(
                    Triple(corner(i, k), u0, vA),
                    Triple(corner(i + 1, k), u0, vB),
                    Triple(corner(i + 1, k2), u1, vB),
                    Triple(corner(i, k2), u1, vA),
                )
                for ((at, uu, vv) in quad) {
                    buffer.addVertex(m, at.x.toFloat(), at.y.toFloat(), at.z.toFloat())
                        .setColor(r, g, b, alpha)
                        .setUv(uu, vv)
                        .setOverlay(OverlayTexture.NO_OVERLAY)
                        .setLight(light)
                        .setNormal(last, n.x.toFloat(), n.y.toFloat(), n.z.toFloat())
                }
            }
        }
    }

    /**
     * A current, drawn as a chain of little cubes on the block grid.
     *
     * It was a smooth tapered ribbon, and it looked good — but good in the wrong language. Nothing else in
     * Minecraft is a swept curve; everything is voxels on a sixteenth-of-a-block grid, and a vector-smooth
     * beam sitting next to that reads as if it came from another game. So the current is built the way the
     * game builds everything: small axis-aligned cubes, snapped to the pixel grid, of slightly uneven size.
     *
     * They are re-jittered on a [SPARK_STEP]-tick cadence rather than every frame. Per-frame noise strobes
     * and is exhausting to stand next to; a few times a second reads as crackling.
     *
     * Sizes and brightnesses vary along the chain so it is not a row of identical beads, and the middle
     * carries the fatter ones, which is what gives a run of cubes the sense of a beam rather than a dotted
     * line.
     */
    private fun arc(from: Vec3, to: Vec3, cam: Vec3, pose: PoseStack, buffers: MultiBufferSource, time: Double, seed: Int, flow: Double) {
        val span = to.subtract(from)
        if (span.lengthSqr() < 1e-8) return
        val dir = span.normalize()
        val u = perpendicular(dir)
        val v = dir.cross(u).normalize()
        val amp = (0.05 + 0.05 * flow) * span.length()
        // Held for a few ticks at a time: this is what makes it crackle instead of strobe.
        val step = kotlin.math.floor(time / SPARK_STEP)
        val phase = seed * 0.7 + step * 1.31

        val buffer = buffers.getBuffer(ARC)
        val m = pose.last().pose()

        for (i in 0..SPARK_COUNT) {
            val t = i / SPARK_COUNT.toDouble()
            val taper = sin(t * Math.PI)
            val a = sin(phase + t * 5.3) * 0.7 + sin(phase * 0.61 + t * 2.9) * 0.3
            val b = cos(phase * 0.83 + t * 4.1) * 0.7 + cos(phase * 1.13 + t * 2.2) * 0.3
            val world = from.add(span.scale(t)).add(u.scale(a * amp * taper)).add(v.scale(b * amp * taper))

            // Snapped in WORLD space, before the camera comes off: a grid that moved with the viewer would
            // not be a grid at all.
            val at = Vec3(snap(world.x), snap(world.y), snap(world.z)).subtract(cam)

            val h = hash(i * 37 + seed + (step.toInt() * 101))
            val half = (SPARK_MIN + (SPARK_MAX - SPARK_MIN) * h) * (0.45 + 0.55 * taper)
            val hot = 0.55f + 0.45f * h.toFloat()
            spark(m, buffer, at, half, hot, hot * 0.42f, hot * 0.30f, 0.55f + 0.35f * h.toFloat())
        }
    }

    /** To the nearest sixteenth of a block — the grid every other thing in the game sits on. */
    private fun snap(v: Double): Double = Math.round(v * 16.0) / 16.0

    /** A cheap deterministic 0..1 from an int, so the chain looks irregular without a random per frame. */
    private fun hash(n: Int): Double {
        val x = (n * 374761393 + 668265263)
        val y = (x xor (x shr 13)) * 1274126177
        return ((y xor (y shr 16)) and 0x7FFFFFF) / 0x7FFFFFF.toDouble()
    }

    /** One axis-aligned cube of light. */
    private fun spark(m: org.joml.Matrix4f, buffer: com.mojang.blaze3d.vertex.VertexConsumer, at: Vec3, half: Double, r: Float, g: Float, b: Float, alpha: Float) {
        val x0 = (at.x - half).toFloat()
        val x1 = (at.x + half).toFloat()
        val y0 = (at.y - half).toFloat()
        val y1 = (at.y + half).toFloat()
        val z0 = (at.z - half).toFloat()
        val z1 = (at.z + half).toFloat()
        fun quad(vs: Array<FloatArray>) {
            for (c in vs) buffer.addVertex(m, c[0], c[1], c[2]).setColor(r, g, b, alpha)
        }
        quad(arrayOf(floatArrayOf(x0, y0, z1), floatArrayOf(x1, y0, z1), floatArrayOf(x1, y1, z1), floatArrayOf(x0, y1, z1)))
        quad(arrayOf(floatArrayOf(x1, y0, z0), floatArrayOf(x0, y0, z0), floatArrayOf(x0, y1, z0), floatArrayOf(x1, y1, z0)))
        quad(arrayOf(floatArrayOf(x1, y0, z1), floatArrayOf(x1, y0, z0), floatArrayOf(x1, y1, z0), floatArrayOf(x1, y1, z1)))
        quad(arrayOf(floatArrayOf(x0, y0, z0), floatArrayOf(x0, y0, z1), floatArrayOf(x0, y1, z1), floatArrayOf(x0, y1, z0)))
        quad(arrayOf(floatArrayOf(x0, y1, z1), floatArrayOf(x1, y1, z1), floatArrayOf(x1, y1, z0), floatArrayOf(x0, y1, z0)))
        quad(arrayOf(floatArrayOf(x0, y0, z0), floatArrayOf(x1, y0, z0), floatArrayOf(x1, y0, z1), floatArrayOf(x0, y0, z1)))
    }

    /** Any unit vector square to [dir]. */
    private fun perpendicular(dir: Vec3): Vec3 {
        val seed = if (abs(dir.y) > 0.9) Vec3(1.0, 0.0, 0.0) else Vec3(0.0, 1.0, 0.0)
        return dir.cross(seed).normalize()
    }

    /** The fluid an effect names, or null when it names none (energy, experience, a bare transfer). */
    private fun fluidOf(id: String): net.minecraft.world.level.material.Fluid? =
        ResourceLocation.tryParse(id)
            ?.let { BuiltInRegistries.FLUID.getOptional(it).orElse(null) }
            ?.takeIf { it != net.minecraft.world.level.material.Fluids.EMPTY }

    private fun colourOf(kind: EffectKind, item: String): Int = when (kind) {
        // Red, as every tech mod has taught people to read a power cable. It is the one place the mod's
        // teal would be actively misleading: teal is what a working bpm machine looks like, and a live
        // current running between two boxes is not a bpm thing, it is electricity.
        EffectKind.ENERGY -> 0xFF4438
        EffectKind.XP -> 0xA8F04A
        EffectKind.FLUID -> ResourceLocation.tryParse(item)?.let { bpm.platform.valueOf(BuiltInRegistries.FLUID, it) }?.let { bpm.platform.client.FluidVisuals.of(it).tint and 0xFFFFFF } ?: 0x3F76E4
        else -> 0x8AB4F8
    }

    private fun stackOf(item: String): ItemStack =
        ResourceLocation.tryParse(item)?.let { BuiltInRegistries.ITEM.getOptional(it).orElse(null) }?.let { ItemStack(it) } ?: ItemStack.EMPTY

    /** A pulse of fluid throws at most this many drips; a stream is made of many pulses. */
    private const val MAX_DRIPS = 6

    // The current: enough points that the curve is smooth over a half-block hop, and a bright core inside a
    // wider dim body.
    // The fluid column: fewer points than the current needs, because it barely bends.
    private const val STREAM_STEPS = 10
    // Thin: over a half-block run a fatter column reads as a blob rather than as something flowing.
    private const val STREAM_WIDTH = 0.028

    /**
     * How much of the run either end stops short of the tear.
     *
     * Only a token gap now that the mouths are drawn last over a depth-writing tear: enough to keep the
     * column's end cap from z-fighting the disc it is entering, not enough to look like it stops short.
     */
    private const val MOUTH_GAP = 0.08

    // The current, as voxels: how many cubes in the chain, how big they get, and how often they re-jitter.
    private const val SPARK_COUNT = 11
    private const val SPARK_MIN = 0.020
    private const val SPARK_MAX = 0.042
    private const val SPARK_STEP = 2.5
    /**
     * The energy arc's own type.
     *
     * NOT `bpm.platform.client.lightning()`, whose `WEATHER_TARGET` output state sends it to the weather framebuffer
     * for the dedicated lightning pass. Additive on purpose here: an arc IS light added to the scene, and
     * effects are drawn after translucent blocks, so it glows over whatever is behind it.
     */
    /** The energy arc: flat colour, added onto the scene, and NOT depth-writing. */
    private val ARC: RenderType = bpm.platform.client.additiveQuads("bpm_energy_arc")

    private fun flyerCount(): Int = effects.values.sumOf { (it as? Transfer)?.flyers?.size ?: 0 }

    private fun Vec3.lerp(to: Vec3, t: Double): Vec3 = Vec3(x + (to.x - x) * t, y + (to.y - y) * t, z + (to.z - z) * t)

    // The reach geometry lives in bpm.world.LinkAnchors so the SERVER can put real entities at the same
    // points these effects draw to — see `items.drop`, which spawns its stack exactly where the hand is.
    private const val OFF_FACE = bpm.world.LinkAnchors.OFF_FACE
    private const val SELF_HEIGHT = bpm.world.LinkAnchors.SELF_HEIGHT
    private const val SELF_CORE = bpm.world.LinkAnchors.SELF_CORE

    /** How far above a person's head their rift hangs, as [SELF_HEIGHT] does over the controller. */
    private const val HEAD_CLEARANCE = 0.45

    /** A tear that hangs over something lies flat, looking down at it — a person, or the controller. */
    private val DOWN = Vec3(0.0, -1.0, 0.0)

    // Where a person's two points go when the client cannot see them, measured from the centre of the block
    // they were last reported standing in — a head at 1.8 and the rift's clearance over it.
    private const val PERSON_REST_HEAD = 1.3
    private const val PERSON_REST_OVERHEAD = 1.75

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
