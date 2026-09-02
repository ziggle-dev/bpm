package bpm.platform

/**
 * A namespaced id, under whichever name this version has for it.
 *
 * Mojang renamed `ResourceLocation` to `Identifier` at 1.21.9. It is the same class doing the same job
 * -- a namespace and a path -- and it is named in about a hundred places across this mod, which makes it
 * the single largest surface the version ladder touches.
 *
 * An ALIAS rather than a build-level find-and-replace, which is what the compatibility plan originally
 * proposed. A textual rewrite of an identifier that common is unchecked by anything: it would happily
 * rename a string literal, a comment, or some unrelated symbol that merely shares the name, and nothing
 * would notice until a jar misbehaved. A typealias is resolved by the compiler, so a place it does not
 * fit is a compile error rather than a silent edit.
 *
 * The cost is that the shared tree imports this rather than the Minecraft class directly. That is the
 * right dependency anyway: the shared tree is not entitled to know which name the current version uses.
 */
//? if >=1.21.9 {
/*typealias ResourceLocation = net.minecraft.resources.Identifier
*///?} else {
typealias ResourceLocation = net.minecraft.resources.ResourceLocation
//?}
