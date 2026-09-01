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
val fabricApiVersion = property("fabric_api_version") as String
val flkVersion = property("flk_version") as String
val geckolibVersion = property("geckolib_version") as String
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
    accessWidenerPath = rootProject.file("src/fabric/resources/bpm.accesswidener")
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
 */
kotlin.sourceSets.named("core") {
    kotlin.setSrcDirs(listOf(rootProject.file("src/core/kotlin")))
}
sourceSets.named("core") {
    resources.setSrcDirs(listOf(rootProject.file("src/core/resources")))
}
kotlin.sourceSets.named("main") {
    kotlin.setSrcDirs(listOf(rootProject.file("src/main/kotlin"), rootProject.file("src/fabric/kotlin")))
}
sourceSets.named("main") {
    resources.setSrcDirs(listOf(rootProject.file("src/main/resources"), rootProject.file("src/fabric/resources")))
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
        named("client") { runDir("../../../run-fabric") }
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
    // JEI's API on the compile path only, exactly as on NeoForge: a pack without JEI never loads the
    // plugin class, because @JeiPlugin is read by JEI itself.
    modCompileOnly("mezz.jei:jei-$minecraftVersion-fabric-api:$jeiVersion")
    // Ponder, for the in-game scene tutorials. Compile path only, as on NeoForge.
    //
    // NOT Ponder-Common as well: that jar carries a second copy of catnip's PlatformHelper interface,
    // and having both on a runtime classpath is what made the NeoForge client hang on the loading bar.
    // One jar, the platform one, on both loaders.
    modCompileOnly("net.createmod.ponder:Ponder-Fabric-$minecraftVersion:$ponderVersion") { isTransitive = false }

    for (m in listOf("vscript", "vscript-runtime", "vscript-ui", "vscript-runview", "editor-host", "editor-graph")) {
        implementation("dev.ziggle:$m:$vscriptVersion")
        "coreImplementation"("dev.ziggle:$m:$vscriptVersion")
    }
    implementation("io.github.spair:imgui-java-binding:$imguiVersion")
    // The GL3 backend and the JNI natives are the host's to supply, and this mod is the host on both
    // loaders. LWJGL is excluded because the game supplies its own and two copies do not coexist.
    implementation("io.github.spair:imgui-java-lwjgl3:$imguiVersion") { exclude(group = "org.lwjgl") }
    "coreImplementation"("io.github.spair:imgui-java-binding:$imguiVersion")
    "coreCompileOnly"("com.google.code.gson:gson:2.10.1")
}
