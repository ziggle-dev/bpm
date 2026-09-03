package bpm.platform

import net.minecraft.core.Direction
import net.minecraft.core.Vec3i
import net.minecraft.world.level.block.RenderShape
import bpm.platform.keyId

/**
 * Small version differences that the shared tree cannot express, given as values and helpers.
 *
 * The shared tree is compiled by both loaders and is therefore NOT processed for `//? if` directives —
 * a branch that wanted the other answer would get this one. So anything that differs by Minecraft
 * version has to be named here, in a branch's own source, and called from there.
 *
 * This works for values, and for functions whose shape stays the same. It does NOT work for an override
 * whose parameter list changed arity: a shared file can only declare one signature. Those are the
 * genuinely expensive ones, and there are a handful in `Traps.kt` and `QuantumWardenEntity.kt`.
 */

/**
 * "This block is drawn by its block entity, not by a baked model."
 *
 * `ENTITYBLOCK_ANIMATED` went away at 1.21.2 along with `BlockEntityWithoutLevelRenderer`; the surviving
 * spelling is `MODEL`, and the block-entity renderer draws over it. Substituting one for the other
 * blindly would change what 1.21.1 draws, which is why this is a switched value and not a rename.
 */
//? if >=1.21.2 {
/*val ANIMATED_BLOCK_SHAPE: RenderShape = RenderShape.MODEL

/** `Direction.normal` became private; `getUnitVec3i` is the accessor that replaced it. */
fun unitVector(direction: Direction): Vec3i = direction.unitVec3i

/**
 * The fifth parameter of `Block.neighborChanged`.
 *
 * It was the position the change came from; at 1.21.2 it became an `Orientation`, which carries the
 * direction as well. The arity did not change, only the type — which is why an alias is enough, and it is
 * enough only because every override of it in this mod passes the value straight to `super` and never
 * asks what it is.
 *
 * If one ever needs to READ it, that override moves to a branch. Aliasing a type you do not touch is
 * honest; aliasing one you do would be pretending two different things are the same.
 */
typealias NeighborSource = net.minecraft.world.level.redstone.Orientation?

/**
 * Looking one entry up in a dynamic registry.
 *
 * `RegistryAccess.registryOrThrow` became `lookupOrThrow` at 1.21.2, and the thing it returns changed with
 * it — a `Registry` before, a `HolderLookup` after — so the two call chains do not merely differ in
 * spelling. Both still answer the same question, which is what these take.
 */
fun <T : Any> holderOrNull(
    access: net.minecraft.core.RegistryAccess,
    registry: net.minecraft.resources.ResourceKey<net.minecraft.core.Registry<T>>,
    entry: net.minecraft.resources.ResourceKey<T>,
): net.minecraft.core.Holder<T>? = access.lookupOrThrow(registry).get(entry).orElse(null)

fun <T : Any> holderOrThrow(
    access: net.minecraft.core.RegistryAccess,
    registry: net.minecraft.resources.ResourceKey<net.minecraft.core.Registry<T>>,
    entry: net.minecraft.resources.ResourceKey<T>,
): net.minecraft.core.Holder<T> = access.lookupOrThrow(registry).getOrThrow(entry)

/** `NativeImage.getPixelRGBA` became `getPixel`; same packed ABGR either way. */
fun pixel(image: com.mojang.blaze3d.platform.NativeImage, x: Int, y: Int): Int = image.getPixel(x, y)

/**
 * One value out of a registry, by id, or null.
 *
 * `Registry.get(ResourceLocation)` used to return the value; from 1.21.2 it returns an
 * `Optional<Holder.Reference<T>>` and the plain value moved to `getValue`. The `containsKey` guard is
 * not redundant on either: these are DEFAULTED registries, so an unknown item id answers AIR rather
 * than nothing, and the scripting language has to be able to tell "minecraft:air" from a typo.
 */
fun <T : Any> valueOf(registry: net.minecraft.core.Registry<T>, id: bpm.platform.ResourceLocation): T? =
    if (registry.containsKey(id)) registry.getValue(id) else null

/** `Registry.asLookup()` went away when `Registry` itself became a `HolderLookup.RegistryLookup`. */
fun blockLookup(): net.minecraft.core.HolderGetter<net.minecraft.world.level.block.Block> =
    net.minecraft.core.registries.BuiltInRegistries.BLOCK

/** `EntityType.Builder.build` takes the registry key rather than a bare name. */
fun <T : net.minecraft.world.entity.Entity> entityType(
    builder: net.minecraft.world.entity.EntityType.Builder<T>,
    id: bpm.platform.ResourceLocation,
): net.minecraft.world.entity.EntityType<T> =
    builder.build(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENTITY_TYPE, id))

