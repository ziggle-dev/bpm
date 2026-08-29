package bpm.catalog

import bpm.catalog.values.BlockPosValue
import bpm.catalog.values.EntityHandle
import bpm.catalog.values.FilterValue
import bpm.catalog.values.FluidStackValue
import bpm.catalog.values.ItemStackValue
import bpm.catalog.values.RegistryIds
import bpm.catalog.values.SlotValue
import bpm.nodes.ControllerHost
import bpm.nodes.DetachedHost
import io.osrsx.vscript.model.HostEnum
import io.osrsx.vscript.model.HostEnums
import io.osrsx.vscript.model.HostField
import io.osrsx.vscript.model.HostRecord
import io.osrsx.vscript.model.HostRecords
import io.osrsx.vscript.model.PinType
import io.osrsx.vscript.model.TypeInfo
import io.osrsx.vscript.model.TypeRef
import io.osrsx.vscript.model.Types
import net.minecraft.core.Direction
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.Property

/**
 * The types this domain teaches the language. Registered once per side, before any catalogue is built.
 *
 * Every one of these is DATA to the language — a record, an enum or a nominal name — never a `PinType`;
 * see vscript's `CLAUDE.md`, "A new host type is DATA". Which of the three each is, and why, is in
 * docs/DESIGN_DOMAIN_RUNTIME.md §5.
 */
object McTypes {
    private val INT = TypeRef(PinType.INT)
    private val FLOAT = TypeRef(PinType.FLOAT)
    private val STRING = TypeRef(PinType.STRING)
    private val BOOL = TypeRef(PinType.BOOL)

    // ---- enums ------------------------------------------------------------------------------------

    val DIRECTION = HostEnum("Direction", Direction.entries.map { directionName(it) }, "a block face")

    /** The four toast levels of `chat.notify`. */
    val NOTIFY = HostEnum("Notify", listOf("Info", "Success", "Warning", "Error"), "how loud a notification is")
    val CLICK = HostEnum("Click", listOf("Left", "Right"), "a mouse button: Left hits, Right uses")
    val AIM = HostEnum("Aim", listOf("Auto", "Block", "Entity"), "what a click lands on: the block, whatever stands in it, or whichever is there")

    fun directionName(d: Direction): String = d.name.lowercase().replaceFirstChar { it.uppercase() }

    fun direction(value: Any?): Direction? = value?.toString()?.trim()?.let { n -> Direction.entries.firstOrNull { it.name.equals(n, ignoreCase = true) } }

    // ---- data records -----------------------------------------------------------------------------

    val BLOCK_POS = HostRecord(
        BlockPosValue.TYPE,
        listOf(HostField("x", INT, "west to east"), HostField("y", INT, "down to up"), HostField("z", INT, "north to south")),
        "a position in the world",
        isData = true,
        read = { BlockPosValue.record(it) },
        write = { BlockPosValue.written(it) },
    )

    val ITEM_STACK = HostRecord(
        ItemStackValue.TYPE,
        listOf(
            HostField("item", TypeRef.named("Item"), "what kind"),
            HostField("count", INT, "how many"),
            HostField("components", STRING.orNull(), "the data components as JSON, when there are any"),
        ),
        "a snapshot of a stack: what, how many, and what was attached",
        isData = true,
        read = { ItemStackValue.record(it) },
    )

    val FLUID_STACK = HostRecord(
        FluidStackValue.TYPE,
        listOf(HostField("fluid", TypeRef.named("Fluid"), "what kind"), HostField("amount", INT, "millibuckets")),
        "a snapshot of some fluid",
        isData = true,
        read = { FluidStackValue.record(it) },
    )

    val FILTER = HostRecord(
        FilterValue.TYPE,
        listOf(
            HostField("item", TypeRef.named("Item").orNull(), "only this item"),
            HostField("tag", TypeRef.named("Tag").orNull(), "only items in this tag"),
            HostField("min", INT, "how many must be there before Has and Wait For say yes; 0 = any"),
            HostField("max", INT, "a cap for verbs that move a quantity; 0 = none"),
            HostField("enchant", STRING.orNull(), "only stacks carrying this enchantment, by id"),
            HostField("level", INT, "…at least this level; 0 = any level"),
            HostField("component", STRING.orNull(), "only stacks carrying this data component, by id"),
            HostField("name", STRING.orNull(), "only stacks whose display name contains this"),
            HostField("damaged", BOOL.orNull(), "only damaged (true) or undamaged (false) stacks"),
            HostField("predicate", STRING.orNull(), "a vanilla item predicate as JSON — the whole game's own filter language"),
            HostField("any", TypeRef.list(TypeRef.named(FilterValue.TYPE)).orNull(), "admit what any of these admit"),
            HostField("all", TypeRef.list(TypeRef.named(FilterValue.TYPE)).orNull(), "admit only what all of these admit"),
            HostField("not", TypeRef.named(FilterValue.TYPE).orNull(), "refuse what this admits"),
        ),
        "which stacks a verb should touch; every field is ignored when empty",
        isData = true,
        read = { FilterValue.record(it) },
    )

