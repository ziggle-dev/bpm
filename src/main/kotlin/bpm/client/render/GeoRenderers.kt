package bpm.client.render

import bpm.Bpm
import bpm.world.ControllerBlock
import bpm.world.ControllerBlockEntity
import bpm.world.ControllerBlockItem
import bpm.world.ControllerStatus
import bpm.world.LinkerItem
import bpm.world.ModBlockEntities
import bpm.world.ModComponents
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.AABB
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions
import software.bernie.geckolib.animatable.GeoAnimatable
import software.bernie.geckolib.constant.DataTickets
import software.bernie.geckolib.loading.math.MolangQueries
import software.bernie.geckolib.model.GeoModel
import software.bernie.geckolib.renderer.GeoBlockRenderer
import software.bernie.geckolib.renderer.GeoItemRenderer

/** A GeckoLib model at fixed asset paths, so one model file can serve a block entity and its item. */
open class PathGeoModel<T : GeoAnimatable>(private val geo: ResourceLocation, private val anim: ResourceLocation, private val tex: ResourceLocation) : GeoModel<T>() {
    override fun getModelResource(animatable: T): ResourceLocation = geo
    override fun getAnimationResource(animatable: T): ResourceLocation = anim
    override fun getTextureResource(animatable: T): ResourceLocation = tex
}

private fun rl(path: String) = ResourceLocation.fromNamespaceAndPath(Bpm.ID, path)

class ControllerModel<T : GeoAnimatable> : PathGeoModel<T>(
    rl("geo/block/quantum_controller.geo.json"),
    rl("animations/block/quantum_controller.animation.json"),
    rl("textures/block/quantum_controller.png"),
)

class LinkerModel : PathGeoModel<LinkerItem>(
    rl("geo/item/quantum_linker.geo.json"),
    rl("animations/item/quantum_linker.animation.json"),
    rl("textures/item/quantum_linker.png"),
)

/** The controller block. Beams carry alpha, so the whole model draws translucent; the glow mask rides on top. */
class ControllerRenderer : GeoBlockRenderer<ControllerBlockEntity>(ControllerModel()) {
    init {
        addRenderLayer(GlowLayer(this))
    }

    /** The model reaches 1.33 blocks up and the flanges poke half a block out. */
    override fun getRenderBoundingBox(blockEntity: ControllerBlockEntity): AABB =
        AABB(blockEntity.blockPos).inflate(0.5, 0.0, 0.5).expandTowards(0.0, 0.75, 0.0)

    override fun getRenderType(animatable: ControllerBlockEntity, texture: ResourceLocation, bufferSource: MultiBufferSource?, partialTick: Float): RenderType =
        RenderType.entityTranslucent(texture)
}

class ControllerItemRenderer : GeoItemRenderer<ControllerBlockItem>(ControllerModel()) {
    init {
        addRenderLayer(GlowLayer(this))
    }

    override fun getRenderType(animatable: ControllerBlockItem, texture: ResourceLocation, bufferSource: MultiBufferSource?, partialTick: Float): RenderType =
        RenderType.entityTranslucent(texture)
}

class LinkerRenderer : GeoItemRenderer<LinkerItem>(LinkerModel()) {
    init {
        addRenderLayer(GlowLayer(this))
    }
}

object ControllerItemExtensions : IClientItemExtensions {
    private val renderer by lazy { ControllerItemRenderer() }
    override fun getCustomRenderer(): BlockEntityWithoutLevelRenderer = renderer
}

object LinkerItemExtensions : IClientItemExtensions {
    private val renderer by lazy { LinkerRenderer() }
    override fun getCustomRenderer(): BlockEntityWithoutLevelRenderer = renderer
}

/**
 * Client wiring for the models: the block-entity renderer and the Molang variables the animations read.
 *
 * The idle animations are Molang-driven (see the READMEs in `art/`): an unset variable evaluates to 0, which
 * freezes the core, so every variable each model reads is set here from the animatable being rendered.
 */
object GeoRenderers {
    fun registerRenderers(event: EntityRenderersEvent.RegisterRenderers) {
        event.registerBlockEntityRenderer(ModBlockEntities.CONTROLLER.get()) { ControllerRenderer() }
        DeviceRenderers.register(event)
        event.registerEntityRenderer(bpm.world.entity.ModEntities.WARDEN.get()) { WardenRenderer(it) }
        event.registerEntityRenderer(bpm.world.entity.ModEntities.BOLT.get()) { WardenBoltRenderer(it) }
        event.registerEntityRenderer(bpm.world.entity.ModEntities.PULSE.get()) { LinkerPulseRenderer(it) }
    }

