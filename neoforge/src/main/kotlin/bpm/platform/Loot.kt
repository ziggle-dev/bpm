package bpm.platform

/**
 * A loot table, named and fetched.
 *
 * Loot tables became a REGISTRY at 1.21: a table is a `ResourceKey<LootTable>` reached through
 * `reloadableRegistries()`. Before that they were keyed by a bare id and fetched from the server's loot
 * data. Both name the same file in the same datapack; only the handle differs.
 */
//? if >=1.21 {
typealias LootKey = net.minecraft.resources.ResourceKey<net.minecraft.world.level.storage.loot.LootTable>

fun lootKey(id: ResourceLocation): LootKey =
    net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.LOOT_TABLE, id)

fun lootTable(server: net.minecraft.server.MinecraftServer, key: LootKey): net.minecraft.world.level.storage.loot.LootTable =
    server.reloadableRegistries().getLootTable(key)

/** The last few tick durations, in nanoseconds. */
fun tickTimesNanos(server: net.minecraft.server.MinecraftServer): LongArray = server.tickTimesNanos
//?} else {
/*typealias LootKey = ResourceLocation

fun lootKey(id: ResourceLocation): LootKey = id

fun lootTable(server: net.minecraft.server.MinecraftServer, key: LootKey): net.minecraft.world.level.storage.loot.LootTable =
    server.lootData.getLootTable(key)

/** Same array under its older name; it held nanoseconds here too. */
fun tickTimesNanos(server: net.minecraft.server.MinecraftServer): LongArray = server.tickTimes
*///?}
