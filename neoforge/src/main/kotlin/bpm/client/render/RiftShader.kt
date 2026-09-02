package bpm.client.render

import bpm.Bpm
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.renderer.RenderStateShard
import bpm.platform.RenderType
import bpm.platform.ResourceLocation
import net.neoforged.neoforge.client.event.RegisterShadersEvent

/** Which rift the client draws. Switch live with `/bpm rift <cube|tear>`. */

/**
 * The two core shaders a rift can be made of, and the render types that carry them.
 *
 * **No samplers.** Both effects generate everything from value noise in the fragment shader, so there is no
 * texture to paint and no UV layout to keep in step. The format is
 * [DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL] with [RenderStateShard.NO_TEXTURE], and the three
 * non-position channels are carrying data rather than appearance:
 *
 * - **UV0** — where in the face this fragment sits, 0..1.
 * - **Color** — for the cube, the fragment's position in the cube's own frame, which is what lets the
 *   shader fire the view ray onward and solve the interior. For the tear, sixteen bits of seed hashed from
 *   the anchor's block position, so a tear is the same shape every time it opens there.
 * - **Normal** — for the tear, the eye direction measured in the tear's own frame, which drives the
 *   parallax between its depth layers. Unused by the cube.
 *
 * All of it is per-vertex on purpose: nothing is a uniform, so every rift on screen batches into one draw
 * call however many there are and whatever they are showing.
 */
object RiftShader : bpm.platform.client.RiftLook {

    /** Declared first: the shader programs below are built from it as the object initialises. */
    val FORMAT: VertexFormat = DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL

    /*
     * Registering a core shader, on both sides of 1.21.2's shader rework.
     *
     * Until 1.21.2 a mod BUILT the `ShaderInstance` -- compiling it there and then from a
     * `ResourceProvider` the event handed over -- and gave the game a callback to receive it. From 1.21.2
     * it DECLARES a `ShaderProgram`: an id, a vertex format and a set of defines, three values with no
     * GL state in them. The game compiles it when it loads resources and recompiles it on every pack
     * reload, which is the whole point of the change -- the old callback shape had every mod holding a
     * handle that a resource reload had already invalidated.
     *
     * Two consequences for the code below. There is nothing to hold on to any more, so `ready` asks the
     * shader manager rather than checking a field; and a `RenderType` names the program rather than a
     * supplier of the instance.
     *
     * The GLSL is untouched. The config JSON is not: `vertex` and `fragment` became real resource
     * locations resolved under `shaders/`, where they used to be bare names resolved under
     * `shaders/core/`, so 1.21.4 wants `bpm:core/rift_cube` where 1.21.1 wants `bpm:rift_cube`. No single
     * spelling works on both -- hence the pair of JSONs under this node's own resources.
     */
    //? if >=1.21.2 {
    /*private val CUBE_PROGRAM = net.minecraft.client.renderer.ShaderProgram(
        rl("core/rift_cube"), FORMAT, net.minecraft.client.renderer.ShaderDefines.EMPTY,
    )
    private val TEAR_PROGRAM = net.minecraft.client.renderer.ShaderProgram(
        rl("core/rift_tear"), FORMAT, net.minecraft.client.renderer.ShaderDefines.EMPTY,
    )

    fun register(event: RegisterShadersEvent) {
        event.registerShader(CUBE_PROGRAM)
        event.registerShader(TEAR_PROGRAM)
        Bpm.LOGGER.info("bpm rift shaders registered (cube, tear)")
    }

    /** True once both have compiled; nothing draws a rift before then. */
    override val ready: Boolean
        get() {
            val shaders = net.minecraft.client.Minecraft.getInstance().shaderManager
            return shaders.getProgram(CUBE_PROGRAM) != null && shaders.getProgram(TEAR_PROGRAM) != null
        }
    *///?} else {
    private var cube: net.minecraft.client.renderer.ShaderInstance? = null
    private var tear: net.minecraft.client.renderer.ShaderInstance? = null

    fun register(event: RegisterShadersEvent) {
        event.registerShader(net.minecraft.client.renderer.ShaderInstance(event.resourceProvider, rl("rift_cube"), FORMAT)) { cube = it }
        event.registerShader(net.minecraft.client.renderer.ShaderInstance(event.resourceProvider, rl("rift_tear"), FORMAT)) { tear = it }
        Bpm.LOGGER.info("bpm rift shaders registered (cube, tear)")
    }

    /** True once both have loaded; nothing draws a rift before then. */
    override val ready: Boolean get() = cube != null && tear != null
    //?}

    override fun typeFor(style: RiftStyle): RenderType = if (style == RiftStyle.CUBE) CUBE else TEAR

    /**
     * Additive and unculled, and it DOES write depth.
     *
     * Depth used to be off on the theory that a rift is light added to the scene rather than a surface. But
     * a hole is a surface: without depth, anything drawn after it painted straight over the disc, so a
     * column of fluid entering the tear looked stuck to the front of it. Both fragment shaders already
     * `discard` transparent fragments — and the tear discards everything outside its radius — so only the
     * visible mouth writes depth, never the quad it is cut from.
     */
    private fun type(name: String, shader: RenderStateShard.ShaderStateShard): RenderType = net.minecraft.client.renderer.RenderType.create(
        name,
        FORMAT,
        VertexFormat.Mode.QUADS,
        256,
        false,
        false,
        net.minecraft.client.renderer.RenderType.CompositeState.builder()
            .setShaderState(shader)
            .setTextureState(RenderStateShard.NO_TEXTURE)
            .setTransparencyState(RenderStateShard.ADDITIVE_TRANSPARENCY)
            .setCullState(RenderStateShard.NO_CULL)
            .setWriteMaskState(RenderStateShard.COLOR_DEPTH_WRITE)
            .createCompositeState(false),
    )

    //? if >=1.21.2 {
    /*private val CUBE: RenderType = type("bpm_rift_cube", RenderStateShard.ShaderStateShard(CUBE_PROGRAM))
    private val TEAR: RenderType = type("bpm_rift_tear", RenderStateShard.ShaderStateShard(TEAR_PROGRAM))
    *///?} else {
    private val CUBE: RenderType = type("bpm_rift_cube", RenderStateShard.ShaderStateShard { cube })
    private val TEAR: RenderType = type("bpm_rift_tear", RenderStateShard.ShaderStateShard { tear })
    //?}

    private fun rl(path: String) = ResourceLocation.fromNamespaceAndPath(Bpm.ID, path)
}
