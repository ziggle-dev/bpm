import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

/*
 * bpm on Fabric.
 *
 * Architectury Loom here and ModDevGradle on the NeoForge branch — see settings.gradle.kts for why the
 * two loaders deliberately use different build backends rather than one.
 *
 * `src/main/kotlin` is the shared tree and `src/fabric/kotlin` holds this loader's implementations of
 * the seam — the counterparts of the fourteen files in `src/neoforge/kotlin`.
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
val fabricLoaderVersion = property("fabric_loader_version") as String
val fabricApiVersion = property("fabric_api_version_" + minecraftVersion.replace('.', '_')) as String
val flkVersion = property("flk_version") as String
val geckolibVersion = property("geckolib_version_" + minecraftVersion.replace('.', '_')) as String
val energyApiVersion = property("energy_api_version_" + minecraftVersion.replace('.', '_')) as String
/*
 * The optional integrations -- JEI's API and Ponder -- publish for some Minecraft versions and not
 * others, exactly as on NeoForge. Neither ships and neither is worth blocking a node on, so they are
 * added only where they resolve and their source is excluded where they are not. A node without them
 * builds, runs and passes its game tests; it is simply a plainer dev world.
 */
val hasDevMods = minecraftVersion == "1.21.1"
val jeiVersion = property("jei_version") as String
val ponderVersion = property("ponder_version") as String

version = modVersion
group = "bpm"
base { archivesName = "$modId-fabric" }

/*
 * NeoForge compiles against a Minecraft whose access transformers have already widened a good deal of
 * vanilla. Fabric does not, so anything the shared tree reaches for that vanilla keeps to itself has to
 * be named in an access widener. See the file for what and why.
 */
loom {
    // The BRANCH's resources, named from the root: `file(...)` here would resolve against the
    // version node, which holds no sources of its own.
    accessWidenerPath = rootProject.file("fabric/src/main/resources/bpm.accesswidener")
}

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
    maven {
        name = "modmuss50"
        url = uri("https://maven.modmuss50.me/")
        content { includeGroup("teamreborn") }
    }
    maven {
        name = "GeckoLib"
        url = uri("https://dl.cloudsmith.io/public/geckolib3/geckolib/maven/")
        content { includeGroupByRegex("software\\.bernie.*") }
    }
    maven {
        name = "BlameJared"
        url = uri("https://maven.blamejared.com/")
        content { includeGroup("mezz.jei") }
    }
    maven {
        name = "CreateMod"
        url = uri("https://maven.createmod.net")
        content {
            includeGroup("net.createmod.ponder")
            includeGroup("net.createmod.catnip")
            includeGroup("dev.engine-room.flywheel")
        }
    }
}

/*
 * The core has no Minecraft on its classpath at all, which is why it needs no porting: these
 * twenty-seven files are already correct on both loaders, and this branch compiles the very same ones.
 */
val core by sourceSets.creating

sourceSets {
    main {
        compileClasspath += core.output
        runtimeClasspath += core.output
    }
}

/*
 * Every source directory named against rootProject, because this script's projectDir is
 * `fabric/versions/<version>/`. A missed convention here is not an error, it is an empty compilation.
 *
 * **srcDir, not setSrcDirs.** Stonecutter adds `<branch>/src/...` and a generated directory to every
 * source set as it is created, and those are what make `//? if` directives work. Replacing the list threw
 * them away, and the directives then sat inert on every node — silently, which is the whole problem with
 * it. See the longer note in the neoforge branch's script.
 */
kotlin.sourceSets.named("core") {
    kotlin.srcDir(rootProject.file("src/core/kotlin"))
}
sourceSets.named("core") {
    resources.srcDir(rootProject.file("src/core/resources"))
}
kotlin.sourceSets.named("main") {
    // The Java directory is listed among the KOTLIN dirs too, not just under `java` below: Kotlin reads
    // .java sources for resolution but only from its own source set, and without it the Kotlin code
    // cannot see the accessor mixin it calls.
    kotlin.srcDir(rootProject.file("src/main/kotlin"))

    // See `hasDevMods`: where the integration's API is absent, its source is not compiled either.
    if (!hasDevMods) {
        kotlin.exclude("bpm/compat/jei/**", "bpm/client/ponder/**")
    }
}
/*
 * **The mixins are Java, and they have to be.**
 *
 * Mixin's annotation processor is a javac processor: it cannot see Kotlin sources, so a mixin written in
 * Kotlin gets no refmap generated and then silently fails to apply on any obfuscated build. It would work
 * in this dev run and nowhere else, which is the worst kind of broken. This directory is the only Java in
 * the project and exists solely for that reason.
 */
sourceSets.named("main") {
}
/*
 * **A resource whose SCHEMA changed lives in a per-version tree, shared by both loaders.**
 *
 * Almost every one of the 386 resource files is version-neutral and lives in `src/<set>/resources`. A
 * handful are not, because the file FORMAT changed rather than its content: 1.21.4's client-item files,
 * its recipes in the new string-ingredient form, the base models that lost `builtin/entity`, the rift
 * shader configs whose `vertex` field became a real resource location. A JSON cannot carry a `//? if`
 * directive, so those cannot be expressed in the shared tree.
 *
 * They are version-shaped, NOT loader-shaped -- a 1.21.4 recipe is the same file on either loader -- so
 * they belong in one place that both branches read, which is what this is. The genuinely loader-specific
 * exceptions (NeoForge composites its bucket from a fluid container; Fabric uses a plain model) stay in
 * their own node's `src/main/resources`, which is searched first.
 *
 * Added BEFORE the shared tree so that, with `processResources` set to EXCLUDE duplicates, the
 * version's answer wins over the version-neutral one it is replacing.
 */
