package bpm.client.render

import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3

/**
 * Where a model's bones actually are, published by the renderer that drew them.
 *
 * **Not GeckoLib's `locators`.** A geo file may declare them and this version parses them into
 * `loading.json.raw.Bone`, but nothing reads them back: the baker never looks, and `GeoBone` has no field
 * for one. Locators in the geo are inert.
 *
 * **Not `GeoBone.getWorldPosition()` either**, though that one at least exists. It reads a matrix the
 * renderers fill in only when `isTrackingMatrices()` is already true, and the getter is what turns tracking
 * on — so the first read of any bone is a frame stale and reads zero. For a block it then post-multiplies
 * the block position onto a matrix that already carries the renderer's own half-block translate and
 * whatever facing rotation `rotateBlock` applied, which is not the sum it looks like.
 *
 * Reading the pose stack at the bone needs no correction and no guessing. Inside `renderRecursively`, after
 * GeckoLib has translated to the bone and rotated around it, the top of the stack IS the bone pivot's
 * transform — already in block units, because `RenderUtil.translateMatrixToBone` divides by 16.
 *
 * Positions are stored absolute; the caller adds the camera back at capture time and takes it off again at
 * draw time. [sweep] drops anything that stopped being drawn, so a culled chunk or a broken block never
 * leaves an effect anchored to a spot nothing occupies.
 */
object BoneAnchors {

    private class Bones {
        val at = HashMap<String, Vec3>()
        var seen = 0L
    }

    // Two maps rather than one: a packed BlockPos and an entity id are both longs and would collide.
    private val byBlock = HashMap<Long, Bones>()
    private val byEntity = HashMap<Int, Bones>()
    private var frame = 0L

    /** Called from a block renderer, once per bone per frame, with the bone pivot in absolute world space. */
    fun capture(pos: BlockPos, bone: String, at: Vec3) = put(byBlock.getOrPut(pos.asLong()) { Bones() }, bone, at)

    /** The same, for an entity's model. */
    fun capture(entityId: Int, bone: String, at: Vec3) = put(byEntity.getOrPut(entityId) { Bones() }, bone, at)

    /** Where [bone] was last drawn, or null if that model has not been on screen recently. */
    fun of(pos: BlockPos, bone: String): Vec3? = byBlock[pos.asLong()]?.at?.get(bone)

    fun of(entityId: Int, bone: String): Vec3? = byEntity[entityId]?.at?.get(bone)

    private fun put(bones: Bones, bone: String, at: Vec3) {
        bones.at[bone] = at
        bones.seen = frame
    }

    /**
     * A frame has ended. Entries are kept for a few frames rather than one: a block entity is not drawn on
     * every client frame, and dropping an anchor the moment a frame missed it makes every effect hanging
     * off it jump back to its fallback and out again.
     */
    fun endFrame() {
        frame++
        if (frame % SWEEP_EVERY != 0L) return
        byBlock.entries.removeIf { frame - it.value.seen > KEEP_FRAMES }
        byEntity.entries.removeIf { frame - it.value.seen > KEEP_FRAMES }
    }

    /** Leaving a world invalidates every position in it. */
    fun clear() {
        byBlock.clear()
        byEntity.clear()
    }

    private const val KEEP_FRAMES = 20L
    private const val SWEEP_EVERY = 20L
}
