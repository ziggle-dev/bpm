package bpm.catalog

import bpm.catalog.values.BlockPosValue
import bpm.catalog.values.EntityHandle
import bpm.catalog.values.FluidStackValue
import bpm.catalog.values.ItemStackValue
import bpm.catalog.values.RegistryIds
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.nodes.OutputConverter
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.material.Fluid

/**
 * How a host value becomes a language value on its way out of a node, keyed on the pin's declared type.
 *
 * Applied by `NodeLibrary.install` to every result pin whose type this handles — through lists too — so a
 * body may hand back a live `ItemStack` or `Entity` and the register receives the record or the handle.
 */
object McValueOut : OutputConverter {
    override fun handles(type: TypeRef): Boolean = reader(type) != null

    override fun convert(type: TypeRef, value: Any?): Any? = if (value == null) null else reader(type)?.invoke(value) ?: value

    private fun reader(type: TypeRef): ((Any?) -> Any?)? = when (type.required()) {
        McTypes.BLOCK_POS.type -> { v -> BlockPosValue.record(v) }
        McTypes.ITEM_STACK.type -> { v -> ItemStackValue.record(v) }
        McTypes.FLUID_STACK.type -> { v -> FluidStackValue.record(v) }
        McTypes.ITEM.type -> { v -> RegistryIds.itemId(v) }
        McTypes.BLOCK.type -> { v -> (v as? Block)?.let(RegistryIds::of) ?: v }
        McTypes.FLUID.type -> { v -> (v as? Fluid)?.let(RegistryIds::of) ?: v }
        McTypes.ENTITY, McTypes.PLAYER -> { v -> (v as? Entity)?.let(EntityHandle::of) ?: v }
        else -> null
    }
}
