pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://maven.neoforged.net/releases") }
    }
}

plugins {
    // Provisions the JDK 21 toolchain Minecraft 1.21 needs, and the JDK 17 one the included vscript build asks for.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "bpm"

/*
 * **vscript is a git submodule (`vscript/`), and by default it is built as part of this build.** Edits to
 * the language, the runtime or the canvas are picked up on the next `gradlew` run with no publish step.
 *
 * `-Pvscript.local=false` resolves it from mavenLocal instead, after `vscript/gradlew publishToMavenLocal`.
 *
 * That switch exists for the version ladder. A composite build loads ONE copy of every plugin into a shared
 * classloader, and at the full ladder that classloader would be shared by roughly two dozen Kotlin projects
 * plus Loom, architectury-plugin, Shadow and Stonecutter. Worse, `include(project(...))` of a
 * dependency-substituted composite project is a rough edge in Loom, which wants resolvable coordinates to
 * generate jar-in-jar metadata from.
 *
 * The default stays the composite for now, because that is the inner loop that works today and this build
 * still has one node. **It flips at the Stonecutter swap** — at which point the fast loop is publishing to
 * mavenLocal, which is why bpm's repositories now include a content-filtered `mavenLocal()`.
 *
 * The substitutions are stated explicitly, and the first one is load-bearing: the language module is
 * `:vscript-lang` but its artifact is `dev.ziggle:vscript` (see vscript-lang/build.gradle), and
 * includeBuild's automatic matching goes by the project's own coordinates. Without the line the dependency
 * silently resolves to whatever stale jar mavenLocal holds and fails as a page of unresolved references.
 */
val useVscriptComposite = (providers.gradleProperty("vscript.local").orNull ?: "true").toBoolean()

if (useVscriptComposite) {
    includeBuild("vscript") {
        dependencySubstitution {
            substitute(module("dev.ziggle:vscript")).using(project(":vscript-lang"))
            substitute(module("dev.ziggle:vscript-runtime")).using(project(":vscript-runtime"))
            substitute(module("dev.ziggle:vscript-ui")).using(project(":vscript-ui"))
            substitute(module("dev.ziggle:vscript-runview")).using(project(":vscript-runview"))
            substitute(module("dev.ziggle:editor-host")).using(project(":editor-host"))
            substitute(module("dev.ziggle:editor-graph")).using(project(":editor-graph"))
        }
    }
}