/** `Direction.getNearest(double, double, double)` is `getApproximateNearest`; `getNearest` now needs a fallback. */
fun nearestDirection(x: Double, y: Double, z: Double): Direction = Direction.getApproximateNearest(x, y, z)

/**
 * Item cooldowns, which became per-stack.
 *
 * 1.21.2 introduced cooldown groups: a cooldown is keyed by a stack's `USE_COOLDOWN` component if it
 * has one and by its item otherwise, so the API takes the stack. Passing the stack on both sides means
 * the call site never has to know which, and a cooldown group would be honoured the moment one is set.
 */
fun addCooldown(player: net.minecraft.world.entity.player.Player, stack: net.minecraft.world.item.ItemStack, ticks: Int) =
    player.cooldowns.addCooldown(stack, ticks)

fun onCooldown(player: net.minecraft.world.entity.player.Player, stack: net.minecraft.world.item.ItemStack): Boolean =
    player.cooldowns.isOnCooldown(stack)

/** `teleportTo` gained a relative-movement set and a "set camera" flag; this is the old behaviour, spelled anew. */
fun teleport(
    player: net.minecraft.server.level.ServerPlayer,
    level: net.minecraft.server.level.ServerLevel,
    x: Double, y: Double, z: Double, yaw: Float, pitch: Float,
) {
    player.teleportTo(level, x, y, z, java.util.Set.of(), yaw, pitch, true)
}

/** `Registry.getTags()` stopped pairing each tag with its key -- the key is on the `HolderSet.Named` now. */
fun <T : Any> tagIds(registry: net.minecraft.core.Registry<T>): List<String> =
    registry.tags.map { it.key().keyId().toString() }.toList()

/** `BlockState.isSolidRender` stopped asking where it was -- it is a property of the state alone now. */
fun solidRender(
    state: net.minecraft.world.level.block.state.BlockState,
    level: net.minecraft.world.level.BlockGetter,
    pos: net.minecraft.core.BlockPos,
): Boolean = state.isSolidRender

/** Redstone dust particles take a packed RGB int rather than a `Vector3f`. */
fun dust(colour: Int, scale: Float): net.minecraft.core.particles.DustParticleOptions =
    net.minecraft.core.particles.DustParticleOptions(colour and 0xFFFFFF, scale)

/**
 * Finding a recipe, on a level that no longer owns the recipe book.
 *
 * `Level.recipeManager` became `Level.recipeAccess()`, and a recipe's identity went from a
 * `ResourceLocation` to a `ResourceKey<Recipe<?>>`. The id is PERSISTED by the assembler -- it survives
 * a chunk unload mid-job -- so the saved form stays a plain `ResourceLocation` on both, and the key is
 * built here at the moment of the lookup rather than being written to disk in a shape that changed.
 */
fun <I : net.minecraft.world.item.crafting.RecipeInput, T : net.minecraft.world.item.crafting.Recipe<I>> findRecipe(
    level: net.minecraft.world.level.Level,
    type: net.minecraft.world.item.crafting.RecipeType<T>,
    input: I,
): net.minecraft.world.item.crafting.RecipeHolder<T>? =
    (level as? net.minecraft.server.level.ServerLevel)?.recipeAccess()?.getRecipeFor(type, input, level)?.orElse(null)

fun recipeById(
    level: net.minecraft.world.level.Level,
    id: bpm.platform.ResourceLocation,
): net.minecraft.world.item.crafting.RecipeHolder<*>? =
    (level as? net.minecraft.server.level.ServerLevel)?.recipeAccess()
        ?.byKey(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.RECIPE, id))
        ?.orElse(null)

fun recipeIdOf(holder: net.minecraft.world.item.crafting.RecipeHolder<*>): bpm.platform.ResourceLocation =
    holder.id().keyId()

/**
 * The codec for one ingredient that must not be empty.
 *
 * `Ingredient.CODEC_NONEMPTY` and `Ingredient.CODEC` were two codecs until 1.21.2, differing only in
 * whether an empty holder set was accepted. The pair collapsed: `CODEC` is now the non-empty one and
 * the other name is gone, so this is the same guarantee under whichever name the band has for it.
 */
val INGREDIENT_CODEC: com.mojang.serialization.Codec<net.minecraft.world.item.crafting.Ingredient> =
    net.minecraft.world.item.crafting.Ingredient.CODEC
*///?} else {
val ANIMATED_BLOCK_SHAPE: RenderShape = RenderShape.ENTITYBLOCK_ANIMATED

fun unitVector(direction: Direction): Vec3i = direction.normal

typealias NeighborSource = net.minecraft.core.BlockPos

