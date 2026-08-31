package bpm.catalog

import bpm.catalog.values.BlockPosValue
import bpm.catalog.values.EntityHandle
import bpm.catalog.values.FilterValue
import bpm.catalog.values.FluidStackValue
import bpm.catalog.values.ItemStackValue
import io.osrsx.vscript.nodes.Vs
import io.osrsx.vscript.nodes.VsType
import io.osrsx.vscript.vm.StructValue

/** The pin types the node declarations are written in — what a parameter of each kind READS as. */
object McVs {
    val int: VsType<Long> get() = Vs.int
    val float: VsType<Double> get() = Vs.float
    val string: VsType<String> get() = Vs.string
    val bool: VsType<Boolean> get() = Vs.bool

    /** A link, read as its NAME. The host resolves it. */
    val link: VsType<String> = VsType.of(McTypes.LINK) { _, v -> v?.toString().orEmpty() }

    val direction: VsType<String> = Vs.enum(McTypes.DIRECTION)
    val notify: VsType<String> = Vs.enum(McTypes.NOTIFY)
    val click: VsType<String> = Vs.enum(McTypes.CLICK)
    val aim: VsType<String> = Vs.enum(McTypes.AIM)
    val hand: VsType<String> = Vs.enum(McTypes.HAND)
    val equip: VsType<String> = Vs.enum(McTypes.EQUIP)
    /** A key by name; the host normalises whatever was typed. */
    val key: VsType<String> = VsType.of(McTypes.KEY.type) { _, v -> bpm.world.KeyNames.normalise(v?.toString()) }
    val anchor: VsType<String> = Vs.enum(McTypes.ANCHOR)

    /** An item by registry id; the picker stores the id and a typed literal is the id. */
    val item: VsType<String> = VsType.of(McTypes.ITEM.type) { _, v -> v?.toString().orEmpty() }
    val block: VsType<String> = VsType.of(McTypes.BLOCK.type) { _, v -> v?.toString().orEmpty() }

    /** A `MAP<STRING, STRING>` result — a block state's properties, say — handed over as the map it is. */
    val stringMap: VsType<Any?> = VsType.of(io.osrsx.vscript.model.TypeRef.map(io.osrsx.vscript.model.TypeRef(io.osrsx.vscript.model.PinType.STRING), io.osrsx.vscript.model.TypeRef(io.osrsx.vscript.model.PinType.STRING))) { _, v -> v }
    val fluid: VsType<String> = VsType.of(McTypes.FLUID.type) { _, v -> v?.toString().orEmpty() }
    val tag: VsType<String> = VsType.of(McTypes.TAG.type) { _, v -> v?.toString().orEmpty() }

    /** A position record; a document stores it as `"x,y,z"`, a host may hand over a `BlockPos`. */
    val blockPos: VsType<StructValue?> = VsType.of(
        McTypes.BLOCK_POS.type,
        { _, v -> BlockPosValue.record(v) },
        { v -> BlockPosValue.written(v) },
    )

    val itemStack: VsType<StructValue?> = VsType.of(McTypes.ITEM_STACK.type) { _, v -> ItemStackValue.record(v) }
    val fluidStack: VsType<StructValue?> = VsType.of(McTypes.FLUID_STACK.type) { _, v -> FluidStackValue.record(v) }
    val filter: VsType<StructValue?> = VsType.of(McTypes.FILTER.type) { _, v -> FilterValue.record(v) }
    val slot: VsType<StructValue?> = VsType.of(McTypes.SLOT.type) { _, v -> v as? StructValue }
    val widget: VsType<StructValue?> = VsType.of(McTypes.WIDGET.type) { _, v -> v as? StructValue }

    /** The language's own JSON value — what `readJson` hands back and `as` reads. */
    val json: VsType<Any?> = VsType.of(io.osrsx.vscript.model.TypeRef.named("Json")) { _, v -> v }

    /** `MAP<STRING, INT>`. */
    val stringIntMap: VsType<Map<Any?, Any?>> = VsType.of(
        io.osrsx.vscript.model.TypeRef.map(io.osrsx.vscript.model.TypeRef(io.osrsx.vscript.model.PinType.STRING), io.osrsx.vscript.model.TypeRef(io.osrsx.vscript.model.PinType.INT)),
    ) { _, v -> (v as? Map<*, *>)?.let { m -> LinkedHashMap<Any?, Any?>().also { out -> m.forEach { (k, x) -> out[k] = x } } } ?: LinkedHashMap() }
    val blockState: VsType<net.minecraft.world.level.block.state.BlockState?> =
        VsType.of(McTypes.BLOCK_STATE.type) { _, v -> v as? net.minecraft.world.level.block.state.BlockState }
    val entity: VsType<EntityHandle?> = VsType.of(McTypes.ENTITY) { _, v -> v as? EntityHandle }
    val player: VsType<EntityHandle?> = VsType.of(McTypes.PLAYER) { _, v -> v as? EntityHandle }
}
