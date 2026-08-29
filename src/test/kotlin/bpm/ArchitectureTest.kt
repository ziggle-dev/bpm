package bpm

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.streams.toList
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `bpm.client.editor` is the workbench: ImGui + vscript only, so that it runs under the headless harness and
 * could move to another host. No Minecraft, NeoForge or Mojang import may appear there.
 */
class ArchitectureTest {
    @Test
    fun `the editor package is free of Minecraft`() {
        val root = Path.of("src/main/kotlin/bpm/client/editor")
        if (!Files.isDirectory(root)) return
        val forbidden = Regex("""^import\s+(net\.minecraft|net\.neoforged|com\.mojang|org\.lwjgl)""", RegexOption.MULTILINE)
        val offenders = Files.walk(root).use { s -> s.filter { it.extension == "kt" }.toList() }
            .filter { forbidden.containsMatchIn(it.readText()) }
        assertTrue(offenders.isEmpty(), "Minecraft imports in the editor package: $offenders")
    }
}