fun <T : Any> holderOrNull(
    access: net.minecraft.core.RegistryAccess,
    registry: net.minecraft.resources.ResourceKey<net.minecraft.core.Registry<T>>,
    entry: net.minecraft.resources.ResourceKey<T>,
): net.minecraft.core.Holder<T>? = access.registryOrThrow(registry).getHolder(entry).orElse(null)

fun <T : Any> holderOrThrow(
    access: net.minecraft.core.RegistryAccess,
    registry: net.minecraft.resources.ResourceKey<net.minecraft.core.Registry<T>>,
    entry: net.minecraft.resources.ResourceKey<T>,
): net.minecraft.core.Holder<T> = access.registryOrThrow(registry).getHolderOrThrow(entry)

fun pixel(image: com.mojang.blaze3d.platform.NativeImage, x: Int, y: Int): Int = image.getPixelRGBA(x, y)

fun <T : Any> valueOf(registry: net.minecraft.core.Registry<T>, id: bpm.platform.ResourceLocation): T? =
    if (registry.containsKey(id)) registry.get(id) else null

fun blockLookup(): net.minecraft.core.HolderGetter<net.minecraft.world.level.block.Block> =
    net.minecraft.core.registries.BuiltInRegistries.BLOCK.asLookup()

fun <T : net.minecraft.world.entity.Entity> entityType(
    builder: net.minecraft.world.entity.EntityType.Builder<T>,
    id: bpm.platform.ResourceLocation,
): net.minecraft.world.entity.EntityType<T> = builder.build(id.path)

fun nearestDirection(x: Double, y: Double, z: Double): Direction = Direction.getNearest(x, y, z)

fun addCooldown(player: net.minecraft.world.entity.player.Player, stack: net.minecraft.world.item.ItemStack, ticks: Int) =
    player.cooldowns.addCooldown(stack.item, ticks)

fun onCooldown(player: net.minecraft.world.entity.player.Player, stack: net.minecraft.world.item.ItemStack): Boolean =
    player.cooldowns.isOnCooldown(stack.item)

fun teleport(
    player: net.minecraft.server.level.ServerPlayer,
    level: net.minecraft.server.level.ServerLevel,
    x: Double, y: Double, z: Double, yaw: Float, pitch: Float,
) {
    player.teleportTo(level, x, y, z, yaw, pitch)
}

fun <T : Any> tagIds(registry: net.minecraft.core.Registry<T>): List<String> =
    registry.tags.map { it.first.keyId().toString() }.toList()

fun solidRender(
    state: net.minecraft.world.level.block.state.BlockState,
    level: net.minecraft.world.level.BlockGetter,
    pos: net.minecraft.core.BlockPos,
): Boolean = state.isSolidRender(level, pos)

fun dust(colour: Int, scale: Float): net.minecraft.core.particles.DustParticleOptions =
    net.minecraft.core.particles.DustParticleOptions(
        org.joml.Vector3f(((colour shr 16) and 0xFF) / 255f, ((colour shr 8) and 0xFF) / 255f, (colour and 0xFF) / 255f),
        scale,
    )

/*
 * A recipe lookup, and what it hands back.
 *
 * From 1.20.5 the manager returns a `RecipeHolder` -- the recipe paired with its id -- and takes a
 * `RecipeInput`. Below it the manager returns the RECIPE, which carries its own id, and takes a
 * `Container`. [bpm.platform.RecipeEntry] is whichever of those two this band means.
 */
//? if >=1.20.5 {
fun <I : net.minecraft.world.item.crafting.RecipeInput, T : net.minecraft.world.item.crafting.Recipe<I>> findRecipe(
    level: net.minecraft.world.level.Level,
    type: net.minecraft.world.item.crafting.RecipeType<T>,
    input: I,
): bpm.platform.RecipeEntry<T>? =
    level.recipeManager.getRecipeFor(type, input, level).orElse(null)

fun recipeById(
    level: net.minecraft.world.level.Level,
    id: bpm.platform.ResourceLocation,
): bpm.platform.RecipeEntry<*>? = level.recipeManager.byKey(id).orElse(null)

fun recipeIdOf(entry: bpm.platform.RecipeEntry<*>): bpm.platform.ResourceLocation = entry.id()
//?} else {
/*fun <I : net.minecraft.world.Container, T : net.minecraft.world.item.crafting.Recipe<I>> findRecipe(
    level: net.minecraft.world.level.Level,
    type: net.minecraft.world.item.crafting.RecipeType<T>,
    input: I,
): bpm.platform.RecipeEntry<T>? =
    level.recipeManager.getRecipeFor(type, input, level).map { bpm.platform.RecipeEntry(it) }.orElse(null)

fun recipeById(
    level: net.minecraft.world.level.Level,
    id: bpm.platform.ResourceLocation,
): bpm.platform.RecipeEntry<*>? = level.recipeManager.byKey(id).map { bpm.platform.RecipeEntry(it) }.orElse(null)

/** The recipe carries its own id on this band, which is where getId() went at 1.20.5. */
fun recipeIdOf(entry: bpm.platform.RecipeEntry<*>): bpm.platform.ResourceLocation = entry.id()
*///?}

