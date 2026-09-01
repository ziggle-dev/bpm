package bpm

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.streams.toList
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The packages that must stay free of Minecraft, NeoForge, Mojang and LWJGL types.
 *
 * `bpm.client.editor` is the workbench: ImGui + vscript only, so that it runs under the headless
 * harness and could move to another host. `bpm.session` and `bpm.net.chunk` are pure logic that the
 * editor and the commit pipeline share. Together they are the seed of a future loader-free module,
 * and this test is what keeps them that way.
 *
 * Two things this guard learned the hard way, both worth keeping:
 *
 * 1. **It scans the whole file, not just the import lines.** This codebase writes fully-qualified
 *    names inline all over the place — `net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(...)`,
 *    `(inv as? net.neoforged.neoforge.items.IItemHandlerModifiable)`, `net.minecraft.core.GlobalPos.of(...)`.
 *    There are over two hundred such references outside imports in `src/main`, so an import-only
 *    regex would happily certify a package that was full of platform types.
 *
 * 2. **A missing directory is a failure, not a pass.** The previous version opened with
 *    `if (!Files.isDirectory(root)) return`, which meant that the moment a package moved the guard
 *    silently stopped guarding anything and still went green.
 */
class ArchitectureTest {

    private companion object {
        /**
         * Matches a platform package reference anywhere in the file. The lookbehind stops it firing
         * on an unrelated identifier that merely ends in the same letters (`subnet.minecraft`).
         */
        val FORBIDDEN = Regex("""(?<![A-Za-z0-9_.])(net\.minecraft|net\.neoforged|com\.mojang|org\.lwjgl)\.""")

        val PLATFORM_FREE = listOf(
            "src/main/kotlin/bpm/client/editor",
            "src/main/kotlin/bpm/session",
            "src/main/kotlin/bpm/net/chunk",
        )

        /**
         * The unit-test launch does not run with the project directory as its working directory, so
         * a bare relative path resolves to nothing. That is not a hypothetical: the first version of
         * this test opened with `if (!Files.isDirectory(root)) return`, which meant it silently
         * checked nothing on every run it ever made.
         *
         * Walk up to the directory holding `settings.gradle` instead, and let `-Dbpm.projectDir`
         * override it if a future build layout wants to say so explicitly.
         */
        val PROJECT_ROOT: Path by lazy {
            System.getProperty("bpm.projectDir")?.let { return@lazy Path.of(it) }
            var dir: Path? = Path.of("").toAbsolutePath()
            val from = dir
            while (dir != null) {
                if (Files.isRegularFile(dir.resolve("settings.gradle")) ||
                    Files.isRegularFile(dir.resolve("settings.gradle.kts"))
                ) return@lazy dir
                dir = dir.parent
            }
            fail("could not find the project root (no settings.gradle at or above $from); pass -Dbpm.projectDir")
        }
    }

    @Test
    fun `the platform-free packages name no platform types`() {
        val offenders = mutableListOf<String>()

        for (relative in PLATFORM_FREE) {
            val root = PROJECT_ROOT.resolve(relative)
            if (!Files.isDirectory(root)) {
                fail(
                    "$relative does not exist, so this guard would have passed without checking anything. " +
                        "If the package moved, update ArchitectureTest.PLATFORM_FREE to follow it."
                )
            }

            val sources = Files.walk(root).use { s -> s.filter { it.extension == "kt" }.toList() }
            assertTrue(sources.isNotEmpty(), "$relative contains no .kt files — the guard is not testing anything.")

            for (file in sources) {
                file.readText().lineSequence().forEachIndexed { i, line ->
                    FORBIDDEN.find(line)?.let { offenders += "$file:${i + 1}: ${line.trim()}" }
                }
            }
        }

        assertTrue(
            offenders.isEmpty(),
            "platform types referenced in a platform-free package:\n" + offenders.joinToString("\n")
        )
    }
}
