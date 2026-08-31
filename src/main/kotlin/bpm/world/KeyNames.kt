package bpm.world

/**
 * Key names as the graph writes them and the wire carries them.
 *
 * A graph names an actual key — `g`, `f7`, `left_shift` — rather than one of a fixed set of slots the player
 * has to bind first. That means the *name* has to travel, because `InputConstants` is client-only: the server
 * never resolves a key to a code, it only ever compares these strings.
 *
 * Canonical form is bare and lowercase, with underscores: `g`, `f7`, `left_shift`, `space`. `key.keyboard.g`
 * and `G` and `Left Shift` all normalise to it, so a graph author can write whichever they expect to work.
 */
object KeyNames {
    private const val KEYBOARD = "key.keyboard."
    private const val MOUSE = "key.mouse."

    /** The canonical name for whatever was typed, or empty when it is not a key at all. */
    fun normalise(text: String?): String {
        var s = text?.trim()?.lowercase() ?: return ""
        if (s.isEmpty()) return ""
        if (s.startsWith(KEYBOARD)) s = s.removePrefix(KEYBOARD)
        else if (s.startsWith(MOUSE)) return MOUSE + s.removePrefix(MOUSE)
        s = s.replace(' ', '_').replace('-', '_')
        return if (s.all { it.isLetterOrDigit() || it == '_' || it == '.' }) s else ""
    }

    /** What the client hands to `InputConstants.getKey`. Mouse names already carry their own prefix. */
    fun toInputName(name: String): String = if (name.startsWith(MOUSE)) name else KEYBOARD + name

    /** How a name reads in a tooltip: `left_shift` → `Left Shift`. */
    fun label(name: String): String =
        name.removePrefix(MOUSE).split('_').joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }

    /** At most this many keys one player's client is asked to watch, across every controller. */
    const val MAX_WATCHED = 32
}
