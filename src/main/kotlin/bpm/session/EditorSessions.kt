package bpm.session

import java.util.UUID

enum class Role { VIEWER, HOLDER }

enum class LeaseOutcome { GRANTED, HELD_BY_OTHER, STOLEN }

/** Why a participant's session state changed, for the message the client shows. */
enum class SessionReason { NONE, GRANTED, RELEASED, STOLEN, EXPIRED, DELETED, HOLDER_LEFT }

/**
 * Who is editing what: one optional lease holder per document, any number of viewers, all keyed by player id.
 *
 * Pure bookkeeping over a tick clock ([now]) so every row of the lease table is a unit test. The server glue
 * turns the answers into packets. Rules: a lease is free or held; opening with `wantEdit` takes a free lease
 * and otherwise makes a viewer; a viewer is never promoted on its own — it asks ([requestLease]) and may
 * steal only when the caller says it may (operator or owner); a holder that stops heartbeating for
 * [leaseTicks] loses the lease ([expire]); leaving the server frees everything at once ([playerLeft]).
 */
class EditorSessions(private val now: () -> Long, val leaseTicks: Long = DEFAULT_LEASE_TICKS) {

    class DocSession(val docId: UUID) {
        var holder: UUID? = null
        var holderName: String = ""
        var leaseAt: Long = 0
        val participants = LinkedHashSet<UUID>()
        val names = HashMap<UUID, String>()
    }

    class Open(val role: Role, val holder: UUID?, val holderName: String)

    class Lease(val outcome: LeaseOutcome, val holder: UUID?, val holderName: String, val previous: UUID?)

    class Expired(val docId: UUID, val holder: UUID, val holderName: String)

    private val docs = HashMap<UUID, DocSession>()
    private val library = LinkedHashSet<UUID>()

    val openDocuments: Collection<DocSession> get() = docs.values

    operator fun get(docId: UUID): DocSession? = docs[docId]

    fun open(player: UUID, name: String, docId: UUID, wantEdit: Boolean): Open {
        val d = docs.getOrPut(docId) { DocSession(docId) }
        d.participants.add(player)
        d.names[player] = name
        if (d.holder == player) {
            d.leaseAt = now()
            return Open(Role.HOLDER, player, name)
        }
        if (wantEdit && d.holder == null) {
            grant(d, player, name)
            return Open(Role.HOLDER, player, name)
        }
        return Open(Role.VIEWER, d.holder, d.holderName)
    }

    fun requestLease(player: UUID, name: String, docId: UUID, steal: Boolean, maySteal: Boolean): Lease {
        val d = docs.getOrPut(docId) { DocSession(docId) }
        d.participants.add(player)
        d.names[player] = name
        val current = d.holder
        if (current == null || current == player) {
            grant(d, player, name)
            return Lease(LeaseOutcome.GRANTED, player, name, null)
        }
        if (steal && maySteal) {
            grant(d, player, name)
            return Lease(LeaseOutcome.STOLEN, player, name, current)
        }
        return Lease(LeaseOutcome.HELD_BY_OTHER, current, d.holderName, null)
    }

    /** The holder gives the lease up but stays a viewer. True when [player] held it. */
    fun release(player: UUID, docId: UUID): Boolean {
        val d = docs[docId] ?: return false
        if (d.holder != player) return false
        clearHolder(d)
        return true
    }

    /** [player] leaves the document. True when a lease was freed by it. */
    fun close(player: UUID, docId: UUID): Boolean {
        val d = docs[docId] ?: return false
        d.participants.remove(player)
        d.names.remove(player)
        val freed = d.holder == player
        if (freed) clearHolder(d)
        if (d.participants.isEmpty()) docs.remove(docId)
        return freed
    }

    fun heartbeat(player: UUID, docId: UUID): Boolean {
        val d = docs[docId] ?: return false
        if (d.holder != player) return false
        d.leaseAt = now()
        return true
    }

    /** Leases whose holder has been silent for [leaseTicks]; each is freed. */
    fun expire(): List<Expired> {
        val t = now()
        val out = ArrayList<Expired>()
        for (d in docs.values) {
            val h = d.holder ?: continue
            if (t - d.leaseAt >= leaseTicks) {
                out += Expired(d.docId, h, d.holderName)
                clearHolder(d)
            }
        }
        return out
    }

    /** Documents whose lease [player] held; the player is removed from every document and the library. */
    fun playerLeft(player: UUID): List<UUID> {
        library.remove(player)
        val freed = ArrayList<UUID>()
        val it = docs.values.iterator()
        while (it.hasNext()) {
            val d = it.next()
            if (!d.participants.remove(player)) continue
            d.names.remove(player)
            if (d.holder == player) {
                clearHolder(d)
                freed += d.docId
            }
            if (d.participants.isEmpty()) it.remove()
        }
        return freed
    }

    /** A new server: nothing is open. */
    fun playerLeftAll() {
        docs.clear()
        library.clear()
    }

    /** Everyone who had the document open; the session is gone afterwards. */
    fun documentDeleted(docId: UUID): Set<UUID> = docs.remove(docId)?.participants?.toSet() ?: emptySet()

    fun holderOf(docId: UUID): UUID? = docs[docId]?.holder

    fun isHolder(player: UUID, docId: UUID): Boolean = docs[docId]?.holder == player

    fun participantsOf(docId: UUID): Set<UUID> = docs[docId]?.participants?.toSet() ?: emptySet()

    fun roleOf(player: UUID, docId: UUID): Role? {
        val d = docs[docId] ?: return null
        if (player !in d.participants) return null
        return if (d.holder == player) Role.HOLDER else Role.VIEWER
    }

    // ---- library screen subscribers ----------------------------------------------------------------------

    fun subscribeLibrary(player: UUID) {
        library.add(player)
    }

    fun unsubscribeLibrary(player: UUID) {
        library.remove(player)
    }

    val librarySubscribers: Set<UUID> get() = library.toSet()

    private fun grant(d: DocSession, player: UUID, name: String) {
        d.holder = player
        d.holderName = name
        d.leaseAt = now()
    }

    private fun clearHolder(d: DocSession) {
        d.holder = null
        d.holderName = ""
        d.leaseAt = 0
    }

    companion object {
        /** Thirty seconds of silence from a holder and the lease is free. Clients heartbeat every five. */
        const val DEFAULT_LEASE_TICKS = 600L
    }
}
