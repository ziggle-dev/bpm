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

/**
 * `ItemPredicate` has moved twice: `advancements.critereon` to `advancements.criterion` at 1.21.9, and
 * on to `advancements.predicates` at 26.1. The type itself is unchanged -- the same record, the same
 * `CODEC`, the same `test` -- so an alias carries all three. What it tests is now spelled `ItemInstance`
 * rather than `ItemStack`, which costs nothing here because `ItemStack` implements it.
 */
//? if >=26.1 {
/*typealias ItemPredicate = net.minecraft.advancements.predicates.ItemPredicate
*///?} elif >=1.21.9 {
/*typealias ItemPredicate = net.minecraft.advancements.criterion.ItemPredicate
*///?} else {
typealias ItemPredicate = net.minecraft.advancements.critereon.ItemPredicate
//?}

/**
 * The id inside a registry key.
 *
 * `ResourceKey.location()` became `identifier()` when the class it returns was renamed -- the accessor
 * followed the type. `TagKey` did NOT follow: it is a record whose component is still called `location`,
 * so it keeps its name on every version. Both are given the same name here so a call site does not have
 * to know which kind of key it is holding.
 */
//? if >=1.21.9 {
/*fun net.minecraft.resources.ResourceKey<*>.keyId(): ResourceLocation = identifier()
*///?} else {
fun net.minecraft.resources.ResourceKey<*>.keyId(): ResourceLocation = location()
//?}

fun net.minecraft.tags.TagKey<*>.keyId(): ResourceLocation = location()

/**
 * Build a namespaced id.
 *
 * 1.21 replaced `ResourceLocation`'s public constructors with static factories -- `fromNamespaceAndPath`
 * and `withDefaultNamespace` -- and made the constructors non-public. On 1.20.1 the factories do not
 * exist and the constructors do, so the seam is a pair of functions rather than an alias: a typealias
 * renames a type, and this is a difference in how one is BUILT.
 *
 * `tryParse` needs no seam; it is a static on both.
 */
//? if >=1.21 {
fun idOf(namespace: String, path: String): ResourceLocation =
    ResourceLocation.fromNamespaceAndPath(namespace, path)

/** An id in the `minecraft` namespace. */
fun vanillaId(path: String): ResourceLocation = ResourceLocation.withDefaultNamespace(path)
//?} else {
/*fun idOf(namespace: String, path: String): ResourceLocation = ResourceLocation(namespace, path)

/** An id in the `minecraft` namespace. The one-argument constructor defaults it. */
fun vanillaId(path: String): ResourceLocation = ResourceLocation(path)
*///?}
