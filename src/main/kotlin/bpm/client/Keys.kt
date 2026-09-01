package bpm.client

import bpm.net.KeyEdgePayload
import bpm.net.KeyWatchPayload
import bpm.world.KeyNames
import bpm.world.ModComponents
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.neoforged.neoforge.client.event.InputEvent
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent
import net.neoforged.neoforge.client.settings.KeyConflictContext
import bpm.platform.net.Net
import org.lwjgl.glfw.GLFW

/**
 * Raw keys, reported to the server only where a graph asked for them — `docs/DESIGN_PLAYER_LINK.md` §9.
 *
 * There are no bpm hotkey slots. A graph names the key it wants (`g`, `alt+w`), the server tells this client
 * which names to watch, and only those are ever reported. Watching nothing is the resting state, so a client
 * with no tether sends no key traffic at all — which is the difference between this and a keylogger.
 *
 * The one exception in the controls screen is [focus], which opens the panel, because a key that opens a UI
 * has to work before any graph is running to ask for it.
 */
object Keys {

    /** What the server asked us to watch, by canonical name. */
    private class Watch(val consumeAlways: Boolean, val consumeWithModifier: Boolean)

    private val watched = HashMap<String, Watch>()

    /** Canonical name → the key it resolves to, so the hot path does no string work. */
    private val resolved = HashMap<String, InputConstants.Key>()

    /** Which watched keys are down, for the suppression pass. */
    private val down = HashSet<String>()

    /** The panel key: the only bpm binding in the controls screen, and the modifier the graph nodes mean. */
    lateinit var focus: KeyMapping
        private set

    fun register(event: RegisterKeyMappingsEvent) {
        focus = KeyMapping("key.bpm.panel_focus", KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_ALT, CATEGORY)
        event.register(focus)
    }

    /** The server changed what it wants reported. */
    fun onWatch(p: KeyWatchPayload) {
        Minecraft.getInstance().execute {
            watched.clear()
            resolved.clear()
            down.clear()
            for (k in p.keys) {
                val name = KeyNames.normalise(k.name)
                if (name.isEmpty()) continue
                val key = runCatching { InputConstants.getKey(KeyNames.toInputName(name)) }.getOrNull() ?: continue
                if (key == InputConstants.UNKNOWN) continue
                watched[name] = Watch(k.consumeAlways, k.consumeWithModifier)
                resolved[name] = key
            }
        }
    }

    fun reset() {
        watched.clear()
        resolved.clear()
        down.clear()
    }

    /** True while the modifier is held — what a graph's `Modifier` pin means. */
    val modifierHeld: Boolean get() = ::focus.isInitialized && focus.isDown

    /**
     * A raw key moved. Only watched keys are reported, and never while a screen is open — otherwise typing a
     * `g` into chat would fire a graph.
     */
    fun onKey(event: InputEvent.Key) {
        if (watched.isEmpty()) return
        val mc = Minecraft.getInstance()
        if (mc.screen != null || mc.player == null || mc.connection == null) return
        if (event.action == GLFW.GLFW_REPEAT) return
        val name = resolved.entries.firstOrNull { (_, k) -> k.type == InputConstants.Type.KEYSYM && k.value == event.key }?.key ?: return
        if (!carryingBoundTether()) return
        val isDown = event.action == GLFW.GLFW_PRESS
        if (isDown) down.add(name) else down.remove(name)
        Net.sendToServer(KeyEdgePayload(name, isDown, modifierHeld))
    }

    /**
     * Before the game reads input for the tick, take back the keys a graph asked to swallow.
     *
     * `InputEvent.Key` cannot be cancelled, so the press has already reached the key mappings by the time we
     * see it. Clearing the mapping's own down-state before the tick that would act on it is what actually
     * stops `alt+W` walking you forward, and it is re-cleared every tick the key is still held.
     */
    fun tick() {
        val mc = Minecraft.getInstance()
        // The focus key is a tap, not a hold: it opens the panels with the cursor free.
        if (::focus.isInitialized) {
            while (focus.consumeClick()) {
                if (mc.screen == null) bpm.client.mc.PanelScreen.open()
            }
        }
        if (down.isEmpty() || mc.screen != null) return
        val mod = modifierHeld
        for (name in down) {
            val watch = watched[name] ?: continue
            if (!(watch.consumeAlways || (watch.consumeWithModifier && mod))) continue
            val key = resolved[name] ?: continue
            KeyMapping.set(key, false)
        }
    }

    /** Whether any stack this player holds is a tether bound to something — the cheap client-side gate. */
    private fun carryingBoundTether(): Boolean {
        val inv = Minecraft.getInstance().player?.inventory ?: return false
        for (i in 0 until inv.containerSize) {
            if (inv.getItem(i).has(ModComponents.TETHER_CONTROLLER.get())) return true
        }
        return false
    }

    private const val CATEGORY = "key.categories.bpm"
}
