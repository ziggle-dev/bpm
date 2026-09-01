package bpm.platform

import bpm.platform.config.ConfigSpec
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The config, which has the nastiest quiet failure of anything on this branch: a server owner who has
 * tuned their Warden and comes back to find it reset would have no reason to suspect the config reader,
 * and nothing in the log would tell them.
 *
 * So the tests that matter here are the ones about reading somebody else's file, not about writing ours.
 */
class ConfigSpecTest {

    private fun spec(): Triple<ConfigSpec, ConfigSpec.DoubleValue, ConfigSpec.IntValue> {
        val b = ConfigSpec.Builder()
        b.push("warden")
        val health = b.comment("How much the Warden has").defineInRange("health", 500.0, 1.0, 10000.0)
        b.pop()
        b.push("trap")
        val range = b.defineInRange("turretRange", 16, 1, 64)
        b.pop()
        return Triple(b.build(), health, range)
    }

    @Test
    fun `a hand-edited file is read, not overwritten with defaults`() {
        // Exactly the shape NightConfig writes, tabs and all, with two values changed by hand.
        val written = """
            [warden]
            ${'\t'}#How much the Warden has
            ${'\t'}#Range: 1.0 ~ 10000.0
            ${'\t'}health = 1234.0

            [trap]
            ${'\t'}#Range: 1 ~ 64
            ${'\t'}turretRange = 24
        """.trimIndent()

        val file = Files.createTempFile("bpm-server", ".toml")
        Files.writeString(file, written)

        val (spec, health, range) = spec()
        spec.load(file)

        assertEquals(1234.0, health.get(), "the edited Warden health was thrown away")
        assertEquals(24, range.get(), "the edited turret range was thrown away")
    }

    @Test
    fun `values survive a write and a read back`() {
        val file = Files.createTempFile("bpm-roundtrip", ".toml")
        val (first, health1, range1) = spec()
        Files.writeString(file, "[warden]\n\thealth = 777.0\n\n[trap]\n\tturretRange = 3\n")
        first.load(file)
        assertEquals(777.0, health1.get())
        assertEquals(3, range1.get())

        // load() rewrites the file; a fresh spec reading that output must see the same values.
        val (second, health2, range2) = spec()
        second.load(file)
        assertEquals(777.0, health2.get(), "a value did not survive our own round trip")
        assertEquals(3, range2.get(), "a value did not survive our own round trip")
    }

    @Test
    fun `a missing file writes defaults rather than failing`() {
        val dir = Files.createTempDirectory("bpm-cfg")
        val file = dir.resolve("nested").resolve("bpm-server.toml")
        val (spec, health, _) = spec()
        spec.load(file)

        assertEquals(500.0, health.get())
        assertTrue(Files.exists(file), "the file should have been created")
        assertTrue(file.readText().contains("health"), "the written file should carry the key")
    }

    @Test
    fun `an out-of-range value is clamped rather than refused`() {
        // Somebody typing 99999 wants the biggest allowed, not a stack trace on boot.
        val file = Files.createTempFile("bpm-clamp", ".toml")
        Files.writeString(file, "[warden]\n\thealth = 99999.0\n\n[trap]\n\tturretRange = 0\n")
        val (spec, health, range) = spec()
        spec.load(file)
        assertEquals(10000.0, health.get())
        assertEquals(1, range.get())
    }

    @Test
    fun `nonsense falls back to the default instead of throwing`() {
        val file = Files.createTempFile("bpm-junk", ".toml")
        Files.writeString(file, "[warden]\n\thealth = quite a lot\n")
        val (spec, health, _) = spec()
        spec.load(file)
        assertEquals(500.0, health.get())
    }

    @Test
    fun `isLoaded is false until it is`() {
        val (spec, _, _) = spec()
        assertFalse(spec.isLoaded)
        spec.load(Files.createTempFile("bpm-loaded", ".toml"))
        assertTrue(spec.isLoaded)
    }

    @Test
    fun `the real config declares every section it used to`() {
        // A smoke test over the actual spec: if a section were dropped in the port, the file a server
        // already has would quietly stop being read for those keys.
        val rendered = bpm.BpmConfig.SPEC.render()
        for (section in listOf("[link]", "[relay]", "[gate]", "[chamber]", "[warden]", "[trap]", "[effects]", "[fluids]")) {
            assertTrue(rendered.contains(section), "the config no longer writes $section")
        }
        assertTrue(rendered.contains("health"), "warden.health is missing")
        assertTrue(rendered.contains("turretRange"), "trap.turretRange is missing")
        // The per-tier block is the one nested section, and the one built in a loop.
        for (tier in bpm.world.CoreTier.entries) {
            assertTrue(rendered.contains("[tier." + tier.key + "]"), "the config no longer writes [tier." + tier.key + "]")
        }
    }
}
