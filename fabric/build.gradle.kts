import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

/*
 * bpm on Fabric.
 *
 * Architectury Loom here and ModDevGradle on the NeoForge branch — see settings.gradle.kts for why the
 * two loaders deliberately use different build backends rather than one.
 *
 * **This branch is a skeleton.** It provisions Fabric, compiles the loader-free core, and produces a
 * jar. It does NOT yet compile the shared `src/main/kotlin` tree, because that tree calls into the
 * platform seam and nothing implements the seam for Fabric yet: `src/fabric/kotlin` needs the
 * counterparts of the fourteen files in `src/neoforge/kotlin`, and `Platform.install(...)` has to be
 * given all of them or it fails at first use naming the one that is missing. Writing those adapters is
 * the next piece of work, and until then the honest thing is a branch that builds what it can rather
 * than one that pretends.
 */
plugins {
    id("dev.kikugie.stonecutter")
    id("dev.architectury.loom") version "1.17.491"
    kotlin("jvm") version "2.3.21"
}

val modId = property("mod_id") as String
val modVersion = property("mod_version") as String
val minecraftVersion: String = stonecutter.current.version
val vscriptVersion = property("vscript_version") as String
val imguiVersion = property("imgui_version") as String

version = modVersion
group = "bpm"
base { archivesName = "$modId-fabric" }

java.toolchain.languageVersion = JavaLanguageVersion.of(21)

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        // Same ceiling as the NeoForge branch: Fabric Language Kotlin supplies the stdlib in a player's
        // game, and it must satisfy both this and the compiler that built vscript.
        apiVersion = KotlinVersion.KOTLIN_2_3
        languageVersion = KotlinVersion.KOTLIN_2_3
        javaParameters = true
    }
}

repositories {
    mavenLocal { content { includeGroup("dev.ziggle") } }
    mavenCentral()
    maven { url = uri("https://maven.fabricmc.net/") }
    maven { url = uri("https://maven.architectury.dev/") }
}

/*
 * Only the core for now. It has no Minecraft on its classpath at all, which is exactly why it is the
 * part that needs no porting: whatever this branch eventually does with the shared tree, these
 * twenty-seven files are already correct on both loaders.
 */
val core by sourceSets.creating

kotlin.sourceSets.named("core") {
    kotlin.setSrcDirs(listOf(rootProject.file("src/core/kotlin")))
}
sourceSets.named("core") {
    resources.setSrcDirs(listOf(rootProject.file("src/core/resources")))
}
// The shared tree is not compiled here yet — see the note at the top of this file.
kotlin.sourceSets.named("main") {
    kotlin.setSrcDirs(emptyList<Any>())
}
sourceSets.named("main") {
    resources.setSrcDirs(listOf(rootProject.file("src/main/resources")))
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings(loom.officialMojangMappings())

    for (m in listOf("vscript", "vscript-runtime", "vscript-ui", "vscript-runview", "editor-host", "editor-graph")) {
        "coreImplementation"("dev.ziggle:$m:$vscriptVersion")
    }
    "coreImplementation"("io.github.spair:imgui-java-binding:$imguiVersion")
    "coreCompileOnly"("com.google.code.gson:gson:2.10.1")
}
