package bpm.world

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.phys.Vec3

/**
 * Where the controller's reach lands on a linked block — the geometry the effects are drawn to, stated once
 * so the server can put real things in the same places.
 *
 * These numbers used to live as private constants inside `EffectManager`, which is client-only, so anything
 * the server actually spawned had no way to agree with them and simply did not: `drop` handed its stack to
 * `Containers.dropItemStack` at the block's CENTRE, with vanilla's random offset and random velocity on top.
 * The animation flew the item out to the face and then a real entity blinked into existence most of a block
 * away at a different size. One set of constants, both sides, and the seam closes.
 *
 * The three points along the outward normal, in order:
 *
 *  - [onFace] — flush with the block's surface, where a transfer's items arrive.
 *  - [hand] — a little further out: where an action holds its tool, and where the controller lets go of
 *    something it is dropping. Named for what it reads as, which is a hand reaching in.
 *  - [offFace] — as far out as the effect goes, where a rift hangs and items appear from.
 */
object LinkAnchors {

    /** Flush with the face of a full block. */
    const val ON_FACE = 0.5

    /** How far out from the face a pulled item vanishes and a pushed one appears — where the rift sits. */
    const val OFF_FACE = 1.05

    /** How far along [onFace] → [offFace] the hand sits. */
    const val HAND_ALONG = 0.65

    /** The controller's own end of an effect hangs this far above it. */
    const val SELF_HEIGHT = 1.45

    /** Where the controller's core sits when its model was not drawn this frame — a stand-in only. */
    const val SELF_CORE = 0.9

    /** The outward normal of [face], or up for a link with no face of its own. */
    fun normal(face: Direction?): Vec3 = Vec3.atLowerCornerOf((face ?: Direction.UP).normal)

    fun onFace(pos: BlockPos, face: Direction?): Vec3 = Vec3.atCenterOf(pos).add(normal(face).scale(ON_FACE))

    fun offFace(pos: BlockPos, face: Direction?): Vec3 = Vec3.atCenterOf(pos).add(normal(face).scale(OFF_FACE))

    /** Between the two, where the tool is held and where a dropped stack is released. */
    fun hand(pos: BlockPos, face: Direction?): Vec3 =
        Vec3.atCenterOf(pos).add(normal(face).scale(ON_FACE + HAND_ALONG * (OFF_FACE - ON_FACE)))

    /** The controller's own hand, for a link that is the controller itself. */
    fun selfHand(pos: BlockPos): Vec3 = Vec3.atCenterOf(pos).add(0.0, SELF_HEIGHT, 0.0)

    /**
     * How far above its own position an `ItemEntity` actually DRAWS itself.
     *
     * An entity's position is the bottom-centre of its box, not the middle of the thing you see, and three
     * separate lifts stack on top of that before the item is drawn:
     *
     *  - `ItemEntityRenderer` translates by `bob + 0.25 * groundScale.y`, where the bob is
     *    `sin(age) * 0.1 + 0.1` — 0.0 to 0.2, averaging 0.1.
     *  - the model's own GROUND display transform translates again, and that one is applied BEFORE its
     *    scale, so it contributes in full.
     *
     * Those land on the same total for both kinds of item, which is why one constant is right rather than a
     * compromise between them:
     *
     * | | bob | `0.25 × scale.y` | ground translation | total |
     * |---|---|---|---|---|
     * | `item/generated` | 0.100 | 0.125 (scale 0.5) | 0.125 (2/16) | **0.350** |
     * | `block/block` | 0.100 | 0.0625 (scale 0.25) | 0.1875 (3/16) | **0.350** |
     *
     * Spawn something at a point you want it to LOOK like it is at and it sits a third of a block past it.
     * [bpm.nodes.releaseStack] takes it off. Always vertical, whatever the link's normal is, because every
     * one of these lifts is applied in world Y.
     *
     * The bob is live, so the item still drifts ±0.1 around this forever.
     *
     * **The value is 0.45, not the 0.35 the table adds up to, and that is deliberate.** Dropping the item
     * a further tenth of a block clears it of the portal instead of centring it in the mouth — a thing
     * falling OUT of a hole should be below the hole, not halfway through it. The table is what the number
     * would be to make the item's middle sit exactly on the rift's; the extra is the look.
     */
    const val ITEM_RENDER_LIFT = 0.45
}

/**
 * How long the drawn half of a transfer takes, stated where the server can read it.
 *
 * The client flies an item out of the origin's face, holds it out of sight for a beat, then grows it into
 * the target's face. Anything the SERVER puts in the world for that transfer has to wait the same length,
 * or it exists while its own animation is still in the air — which is two of the item on screen at once,
 * one real and one drawn. `items.drop` waits [ARRIVAL_TICKS] before spawning for exactly that reason.
 */
object EffectTiming {

    /** One leg of the flight: out of the origin, and again into the target. */
    const val LEG_TICKS = 6

    /** The beat in between, where the item is nowhere. */
    const val VOID_TICKS = 3

    /** From the effect being sent to the first item landing at the far end. */
    const val ARRIVAL_TICKS = LEG_TICKS + VOID_TICKS + LEG_TICKS

    /**
     * When something REAL should appear at the far end instead of a drawn arrival.
     *
     * The outgoing leg has finished and the void beat is over, so this is the moment the target rift would
     * have started producing the item — which is where `items.drop` spawns its entity.
     */
    const val EMERGE_TICKS = LEG_TICKS + VOID_TICKS
}
