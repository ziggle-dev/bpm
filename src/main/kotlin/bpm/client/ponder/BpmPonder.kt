package bpm.client.ponder

import bpm.Bpm
import net.createmod.ponder.api.registration.PonderPlugin
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper
import net.createmod.ponder.api.scene.SceneBuilder
import net.createmod.ponder.api.scene.SceneBuildingUtil
import net.createmod.ponder.foundation.PonderIndex
import net.minecraft.core.Direction
import bpm.platform.ResourceLocation

/**
 * bpm's in-game scene tutorials, on Create's Ponder.
 *
 * **Why a dependency rather than our own.** The parts of this mod that are hard to learn are spatial — that
 * pedestals ring the assembler within two blocks, that it is fed from underneath, which FACE a link was made
 * on — and none of that can be written down usefully. The node language documents itself already: every node
 * carries its own prose and the palette shows it on hover, so a book would only duplicate 300 entries and
 * then drift from them. What was missing was a way to *show* a build assembling itself, and Ponder is that,
 * already solved, with a camera, a timeline and a text overlay that players of other mods already know how
 * to drive.
 *
 * **What it costs.** Ponder pulls Flywheel, and Catnip alongside it — so a player installing bpm installs
 * three more jars. That is the price of not writing and maintaining a scene renderer, a schematic loader and
 * a timeline of our own, and it buys an interface people have already learnt elsewhere.
 *
 * Registered from the client entry point only. Nothing on the server ever touches this, and the whole class
 * is behind the same client-only guard as the rest of `bpm.client`.
 */
class BpmPonder : PonderPlugin {

    override fun getModId(): String = Bpm.ID

    override fun registerScenes(helper: PonderSceneRegistrationHelper<ResourceLocation>) {
        // Keyed on the items a player would be holding when they wonder. The assembler scene answers from
        // the machine AND from a pedestal, because "what is this plinth for" is the more likely question.
        helper.addStoryBoard(id("quantum_assembler"), "assembler", ::assembler)
        helper.addStoryBoard(id("core_pedestal"), "assembler", ::assembler)
        helper.addStoryBoard(id("quantum_linker"), "links", ::links)
        helper.addStoryBoard(id("quantum_controller"), "links", ::links)
    }

    /**
     * The assembler: the ring, the reach, and the one rule nobody guesses — that power and experience go in
     * from below.
     */
    private fun assembler(scene: SceneBuilder, util: SceneBuildingUtil) {
        scene.title("bpm_assembler", "Fabricating with the Quantum Assembler")
        scene.configureBasePlate(0, 0, 5)
        scene.showBasePlate()
        scene.idle(10)

        scene.world().showSection(util.select().position(2, 2, 2), Direction.DOWN)
        scene.idle(10)
        scene.overlay().showText(70)
            .placeNearTarget()
            .pointAt(util.vector().topOf(2, 2, 2))
            .text("The Quantum Assembler makes what a crafting grid cannot.")
        scene.idle(80)

        scene.world().showSection(util.select().layer(1), Direction.DOWN)
        scene.idle(15)
        scene.overlay().showText(90)
            .placeNearTarget()
            .pointAt(util.vector().topOf(0, 1, 2))
            .text("Ingredients rest on Core Pedestals within 2 blocks — in any arrangement.")
        scene.idle(100)

        scene.addKeyframe()
        scene.rotateCameraY(140f)
        scene.idle(20)
        scene.overlay().showText(90)
            .placeNearTarget()
            .pointAt(util.vector().blockSurface(util.grid().at(2, 2, 2), Direction.DOWN))
            .text("Power and liquid experience are fed in from UNDERNEATH. Leave the block below it clear.")
        scene.idle(100)

        scene.addKeyframe()
        scene.overlay().showText(90)
            .placeNearTarget()
            .pointAt(util.vector().topOf(2, 2, 2))
            .text("Feed it the exact rate the recipe asks for. Too little or too much and the job decoheres.")
        scene.idle(100)

        scene.overlay().showText(90)
            .placeNearTarget()
            .pointAt(util.vector().topOf(2, 2, 2))
            .text("Hand it the catalyst last. That starts the job, and the result drops on top when it is done.")
        scene.idle(100)

        scene.markAsFinished()
    }

    /** Links: what one is, and that the face you click is part of it. */
    private fun links(scene: SceneBuilder, util: SceneBuildingUtil) {
        scene.title("bpm_links", "Linking blocks to a Quantum Controller")
        scene.configureBasePlate(0, 0, 5)
        scene.showBasePlate()
        scene.idle(10)

        scene.world().showSection(util.select().position(2, 1, 2), Direction.DOWN)
        scene.idle(10)
        scene.overlay().showText(80)
            .placeNearTarget()
            .pointAt(util.vector().topOf(2, 1, 2))
            .text("A Quantum Controller reaches out to blocks instead of touching them.")
        scene.idle(90)

        scene.world().showSection(util.select().layer(1), Direction.DOWN)
        scene.idle(15)
        scene.overlay().showText(90)
            .placeNearTarget()
            .pointAt(util.vector().topOf(0, 1, 0))
            .text("Click a block with the Quantum Linker to link it. Its name is how a graph refers to it.")
        scene.idle(100)

        scene.addKeyframe()
        scene.rotateCameraY(120f)
        scene.idle(20)
        scene.overlay().showText(100)
            .placeNearTarget()
            .pointAt(util.vector().topOf(4, 1, 4))
            .text("The FACE you click is remembered. A machine that only accepts power from one side must be linked on that side.")
        scene.idle(110)

        scene.overlay().showText(90)
            .placeNearTarget()
            .pointAt(util.vector().topOf(2, 1, 2))
            .text("The core's tier sets how far it reaches and how many links it can hold.")
        scene.idle(100)

        scene.markAsFinished()
    }

    private fun id(path: String): ResourceLocation = ResourceLocation.fromNamespaceAndPath(Bpm.ID, path)

    companion object {
        /**
         * Called only through [PonderCompat], which has already checked that Ponder is loaded.
         *
         * The `runCatching` here is NOT the absent-Ponder guard — it cannot be, since reaching this method
         * means this class has already loaded and therefore `PonderPlugin` resolved. It is for a
         * registration that fails on a Ponder whose API has moved, which should cost the tutorials and
         * nothing else.
         */
        fun install() {
            runCatching { PonderIndex.addPlugin(BpmPonder()) }
                .onFailure { Bpm.LOGGER.warn("bpm: Ponder rejected our scenes, tutorials are off", it) }
                .onSuccess { Bpm.LOGGER.info("bpm: Ponder scenes registered") }
        }
    }
}
