package bpm.world

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The odds JEI shows are the odds the game rolls.
 *
 * [CoreDrops] restates the core-quality loot tables in Kotlin, because loot tables are not synced to clients
 * and a JEI page therefore cannot read the real one. A restatement that drifts is a lie the player cannot
 * check, so it is checked here instead: this parses the actual datapack JSON and asserts every item and
 * weight agrees, in order.
 *
 * Read off the CLASSPATH rather than by file path: the test's working directory is not the project root, and
 * going through the classpath also means this checks the resource as it will actually be packaged. No
 * Minecraft bootstrap and no loot-table codec, so it fails for exactly one reason.
 */
class CoreDropsTest {

    private fun lootTable(name: String): List<Pair<String, Int>> {
        val path = "/data/bpm/loot_table/gameplay/$name.json"
        val text = javaClass.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }
        assertTrue(text != null, "missing loot table on the classpath: $path")
        val pools = JsonParser.parseString(text).asJsonObject.getAsJsonArray("pools")
        assertEquals(1, pools.size(), "$name should roll from exactly one pool")
        return pools[0].asJsonObject.getAsJsonArray("entries").map { entry ->
            val o = entry.asJsonObject
            o.get("name").asString to o.get("weight").asInt
        }
    }

    @Test
    fun `every core table matches the loot table it mirrors`() {
        for (table in CoreDrops.all) {
            val fromJson = lootTable(table.lootTable)
            val fromCode = table.drops.map { "bpm:" + it.item.id.path to it.weight }
            assertEquals(fromJson, fromCode, "${table.lootTable} has drifted from CoreDrops")
        }
    }

    @Test
    fun `the odds are stated out of a real total`() {
        for (table in CoreDrops.all) {
            assertTrue(table.total > 0, "${table.lootTable} has no weight at all")
            // A table whose entries sum to something absurd usually means a weight was typed as a percentage.
            assertTrue(table.total < 1000, "${table.lootTable} weights look like percentages, not weights")
        }
    }
}