    /** A slot number and its stack — what `items.listSlots` answers. */
    val SLOT = HostRecord(
        SlotValue.TYPE,
        listOf(HostField("slot", INT, "the slot number, 0-based"), HostField("stack", TypeRef.named(ItemStackValue.TYPE), "what it holds")),
        "one slot of an inventory and what is in it",
        isData = true,
    )

    /** One thing on a monitor's screen — a data record a script builds and hands to `monitor.show`. */
    val WIDGET = HostRecord(
        bpm.catalog.values.WidgetValue.TYPE,
        listOf(
            HostField("kind", STRING, "Text, Item, Fluid, Energy or Bar"),
            HostField("text", STRING, "what a Text says"),
            HostField("label", STRING, "the label beside an item or on a gauge"),
            HostField("value", FLOAT, "how much a gauge holds"),
            HostField("max", FLOAT, "of how much"),
            HostField("item", TypeRef.named(ItemStackValue.TYPE).orNull(), "the stack an Item shows"),
            HostField("fluid", STRING, "the fluid a Fluid gauge is of, by id"),
            HostField("colour", STRING, "a colour name or #rrggbb"),
            HostField("size", INT, "a Text's height, 1 to 4"),
            HostField("align", STRING, "Left, Center or Right"),
            HostField("unit", STRING, "after a gauge's numbers"),
            HostField("span", INT, "columns it takes on the screen; 0 for the whole row"),
        ),
        "one thing on a monitor's screen",
        isData = true,
    )

    // ---- nominal names over a registry id --------------------------------------------------------

    val ITEM = HostRecord(
        "Item",
        listOf(
            HostField("id", STRING, "the registry id") { it?.toString().orEmpty() },
            HostField("name", STRING, "the display name") { RegistryIds.item(it?.toString().orEmpty())?.description?.string.orEmpty() },
            HostField("maxStack", INT, "how many fit in one stack") { (RegistryIds.item(it?.toString().orEmpty())?.defaultMaxStackSize ?: 0).toLong() },
            HostField("isBlock", BOOL, "can it be placed") { RegistryIds.item(it?.toString().orEmpty()) is BlockItem },
        ),
        "a kind of item, by registry id",
        over = STRING,
    )

    val BLOCK = HostRecord(
        "Block",
        listOf(
            HostField("id", STRING, "the registry id") { it?.toString().orEmpty() },
            HostField("name", STRING, "the display name") { RegistryIds.block(it?.toString().orEmpty())?.name?.string.orEmpty() },
        ),
        "a kind of block, by registry id",
        over = STRING,
    )

    val FLUID = HostRecord(
        "Fluid",
        listOf(
            HostField("id", STRING, "the registry id") { it?.toString().orEmpty() },
            HostField("name", STRING, "the display name") { RegistryIds.fluid(it?.toString().orEmpty())?.fluidType?.description?.string.orEmpty() },
        ),
        "a kind of fluid, by registry id",
        over = STRING,
    )

    val TAG = HostRecord(
        "Tag",
        listOf(HostField("id", STRING, "the tag id, without the #") { it?.toString().orEmpty() }),
        "an item tag such as c:ingots/iron",
        over = STRING,
    )

    // ---- accessor records over live things --------------------------------------------------------

    val BLOCK_STATE = HostRecord(
        "BlockState",
        listOf(
            HostField("block", TypeRef.named("Block"), "what block") { (it as? BlockState)?.block?.let(RegistryIds::of).orEmpty() },
            HostField("isAir", BOOL, "nothing there") { (it as? BlockState)?.isAir ?: true },
            HostField("properties", TypeRef.map(STRING, STRING), "every property, as text") { propertiesOf(it as? BlockState) },
        ),
        "a block and its properties, as found",
    )

    val ENTITY: TypeRef = TypeRef.named("Entity")
    val PLAYER: TypeRef = TypeRef.named("Player")
    val LINK: TypeRef = TypeRef.named("Link")

    /** `Entity` and `Player` resolve through the controller that holds the handle — built per host. */
    fun entityRecords(host: ControllerHost): List<HostRecord> = listOf(
        HostRecord("Entity", entityFields(host), "a live thing in the world; ask `exists` before trusting the rest", type = ENTITY),
        HostRecord(
            "Player",
            entityFields(host) + HostField("displayName", STRING, "the name shown in chat") { (host.entity(it) as? Player)?.displayName?.string.orEmpty() },
            "a player",
            type = PLAYER,
            widensTo = ENTITY,
        ),
    )

    private fun entityFields(host: ControllerHost) = listOf(
        HostField("exists", BOOL, "is it still there — the field to branch on") { host.entity(it) != null },
        HostField("pos", BLOCK_POS.type, "where it stands") { host.entity(it)?.blockPosition()?.let(BlockPosValue::of) },
        HostField("name", STRING, "its name") { host.entity(it)?.name?.string.orEmpty() },
        HostField("type", STRING, "its kind, as a registry id") { host.entity(it)?.type?.let { t -> net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(t).toString() }.orEmpty() },
        HostField("health", FLOAT, "hit points, 0 for a thing without any") { (host.entity(it) as? LivingEntity)?.health?.toDouble() ?: 0.0 },
        HostField("isPlayer", BOOL, "is it a player") { host.entity(it) is Player },
    )