/**
 * The codec for one ingredient that must not be empty.
 *
 * `Ingredient.CODEC_NONEMPTY` and `Ingredient.CODEC` were two codecs until 1.21.2, differing only in
 * whether an empty holder set was accepted -- and below 1.20.5 there is no ingredient codec at all.
 */
//? if >=1.20.5 {
val INGREDIENT_CODEC: com.mojang.serialization.Codec<net.minecraft.world.item.crafting.Ingredient> =
    net.minecraft.world.item.crafting.Ingredient.CODEC_NONEMPTY
//?} else {
/*// An ingredient is read by a hand-written `fromJson` and written by `toJson` here, which is what the
// recipe serializer itself uses. The recipe's own codec still asks for a codec, so this is that pair
// wrapped as one: PASSTHROUGH takes whatever ops the caller brought and both directions convert to
// JSON, which is the only shape `fromJson` knows. The serializer only ever drives it with JsonOps (a
// datapack) or NbtOps (the network), and the ingredient's JSON shape survives that trip.
val INGREDIENT_CODEC: com.mojang.serialization.Codec<net.minecraft.world.item.crafting.Ingredient> =
    com.mojang.serialization.Codec.PASSTHROUGH.flatXmap(
        { dynamic ->
            runCatching {
                net.minecraft.world.item.crafting.Ingredient.fromJson(
                    dynamic.convert(com.mojang.serialization.JsonOps.INSTANCE).value as com.google.gson.JsonElement,
                )
            }.fold(
                { com.mojang.serialization.DataResult.success(it) },
                { failure -> com.mojang.serialization.DataResult.error { "not an ingredient: ${failure.message}" } },
            )
        },
        { ingredient ->
            com.mojang.serialization.DataResult.success(
                com.mojang.serialization.Dynamic(com.mojang.serialization.JsonOps.INSTANCE, ingredient.toJson()),
            )
        },
    )

// Slowness, and an instance of it, which took the effect itself before it was holder-wrapped.
fun slowness(duration: Int, amplifier: Int): net.minecraft.world.effect.MobEffectInstance =
    net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN, duration, amplifier)
*///?}

//?}

/**
 * A block-entity type, built the way this loader has always built one.
 *
 * NOT version-switched, unlike its NeoForge counterpart, and the reason is worth recording. Vanilla
 * deleted `BlockEntityType.Builder` at 1.21.4 and left the constructor it hid PRIVATE; NeoForge widens
 * it, so that branch simply calls it. Fabric does not widen it -- it ships a builder of its own instead,
 * and that builder has the same shape on both versions.
 *
 * So what is a version difference on one loader is a plain loader difference on the other, answered
 * once. That is the two axes doing their job: a thing only becomes version-shaped where the version
 * actually changed it.
 */
fun <T : net.minecraft.world.level.block.entity.BlockEntity> blockEntityType(
    factory: (net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState) -> T,
    block: net.minecraft.world.level.block.Block,
): net.minecraft.world.level.block.entity.BlockEntityType<T> =
    net.fabricmc.fabric.api.`object`.builder.v1.block.entity.FabricBlockEntityTypeBuilder
        .create({ pos, state -> factory(pos, state) }, block)
        .build()

/**
 * The server, and the level, reached from a player.
 *
 * Both accessors moved at 1.21.9 and in opposite directions. `ServerPlayer.server` became private, so
 * the route is now through the level it is in -- which is fine, because `ServerLevel.getServer()` is
 * public on every version. And `serverLevel()` went away because it no longer had anything to do:
 * `ServerPlayer.level()` is covariantly typed to return a `ServerLevel` directly, where before it
 * returned the general `Level` and needed a second, narrower accessor beside it.
 */
//? if >=1.21.6 {
/*fun serverOf(player: net.minecraft.server.level.ServerPlayer): net.minecraft.server.MinecraftServer =
    player.level().server

fun levelOf(player: net.minecraft.server.level.ServerPlayer): net.minecraft.server.level.ServerLevel =
    player.level()
*///?} else {
fun serverOf(player: net.minecraft.server.level.ServerPlayer): net.minecraft.server.MinecraftServer =
    player.server

fun levelOf(player: net.minecraft.server.level.ServerPlayer): net.minecraft.server.level.ServerLevel =
    player.serverLevel()
