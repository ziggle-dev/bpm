package bpm.catalog

import bpm.nodes.ChatNodes
import bpm.nodes.ControllerHost
import bpm.nodes.ControllerNodes
import bpm.nodes.DetachedHost
import bpm.nodes.EnergyNodes
import bpm.nodes.XpNodes
import bpm.nodes.DeviceNodes
import bpm.nodes.MonitorNodes
import bpm.nodes.FluidNodes
import bpm.nodes.HudNodes
import bpm.nodes.ItemNodes
import bpm.nodes.PlayerNodes
import bpm.nodes.RedstoneNodes
import bpm.nodes.WorldNodes
import io.osrsx.vscript.manifest.CatalogManifest
import io.osrsx.vscript.model.BuiltinNodes
import io.osrsx.vscript.model.NodeCatalog
import io.osrsx.vscript.nodes.Contribution
import io.osrsx.vscript.nodes.NodeLibrary
import java.security.MessageDigest

/**
 * The node catalogue both sides agree on, and the packs that make it.
 *
 * Bodies are built per controller (`library(host)`), because a body reads the world through the controller
 * it runs in; descriptors are built once with [DetachedHost], because a descriptor never runs. The two come
 * from the same declarations, so they cannot drift — and [hash] is what the login handshake compares.
 */
object BpmCatalog {
    /**
     * Builtins the graph does not offer: the render entry (no render loop on a server), the OSRS-shaped
     * literal nodes, and the nodes that exist for the text language's sake — optionals, narrowing, casts,
     * locals, function values and the higher-order list verbs that need them.
     */
    val EXCLUDED: Set<String> = setOf(
        BuiltinNodes.ENTRY_RENDER,
        BuiltinNodes.LITERAL_NPC, BuiltinNodes.LITERAL_OBJECT, BuiltinNodes.LITERAL_SKILL, BuiltinNodes.LITERAL_TILE,
        // Optionals stay: `If Some` (branch on a value that may be absent) and `Or Else` (a fallback) are
        // how a graph handles nullability. `Narrowed` and `If Present` are the text language's spellings.
        BuiltinNodes.NARROW, BuiltinNodes.IF_PRESENT,
        BuiltinNodes.HOLD, BuiltinNodes.CAST, BuiltinNodes.IS_TYPE, BuiltinNodes.LOCAL_SET,
        BuiltinNodes.FUNCTION_REF, BuiltinNodes.INVOKE,
        BuiltinNodes.LIST_MAP, BuiltinNodes.LIST_FILTER, BuiltinNodes.LIST_FIRST_WHERE, BuiltinNodes.LIST_SORTED_BY,
    )

    /** Extra packs — addons register here; see `bpm.api`. */
    private val packs = ArrayList<(ControllerHost) -> Contribution>()
    private val packIds = ArrayList<String>()

    init {
        // The language's type registries are global and replace-by-name; any catalogue needs them first.
        McTypes.registerAll()
    }

    /** A pack of nodes from another mod; [id]`@`[version] takes part in the catalogue hash both sides compare. */
    fun addPack(id: String, version: String, pack: (ControllerHost) -> Contribution) {
        check(!catalogBuilt) { "bpm: packs must be added before the catalogue is built (mod construction)" }
        packs += pack
        packIds += "$id@$version"
    }

    /** `id@version` of every pack, bpm's own first — the list the handshake reports when hashes differ. */
    val packList: List<String> get() = listOf("bpm@$VERSION") + packIds

    @Volatile
    private var catalogBuilt = false

    /** Everything, bound to [host]: the node bodies, the enums and the records. */
    fun contribution(host: ControllerHost): Contribution =
        Contribution.of(
            listOf(
                ControllerNodes.contribution(host),
                ItemNodes.contribution(host),
                FluidNodes.contribution(host),
                EnergyNodes.contribution(host),
                XpNodes.contribution(host),
                DeviceNodes.trap(host),
                DeviceNodes.turret(host),
                DeviceNodes.phase(host),
                DeviceNodes.gate(host),
                DeviceNodes.pedestal(host),
                MonitorNodes.contribution(host),
                RedstoneNodes.contribution(host),
                WorldNodes.contribution(host),
                ChatNodes.contribution(host),
                PlayerNodes.contribution(host),
                HudNodes.contribution(host),
            ) + packs.map { it(host) },
        ) + Contribution(enums = McTypes.enums, records = McTypes.records(host))

    /** A library whose bodies act through [host] — one per running controller. */
    fun library(host: ControllerHost): NodeLibrary = contribution(host).let { NodeLibrary(it.defs, it.enums, it.records) }

    /** The catalogue: signatures only, the same on both sides. */
    val catalog: NodeCatalog by lazy { catalogBuilt = true; NodeCatalog(library(DetachedHost).descriptors, EXCLUDED) }

    val manifestJson: String by lazy { CatalogManifest.toJson(catalog) }

    /** What the login handshake compares — SHA-256 of the manifest plus the pack list, hex. */
    val hash: String by lazy {
        val text = packList.sorted().joinToString(separator = "|", prefix = manifestJson + "|")
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    const val VERSION = "0.1.0"
}
