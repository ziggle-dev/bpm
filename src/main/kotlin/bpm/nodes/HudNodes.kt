package bpm.nodes

import bpm.catalog.McVs
import bpm.catalog.values.WidgetValue
import bpm.runtime.HudPanels
import bpm.runtime.PredicateJob
import bpm.world.Grant
import bpm.world.devices.Widget
import io.osrsx.vscript.nodes.Contribution
import io.osrsx.vscript.nodes.library
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player

/**
 * `hud.*` — a 2D panel the controller draws on a tethered player's own screen.
 *
 * The same [Widget] records a monitor shows, rendered by the client with ImGui instead of on a wall of blocks,
 * plus two kinds only a screen can offer: a `Button` and a `Toggle`, which the graph reads back with
 * [hud.clicked] and [hud.value]. That closes the loop with no new event machinery — a controller draws a
 * control panel on your screen, you press it, and a fiber parked on `hud.waitForClick` wakes up.
 *
 * Drawing needs the tether's `hud` grant, reading presses needs `input`. See `docs/DESIGN_PLAYER_LINK.md` §10.
 */
object HudNodes {

    fun contribution(host: ControllerHost): Contribution = library("hud", "Panel") {

        // ---- the two kinds a monitor cannot show ----------------------------------------------------

        func("button") {
            title("Button Widget")
            doc("A button for a panel. `Id` is how `Clicked` and `Wait For Click` find it again, so give each one its own.")
            val id = param("Id", McVs.string, "a name for this button")
            val label = param("Label", McVs.string, "what it says")
            val colour = param("Colour", McVs.string, "a colour name or #rrggbb; empty for the theme's", default = "")
            val span = param("Span", McVs.int, "columns it takes; 0 for the whole row", default = 0L)
            result("Widget", McVs.widget)
            query { WidgetValue.of(Widget(Widget.BUTTON, id = id(), label = label(), colour = colour(), span = span().toInt().coerceIn(0, 8))) }
        }
        func("toggle") {
            title("Toggle Widget")
            doc("A switch for a panel, on or off. The player's answer comes back from `Value`; `On` is only where it starts.")
            val id = param("Id", McVs.string, "a name for this toggle")
            val label = param("Label", McVs.string, "what it says")
            val on = param("On", McVs.bool, "where it starts", default = false)
            val span = param("Span", McVs.int, "columns it takes; 0 for the whole row", default = 0L)
            result("Widget", McVs.widget)
            query { WidgetValue.of(Widget(Widget.TOGGLE, id = id(), label = label(), value = if (on()) 1.0 else 0.0, span = span().toInt().coerceIn(0, 8))) }
        }

        func("slider") {
            title("Slider Widget")
            doc("A slider for a panel, running 0 to Max. `Value` is only where it starts; where it stands afterwards comes back from Value, because the person owns it once it is on their screen. Click along it or drag it.")
            val id = param("Id", McVs.string, "a name for this slider")
            val label = param("Label", McVs.string, "what it says")
            val value = param("Value", McVs.float, "where it starts", default = 0.0)
            val max = param("Max", McVs.float, "the far end", default = 100.0)
            val unit = param("Unit", McVs.string, "after the number", default = "")
            val colour = param("Colour", McVs.string, "a colour name or #rrggbb; empty for teal", default = "")
            val span = param("Span", McVs.int, "columns it takes; 0 for the whole row", default = 0L)
            result("Widget", McVs.widget)
            query {
                WidgetValue.of(
                    Widget(
                        Widget.SLIDER, id = id(), label = label(), value = value(), max = max().coerceAtLeast(0.0001),
                        unit = unit(), colour = colour(), span = span().toInt().coerceIn(0, 8),
                    ),
                )
            }
        }
        func("field") {
            title("Text Field Widget")
            doc("A line of text on a panel that the player can change: clicking it opens a box to type in. `Text` is only what it starts with; what it holds afterwards comes back from Text Of.")
            val id = param("Id", McVs.string, "a name for this field")
            val text = param("Text", McVs.string, "what it starts with", default = "")
            val label = param("Label", McVs.string, "shown while it is empty", default = "")
            val span = param("Span", McVs.int, "columns it takes; 0 for the whole row", default = 0L)
            result("Widget", McVs.widget)
            query { WidgetValue.of(Widget(Widget.FIELD, id = id(), text = text(), label = label(), span = span().toInt().coerceIn(0, 8))) }
        }

        // ---- putting it on their screen --------------------------------------------------------------

        func("show") {
            title("Show Panel")
            doc(
                """
                Put a list of widgets on a tethered player's screen, replacing whatever this controller had
                there. Needs their tether's `hud` grant. Safe to call every tick: an unchanged panel is not
                sent, and a changed one at most every other tick.

                This is a HEARTBEAT, not a one-shot. Stop calling it and the panel comes down after Timeout
                Ticks — a graph that stopped drawing should not leave its last frame on someone's screen
                looking live. 0 keeps it up until something replaces it.
                """,
            )
            val player = param("Player", McVs.player, "whose screen")
            val widgets = param("Widgets", McVs.widget.list(), "what to show")
            val anchor = param("Anchor", McVs.anchor, "which corner it hangs from", default = "TopRight")
            val offsetX = param("Offset X", McVs.int, "pixels in from that corner", default = 8L)
            val offsetY = param("Offset Y", McVs.int, "pixels down from that corner", default = 8L)
            val width = param("Width", McVs.int, "how wide, in pixels", default = 150L)
            val scale = param("Scale", McVs.float, "1 is the game's own GUI scale", default = 1.0)
            val timeout = param("Timeout Ticks", McVs.int, "come down if nothing refreshes it for this many ticks; 0 to stay", default = 20L)
            result("Shown", McVs.bool)
            command {
                val p = granted(host, player(), Grant.HUD, "show") ?: return@command false
                val list = (widgets() as? List<*>)?.mapNotNull { WidgetValue.toWidget(it) }?.take(Widget.MAX_WIDGETS).orEmpty()
                host.showPanel(
                    p, list, anchor(), offsetX().toInt(), offsetY().toInt(),
                    width().toInt().coerceIn(48, 512), scale().coerceIn(0.5, 3.0), timeout().toInt(),
                )
                true
            }
        }
        func("clear") {
            title("Clear Panel")
            doc("Take this controller's panel off a player's screen. Answers whether there was one.")
            val player = param("Player", McVs.player, "whose screen")
            result("Cleared", McVs.bool)
            command {
                val p = (host.entity(player()) as? Player) ?: return@command false
                host.clearPanel(p.uuid)
            }
        }
        func("visible") {
            title("Panel Is Visible")
            doc("Whether the panel is actually on their screen — a player may hide every bpm panel, and a graph that draws a warning should know it was not read.")
            val player = param("Player", McVs.player, "whose screen")
            result("Visible", McVs.bool)
            query {
                val p = (host.entity(player()) as? Player) ?: return@query false
                host.panelVisible(p.uuid)
            }
        }

        // ---- what they pressed -------------------------------------------------------------------------

        func("clicked") {
            title("Button Clicked")
            doc("Whether a panel button was pressed since this was last asked, and takes the press. Needs the tether's `input` grant. The press is taken by whoever asks first, so ask in one place.")
            val player = param("Player", McVs.player, "whose screen")
            val id = param("Id", McVs.string, "the button's id")
            result("Clicked", McVs.bool)
            query {
                val p = granted(host, player(), Grant.INPUT, "clicked") ?: return@query false
                host.takePanelPress(p.uuid, id())
            }
        }
        func("value") {
            title("Panel Value")
            doc("Where a panel toggle stands right now: 1 for on, 0 for off. Needs the tether's `input` grant.")
            val player = param("Player", McVs.player, "whose screen")
            val id = param("Id", McVs.string, "the widget's id")
            val value = result("Value", McVs.float)
            val on = result("On", McVs.bool)
            query {
                value set 0.0
                on set false
                val p = granted(host, player(), Grant.INPUT, "value") ?: return@query null
                val v = host.panelValue(p.uuid, id())
                value set v
                on set (v >= 0.5)
                null
            }
        }
        func("textOf") {
            title("Panel Text")
            doc("What the player typed into a panel's text field. Empty until they have. Needs the tether's `input` grant.")
            val player = param("Player", McVs.player, "whose screen")
            val id = param("Id", McVs.string, "the field's id")
            result("Text", McVs.string)
            query {
                val p = granted(host, player(), Grant.INPUT, "textOf") ?: return@query ""
                host.panelText(p.uuid, id())
            }
        }
        func("waitForClick") {
            title("Wait For Click")
            doc(
                """
                Park until a panel button is pressed, or the timeout passes.

                **Exec carries on either way; `Ok` is what tells them apart.** Leave Timeout Ticks at 0 for a
                button you mean to wait on, or whatever runs next will fire on the timer as well as on the
                press. A branch of the graph can sit here indefinitely, which is how a panel becomes a
                control surface rather than a readout.
                """,
            )
            val player = param("Player", McVs.player, "whose screen")
            val id = param("Id", McVs.string, "the button's id")
            val timeout = param("Timeout Ticks", McVs.int, "give up after this many ticks; 0 = never", default = 0L)
            result("Ok", McVs.bool)
            action {
                val handle = player()
                val want = id()
                host.jobs.start(
                    PredicateJob("hud.waitForClick", timeout().toInt()) {
                        val link = (host.entity(handle) as? Player)?.let { host.presence(it.uuid) }
                        val p = link?.takeIf { it.mayI(Grant.INPUT) }?.player
                        p != null && host.takePanelPress(p.uuid, want)
                    },
                )
            }
        }
    }

    /** The live player behind a handle, but only if their tether is open for [grant]; one line in the console otherwise. */
    private fun granted(host: ControllerHost, handle: Any?, grant: Grant, verb: String): ServerPlayer? {
        val p = host.entity(handle) as? Player ?: return null
        val link = host.presence(p.uuid) ?: run {
            host.warnOnce("hud/${p.uuid}/tether", "'${p.name.string}' is not tethered to this controller")
            return null
        }
        val why = link.reason(grant)
        if (why != null) {
            host.warnOnce("hud/${p.uuid}/$verb", "hud.$verb on '${p.name.string}': $why")
            return null
        }
        return link.player
    }

    @Suppress("unused")
    private val maxPanels = HudPanels.MAX_PER_PLAYER
}