//?}

/**
 * Where a level says players appear.
 *
 * `Level.getSharedSpawnPos()` is gone at 1.21.9: a level now carries a `RespawnData` record -- position,
 * yaw and pitch together, and the dimension it belongs to -- and the position is one field of it. This
 * mod only ever wanted the block, which is the same block on either band.
 */
fun spawnPosOf(level: net.minecraft.world.level.Level): net.minecraft.core.BlockPos =
    //? if >=1.21.9 {
    /*level.respawnData.pos()
    *///?} else {
    level.sharedSpawnPos
    //?}

/**
 * Whether this may run the mod's operator commands.
 *
 * Numeric permission levels are gone at 1.21.9: a source now carries a `PermissionSet` and is asked
 * about a named `Permission`. Level 2 -- the one every check in this mod used -- is
 * `Permissions.COMMANDS_GAMEMASTER`, so the two spellings mean the same thing and the caller can stop
 * naming a number it never chose for its own reasons.
 */
fun mayAdminister(source: net.minecraft.commands.CommandSourceStack): Boolean =
    //? if >=1.21.9 {
    /*source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)
    *///?} else {
    source.hasPermission(2)
    //?}

/** The same question of a player. */
fun mayAdminister(player: net.minecraft.server.level.ServerPlayer): Boolean =
    //? if >=1.21.9 {
    /*player.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)
    *///?} else {
    player.hasPermissions(2)
    //?}

/**
 * A bare `namespace:path` command argument.
 *
 * Renamed with the type it produces: `ResourceLocationArgument` became `IdentifierArgument`, and its two
 * static methods came with it.
 */
fun idArgument(): com.mojang.brigadier.arguments.ArgumentType<ResourceLocation> =
    //? if >=1.21.9 {
    /*net.minecraft.commands.arguments.IdentifierArgument.id()
    *///?} else {
    net.minecraft.commands.arguments.ResourceLocationArgument.id()
    //?}

/** The argument [name] of [context], as an id. */
fun idArgumentOf(context: com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack>, name: String): ResourceLocation =
    //? if >=1.21.9 {
    /*net.minecraft.commands.arguments.IdentifierArgument.getId(context, name)
    *///?} else {
    net.minecraft.commands.arguments.ResourceLocationArgument.getId(context, name)
    //?}

/**
 * Whether water boils off here.
 *
 * `DimensionType.ultraWarm()` is gone at 1.21.9: what it decided moved into the environment attribute
 * map as `WATER_EVAPORATES`, which is a better place for it -- the flag was always about a dimension's
 * weather rather than its shape, and it can now vary within one.
 */
fun waterEvaporatesIn(level: net.minecraft.world.level.Level, pos: net.minecraft.core.BlockPos): Boolean =
    //? if >=1.21.9 {
    /*level.environmentAttributes().getValue(net.minecraft.world.attribute.EnvironmentAttributes.WATER_EVAPORATES, pos)
    *///?} else {
    level.dimensionType().ultraWarm()
    //?}

/** Whether it is daytime in [level]. `isDay` became `isBrightOutside`. */
fun isDaytime(level: net.minecraft.world.level.Level): Boolean =
    //? if >=1.21.5 {
    /*level.isBrightOutside
    *///?} else {
    level.isDay
    //?}

/** Which hotbar slot the player has selected. The field behind it went private behind an accessor. */
fun selectedSlot(inventory: net.minecraft.world.entity.player.Inventory): Int =
    //? if >=1.21.5 {
    /*inventory.selectedSlot
    *///?} else {
    inventory.selected
    //?}

/**
 * The slowness effect.
 *
 * `MobEffects.MOVEMENT_SLOWDOWN` was renamed to `SLOWNESS` -- the name players and the wiki have always
 * used -- at 1.21.9. Nothing else about it changed.
 */
//? if >=1.20.5 {
val SLOWNESS: net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect>
    //? if >=1.21.5 {
    /*get() = net.minecraft.world.effect.MobEffects.SLOWNESS
    *///?} else {
    get() = net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN
    //?}

/** Slowness, and an instance of it. */
fun slowness(duration: Int, amplifier: Int): net.minecraft.world.effect.MobEffectInstance =
    net.minecraft.world.effect.MobEffectInstance(SLOWNESS, duration, amplifier)
//?}

/**
 * A command source with full permission, for the dev harness to run commands through.
 *
 * `CommandSourceStack` took an int permission level until 1.21.9 and takes a `PermissionSet` after it,
 * so the whole construction is switched rather than one argument. `ALL_PERMISSIONS` is what level 4 meant.
 */
