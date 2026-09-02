package bpm.client.render

import bpm.Bpm
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.renderer.RenderStateShard
import bpm.platform.RenderType
import bpm.platform.ResourceLocation

/**
 * The rift's two core shaders on Fabric.
 *
 * The counterpart of `RiftShader` on the other loader, and the same two render types built the same way
 * from the same GLSL — only how the shader is *obtained* differs, and it differs twice over.
 *
 * **On 1.21.1** Fabric has `CoreShaderRegistrationCallback`, which exists because vanilla's own
 * `ShaderInstance` resolves its name in the `minecraft` namespace and a mod's cannot. Fabric's
 * `FabricShaderProgram` is what makes `bpm:rift_cube` load `assets/bpm/shaders/core/rift_cube.json`.
 * The instance is handed back through a consumer and held, exactly as NeoForge's event does it.
 *
 * **On 1.21.4 there is nothing to register.** Fabric removed that callback when 1.21.2 reworked shaders,
 * and did not replace it — and it turns out none is needed: `ShaderManager.getProgram` goes through
 * `getOrCompileProgram`, which COMPILES ON DEMAND from any shader config the resource manager found under `shaders/`,
 * mod namespaces included. NeoForge's `RegisterShadersEvent` still exists on that version, but what it
 * buys is preloading — one compile at load rather than one on first draw — not correctness. So this
 * branch declares the two programs and asks for them; the first frame that draws a rift compiles them.
 */
object FabricRiftShader : bpm.platform.client.RiftLook {

    /** Declared first: the shader handles below are built from it as the object initialises. */
    val FORMAT: VertexFormat = DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL

    private fun rl(path: String) = ResourceLocation.fromNamespaceAndPath(Bpm.ID, path)

    //? if >=1.21.2 {
    /*private val CUBE_PROGRAM = net.minecraft.client.renderer.ShaderProgram(
        rl("core/rift_cube"), FORMAT, net.minecraft.client.renderer.ShaderDefines.EMPTY,
    )
    private val TEAR_PROGRAM = net.minecraft.client.renderer.ShaderProgram(
        rl("core/rift_tear"), FORMAT, net.minecraft.client.renderer.ShaderDefines.EMPTY,
    )

    private fun registerShaders() {
        // Nothing to register: the shader manager compiles these the first time one is asked for.
        Bpm.LOGGER.info("bpm rift shaders declared (cube, tear); compiled on first use")
    }

    /**
     * True once both compile.
     *
     * This ASKS for the program, which is also what compiles it, so the first call does the work and
     * every later one is a map lookup. A program that fails to compile is remembered as absent, so this
     * answers false forever rather than retrying every frame — which is the behaviour the rift wants: it
     * is decoration for a transfer that has already happened and must never be worth a stutter.
     */
    override val ready: Boolean
        get() {
            val shaders = net.minecraft.client.Minecraft.getInstance().shaderManager
            return shaders.getProgram(CUBE_PROGRAM) != null && shaders.getProgram(TEAR_PROGRAM) != null
        }

    private val CUBE: RenderType = type("bpm_rift_cube", RenderStateShard.ShaderStateShard(CUBE_PROGRAM))
    private val TEAR: RenderType = type("bpm_rift_tear", RenderStateShard.ShaderStateShard(TEAR_PROGRAM))
    *///?} else {
    private var cube: net.minecraft.client.renderer.ShaderInstance? = null
    private var tear: net.minecraft.client.renderer.ShaderInstance? = null

    private fun registerShaders() {
        net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback.EVENT.register { ctx ->
            ctx.register(rl("rift_cube"), FORMAT) { cube = it }
            ctx.register(rl("rift_tear"), FORMAT) { tear = it }
            Bpm.LOGGER.info("bpm rift shaders registered (cube, tear)")
        }
    }

    /** True once both have loaded; nothing draws a rift before then. */
    override val ready: Boolean get() = cube != null && tear != null

    private val CUBE: RenderType = type("bpm_rift_cube", RenderStateShard.ShaderStateShard { cube })
    private val TEAR: RenderType = type("bpm_rift_tear", RenderStateShard.ShaderStateShard { tear })
    //?}

    /** Declare the shaders and hand this look to the renderer. Call from the client entry point. */
    fun install() {
        registerShaders()
        bpm.platform.client.RiftLooks.install(this)
    }

    override fun typeFor(style: RiftStyle): RenderType = if (style == RiftStyle.CUBE) CUBE else TEAR

    /**
     * Additive and unculled, and it DOES write depth — the same composite as the other loader builds.
     *
     * Depth is not an oversight: a hole is a surface. Without it, anything drawn after the rift paints
     * straight over the disc and a column of fluid entering the tear looks stuck to the front of it. Both
     * fragment shaders `discard` transparent fragments, so only the visible mouth writes depth, never the
     * quad it is cut from.
     */
    private fun type(name: String, shader: RenderStateShard.ShaderStateShard): RenderType = net.minecraft.client.renderer.RenderType.create(
        name,
        FORMAT,
        com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS,
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
}
