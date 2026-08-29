package bpm.client.mc

import bpm.Bpm
import bpm.client.editor.BlockPreviews
import bpm.client.editor.ItemIcons
import bpm.client.editor.LinkView
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.VertexSorting
import io.osrsx.vscript.editor.host.IconRef
import io.osrsx.vscript.editor.host.IconRegion
import io.osrsx.vscript.editor.host.IconSource
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import org.joml.Matrix4f

/**
 * Renders items into small off-screen textures the editor can show: the block a link points at, the
 * controller's buffer, the value picker's icons. Each is the picture the inventory would draw, at 6×.
 *
 * Asked for during a frame ([want]/[region]), rendered on the render thread *before* the next ImGui frame
 * ([renderPending]) — so a picture appears the frame after it was first wanted — and kept for a few seconds
 * after it was last wanted. One texture per item id.
 */
object BlockPreviewRenderer : BlockPreviews, ItemIcons, IconSource {
    private class Slot(val target: TextureTarget) {
        var rendered = false
        var wantedAt = 0L
        var label = ""
    }

    private val slots = HashMap<String, Slot>()
    private val wanted = LinkedHashSet<String>()
    private val blockAt = HashMap<String, String>()
    private val blockLabel = HashMap<String, String>()

    private fun key(l: LinkView) = "${l.dimension}:${l.x},${l.y},${l.z}"

    // ---- blocks at links -------------------------------------------------------------------------------------

    override fun want(link: LinkView) {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        if (level.dimension().location().toString() != link.dimension) return
        val pos = BlockPos(link.x, link.y, link.z)
        if (!level.isLoaded(pos)) return
        val state = level.getBlockState(pos)
        val id = BuiltInRegistries.ITEM.getKey(state.block.asItem()).toString()
        blockAt[key(link)] = id
        blockLabel[key(link)] = state.block.name.string
        want(id)
    }

    override fun region(link: LinkView): IconRegion? = blockAt[key(link)]?.let { region(it) }

    override fun labelOf(link: LinkView): String? = blockLabel[key(link)]

    // ---- items by id -----------------------------------------------------------------------------------------

    override fun want(itemId: String) {
        if (itemId.isEmpty()) return
        wanted.add(itemId)
        slots[itemId]?.wantedAt = System.currentTimeMillis()
    }

    override fun region(itemId: String): IconRegion? {
        val slot = slots[itemId] ?: run { want(itemId); return null }
        slot.wantedAt = System.currentTimeMillis()
        if (!slot.rendered) return null
        // A frame buffer's texture is upside down for a GUI: flip V.
        return IconRegion(slot.target.colorTextureId, 0f, 1f, 1f, 0f)
    }

    override fun labelOf(itemId: String): String? = slots[itemId]?.label?.ifEmpty { null }
        ?: ResourceLocation.tryParse(itemId)?.let { BuiltInRegistries.ITEM.getOptional(it).orElse(null)?.description?.string }

    // ---- vscript's icon source: the value picker's rows and previews --------------------------------------------

    override fun texture(ref: IconRef): Int? = region(ref)?.texture

    override fun region(ref: IconRef): IconRegion? = (ref as? RegistryIcon)?.let { region(it.id) }

    /** Called once per frame before ImGui draws: renders what was asked for, drops what nobody wants. */
    fun renderPending() {
        val mc = Minecraft.getInstance()
        val now = System.currentTimeMillis()
        var budget = PER_FRAME
        val it = wanted.iterator()
        while (it.hasNext() && budget > 0) {
            val id = it.next()
            it.remove()
            val slot = slots.getOrPut(id) { Slot(TextureTarget(SIZE, SIZE, true, Minecraft.ON_OSX)) }
            slot.wantedAt = now
            if (slot.rendered) continue
            val item = ResourceLocation.tryParse(id)?.let { rl -> BuiltInRegistries.ITEM.getOptional(rl).orElse(null) } ?: continue
            val stack = ItemStack(item)
            slot.label = stack.hoverName.string
            runCatching { render(mc, slot, stack) }
                .onSuccess { slot.rendered = true }
                .onFailure { Bpm.LOGGER.warn("item picture failed for {}: {}", id, it.toString()) }
            budget--
        }
        val gone = slots.entries.iterator()
        while (gone.hasNext()) {
            val e = gone.next()
            if (now - e.value.wantedAt > KEEP_MS) {
                e.value.target.destroyBuffers()
                gone.remove()
            }
        }
    }

    private fun render(mc: Minecraft, slot: Slot, stack: ItemStack) {
        val target = slot.target
        target.setClearColor(0f, 0f, 0f, 0f)
        target.clear(Minecraft.ON_OSX)
        target.bindWrite(true)
        RenderSystem.backupProjectionMatrix()
        // A 16-unit GUI space with the GUI's own depth range: the model-view the game has set for screens
        // pushes everything back by 11000, so anything nearer than 1000 would be clipped away.
        RenderSystem.setProjectionMatrix(Matrix4f().setOrtho(0f, 16f, 16f, 0f, 1000f, 21000f), VertexSorting.ORTHOGRAPHIC_Z)
        val graphics = GuiGraphics(mc, mc.renderBuffers().bufferSource())
        try {
            graphics.renderItem(stack, 0, 0)
        } finally {
            graphics.flush()
            RenderSystem.restoreProjectionMatrix()
            target.unbindWrite()
            mc.mainRenderTarget.bindWrite(true)
        }
    }

    fun clear() {
        for (s in slots.values) s.target.destroyBuffers()
        slots.clear()
        wanted.clear()
        blockAt.clear()
        blockLabel.clear()
    }

    private const val SIZE = 96
    private const val KEEP_MS = 8_000L
    private const val PER_FRAME = 8
}
