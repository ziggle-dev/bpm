import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

/*
 * bpm: a NeoForge 1.21.1 mod that hosts the vscript language, VM and canvas editor. See docs/ for the design.
 */
plugins {
    id("dev.kikugie.stonecutter")
    id("net.neoforged.moddev") version "2.0.144"
    // The SAME Kotlin plugin version as the included vscript build: a composite loads one copy of a plugin,
    // and two versions of the Kotlin plugin in one build fail to load at all.
    kotlin("jvm") version "2.3.21"
}

/*
 * This script is shared by every version node, and its projectDir is `versions/<version>/`, not the
 * repository root. Anything that used to rely on a path convention has to be named against rootProject
 * from here on.
 */

// gradle.properties, read once and named. Kotlin has no dynamic property access, which is the one real cost
// of this DSL — and also the reason a typo here is a build failure rather than a null two hundred lines later.
val modId = property("mod_id") as String
val modVersion = property("mod_version") as String
// The node IS the Minecraft version: no separate property to keep in step with the version list.
val minecraftVersion: String = stonecutter.current.version
val neoVersion = property("neo_version_" + minecraftVersion.replace('.', '_')) as String

/*
 * The dev-run conveniences — Mekanism, JEI, Just Dire Things, Tag & Component Tooltips, Ponder — are
 * pinned to builds for one Minecraft version, and most of them do not publish for every version on this
 * ladder. They exist so the transfer verbs can be tested against real machines and the scenes walked
 * through; none of them ship, and none of them is worth blocking a node on.
 *
 * So they are added only where they resolve. A node without them still builds, runs and passes its game
 * tests — it is simply a plainer dev world.
 */
val hasDevMods = minecraftVersion == "1.21.1"
val geckolibVersion = property("geckolib_version_" + minecraftVersion.replace('.', '_')) as String
val kffVersion = property("kff_version_" + minecraftVersion.replace('.', '_')) as String
val kotlinRuntimeVersion = property("kotlin_runtime_version") as String
val vscriptVersion = property("vscript_version") as String
val imguiVersion = property("imgui_version") as String

val mekanismVersion = property("mekanism_version") as String
val jeiVersion = property("jei_version") as String
val justdirethingsCurse = property("justdirethings_curse") as String
val tactVersion = property("tact_version") as String
val ponderVersion = property("ponder_version") as String
val flywheelVersion = property("flywheel_version") as String

version = modVersion
group = "bpm"
base { archivesName = modId }

java.toolchain.languageVersion = JavaLanguageVersion.of(21)

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        // The mod may not reach for stdlib API newer than the compiler that built vscript (2.3.x): KFF's
        // runtime is the only stdlib present in a player's game and it must satisfy both.
        apiVersion = KotlinVersion.KOTLIN_2_3
        languageVersion = KotlinVersion.KOTLIN_2_3
        javaParameters = true
    }
}

