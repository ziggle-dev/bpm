package bpm.client.mc

import bpm.Bpm
import bpm.client.editor.BlockPreviews
import bpm.client.editor.ItemIcons
import bpm.client.editor.LinkView
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.VertexSorting
import dev.ziggle.vscript.editor.host.IconRef
import dev.ziggle.vscript.editor.host.IconRegion
import dev.ziggle.vscript.editor.host.IconSource
import net.minecraft.client.Minecraft
import com.mojang.blaze3d.vertex.ByteBufferBuilder
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.MultiBufferSource
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

    // ---- faces of the people tethered to the controller -------------------------------------------------------

    /**
     * The head off a player's skin: the 8x8 face at (8,8) of the 64x64 sheet, as a region over the skin
     * texture itself. No off-screen render needed — unlike a block, a face is already a picture.
     *
     * The skin comes from the player when the client can see them, and from the uuid's default skin when it
     * cannot, so a row for someone across the world or logged out still shows a face rather than a hole.
     */
    override fun head(playerId: String): IconRegion? {
        val uuid = runCatching { java.util.UUID.fromString(playerId) }.getOrNull() ?: return null
        val mc = Minecraft.getInstance()
        val skin = mc.level?.players()?.firstOrNull { it.uuid == uuid }?.skin
            ?: net.minecraft.client.resources.DefaultPlayerSkin.get(uuid)
        // Widened where it is produced, not where it is drawn: IconRegion takes a Long because from
        // 1.21.6 the game stops handing out GL names at all. Today this is still one, so it widens here.
        val id = mc.textureManager.getTexture(skin.texture()).id.toLong()
        // 8/64 .. 16/64 — the face; the hat layer at 40/64 is left off, it reads as noise at this size.
        return IconRegion(id, 0.125f, 0.125f, 0.25f, 0.25f)
    }

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
        return IconRegion(slot.target.colorTextureId.toLong(), 0f, 1f, 1f, 0f)
    }

    override fun labelOf(itemId: String): String? = slots[itemId]?.label?.ifEmpty { null }
        ?: ResourceLocation.tryParse(itemId)?.let { bpm.platform.valueOf(BuiltInRegistries.ITEM, it)?.let { item -> net.minecraft.network.chat.Component.translatable(item.descriptionId).string } }

    // ---- vscript's icon source: the value picker's rows and previews --------------------------------------------

    override fun texture(ref: IconRef): Long? = region(ref)?.texture

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
                .onFailure {
                    lastFailure = "$id: $it"
                    Bpm.LOGGER.warn("item picture failed for {}: {}", id, it.toString())
                }
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

    /**
     * Our own buffer source rather than the game's shared one.
     *
     * `mc.renderBuffers().bufferSource()` may already hold geometry another mod queued this frame; flushing it
     * here would draw that into this 96x96 texture, and leave ours to be flushed into theirs later.
     */
    private val buffers: MultiBufferSource.BufferSource by lazy { MultiBufferSource.immediate(ByteBufferBuilder(1536)) }

    private fun render(mc: Minecraft, slot: Slot, stack: ItemStack) {
        val target = slot.target
        target.setClearColor(0f, 0f, 0f, 0f)
        bpm.platform.client.clearTarget(target)
        target.bindWrite(true)
        RenderSystem.backupProjectionMatrix()
        val modelView = RenderSystem.getModelViewStack()
        modelView.pushMatrix()
        modelView.identity()
        bpm.platform.client.applyModelView()
        // A 16-unit GUI space against an IDENTITY model-view, both set here.
        //
        // This used to set only the projection, with a 1000..21000 depth range chosen to match the -11000 Z
        // push the vanilla GUI model-view happens to carry — that is, it borrowed whatever model-view the
        // frame had left lying around. It works in a plain client and fails wherever another mod has set a
        // different one, because then the item lands outside the depth range and clips away silently.
        bpm.platform.client.setGuiProjection(Matrix4f().setOrtho(0f, 16f, 16f, 0f, -1000f, 1000f))
        val graphics = GuiGraphics(mc, buffers)
        try {
            graphics.renderItem(stack, 0, 0)
            graphics.flush()
        } finally {
            modelView.popMatrix()
            bpm.platform.client.applyModelView()
            RenderSystem.restoreProjectionMatrix()
            target.unbindWrite()
            mc.mainRenderTarget.bindWrite(true)
        }
    }

    /** What `/bpm previews` reports — enough to tell "nothing was asked for" from "everything failed". */
    fun diagnostics(): String {
        val rendered = slots.values.count { it.rendered }
        return "previews: ${slots.size} textures, $rendered drawn, ${wanted.size} queued" +
            (lastFailure?.let { " · last failure: $it" } ?: " · no failures")
    }

    private var lastFailure: String? = null

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
