package bpm.client.render

import bpm.Bpm
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.resources.ResourceLocation
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

    private var cube: ShaderInstance? = null
    private var tear: ShaderInstance? = null

    fun register(event: RegisterShadersEvent) {
        event.registerShader(ShaderInstance(event.resourceProvider, rl("rift_cube"), FORMAT)) { cube = it }
        event.registerShader(ShaderInstance(event.resourceProvider, rl("rift_tear"), FORMAT)) { tear = it }
        Bpm.LOGGER.info("bpm rift shaders registered (cube, tear)")
    }

    /** True once both have loaded; nothing draws a rift before then. */
    override val ready: Boolean get() = cube != null && tear != null

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
    private fun type(name: String, shader: () -> ShaderInstance?): RenderType = RenderType.create(
        name,
        FORMAT,
        VertexFormat.Mode.QUADS,
        256,
        false,
        false,
        RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.ShaderStateShard(shader))
            .setTextureState(RenderStateShard.NO_TEXTURE)
            .setTransparencyState(RenderStateShard.ADDITIVE_TRANSPARENCY)
            .setCullState(RenderStateShard.NO_CULL)
            .setWriteMaskState(RenderStateShard.COLOR_DEPTH_WRITE)
            .createCompositeState(false),
    )

    val FORMAT: VertexFormat = DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL
    private val CUBE: RenderType = type("bpm_rift_cube") { cube }
    private val TEAR: RenderType = type("bpm_rift_tear") { tear }

    private fun rl(path: String) = ResourceLocation.fromNamespaceAndPath(Bpm.ID, path)
}