sourceSets.named("main") {
    resources.srcDir(rootProject.file("src/main/resources-$minecraftVersion"))
    resources.srcDir(rootProject.file("src/main/resources"))
}

/** A node's own resources, then the version's, then the shared tree: the most specific copy wins. */
tasks.named<ProcessResources>("processResources") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

/*
 * Runs land in their own directory, not the one a person plays in.
 *
 * THREE levels up, not two: `runDir` is resolved against the project directory, and with a loader
 * branch that is `fabric/versions/<version>/`. Two levels lands in `fabric/`, which is how this was
 * first written and why the server could not find the eula.txt sitting at the repository root.
 */
loom {
    runs {
        named("server") { runDir("../../../run-fabric") }
        named("client") {
            runDir("../../../run-fabric")
            // `gradlew :fabric:1.21.1:runClient -Pframes=120`: open the editor on the title screen, draw N
            // frames, write a marker, quit. The same harness the NeoForge branch uses, and it works here
            // only because `Minecraft#setScreen` is mixed into — that is what tells SmokeRun a title
            // screen appeared.
            vmArg("-Dbpm.editor.frames=" + (project.findProperty("frames") ?: "0"))
        }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
    // Fabric Language Kotlin is this loader's KotlinForForge: it supplies the stdlib in a player's game.
    modImplementation("net.fabricmc:fabric-language-kotlin:$flkVersion")
    // GeckoLib publishes per loader from the same source; the model, animation and Molang APIs the mod
    // uses are identical, which is why none of bpm's renderer code is loader-specific.
    modImplementation("software.bernie.geckolib:geckolib-fabric-$minecraftVersion:$geckolibVersion")
    /*
     * Team Reborn's Energy API.
     *
     * Fabric API has no energy storage of its own, and this is what every Fabric mod that moves power
     * actually implements — so it is the difference between `energy.move` finding other mods' machines
     * and finding nothing at all.
     *
     * Nested with `include` rather than left as a dependency a player must install: it is a small API
     * library that expects to be bundled, and one more required download for a verb that should just
     * work is a bad trade.
     */
    modImplementation("teamreborn:energy:$energyApiVersion")
    include("teamreborn:energy:$energyApiVersion")
    // JEI's API on the compile path only, exactly as on NeoForge: a pack without JEI never loads the
    // plugin class, because @JeiPlugin is read by JEI itself.
    if (hasDevMods) modCompileOnly("mezz.jei:jei-$minecraftVersion-fabric-api:$jeiVersion")
    // Ponder, for the in-game scene tutorials. Compile path only, as on NeoForge.
    //
    // NOT Ponder-Common as well: that jar carries a second copy of catnip's PlatformHelper interface,
    // and having both on a runtime classpath is what made the NeoForge client hang on the loading bar.
    // One jar, the platform one, on both loaders.
    if (hasDevMods) modCompileOnly("net.createmod.ponder:Ponder-Fabric-$minecraftVersion:$ponderVersion") { isTransitive = false }

    for (m in listOf("vscript", "vscript-runtime", "vscript-ui", "vscript-runview", "editor-host", "editor-graph")) {
        implementation("dev.ziggle:$m:$vscriptVersion")
        "coreImplementation"("dev.ziggle:$m:$vscriptVersion")
        // Loom's `include` is this loader's jar-in-jar, the counterpart of NeoForge's `jarJar`.
        include("dev.ziggle:$m:$vscriptVersion")
    }
    include("org.commonmark:commonmark:0.22.0")
    /*
     * Dear ImGui, and this is the whole list on purpose.
     *
     * The binding alone compiles and then fails at runtime with `UnsatisfiedLinkError: no imgui-java64 in
     * java.library.path`, because the natives live in their own per-platform jars and the host is
     * expected to supply them. This mod is the host on both loaders, so all three ship, and the editor is
     * the most visible thing in it — an omission here is not subtle.
     *
     * LWJGL is excluded because the game supplies its own and two copies do not coexist.
     */
    for (m in listOf(
        "imgui-java-binding",
        "imgui-java-natives-windows",
        "imgui-java-natives-linux",
        "imgui-java-natives-macos",
    )) {
        implementation("io.github.spair:$m:$imguiVersion")
        include("io.github.spair:$m:$imguiVersion")
    }
    implementation("io.github.spair:imgui-java-lwjgl3:$imguiVersion") { exclude(group = "org.lwjgl") }
    include("io.github.spair:imgui-java-lwjgl3:$imguiVersion") { exclude(group = "org.lwjgl") }
    "coreImplementation"("io.github.spair:imgui-java-binding:$imguiVersion")
    "coreCompileOnly"("com.google.code.gson:gson:2.10.1")
}
