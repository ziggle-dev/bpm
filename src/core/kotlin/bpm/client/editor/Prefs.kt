package bpm.client.editor

/**
 * What the workbench remembers between openings — layout and taste, never document state. The host gives it a
 * file; the panels read and write through it.
 */
interface Prefs {
    fun getString(key: String, default: String): String
    fun putString(key: String, value: String)

    fun getFloat(key: String, default: Float): Float = getString(key, "").toFloatOrNull() ?: default
    fun putFloat(key: String, value: Float) = putString(key, value.toString())
    fun getBool(key: String, default: Boolean): Boolean = getString(key, "").toBooleanStrictOrNull() ?: default
    fun putBool(key: String, value: Boolean) = putString(key, value.toString())
    fun getInt(key: String, default: Int): Int = getString(key, "").toIntOrNull() ?: default
    fun putInt(key: String, value: Int) = putString(key, value.toString())

    /** Forgets everything (tests). */
    object Memory : Prefs {
        private val map = HashMap<String, String>()
        override fun getString(key: String, default: String): String = map[key] ?: default
        override fun putString(key: String, value: String) {
            map[key] = value
        }
    }
}