fun fullPermissionSource(
    source: net.minecraft.commands.CommandSource,
    level: net.minecraft.server.level.ServerLevel,
    at: net.minecraft.world.phys.Vec3,
    name: String,
    server: net.minecraft.server.MinecraftServer,
): net.minecraft.commands.CommandSourceStack =
    //? if >=1.21.9 {
    /*net.minecraft.commands.CommandSourceStack(
        source, at, net.minecraft.world.phys.Vec2.ZERO, level,
        net.minecraft.server.permissions.PermissionSet.ALL_PERMISSIONS,
        name, net.minecraft.network.chat.Component.literal(name), server, null,
    )
    *///?} else {
    net.minecraft.commands.CommandSourceStack(
        source, at, net.minecraft.world.phys.Vec2.ZERO, level, 4,
        name, net.minecraft.network.chat.Component.literal(name), server, null,
    )
    //?}

/**
 * A stack from a parsed `/give`-style item argument.
 *
 * `ItemInput.createItemStack` dropped its "allow oversized" flag at 26.1. This mod always passed false,
 * so the flag going away changes nothing -- but the arity does, and a shared file cannot say both.
 */
fun itemStackOf(
    input: net.minecraft.commands.arguments.item.ItemInput,
    count: Int,
): net.minecraft.world.item.ItemStack =
    //? if >=26.1 {
    /*input.createItemStack(count)
    *///?} else {
    input.createItemStack(count, false)
    //?}

/**
 * The time of day in this dimension, in ticks.
 *
 * `Level.dayTime` became a world CLOCK at 26.1: a dimension names one in its type, and
 * `getDefaultClockTime()` reads that dimension's own -- which is what `dayTime` always was.
 */
fun dayTime(level: net.minecraft.world.level.Level): Long =
    //? if >=26.1 {
    /*level.getDefaultClockTime()
    *///?} else {
    level.dayTime
    //?}

/**
 * A block state's properties, as names and value names.
 *
 * `getValues()` returned a `Map<Property, Comparable>` until 26.1 and returns a
 * `Stream<Property.Value>` now -- a record that knows how to name its own value, which is the only
 * thing this was ever doing with the pair.
 */
fun blockStateProperties(state: net.minecraft.world.level.block.state.BlockState): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    //? if >=26.1 {
    /*state.values.forEach { out[it.property().name] = it.valueName() }
    *///?} else {
    for ((p, v) in state.values) {
        @Suppress("UNCHECKED_CAST")
        val prop = p as net.minecraft.world.level.block.state.properties.Property<Comparable<Any>>
        @Suppress("UNCHECKED_CAST")
        out[prop.name] = prop.getName(v as Comparable<Any>)
    }
    //?}
    return out
}

/** `interactOn` gained the point on the entity that was clicked; the mod has no cursor, so its middle. */
fun interactWith(
    player: net.minecraft.world.entity.player.Player,
    target: net.minecraft.world.entity.Entity,
    hand: net.minecraft.world.InteractionHand,
): net.minecraft.world.InteractionResult =
    //? if >=26.1 {
    /*player.interactOn(target, hand, target.position())
    *///?} else {
    player.interactOn(target, hand)
    //?}

/** The impulse flag gained the position the impulse came from, which for a linker pulse is the target. */
fun ignoreImpulseFallDamage(player: net.minecraft.world.entity.player.Player, at: net.minecraft.world.phys.Vec3) {
    //? if >=26.1 {
    /*player.setIgnoreFallDamageFromCurrentImpulse(true, at)
    *///?} elif >=1.20.5 {
    player.setIgnoreFallDamageFromCurrentImpulse(true)
    //?} else {
    /*// The flag arrived with the wind charge that needed it; there is nothing to set on this band, and
    // nothing that sets it either -- a linker pulse simply does not suppress fall damage here.
    *///?}
}

/**
 * A boss bar for one boss.
 *
 * 26.1 gave `ServerBossEvent` an explicit id where it used to make its own. Derived from the boss's own
 * uuid rather than randomised, so the bar keeps its identity if the fight is rebuilt -- a random one
 * would show a client a second, empty bar beside the first.
 */
fun bossBar(
    id: java.util.UUID,
    // Nullable because vanilla's own parameter is, and this only forwards: an entity's display name is a
    // platform type, and tightening it here would be this seam inventing a constraint the game does not have.
    name: net.minecraft.network.chat.Component?,
    colour: net.minecraft.world.BossEvent.BossBarColor,
    overlay: net.minecraft.world.BossEvent.BossBarOverlay,
): net.minecraft.server.level.ServerBossEvent =
    // A nameless bar rather than a crash: the name comes in as a platform type on one band and a
    // non-null one on the next, and a boss with no display name should still get a bar.
    net.minecraft.network.chat.Component.empty().let { blank ->
        //? if >=26.1 {
        /*net.minecraft.server.level.ServerBossEvent(id, name ?: blank, colour, overlay)
        *///?} else {
        net.minecraft.server.level.ServerBossEvent(name ?: blank, colour, overlay)
        //?}
    }