    /** `Link` is the NAME of one of the controller's links; its fields resolve through the controller. */
    fun linkRecord(host: ControllerHost): HostRecord = HostRecord(
        "Link",
        listOf(
            HostField("name", STRING, "what this link is called") { it?.toString().orEmpty() },
            HostField("pos", BLOCK_POS.type, "the linked block") { host.link(it?.toString().orEmpty())?.link?.pos?.let(BlockPosValue::of) },
            HostField("side", DIRECTION.type.orNull(), "the linked face, when one was chosen") { host.link(it?.toString().orEmpty())?.link?.side?.let(::directionName) },
            HostField("exists", BOOL, "is there a link by this name") { host.link(it?.toString().orEmpty()) != null },
            HostField("loaded", BOOL, "is its chunk loaded right now") { host.link(it?.toString().orEmpty())?.loaded ?: false },
            HostField("hasItems", BOOL, "does the face offer an inventory") { host.items(it?.toString().orEmpty()) != null },
            HostField("hasFluids", BOOL, "does the face offer a tank") { host.fluids(it?.toString().orEmpty()) != null },
            HostField("hasEnergy", BOOL, "does the face offer energy") { host.energy(it?.toString().orEmpty()) != null },
        ),
        "one face this controller is linked to, by name",
        type = LINK,
        over = STRING,
    )

    /** Every record this domain declares, with accessors bound to [host]. */
    fun records(host: ControllerHost): List<HostRecord> =
        listOf(BLOCK_POS, ITEM_STACK, FLUID_STACK, FILTER, SLOT, WIDGET, ITEM, BLOCK, FLUID, TAG, BLOCK_STATE) + entityRecords(host) + linkRecord(host)

    val enums: List<HostEnum> get() = listOf(DIRECTION, NOTIFY, CLICK, AIM)

    /**
     * Register the SHAPES once, on both sides, before any catalogue is built.
     *
     * Registries replace by name, so the per-controller library re-registering the same names later is a
     * no-op; what matters is that a pin typed `Link` means something the moment the first node is declared.
     */
    fun registerAll() {
        HostEnums.registerAll(enums)
        HostRecords.registerAll(records(DetachedHost))
        Types.register(TypeInfo("BlockPos", BLOCK_POS.type, "a position: x, y and z", authorable = true))
        Types.register(TypeInfo("Item", ITEM.type, "a kind of item, by id", authorable = true))
        Types.register(TypeInfo("Block", BLOCK.type, "a kind of block, by id", authorable = true))
        Types.register(TypeInfo("Fluid", FLUID.type, "a kind of fluid, by id", authorable = true))
        Types.register(TypeInfo("Tag", TAG.type, "an item tag", authorable = true))
        Types.register(TypeInfo("Link", LINK, "one of this controller's links", authorable = true))
        Types.register(TypeInfo("Direction", DIRECTION.type, "a block face", authorable = true))
        // Wire-only values — nobody types a stack into a field — that a graph may still NAME: a function
        // that takes one, a record that holds one, a list of them, a variable (declared `ItemStack?`, or
        // set before it is read).
        Types.register(TypeInfo("ItemStack", ITEM_STACK.type, "a snapshot of a stack", authorable = false, declarable = true))
        Types.register(TypeInfo("FluidStack", FLUID_STACK.type, "a snapshot of some fluid", authorable = false, declarable = true))
        Types.register(TypeInfo("Filter", FILTER.type, "which stacks a verb touches", authorable = false, declarable = true))
        Types.register(TypeInfo("Slot", SLOT.type, "a slot and its stack", authorable = false, declarable = true))
        Types.register(TypeInfo("Widget", WIDGET.type, "one thing on a monitor's screen", authorable = false, declarable = true))
        Types.register(TypeInfo("BlockState", BLOCK_STATE.type, "a block and its properties", authorable = false, declarable = true))
        Types.register(TypeInfo("Entity", ENTITY, "a live thing in the world", authorable = false, declarable = true))
        Types.register(TypeInfo("Player", PLAYER, "a player", authorable = false, declarable = true))
        // Enums have their own picker, like Direction.
        Types.register(TypeInfo("Notify", NOTIFY.type, "a notification level", authorable = true))
        Types.register(TypeInfo("Click", CLICK.type, "a mouse button: Left hits, Right uses", authorable = true))
        Types.register(TypeInfo("Aim", AIM.type, "what a click lands on", authorable = true))
    }

    @Suppress("UNCHECKED_CAST")
    /** Every property of [state] as text, by name — `lit` → `true`, `facing` → `north`, `age` → `7`. */
    fun propertiesOf(state: BlockState?): Map<String, String> {
        if (state == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for ((p, v) in state.values) {
            val prop = p as Property<Comparable<Any>>
            out[prop.name] = prop.getName(v as Comparable<Any>)
        }
        return out
    }
}
