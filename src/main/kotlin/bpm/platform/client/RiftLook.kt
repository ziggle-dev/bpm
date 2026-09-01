package bpm.platform.client

import bpm.client.render.RiftStyle
import net.minecraft.client.renderer.RenderType

/**
 * How a rift is drawn, which is the one piece of this mod's rendering that needs a real shader.
 *
 * The swirl is two custom fragment shaders, and every loader and every Minecraft version disagrees about
 * how to register one: NeoForge fires `RegisterShadersEvent`, Fabric loses `CoreShaderRegistrationCallback`
 * at 1.21.2, and both lose core shaders entirely at 1.21.5 in favour of `RenderPipeline` with inline
 * GLSL. So this asks for the finished `RenderType` and says nothing about where it came from.
 *
 * [ready] is not a detail. A rift is decoration for a transfer that has already happened, so a loader or
 * a version that cannot supply the shader yet should draw no rift and leave everything else working —
 * the same judgement `EffectManager` already makes when a rift throws. The default backend answers false
 * forever, which means a build with no implementation is merely plainer, never broken.
 */
interface RiftLook {
    val ready: Boolean

    fun typeFor(style: RiftStyle): RenderType
}

object RiftLooks {
    private var backend: RiftLook = object : RiftLook {
        override val ready: Boolean get() = false
        override fun typeFor(style: RiftStyle): RenderType =
            error("no rift look installed; nothing should ask for a type while ready is false")
    }

    fun install(impl: RiftLook) {
        backend = impl
    }

    val ready: Boolean get() = backend.ready

    fun typeFor(style: RiftStyle): RenderType = backend.typeFor(style)
}