/**
 * A message to one player -- across the middle of the screen above the hotbar, or in chat.
 *
 * 26.1 SPLIT `displayClientMessage(component, actionBar)` rather than renaming it. The boolean became the
 * choice of method: `sendOverlayMessage` is the action bar, `sendSystemMessage` is chat. An earlier note
 * here claimed it was a two-argument `sendSystemMessage`, which does not exist -- so the branch below is
 * on the argument, not on the name.
 *
 * This is an EXTENSION rather than a function taking the player, and that is deliberate: it makes every
 * call site a one-token rename off the vanilla method instead of a rewrite that moves the receiver into
 * the argument list. Receivers here are not all simple names -- `event.player`, `(owner as? Player)?` --
 * and a mechanical rewrite of those is exactly the kind that mangles a file silently.
 */
fun net.minecraft.world.entity.player.Player.showMessage(
    message: net.minecraft.network.chat.Component,
    actionBar: Boolean = true,
) {
    //? if >=26.1 {
    /*if (actionBar) sendOverlayMessage(message) else sendSystemMessage(message)
    *///?} else {
    displayClientMessage(message, actionBar)
    //?}
}

/**
 * Another block's properties, to build on.
 *
 * `Properties.copy` was renamed `ofFullCopy` at 1.20.5, when a second, shallower `ofLegacyCopy` appeared
 * beside it. This is the full one, which is what `copy` always was.
 */
//? if >=1.20.5 {
fun copyProperties(block: net.minecraft.world.level.block.Block): net.minecraft.world.level.block.state.BlockBehaviour.Properties =
    net.minecraft.world.level.block.state.BlockBehaviour.Properties.ofFullCopy(block)
//?} else {
/*fun copyProperties(block: net.minecraft.world.level.block.Block): net.minecraft.world.level.block.state.BlockBehaviour.Properties =
    net.minecraft.world.level.block.state.BlockBehaviour.Properties.copy(block)
*///?}

/** An ore that drops experience. The two arguments swapped places at 1.20.5. */
//? if >=1.20.5 {
fun dropExperienceBlock(
    xp: net.minecraft.util.valueproviders.IntProvider,
    properties: net.minecraft.world.level.block.state.BlockBehaviour.Properties,
): net.minecraft.world.level.block.DropExperienceBlock =
    net.minecraft.world.level.block.DropExperienceBlock(xp, properties)
//?} else {
/*fun dropExperienceBlock(
    xp: net.minecraft.util.valueproviders.IntProvider,
    properties: net.minecraft.world.level.block.state.BlockBehaviour.Properties,
): net.minecraft.world.level.block.DropExperienceBlock =
    net.minecraft.world.level.block.DropExperienceBlock(properties, xp)
*///?}

/**
 * Putting a fluid into a block that holds one, and taking it back out.
 *
 * Both gained a leading nullable `Player` at 1.20.5 -- so a waterloggable block can tell who is doing it
 * and play the sound at them. Nothing in this mod passes one, so both arms take none.
 */
//? if >=1.20.5 {
fun canPlaceLiquid(
    container: net.minecraft.world.level.block.LiquidBlockContainer,
    level: net.minecraft.world.level.LevelAccessor,
    pos: net.minecraft.core.BlockPos,
    state: net.minecraft.world.level.block.state.BlockState,
    fluid: net.minecraft.world.level.material.Fluid,
): Boolean = container.canPlaceLiquid(null, level, pos, state, fluid)

fun pickupBlock(
    source: net.minecraft.world.level.block.BucketPickup,
    level: net.minecraft.world.level.LevelAccessor,
    pos: net.minecraft.core.BlockPos,
    state: net.minecraft.world.level.block.state.BlockState,
): net.minecraft.world.item.ItemStack = source.pickupBlock(null, level, pos, state)
//?} else {
/*fun canPlaceLiquid(
    container: net.minecraft.world.level.block.LiquidBlockContainer,
    level: net.minecraft.world.level.LevelAccessor,
    pos: net.minecraft.core.BlockPos,
    state: net.minecraft.world.level.block.state.BlockState,
    fluid: net.minecraft.world.level.material.Fluid,
): Boolean = container.canPlaceLiquid(level as net.minecraft.world.level.BlockGetter, pos, state, fluid)

fun pickupBlock(
    source: net.minecraft.world.level.block.BucketPickup,
    level: net.minecraft.world.level.LevelAccessor,
    pos: net.minecraft.core.BlockPos,
    state: net.minecraft.world.level.block.state.BlockState,
): net.minecraft.world.item.ItemStack = source.pickupBlock(level, pos, state)
*///?}