repositories {
    /*
     * vscript, when it is not being built as part of this build (`-Pvscript.local=false`; see
     * settings.gradle.kts for why that switch exists and when its default flips).
     *
     * Content-filtered to `dev.ziggle` and nothing else. An unfiltered mavenLocal is a well-known way to
     * poison a build with whatever a half-finished publish left lying around; filtered to the one group
     * this repository publishes, the blast radius is exactly the modules the switch is about.
     */
    mavenLocal { content { includeGroup("dev.ziggle") } }
    mavenCentral()
    maven {
        name = "Kotlin for Forge"
        url = uri("https://thedarkcolour.github.io/KotlinForForge/")
        content { includeGroup("thedarkcolour") }
    }
    maven {
        name = "GeckoLib"
        url = uri("https://dl.cloudsmith.io/public/geckolib3/geckolib/maven/")
        content { includeGroupByRegex("software\\.bernie.*") }
    }
    maven {
        name = "ModMaven"
        url = uri("https://modmaven.dev/")
        content { includeGroup("mekanism") }
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
    maven {
        name = "BlameJared"
        url = uri("https://maven.blamejared.com/")
        content { includeGroup("mezz.jei") }
    }
    maven {
        name = "CurseMaven"
        url = uri("https://cursemaven.com")
        content { includeGroup("curse.maven") }
    }
    maven {
        name = "Modrinth"
        url = uri("https://api.modrinth.com/maven")
        content { includeGroup("maven.modrinth") }
    }
}

/*
 * **The loader-free, Minecraft-free core.**
 *
 * Its compile classpath is deliberately NOT built from main's: it gets the bundled libraries and nothing
 * else, so a `net.minecraft` or `net.neoforged` import here is a compile error rather than a convention.
 * That is the whole point of the source set — the same boundary a `:core` Gradle project would enforce,
 * without yet taking on jar-in-jar and FML's module layers, which is where the build has historically
 * bitten (see the processDevResources comment below).
 *
 * It stays part of the bpm mod, so nothing about packaging or dev runs changes.
 */
val core by sourceSets.creating

/*
 * **The dev-only source set.** `src/dev/kotlin` holds the debugging endpoint (a websocket console with a Lua
 * `eval`) that is never shipped: it is compiled against `main`, added to the mod's classes in dev runs, and
 * left out of the jar. `main` reaches it only reflectively, by name, so a build without it is complete.
 */
val dev by sourceSets.creating

sourceSets {
    main {
        compileClasspath += core.output
        runtimeClasspath += core.output
    }
    test {
        compileClasspath += core.output
        runtimeClasspath += core.output
    }
}

dev.compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
dev.runtimeClasspath += sourceSets.main.get().output + sourceSets.main.get().runtimeClasspath

/*
 * **The source tree is at the repository root, and `main` is two directories.**
 *
 * This script's projectDir is `neoforge/versions/<version>/`, so every source-directory convention now
 * resolves to the wrong place — a path that does not exist. Left alone that is not a build failure, it is
 * an empty compilation: a jar with no classes in it and no error anywhere. So each one is named
 * explicitly against rootProject.
 *
 * `src/main/kotlin` is the shared tree, compiled by BOTH loaders. `src/neoforge/kotlin` is the fourteen
 * files that name NeoForge — the platform implementations behind the seam, plus the two loader bridges
 * and the rift shader — and only this branch compiles them. That is the `:common`/`:neoforge` split the
 * plan asked for, expressed as source directories rather than Gradle projects, because Stonecutter
 * compiles one shared tree per node and there is no separate `:common` artifact to build.
 *
 * `src/dev` is here rather than shared because all ten of its files are NeoForge: the game tests are
 * written against NeoForge's GameTest API. Fabric will need its own.
 */
/*
 * **srcDir, not setSrcDirs, and the difference is the whole version axis.**
 *
 * Stonecutter configures every source set as it is created — `project.sourceSets.all { configureSource }`
 * — and what that does is ADD two directories to each one: `<branch>/src/...`, which it processes for
 * `//? if` directives, and a generated directory holding the processed output. It works out which
 * directories to add by taking the source set's dirs relative to `<node>/src` and discarding anything
 * that escapes upwards, so it only ever sees the node-relative defaults.
 *
 * Replacing the list afterwards, which is what this used to do, threw both of those away. Nothing failed;
 * the directives simply stayed in whatever state they were authored in, on every node. Adding leaves them
 * in place.
 *
 * So each source set ends up reading from four places:
 *   <node>/src/<name>/kotlin      version-specific overrides for this one version (usually empty)
 *   <branch>/src/<name>/kotlin    PROCESSED — this is where `//? if` directives belong
 *   build/generated/stonecutter   where the processed copy lands for a non-active node
 *   <root>/src/<name>/kotlin      the tree shared with the other loader, added below, not processed
 */
for (name in listOf("main", "test", "core", "dev")) {
    kotlin.sourceSets.named(name) {
        kotlin.srcDir(rootProject.file("src/$name/kotlin"))
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
    sourceSets.named(name) {
        resources.srcDir(rootProject.file("src/$name/resources-$minecraftVersion"))
        resources.srcDir(rootProject.file("src/$name/resources"))
    }
}
/*
 * **Two source trees, and the difference between them is whether Stonecutter processes it.**
 *
 * `<root>/src/main/kotlin`  the tree BOTH loaders compile. Not processed: it is shared with a branch that
 *                           would want the other answer, so it must not contain version directives.
 * `<branch>/src/main/...`   this loader's own code, and PROCESSED — `//? if` directives work here.
 *
 * The loader-specific code used to live at `<root>/src/neoforge/kotlin`, which compiled fine and could
 * never carry a directive. Since almost all of it is render and registry code — exactly what changes
 * between Minecraft versions — it belongs on the processed side.
 */
kotlin.sourceSets.named("main") {
    /*
     * The optional integrations compile only where the mod they integrate with exists.
     *
     * `bpm/compat/jei` and `bpm/client/ponder` are written against JEI's and Ponder's APIs, which are
     * compileOnly and published for some Minecraft versions and not others. They are already optional at
     * runtime — a pack without JEI never loads the plugin class, because @JeiPlugin is read by JEI itself
     * — so a node where the API is absent should simply not compile them rather than fail.
     *
     * This is worth stating plainly because of what it does to a version's apparent cost: excluding these
     * two directories takes the 1.21.4 node from 393 compile errors to 232. The 161 difference was never
     * porting work, it was two integrations that are not published for that version yet.
     */
    if (!hasDevMods) {
        kotlin.exclude("bpm/compat/jei/**", "bpm/client/ponder/**")
    }
}

/*
 * The game tests run on one Minecraft version, not on all of them.
 *
 * 1.21.9 replaced the annotation-driven GameTest framework with a data-driven one: a test is a registered
 * `TestInstance` described by JSON, and `@GameTest`, NeoForge's `@GameTestHolder` and
 * `@PrefixGameTestTemplate` are all gone. Porting sixty-five tests to that shape would be a rewrite of the
 * safety net rather than a use of it, and the plan already says as much -- "given the vanilla GameTest API
 * churn across 1.21.x/26.x, consider running GameTest on ONE MC version per loader".
 *
 * 1.21.1 is that version. The tests exercise server logic -- transfers, chamber state, trap damage, core
 * tiers -- which is exactly the part of the mod that does NOT change between bands, so running them on one
 * node loses very little. The `dev` source set's other members (the commands and the Lua console) are not
 * excluded: `runClient` depends on them, and a launchable client on every band is the point.
 */
val gameTestsCompile = stonecutter.eval(stonecutter.current.version, "<1.21.9")
kotlin.sourceSets.named("dev") {
    if (!gameTestsCompile) {
        kotlin.exclude("bpm/dev/gametest/**")
    }
}



/*
 * `gameLibraries` is the bundled list as a resolvable set (used by tooling); `devLibraries` holds the dev-only
 * Lua console and is put on the boot layer of runs and tests — plain Java, so the boot layer suits it.
 */
val gameLibraries by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
val devLibraries by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = true
}
configurations["devImplementation"].extendsFrom(devLibraries)

neoForge {
    version = neoVersion

    runs {
        create("client") {
            client()
            // `gradlew runClient -Pframes=120`: open the editor on the title screen, draw N frames, write a marker, quit.
            systemProperty("bpm.editor.frames", (project.findProperty("frames") ?: "0").toString())
            // `gradlew runClient -Pworld="Test World"`: straight into that single-player world (created if missing).
            if (project.hasProperty("world")) {
                programArguments.addAll("--quickPlaySingleplayer", project.property("world").toString())
            }
        }
        create("server") {
            server()
            programArgument("--nogui")
        }
        create("gameTestServer") {
            type = "gameTestServer"
            /*
             * Its own directory, NOT the one a person plays in.
             *
             * A game-test server opens the world at `run/` and takes its session lock, so running the
             * tests while someone is playing either fails or interferes — and a run that dies leaves the
             * lock held, which then reads as a build problem and is not one. Nothing about a test run
             * wants the player's world, so it should not open it.
             */
            gameDirectory = rootProject.layout.projectDirectory.dir("run-gametest")
        }
        configureEach {
            systemProperty("forge.enabledGameTestNamespaces", modId)
            logLevel = org.slf4j.event.Level.INFO
            /*
             * The run directory stays at the repository root.
             *
             * MDG defaults it to `project.file("run")`, which under a version node is
             * `neoforge/versions/1.21.1/run` — a fresh, empty game directory. That would quietly orphan
             * the world that is already in `run/`, along with its configs and the dev console's token
             * file. Named explicitly so that adding a version node later cannot split them either.
             *
             * `configureEach` runs after the individual configurations, so this would also overwrite the
             * game-test server's own directory — hence the check. Its tests must never open the world
             * someone is playing in.
             */
            if (name != "gameTestServer") {
                gameDirectory = rootProject.layout.projectDirectory.dir("run")
            }
        }
    }

    mods {
        create("bpm") {
            sourceSet(core)
            sourceSet(sourceSets.main.get())
            sourceSet(dev)
        }
    }

    unitTest {
        enable()
        testedMod = mods.getByName("bpm")
    }
}

/** One entry in the list of libraries that are both compiled against and embedded in the shipped jar. */
data class Bundled(val notation: String, val prefer: String, val excludeLwjgl: Boolean = false)

// The libraries embedded in the shipped jar (`jarJar`) and compiled against (`implementation`) — one list.
val bundled = listOf(
    Bundled("dev.ziggle:vscript:[$vscriptVersion,2.0)", vscriptVersion),
    Bundled("dev.ziggle:vscript-runtime:[$vscriptVersion,2.0)", vscriptVersion),
    Bundled("dev.ziggle:vscript-ui:[$vscriptVersion,2.0)", vscriptVersion),
    Bundled("dev.ziggle:vscript-runview:[$vscriptVersion,2.0)", vscriptVersion),
    Bundled("dev.ziggle:editor-host:[$vscriptVersion,2.0)", vscriptVersion),
    Bundled("dev.ziggle:editor-graph:[$vscriptVersion,2.0)", vscriptVersion),
    // Dear ImGui via imgui-java. The editor modules compile against the binding only; the GL3 backend and the
    // JNI natives are the host's to supply, and this mod is the host.
    Bundled("io.github.spair:imgui-java-binding:[$imguiVersion,1.91)", imguiVersion),
    // Its POM imports the LWJGL 3.3.6 BOM; Minecraft 1.21.1 ships LWJGL 3.3.3 and that is the one we run on.
    Bundled("io.github.spair:imgui-java-lwjgl3:[$imguiVersion,1.91)", imguiVersion, excludeLwjgl = true),
    Bundled("io.github.spair:imgui-java-natives-windows:[$imguiVersion,1.91)", imguiVersion),
    Bundled("io.github.spair:imgui-java-natives-linux:[$imguiVersion,1.91)", imguiVersion),
    Bundled("io.github.spair:imgui-java-natives-macos:[$imguiVersion,1.91)", imguiVersion),
    // vscript-ui's one runtime dependency (Markdown in node docs).
    // Open-ended on purpose: jar-in-jar resolves one version per library for the WHOLE pack, and an
    // unsatisfiable set aborts resolution for every mod (Kotlin for Forge's language provider is nested too).
    // vscript-ui only walks the parser's node tree, which has not changed across 0.2x.
    Bundled("org.commonmark:commonmark:[0.22.0,)", "0.22.0"),
)

dependencies {
    implementation("thedarkcolour:kotlinforforge-neoforge:$kffVersion")
    // GeckoLib is NOT optional — the models are the mod. Its coordinate names the Minecraft version,
    // so a node whose version GeckoLib has not published for will fail here, loudly, which is right.
    implementation("software.bernie.geckolib:geckolib-neoforge-$minecraftVersion:$geckolibVersion")

    // Dev runs only: Mekanism (core + generators) to test the energy and fluid verbs against real machines,
    // cables and pipes. Runtime only — nothing compiles against it, and it is never bundled.
    if (hasDevMods) {
        runtimeOnly("mekanism:Mekanism:$mekanismVersion")
        runtimeOnly("mekanism:Mekanism:$mekanismVersion:generators")
    }
    // JEI: the API on the compile path for our own recipe category (bpm/compat/jei), the full mod at runtime
    // for recipes and item lookup while testing. compileOnly, so a pack without JEI simply never loads the
    // plugin class — @JeiPlugin is only read by JEI itself.
    if (hasDevMods) compileOnly("mezz.jei:jei-$minecraftVersion-neoforge-api:$jeiVersion")

    // Ponder: in-game scene tutorials (bpm/client/ponder). The API on the compile path, the whole stack at
    // runtime so the scenes can actually be walked through in a dev run. Ponder pulls Flywheel, which is
    // why this is three artifacts and not one — see the note in bpm.client.ponder.BpmPonderPlugin about
    // what that costs a player.
    if (hasDevMods) {
        compileOnly("net.createmod.ponder:Ponder-NeoForge-$minecraftVersion:$ponderVersion") { isTransitive = false }
        compileOnly("net.createmod.ponder:Ponder-Common-$minecraftVersion:$ponderVersion") { isTransitive = false }
        runtimeOnly("net.createmod.ponder:Ponder-NeoForge-$minecraftVersion:$ponderVersion") { isTransitive = false }
    }
    /*
     * Ponder-Common is compileOnly and deliberately NOT runtimeOnly.
     *
     * The NeoForge jar is self-contained -- 274 catnip classes, including both the
     * `catnip.platform.services.PlatformHelper` interface and its `NeoForgePlatformHelper`
     * implementation, plus the META-INF/services files that bind them. The common jar carries a SECOND
     * copy of that interface, and having it on the runtime classpath puts one copy in the plain
     * classpath and one in FML's transforming layer.
     *
     * Catnip resolves its platform helper with `ServiceLoader.findFirst`, which walks the classpath. With
     * two copies present that fails as "NeoForgePlatformHelper not a subtype" -- Ponder dies in common
     * setup, and because that is a broken mod state rather than a crash, the client hangs on the loading
     * bar with no crash screen. It was latent for as long as both jars were listed; introducing the
     * version node reordered the classpath enough to expose it.
     */
    if (hasDevMods) runtimeOnly("dev.engine-room.flywheel:flywheel-neoforge-$minecraftVersion:$flywheelVersion") { isTransitive = false }
    // JEI (recipes and item lookup while testing) and Just Dire Things (more machines, tools and goo to link).
    if (hasDevMods) {
        runtimeOnly("mezz.jei:jei-$minecraftVersion-neoforge:$jeiVersion")
        runtimeOnly("curse.maven:$justdirethingsCurse")
        // Tag & Component Tooltips: every item's tags and data components on its tooltip — the ids the filters take.
        runtimeOnly("maven.modrinth:tag-and-component-tooltips:$tactVersion")
    }

    // Gson is compileOnly for the core: Minecraft ships it, so it is always there at runtime, but the
    // core must not carry a copy of its own into the jar.
    "coreCompileOnly"("com.google.code.gson:gson:2.10.1")

    // `coreImplementation` too: the core compiles against the bundled libraries (vscript, imgui) and
    // against nothing else. Minecraft is absent from its classpath by construction.
    for (b in bundled) {
        for (conf in listOf("implementation", "coreImplementation", "jarJar", "gameLibraries")) {
            add(conf, b.notation) {
                version { prefer(b.prefer) }
                if (b.excludeLwjgl) exclude(group = "org.lwjgl")
            }
        }
    }

    // gson comes from Minecraft (2.10.x); vscript compiles against the 2.8.5 API deliberately.

    // Dev only: the Lua console of the debugging endpoint (unsandboxed, full Java access — never shipped).
    devLibraries("party.iroiro.luajava:luajava:4.1.0")
    devLibraries("party.iroiro.luajava:lua54:4.1.0")
    devLibraries("party.iroiro.luajava:lua54-platform:4.1.0:natives-desktop")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

/*
 * **The bundled libraries reach a dev run exactly as they reach a player: as jar-in-jar.** FML's userdev locator
 * only discovers classpath jars that are mods or declare `FMLModType`, so a plain library on the classpath —
 * Dear ImGui's binding, vscript's modules — is invisible to mod code; and the boot layer is no home for them
 * either, because Kotlin's stdlib lives in the GAME layer (KFF's) and a boot-layer vscript cannot see it. What
 * FML does read from a dev mod's classes is `META-INF/jarjar/metadata.json`, the same file it reads from a
 * shipped jar. `jarJar` generates that directory for the jar; copying it into the dev source set's resources
 * puts the nested jars into the GAME layer beside the stdlib, which is where they are in production too.
 */
/*
 * **A node may override a shared resource, and the node's copy wins.**
 *
 * Almost every one of the 386 resource files is version-neutral, so they live in `<root>/src/main/resources`
 * and every node ships the same bytes. The exceptions are files whose SCHEMA changed: the two rift shader
 * configs, whose `vertex`/`fragment` fields became resource locations resolved under `shaders/` at 1.21.2
 * where they had been bare names resolved under `shaders/core/`. No single spelling loads on both, and a
 * JSON cannot carry a `//? if` directive.
 *
 * So a node may put its own copy at `<node>/src/main/resources/...` and it takes precedence. The order is
 * what makes that work rather than a coin toss: Gradle's java plugin registers the node's own directory
 * first, Stonecutter appends the branch's, and the shared root is added last, so EXCLUDE keeps the most
 * specific one. Ordinary duplicates cannot arise -- nothing else is declared twice.
 */
tasks.named<ProcessResources>("processResources") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.named<ProcessResources>("processDevResources") {
    from(tasks.named("jarJar"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
// A plain `build` must leave the dev run launchable too: an IDE-started run reads build/resources/dev directly.
tasks.named("build") { dependsOn(tasks.named("processDevResources")) }

/*
 * The dev-only libraries (the Lua console) are plain Java and go on the boot layer of every run and the unit tests.
 * On that layer every jar is a module, and luajava finds its native through jnigen's
 * `SharedLibraryLoader.class.getResourceAsStream("/lua5464.dll")` — a lookup that, in a named module, only sees the
 * loader's own jar. So the loader, the binding and the natives are merged into one jar first.
 */
val devLuaBundle by tasks.registering(Jar::class) {
    archiveFileName = "luajava-dev-bundle.jar"
    destinationDirectory = layout.buildDirectory.dir("devlibs")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    inputs.files(devLibraries)
    from({ devLibraries.files.map { zipTree(it) } }) {
        exclude(
            "META-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA",
            "META-INF/versions/**", "module-info.class",
        )
    }
}

/*
 * Put that bundle on the legacy classpath of every run and of the unit tests.
 *
 * The obvious `tasks.withType<WriteLegacyClasspath>()` does not compile: MDG's task class is
 * package-private, so Kotlin cannot name it even though its `getEntries` is public. Groovy never noticed,
 * which is one of the few places this migration is a step sideways rather than forwards.
 *
 * `withGroovyBuilder` is the Kotlin DSL's sanctioned escape hatch for exactly this — a plugin type that is
 * real but not public — and it reaches the property the same way the Groovy script did. MDG does expose
 * public `clientLegacyClasspath`/`serverLegacyClasspath`/`gameTestServerLegacyClasspath` configurations
 * that would be cleaner, but nothing public feeds `writeNeoForgeTestClasspath`, and the unit tests need
 * this too: MDG puts the `dev` source set on the test runtime, and `Bpm`'s init starts the Lua console
 * reflectively whenever it is not in production. Narrowing that to three of the four tasks would be a
 * behaviour change discovered as a ClassNotFoundException in a test that happens to touch `Bpm`.
 *
 * All of this disappears at the Architectury Loom swap, where runs take `source("dev")` instead.
 */
tasks.matching {
    it.name.matches(Regex("write(Client|Server|GameTestServer)LegacyClasspath|writeNeoForgeTestClasspath"))
}.configureEach {
    dependsOn(devLuaBundle)
    @Suppress("UNCHECKED_CAST")
    val entries = withGroovyBuilder { getProperty("entries") } as ListProperty<String>
    entries.add(devLuaBundle.flatMap { it.archiveFile }.map { it.asFile.absolutePath })
}

configurations.configureEach {
    // vscript's modules were compiled by Kotlin 2.3.21 and declare that stdlib; KFF ships the game's. One
    // version everywhere, and it is the newer one, which the older compiler's output is guaranteed to run on.
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin" && requested.name.startsWith("kotlin-stdlib")) {
            useVersion(kotlinRuntimeVersion)
        }
    }
}

// The shipped jar carries `main` only.
tasks.named<Jar>("jar") { from(sourceSets.main.get().output) }

tasks.named<Test>("test") {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}
