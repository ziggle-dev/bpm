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

/** `BlockEntityType.Builder` was deleted; the constructor it hid is public. */
fun <T : net.minecraft.world.level.block.entity.BlockEntity> blockEntityType(
    factory: (net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState) -> T,
    block: net.minecraft.world.level.block.Block,
): net.minecraft.world.level.block.entity.BlockEntityType<T> =
    net.minecraft.world.level.block.entity.BlockEntityType({ pos, state -> factory(pos, state) }, setOf(block))

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

@Suppress("DataFlowIssue")
fun <T : net.minecraft.world.level.block.entity.BlockEntity> blockEntityType(
    factory: (net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState) -> T,
    block: net.minecraft.world.level.block.Block,
): net.minecraft.world.level.block.entity.BlockEntityType<T> =
    net.minecraft.world.level.block.entity.BlockEntityType.Builder.of({ pos, state -> factory(pos, state) }, block).build(null)

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

fun <I : net.minecraft.world.item.crafting.RecipeInput, T : net.minecraft.world.item.crafting.Recipe<I>> findRecipe(
    level: net.minecraft.world.level.Level,
    type: net.minecraft.world.item.crafting.RecipeType<T>,
    input: I,
): net.minecraft.world.item.crafting.RecipeHolder<T>? =
    level.recipeManager.getRecipeFor(type, input, level).orElse(null)

fun recipeById(
    level: net.minecraft.world.level.Level,
    id: bpm.platform.ResourceLocation,
): net.minecraft.world.item.crafting.RecipeHolder<*>? = level.recipeManager.byKey(id).orElse(null)

fun recipeIdOf(holder: net.minecraft.world.item.crafting.RecipeHolder<*>): bpm.platform.ResourceLocation =
    holder.id()

val INGREDIENT_CODEC: com.mojang.serialization.Codec<net.minecraft.world.item.crafting.Ingredient> =
    net.minecraft.world.item.crafting.Ingredient.CODEC_NONEMPTY

//?}

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
val SLOWNESS: net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect>
    //? if >=1.21.5 {
    /*get() = net.minecraft.world.effect.MobEffects.SLOWNESS
    *///?} else {
    get() = net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN
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
