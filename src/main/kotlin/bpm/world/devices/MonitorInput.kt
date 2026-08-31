package bpm.world.devices

import net.minecraft.core.BlockPos
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import java.util.function.Consumer

/**
 * Working what a monitor shows, by left-clicking it.
 *
 * **Left click, and it never takes mining away.** A click is consumed only when it lands on something a
 * person can actually work — a Button, Toggle, Slider or Field; anywhere else on the glass falls straight
 * through and breaks the block as it always did. Sneaking always mines, so a wall covered in controls is
 * never a wall you cannot take down. Right-click is left alone: it still toggles the screen.
 *
 * Who does what:
 *  - **Button, Toggle, Slider (first click)** — the server does it, off the same event. It has the player's
 *    look ray, so it can find the widget itself and never has to believe a client about what was pressed.
 *  - **Slider (dragging)** — only the client sees a held mouse (`CLIENT_HOLD`), so it streams the position
 *    and the server re-checks the widget and clamps it. See [bpm.net.MonitorDragPayload].
 *  - **Field** — the world has no way to type into a block, so the client opens a box; the string comes back
 *    as [bpm.net.MonitorTextPayload].
 *
 * The same hit-test runs on both sides, because the client already holds the widgets it draws. That is what
 * lets it cancel the click before the break animation starts while the server independently agrees.
 */
object MonitorInput {

    fun install(bus: IEventBus) {
        bus.addListener(PlayerInteractEvent.LeftClickBlock::class.java, Consumer(::onLeftClick))
    }

    private fun onLeftClick(event: PlayerInteractEvent.LeftClickBlock) {
        val player = event.entity
        if (player.isShiftKeyDown) return
        val action = event.action
        val start = action == PlayerInteractEvent.LeftClickBlock.Action.START
        val holding = action == PlayerInteractEvent.LeftClickBlock.Action.CLIENT_HOLD
        if (!start && !holding) return

        val found = hit(player, event.pos) ?: return
        val (origin, f) = found
        val widget = f.widget

        // A held mouse must not press a button over and over; only a slider keeps answering.
        if (holding && !Widget.isDraggable(widget.kind)) {
            event.isCanceled = true
            return
        }

        if (player.level().isClientSide) {
            if (holding) bpm.client.mc.MonitorDrag.send(origin.blockPos, widget.id, f.along.toFloat())
            if (start && widget.kind == Widget.FIELD) bpm.client.mc.MonitorDrag.editField(origin.blockPos, widget)
        } else if (start) {
            when (widget.kind) {
                Widget.TOGGLE -> {
                    origin.setValue(widget.id, if (origin.valueOf(widget.id) >= 0.5) 0.0 else 1.0)
                    origin.press(widget.id)
                }
                Widget.SLIDER -> {
                    origin.setValue(widget.id, f.along * widget.max)
                    origin.press(widget.id)
                }
                // A field is not set by the click itself; the box the client opens sends the text.
                Widget.FIELD -> Unit
                else -> origin.press(widget.id)
            }
            if (widget.kind != Widget.FIELD) {
                player.level().playSound(
                    null, event.pos, net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(),
                    net.minecraft.sounds.SoundSource.BLOCKS, 0.3f, 1.6f,
                )
            }
        }
        event.isCanceled = true
    }

    /**
     * The wall's origin and what is under the crosshair, or null when the click should mine instead.
     *
     * Public so the payload handlers can re-run exactly the same check against the player who sent them: a
     * drag or a typed string is only believed if that player really is looking at that widget.
     */
    fun hit(player: Player, pos: BlockPos): Pair<MonitorBlockEntity, MonitorHit.Found>? {
        val level = player.level()
        val state = level.getBlockState(pos)
        if (state.block !is MonitorBlock) return null
        val origin = level.getBlockEntity(MonitorWall.originOf(level, pos)) as? MonitorBlockEntity ?: return null
        if (origin.widgets.isEmpty() || !origin.on) return null

        val ray = player.pick(REACH, 0f, false) as? BlockHitResult ?: return null
        if (ray.type != HitResult.Type.BLOCK || ray.blockPos != pos) return null

        val facing = state.getValue(MonitorBlock.FACING)
        // Only the face the screen is on; the sides and back of a monitor are just a block.
        if (ray.direction != facing) return null

        val (w, h) = MonitorWall.sizeOf(level, origin.blockPos)
        val (tileAcross, tileDown) = MonitorHit.tileOffset(facing, origin.blockPos, pos)
        val p = ray.location
        val (across, down) = MonitorHit.inTile(facing, pos, p.x, p.y, p.z)
        val found = MonitorHit.widgetAt(origin.widgets, w, h, MonitorHit.point(tileAcross, tileDown, across, down))
            ?: return null
        if (found.widget.id.isEmpty()) return null
        return origin to found
    }

    /** The wall the player is looking at, whatever tile of it, for a payload naming the origin. */
    fun originLookedAt(player: Player, origin: BlockPos): MonitorBlockEntity? {
        val level = player.level()
        if (!origin.closerThan(player.blockPosition(), REACH + 2)) return null
        return level.getBlockEntity(origin) as? MonitorBlockEntity
    }

    /** Far enough to cover any reach a player can click a block from. */
    const val REACH = 8.0
}
