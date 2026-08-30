package bpm

import net.neoforged.fml.ModLoadingContext
import net.neoforged.fml.config.ModConfig
import net.neoforged.neoforge.common.ModConfigSpec

/**
 * The tunables the mechanics design lists (`docs/DESIGN_MECHANICS.md` §11), as a server config. Read at use,
 * never at registration — a config is only loaded once the server (or the integrated one) starts.
 */
object BpmConfig {
    private val B = ModConfigSpec.Builder()

    val LINK_RANGE: ModConfigSpec.IntValue
    val RELAY_RANGE: ModConfigSpec.IntValue
    val GATE_OPEN_MINUTES: ModConfigSpec.IntValue
    val GATE_REQUIRE_CORNERS: ModConfigSpec.BooleanValue
    val CHAMBER_RESET_MINUTES: ModConfigSpec.IntValue
    val CHAMBER_UNBREAKABLE_WHILE_ALIVE: ModConfigSpec.BooleanValue
    val CHAMBER_RESET_ON_LEAVE: ModConfigSpec.BooleanValue
    val WARDEN_HEALTH: ModConfigSpec.DoubleValue
    val WARDEN_BEAM_DAMAGE: ModConfigSpec.DoubleValue
    val WARDEN_CAGE_MULTIPLIER: ModConfigSpec.DoubleValue
    val WARDEN_PLATE_HEALTH: ModConfigSpec.DoubleValue
    val WARDEN_STATE_HOLD_MINUTES: ModConfigSpec.IntValue
    val WARDEN_AUTOMATED_KILLS_CAN_ROLL: ModConfigSpec.BooleanValue
    val WARDEN_TRAIL_DAMAGE: ModConfigSpec.DoubleValue
    val TRAP_SPIKE_DAMAGE: ModConfigSpec.DoubleValue
    val TRAP_TURRET_DAMAGE: ModConfigSpec.DoubleValue
    val TRAP_TURRET_RANGE: ModConfigSpec.IntValue
    val TRAP_TURRET_TARGETS_PLAYERS_OVERWORLD: ModConfigSpec.BooleanValue
    val TRAP_TURRET_HEAL: ModConfigSpec.DoubleValue
    val EFFECTS_MAX_RIFTS: ModConfigSpec.IntValue
    val FLUID_INFINITE_SOURCES: ModConfigSpec.IntValue

    val SPEC: ModConfigSpec

    init {
        B.push("link")
        LINK_RANGE = B.comment("How far from a controller the linker (and controller.linkAt) may reach, in blocks").defineInRange("range", 32, 4, 256)
        B.pop()
        B.push("relay")
        RELAY_RANGE = B.defineInRange("range", 32, 4, 256)
        B.pop()
        B.push("gate")
        GATE_OPEN_MINUTES = B.comment("How long a lens keeps an overworld gate open").defineInRange("openMinutes", 10, 1, 600)
        GATE_REQUIRE_CORNERS = B.comment("Whether the four corners must be gate_frame_corner").define("requireCorners", false)
        B.pop()
        B.push("chamber")
        CHAMBER_RESET_MINUTES = B.comment("Minutes after a core claim before a player's chamber resets").defineInRange("resetMinutes", 20, 1, 1440)
        CHAMBER_UNBREAKABLE_WHILE_ALIVE = B.define("blocksUnbreakableWhileAlive", true)
        CHAMBER_RESET_ON_LEAVE = B.comment("Whether a room resets, and the gate it was entered through closes (another lens to open it), as soon as its last player leaves or dies").define("resetOnLeave", true)
        B.pop()
        B.push("warden")
        WARDEN_HEALTH = B.defineInRange("health", 500.0, 1.0, 10000.0)
        WARDEN_BEAM_DAMAGE = B.defineInRange("beamDamage", 6.0, 0.0, 100.0)
        WARDEN_CAGE_MULTIPLIER = B.comment("Damage taken while the cage is closed").defineInRange("cageDamageMultiplier", 0.25, 0.0, 1.0)
        WARDEN_PLATE_HEALTH = B.defineInRange("plateHealth", 12.0, 1.0, 1000.0)
        WARDEN_STATE_HOLD_MINUTES = B.comment("How long a fight survives everyone leaving").defineInRange("stateHoldMinutes", 5, 0, 600)
        WARDEN_AUTOMATED_KILLS_CAN_ROLL = B.comment("Whether a kill with no player damage rolls the rare and unique tables").define("automatedKillsCanRoll", false)
        WARDEN_TRAIL_DAMAGE = B.comment("Damage to whoever steps into the decohered floor a grounded Warden leaves behind").defineInRange("trailDamage", 8.0, 0.0, 100.0)
        B.pop()
        B.push("trap")
        TRAP_SPIKE_DAMAGE = B.defineInRange("spikeDamage", 6.0, 0.0, 100.0)
        TRAP_TURRET_DAMAGE = B.defineInRange("turretDamage", 4.0, 0.0, 100.0)
        TRAP_TURRET_RANGE = B.defineInRange("turretRange", 24, 1, 64)
        TRAP_TURRET_TARGETS_PLAYERS_OVERWORLD = B.define("turretTargetsPlayersOverworld", false)
        TRAP_TURRET_HEAL = B.comment("Health a turret bolt gives a grounded Warden when no player is in its sights").defineInRange("turretHeal", 2.0, 0.0, 100.0)
        B.pop()
        B.push("effects")
        EFFECTS_MAX_RIFTS = B.defineInRange("maxRifts", 24, 0, 256)
        B.pop()
        B.push("fluids")
        FLUID_INFINITE_SOURCES = B.comment(
            "A body of liquid with at least this many connected source blocks is treated as bottomless:",
            "fluids.pickup fills from it without taking the block. An ocean or a nether lava sea passes;",
            "a pond does not. Set to 0 to turn it off and always consume the source.",
        ).defineInRange("infiniteSources", 10000, 0, 1000000)
        B.pop()
        SPEC = B.build()
    }

    fun register() {
        ModLoadingContext.get().activeContainer.registerConfig(ModConfig.Type.SERVER, SPEC)
    }

    /** A value, or its default before the config has loaded (registration time, unit tests). */
    fun <T : Any> ModConfigSpec.ConfigValue<T>.orDefault(): T = if (SPEC.isLoaded) get() else default
}
