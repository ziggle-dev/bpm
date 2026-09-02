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
    /*
     * Upstream Fabric Loom, not Architectury's fork.
     *
     * This branch only builds Fabric -- the NeoForge branch is on ModDevGradle -- so the fork bought
     * nothing, and it cannot set up 26.x: Architectury Loom stops at 1.17.491 with no support for a
     * deobfuscated Minecraft. Nothing Architectury-specific was in use here; the switch is this id.
     *
     * A SNAPSHOT, which is not a choice: 26.x support landed on the 1.17 line after 1.17.20 and there is
     * no release carrying it. This is the version Fabric's own 26.2 example mod uses.
     */
    id("net.fabricmc.fabric-loom") version "1.17-SNAPSHOT"
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
// On 26.x this is a Modrinth version id rather than a version number -- see the dependency below.
val geckolibVersion = property(
    if (stonecutter.eval(minecraftVersion, ">=26.1")) "geckolib_fabric_version_" + minecraftVersion.replace('.', '_')
    else "geckolib_version_" + minecraftVersion.replace('.', '_'),
) as String
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
 * One access widener, on two bands.
 *
 * There was one for `SpriteContents.originalImage`, and it went: the fluid gauge now averages the texture
 * off the resource manager, which is public API everywhere. This is a different one, for
 * `AbstractTexture.texture` -- the GpuTexture behind a loaded texture, which the editor's item thumbnails
 * and player-head icons need a handle for.
 *
 * Scoped to 1.21.5-1.21.8 deliberately. Below 1.21.5 the field does not exist; from 1.21.9
 * `AbstractTexture.getTexture()` is public and there is nothing to widen. That scoping is also what keeps
 * it on Loom's CLASSIC access-widener path: from 1.21.9 Loom takes the ClassTweaker route, which accepts
 * only the `intermediary` namespace, and this branch is Mojmap throughout.
 */
val widensAccess = stonecutter.eval(minecraftVersion, ">=1.21.5 <1.21.9")

loom {
    if (widensAccess) {
        // OUTSIDE the resources tree on purpose. Stonecutter shares `fabric/src/main/resources` across
        // every node, and Loom picks up a widener sitting there whether or not this points at it -- so a
        // file meant for two bands would be read by all of them. Loom copies it into the built jar and
        // declares it in fabric.mod.json itself.
        accessWidenerPath = rootProject.file("fabric/accesswidener/bpm.accesswidener")
    }
}

/*
 * JDK 25 from 26.1, and the toolchain and the Kotlin target have to move together.
 *
 * Gradle refuses with "Inconsistent JVM Target Compatibility" if only one of them does -- which is the
 * good outcome; the bad one would be bytecode the game cannot load.
 */
val toolchainVersion = if (stonecutter.eval(minecraftVersion, ">=26.1")) 25 else 21

java.toolchain.languageVersion = JavaLanguageVersion.of(toolchainVersion)

kotlin {
    jvmToolchain(toolchainVersion)
    compilerOptions {
        jvmTarget = if (toolchainVersion == 25) JvmTarget.JVM_25 else JvmTarget.JVM_21
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
    // A band directory rather than a version one -- see the note in neoforge/build.gradle.kts.
    if (stonecutter.eval(minecraftVersion, ">=1.21.6")) {
        resources.srcDir(rootProject.file("src/main/resources-since-1.21.6"))
    }
    if (stonecutter.eval(minecraftVersion, ">=1.21.5")) {
        resources.srcDir(rootProject.file("src/main/resources-since-1.21.5"))
    }
    if (stonecutter.eval(minecraftVersion, ">=1.21.2")) {
        resources.srcDir(rootProject.file("src/main/resources-since-1.21.2"))
    }
    resources.srcDir(rootProject.file("src/main/resources"))
}

// GeckoLib scans assets/<ns>/geckolib/{models,animations} from 1.21.9 and ignores anything outside them.
// Copied at build time rather than duplicated in the tree -- see the note in neoforge/build.gradle.kts.
if (stonecutter.eval(minecraftVersion, ">=1.21.5")) {
    tasks.named<ProcessResources>("processResources") {
        from(rootProject.file("src/main/resources/assets/bpm/geo")) { into("assets/bpm/geckolib/models") }
        from(rootProject.file("src/main/resources/assets/bpm/animations")) { into("assets/bpm/geckolib/animations") }
    }
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

/*
 * `modImplementation` remaps a dependency from intermediary into the namespace being compiled against.
 * On 26.x there is no such namespace and nothing to remap, so these are plain `implementation` there --
 * which is what Fabric's own 26.2 example does. `deobf` names the right one per band so the dependency
 * list below reads the same on all of them.
 */
val deobf = if (stonecutter.eval(minecraftVersion, ">=26.1")) "implementation" else "modImplementation"

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    /*
     * NOTHING is declared here on 26.x, and the silence is the configuration.
     *
     * The game ships DEOBFUSCATED from 26.1: Mojang publishes no mappings, and Fabric publishes neither
     * intermediary nor yarn, because the classes already carry the names this mod is written against.
     * Declaring none is how a build says so. Asking for Mojang mappings there fails with "Failed to find
     * official mojang mappings for 26.2".
     */
    if (!stonecutter.eval(minecraftVersion, ">=26.1")) {
        mappings(loom.officialMojangMappings())
    }
    add(deobf, "net.fabricmc:fabric-loader:$fabricLoaderVersion")
    add(deobf, "net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
    // Fabric Language Kotlin is this loader's KotlinForForge: it supplies the stdlib in a player's game.
    add(deobf, "net.fabricmc:fabric-language-kotlin:$flkVersion")
    // GeckoLib publishes per loader from the same source; the model, animation and Molang APIs the mod
    // uses are identical, which is why none of bpm's renderer code is loader-specific.
    /*
     * GeckoLib, from Modrinth on 26.x.
     *
     * The Cloudsmith maven that serves every earlier band has no `geckolib-fabric-26.2` at all -- it
     * still serves 1.21.11 fine, so this is not a mirror being stale. Modrinth has it, and its maven is
     * already a repository here, so the 26.x pin is a Modrinth version id rather than a version number.
     */
    if (stonecutter.eval(minecraftVersion, ">=26.1")) {
        add(deobf, "maven.modrinth:geckolib:$geckolibVersion")
    } else {
        add(deobf, "software.bernie.geckolib:geckolib-fabric-$minecraftVersion:$geckolibVersion")
    }
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
    add(deobf, "teamreborn:energy:$energyApiVersion")
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