/**
 * The gust of a linker pulse landing, and the sound of it.
 *
 * Both are wind-charge assets that arrived at 1.20.5. Before them the nearest honest pair is a puff of
 * cloud and a wing-beat -- the effect reads as a gust rather than as nothing.
 */
//? if >=1.20.5 {
fun gustParticle(): net.minecraft.core.particles.SimpleParticleType = net.minecraft.core.particles.ParticleTypes.GUST

fun windBurstSound(): net.minecraft.sounds.SoundEvent = net.minecraft.sounds.SoundEvents.WIND_CHARGE_BURST.value()
//?} else {
/*fun gustParticle(): net.minecraft.core.particles.SimpleParticleType = net.minecraft.core.particles.ParticleTypes.CLOUD

fun windBurstSound(): net.minecraft.sounds.SoundEvent = net.minecraft.sounds.SoundEvents.ENDER_DRAGON_FLAP
*///?}

/**
 * Apply a stack's main-hand attribute modifiers to [player], and take them off again.
 *
 * Three things changed at 1.20.5 and none of them changed what this does. An attribute became a
 * `Holder<Attribute>`; the modifiers stopped being a `Multimap` and are walked with `forEachModifier`;
 * and a modifier is keyed by a `ResourceLocation` rather than a UUID, which is why removing one is
 * `removeModifier(id)` there and `removeModifier(uuid)` here.
 *
 * The token handed back is opaque on purpose: the caller only ever passes it straight back, and its
 * element type is one of the things that differs.
 */
//? if >=1.20.5 {
fun armWithModifiers(player: net.minecraft.server.level.ServerPlayer, stack: net.minecraft.world.item.ItemStack): Any {
    val applied = ArrayList<Pair<net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute>, net.minecraft.world.entity.ai.attributes.AttributeModifier>>()
    stack.forEachModifier(net.minecraft.world.entity.EquipmentSlot.MAINHAND) { attribute, modifier ->
        val instance = player.attributes.getInstance(attribute) ?: return@forEachModifier
        instance.addOrUpdateTransientModifier(modifier)
        applied += attribute to modifier
    }
    return applied
}

@Suppress("UNCHECKED_CAST")
fun disarmModifiers(player: net.minecraft.server.level.ServerPlayer, token: Any) {
    val applied = token as List<Pair<net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute>, net.minecraft.world.entity.ai.attributes.AttributeModifier>>
    for ((attribute, modifier) in applied) player.attributes.getInstance(attribute)?.removeModifier(modifier.id())
}
//?} else {
/*fun armWithModifiers(player: net.minecraft.server.level.ServerPlayer, stack: net.minecraft.world.item.ItemStack): Any {
    val applied = ArrayList<Pair<net.minecraft.world.entity.ai.attributes.Attribute, net.minecraft.world.entity.ai.attributes.AttributeModifier>>()
    for (entry in stack.getAttributeModifiers(net.minecraft.world.entity.EquipmentSlot.MAINHAND).entries()) {
        val instance = player.attributes.getInstance(entry.key) ?: continue
        instance.addTransientModifier(entry.value)
        applied += entry.key to entry.value
    }
    return applied
}

@Suppress("UNCHECKED_CAST")
fun disarmModifiers(player: net.minecraft.server.level.ServerPlayer, token: Any) {
    val applied = token as List<Pair<net.minecraft.world.entity.ai.attributes.Attribute, net.minecraft.world.entity.ai.attributes.AttributeModifier>>
    for ((attribute, modifier) in applied) player.attributes.getInstance(attribute)?.removeModifier(modifier.id)
}
*///?}

/**
 * Name a container block entity.
 *
 * A custom name became a component at 1.20.5 and is applied as a patch; before that it was a field on
 * the block entity with its own setter. `setCustomName` still exists on the newer band but the patch is
 * how a placed container is given one, so this keeps the newer route rather than the older one twice.
 */
//? if >=1.20.5 {
fun nameContainer(container: net.minecraft.world.level.block.entity.BlockEntity, name: net.minecraft.network.chat.Component) {
    container.applyComponents(
        net.minecraft.core.component.DataComponentMap.EMPTY,
        net.minecraft.core.component.DataComponentPatch.builder()
            .set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, name)
            .build(),
    )
}
//?} else {
/*fun nameContainer(container: net.minecraft.world.level.block.entity.BlockEntity, name: net.minecraft.network.chat.Component) {
    (container as? net.minecraft.world.level.block.entity.BaseContainerBlockEntity)?.setCustomName(name)
}
*///?}
