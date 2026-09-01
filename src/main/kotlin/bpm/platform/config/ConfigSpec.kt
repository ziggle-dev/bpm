package bpm.platform.config

import java.nio.file.Files
import java.nio.file.Path

/**
 * A server config, in the shape `BpmConfig` already used.
 *
 * NeoForge's `ModConfigSpec` is a good design and this copies the part of it the mod actually touches —
 * `comment`, `push`/`pop`, `define`, `defineInRange`, `build`, `isLoaded`, and values that answer `get()`
 * or `default`. That is deliberate: it means `BpmConfig`'s hundred-odd lines of declarations change only
 * in their import and their type names, and the tunables, ranges and comments stay exactly where a
 * reviewer can see them.
 *
 * The TOML it writes matches NightConfig's layout — `[section]` headers, `key = value`, `#` comments —
 * because an existing `config/bpm-server.toml` has to keep working. Somebody has hand-edited theirs; a
 * config that silently reset a server's Warden health on update would be a genuinely bad failure.
 */
class ConfigSpec private constructor(private val entries: List<Entry<*>>) {

    var isLoaded: Boolean = false
        private set

    abstract class Value<T : Any>(val path: List<String>, val default: T) {
        internal var current: T = default
        fun get(): T = current
    }

    class BoolValue(path: List<String>, default: Boolean) : Value<Boolean>(path, default)
    class IntValue(path: List<String>, default: Int, val min: Int, val max: Int) : Value<Int>(path, default)
    class DoubleValue(path: List<String>, default: Double, val min: Double, val max: Double) : Value<Double>(path, default)

    internal class Entry<T : Any>(val value: Value<T>, val comment: List<String>)

    class Builder {
        private val stack = ArrayList<String>()
        private val entries = ArrayList<Entry<*>>()
        private var pending = ArrayList<String>()

        fun comment(vararg lines: String): Builder {
            pending.addAll(lines)
            return this
        }

        fun push(name: String): Builder {
            stack += name
            return this
        }

        fun pop(): Builder {
            stack.removeAt(stack.size - 1)
            return this
        }

        private fun <T : Any, V : Value<T>> add(value: V): V {
            entries += Entry(value, pending)
            pending = ArrayList()
            return value
        }

        fun define(name: String, default: Boolean): BoolValue = add(BoolValue(stack + name, default))

        fun defineInRange(name: String, default: Int, min: Int, max: Int): IntValue =
            add(IntValue(stack + name, default, min, max))

        fun defineInRange(name: String, default: Double, min: Double, max: Double): DoubleValue =
            add(DoubleValue(stack + name, default, min, max))

        fun build(): ConfigSpec = ConfigSpec(entries.toList())
    }

    /**
     * Read [path], fill in anything missing from the defaults, and write it back with its comments.
     *
     * Writing back on every load is what keeps a config file current when new keys are added, and it is
     * what NightConfig does too. A value outside its range is clamped rather than rejected: a server
     * owner who typed 99999 wants the biggest allowed, not a stack trace on boot.
     */
    fun load(path: Path) {
        val existing = if (Files.exists(path)) parse(Files.readString(path)) else emptyMap()
        for (entry in entries) {
            val key = entry.value.path.joinToString(".")
            val raw = existing[key]
            when (val v = entry.value) {
                is BoolValue -> v.current = raw?.toBooleanStrictOrNull() ?: v.default
                is IntValue -> v.current = (raw?.toIntOrNull() ?: v.default).coerceIn(v.min, v.max)
                is DoubleValue -> v.current = (raw?.toDoubleOrNull() ?: v.default).coerceIn(v.min, v.max)
            }
        }
        isLoaded = true
        path.parent?.let { Files.createDirectories(it) }
        Files.writeString(path, render())
    }

    /** The file this spec would write for its current values. Public so a test can round-trip it. */
    fun render(): String = buildString {
        var section = emptyList<String>()
        for (entry in entries) {
            val here = entry.value.path.dropLast(1)
            if (here != section) {
                if (isNotEmpty()) append('\n')
                if (here.isNotEmpty()) append("[").append(here.joinToString(".")).append("]\n")
                section = here
            }
            for (line in entry.comment) append("\t#").append(line).append('\n')
            val v = entry.value
            val range = when (v) {
                is IntValue -> "\t#Range: ${v.min} ~ ${v.max}\n"
                is DoubleValue -> "\t#Range: ${v.min} ~ ${v.max}\n"
                else -> ""
            }
            append(range)
            append('\t').append(entry.value.path.last()).append(" = ").append(literal(v.current)).append('\n')
        }
    }

    private fun literal(value: Any): String = when (value) {
        is Boolean -> value.toString()
        is Int -> value.toString()
        is Double -> if (value == Math.floor(value) && !value.isInfinite()) "%.1f".format(value) else value.toString()
        else -> value.toString()
    }

    /** Flattens `[a.b]` sections and `key = value` lines into `a.b.key -> value`. Comments and blanks ignored. */
    private fun parse(text: String): Map<String, String> {
        val out = HashMap<String, String>()
        var section = ""
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length - 1).trim()
                continue
            }
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim()
            val value = line.substring(eq + 1).trim().removeSurrounding("\"")
            out[if (section.isEmpty()) key else "$section.$key"] = value
        }
        return out
    }
}
