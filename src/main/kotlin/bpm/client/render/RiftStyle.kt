package bpm.client.render

/**
 * Which of the two rift looks a transfer draws: a cube for a linked block, a tear for a person.
 *
 * A decision about the effect, not about how it is drawn, so it belongs on the shared side. What draws
 * it is `RiftShader`, which is NeoForge-only today because it registers a core shader through
 * `RegisterShadersEvent` — an event Fabric loses at 1.21.2 and both loaders lose at 1.21.5. When that is
 * re-expressed as a `RenderPipeline` with inline GLSL, this enum will not change.
 */
enum class RiftStyle { CUBE, TEAR }
