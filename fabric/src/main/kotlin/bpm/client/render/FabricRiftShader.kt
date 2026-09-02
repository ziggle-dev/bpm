package bpm.client.render

import bpm.platform.idOf

//? if >=1.21.5 {
/*import bpm.platform.client.withQuads
import bpm.platform.client.withBlending
import bpm.platform.client.withDepthWriting
import bpm.platform.client.withGlobals
*///?}

import bpm.Bpm
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import bpm.platform.RenderType
import bpm.platform.ResourceLocation

/**
 * The rift's two core shaders on Fabric.
 *
 * The counterpart of `RiftShader` on the other loader, and the same two render types built the same way
 * from the same GLSL — only how the shader is *obtained* differs, and it differs three times over.
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
 *
 * **On 1.21.11 there is nothing to register either, for the same reason one step further on.** A core
 * shader is not a thing any more: a [com.mojang.blaze3d.pipeline.RenderPipeline] is, and it names its own
 * GLSL. NeoForge collects them through `RegisterRenderPipelinesEvent` and Fabric offers no equivalent —
 * because none is needed. The device compiles a pipeline the first time a render pass is set to it and
 * caches it from then on; registration only moves that compile earlier. This mod already depends on that
 * being true on both loaders: the ImGui backend's pipeline is registered nowhere and draws every frame.
 */
object FabricRiftShader : bpm.platform.client.RiftLook {

    /** Declared first: the shader handles below are built from it as the object initialises. */
    val FORMAT: VertexFormat = DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL

    private fun rl(path: String) = idOf(Bpm.ID, path)

    //? if >=1.21.9 {
    /*// The GLSL is a translation, not a redesign: the same maths against std140 uniform blocks instead
    // of loose uniforms. See `src/main/resources-1.21.11/assets/bpm/shaders/core`, which is generated
    // from the 1.21.1 originals so the two cannot drift.
    private fun pipeline(name: String, shader: String): com.mojang.blaze3d.pipeline.RenderPipeline =
        bpm.platform.client.matricesBuilder()
            // These shaders read GameTime; see the note on `withGlobals`.
            .withGlobals()
            .withLocation("pipeline/" + name)
            .withVertexShader(rl(shader))
            .withFragmentShader(rl(shader))
            .withQuads(FORMAT)
            .withBlending(com.mojang.blaze3d.pipeline.BlendFunction.LIGHTNING)
            .withCull(false)
            .withDepthWriting(true)
            .build()

    private val CUBE_PIPELINE = pipeline("bpm_rift_cube", "core/rift_cube")
    private val TEAR_PIPELINE = pipeline("bpm_rift_tear", "core/rift_tear")

    private fun registerShaders() {
        Bpm.LOGGER.info("bpm rift pipelines declared (cube, tear); compiled on first use")
    }

    // A pipeline is a value, so there is nothing to wait for.
    override val ready: Boolean get() = true

    private val CUBE: RenderType = pipelineType("bpm_rift_cube", CUBE_PIPELINE)
    private val TEAR: RenderType = pipelineType("bpm_rift_tear", TEAR_PIPELINE)
    *///?} elif >=1.21.5 {
    /*// Identical to the band above except for how a RenderType is built from a pipeline; see the
    // NeoForge counterpart. Nothing is registered on either band, which on this loader is not a
    // compromise: Fabric has never had a pipeline registration event.
    private fun pipeline(name: String, shader: String): com.mojang.blaze3d.pipeline.RenderPipeline =
        bpm.platform.client.matricesBuilder()
            // These shaders read GameTime; see the note on `withGlobals`.
            .withGlobals()
            .withLocation("pipeline/" + name)
            .withVertexShader(rl(shader))
            .withFragmentShader(rl(shader))
            .withQuads(FORMAT)
            .withBlending(com.mojang.blaze3d.pipeline.BlendFunction.LIGHTNING)
            .withCull(false)
            .withDepthWriting(true)
            .build()

    private val CUBE_PIPELINE = pipeline("bpm_rift_cube", "core/rift_cube")
    private val TEAR_PIPELINE = pipeline("bpm_rift_tear", "core/rift_tear")

    private fun registerShaders() {
        Bpm.LOGGER.info("bpm rift pipelines declared (cube, tear); compiled on first use")
    }

    override val ready: Boolean get() = true

    private val CUBE: RenderType = pipelineType("bpm_rift_cube", CUBE_PIPELINE)
    private val TEAR: RenderType = pipelineType("bpm_rift_tear", TEAR_PIPELINE)
    *///?} elif >=1.21.2 {
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

    private val CUBE: RenderType = type("bpm_rift_cube", net.minecraft.client.renderer.RenderStateShard.ShaderStateShard(CUBE_PROGRAM))
    private val TEAR: RenderType = type("bpm_rift_tear", net.minecraft.client.renderer.RenderStateShard.ShaderStateShard(TEAR_PROGRAM))
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

    private val CUBE: RenderType = type("bpm_rift_cube", net.minecraft.client.renderer.RenderStateShard.ShaderStateShard { cube })
    private val TEAR: RenderType = type("bpm_rift_tear", net.minecraft.client.renderer.RenderStateShard.ShaderStateShard { tear })
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
    //? if >=1.21.9 {
    /*// Everything the composite state below says is now a property of the pipeline itself: the blend, the
    // cull, the write mask and the vertex format. The only thing left outside it is the buffer size,
    // which is a hint about batching rather than a piece of GPU state.
    private fun pipelineType(name: String, pipeline: com.mojang.blaze3d.pipeline.RenderPipeline): RenderType =
        net.minecraft.client.renderer.rendertype.RenderType.create(
            name,
            bpm.platform.client.quadRenderSetup(pipeline),
        )
    *///?} elif >=1.21.5 {
    /*private fun pipelineType(name: String, pipeline: com.mojang.blaze3d.pipeline.RenderPipeline): RenderType =
        net.minecraft.client.renderer.RenderType.create(
            name,
            256,
            false,
            false,
            pipeline,
            net.minecraft.client.renderer.RenderType.CompositeState.builder().createCompositeState(false),
        )
    *///?} else {
    private fun type(name: String, shader: net.minecraft.client.renderer.RenderStateShard.ShaderStateShard): RenderType = net.minecraft.client.renderer.RenderType.create(
        name,
        FORMAT,
        com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS,
        256,
        false,
        false,
        net.minecraft.client.renderer.RenderType.CompositeState.builder()
            .setShaderState(shader)
            .setTextureState(net.minecraft.client.renderer.RenderStateShard.NO_TEXTURE)
            .setTransparencyState(net.minecraft.client.renderer.RenderStateShard.ADDITIVE_TRANSPARENCY)
            .setCullState(net.minecraft.client.renderer.RenderStateShard.NO_CULL)
            .setWriteMaskState(net.minecraft.client.renderer.RenderStateShard.COLOR_DEPTH_WRITE)
            .createCompositeState(false),
    )
    //?}
}