    fun installMolang() {
        // Controller (block and item): spin/bob follow the status; the phase is per block.
        MolangQueries.setActorVariable<Any>("variable.spin_speed") { actor ->
            when (val a = actor.animatable()) {
                is ControllerBlockEntity -> if (statusOf(a) == ControllerStatus.RUNNING) 1.0 else 0.5
                is LinkerItem -> 1.0
                is bpm.world.devices.DeviceBlockEntity -> 1.0
                is bpm.world.entity.QuantumWardenEntity -> 1.0
                else -> 0.6
            }
        }
        MolangQueries.setActorVariable<Any>("variable.bob_amount") { actor ->
            when (val a = actor.animatable()) {
                is ControllerBlockEntity -> if (statusOf(a) == ControllerStatus.RUNNING) 1.0 else 0.4
                is bpm.world.entity.QuantumWardenEntity -> 1.0
                else -> 0.5
            }
        }
        MolangQueries.setActorVariable<Any>("variable.beam_speed") { actor ->
            when (val a = actor.animatable()) {
                is ControllerBlockEntity -> if (statusOf(a) == ControllerStatus.RUNNING) 0.5 else 0.0
                else -> 0.0
            }
        }
        MolangQueries.setActorVariable<Any>("variable.beam_power") { actor ->
            when (val a = actor.animatable()) {
                is ControllerBlockEntity -> if (statusOf(a) == ControllerStatus.RUNNING) 1.0 else 0.0
                else -> 0.0
            }
        }
        // The rifts: how fast things flow through, and a per-rift spiral offset.
        MolangQueries.setActorVariable<Any>("variable.flow_speed") { actor ->
            when (val a = actor.animatable()) {
                is bpm.client.fx.Rift -> a.flowSpeed
                else -> 1.0
            }
        }
        MolangQueries.setActorVariable<Any>("variable.phase") { actor ->
            when (val a = actor.animatable()) {
                is ControllerBlockEntity -> a.animPhase
                is bpm.client.fx.Rift -> a.phase
                is bpm.world.devices.DeviceBlockEntity -> a.animPhase
                is bpm.world.entity.QuantumWardenEntity -> a.animPhase
                else -> 0.0
            }
        }
        // Devices: the gate's flow direction, the pedestal's core, the turret's aim.
        MolangQueries.setActorVariable<Any>("variable.direction") { actor ->
            when (val a = actor.animatable()) {
                is bpm.world.devices.GateBlockEntity -> a.direction.toDouble()
                else -> 1.0
            }
        }
        MolangQueries.setActorVariable<Any>("variable.has_core") { actor ->
            when (val a = actor.animatable()) {
                is bpm.world.devices.PedestalBlockEntity -> if (a.hasCore) 1.0 else 0.0
                else -> 1.0
            }
        }
        MolangQueries.setActorVariable<Any>("variable.target_yaw") { actor ->
            (actor.animatable() as? bpm.world.devices.TurretBlockEntity)?.targetYaw?.toDouble() ?: 0.0
        }
        MolangQueries.setActorVariable<Any>("variable.target_pitch") { actor ->
            (actor.animatable() as? bpm.world.devices.TurretBlockEntity)?.targetPitch?.toDouble() ?: 0.0
        }
        // The Warden's stage and shield.
        MolangQueries.setActorVariable<Any>("variable.stage") { actor -> (actor.animatable() as? bpm.world.entity.QuantumWardenEntity)?.stage?.toDouble() ?: 1.0 }
        MolangQueries.setActorVariable<Any>("variable.shield") { actor -> if ((actor.animatable() as? bpm.world.entity.QuantumWardenEntity)?.shielded == true) 1.0 else 0.0 }
        // Linker: 1 while a controller is bound to the stack being drawn.
        MolangQueries.setActorVariable<Any>("variable.linked") { actor ->
            val stack = actor.animationState().getData(DataTickets.ITEMSTACK)
            if (stack != null && stack.has(ModComponents.SELECTED_CONTROLLER.get())) 1.0 else 0.0
        }
        // Rift (phase 7): default flow so a rift rendered before the effect system sets it is not frozen.
        MolangQueries.setActorVariable<Any>("variable.flow_speed") { 1.0 }
    }

    private fun statusOf(be: ControllerBlockEntity): ControllerStatus {
        val state = be.blockState
        return if (state.hasProperty(ControllerBlock.STATUS)) state.getValue(ControllerBlock.STATUS) else ControllerStatus.IDLE
    }
}
