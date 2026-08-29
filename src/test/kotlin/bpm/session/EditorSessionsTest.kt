package bpm.session

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorSessionsTest {
    private var tick = 0L
    private val s = EditorSessions({ tick })
    private val doc = UUID.randomUUID()
    private val alice = UUID.randomUUID()
    private val bob = UUID.randomUUID()

    @Test
    fun `open takes a free lease and later openers view`() {
        val a = s.open(alice, "alice", doc, wantEdit = true)
        assertEquals(Role.HOLDER, a.role)
        val b = s.open(bob, "bob", doc, wantEdit = true)
        assertEquals(Role.VIEWER, b.role)
        assertEquals(alice, b.holder)
        assertEquals("alice", b.holderName)
        assertEquals(Role.HOLDER, s.open(alice, "alice", doc, wantEdit = false).role, "the holder reopening keeps the lease")
        assertEquals(setOf(alice, bob), s.participantsOf(doc))
    }

    @Test
    fun `viewers ask for the lease and are refused or granted never promoted on their own`() {
        s.open(alice, "alice", doc, wantEdit = true)
        s.open(bob, "bob", doc, wantEdit = false)
        assertEquals(LeaseOutcome.HELD_BY_OTHER, s.requestLease(bob, "bob", doc, steal = false, maySteal = true).outcome)
        assertEquals(LeaseOutcome.HELD_BY_OTHER, s.requestLease(bob, "bob", doc, steal = true, maySteal = false).outcome, "steal needs permission")
        assertTrue(s.release(alice, doc))
        assertNull(s.holderOf(doc))
        assertEquals(Role.VIEWER, s.roleOf(bob, doc), "release does not promote the viewer")
        val got = s.requestLease(bob, "bob", doc, steal = false, maySteal = false)
        assertEquals(LeaseOutcome.GRANTED, got.outcome)
        assertEquals(bob, s.holderOf(doc))
    }

    @Test
    fun `a permitted steal moves the lease and names the loser`() {
        s.open(alice, "alice", doc, wantEdit = true)
        val r = s.requestLease(bob, "bob", doc, steal = true, maySteal = true)
        assertEquals(LeaseOutcome.STOLEN, r.outcome)
        assertEquals(alice, r.previous)
        assertEquals(bob, s.holderOf(doc))
        assertEquals(Role.VIEWER, s.roleOf(alice, doc))
        assertFalse(s.release(alice, doc), "the loser cannot release what it lost")
    }

    @Test
    fun `leases expire without heartbeats and heartbeats keep them`() {
        s.open(alice, "alice", doc, wantEdit = true)
        tick = 599
        assertTrue(s.expire().isEmpty())
        assertTrue(s.heartbeat(alice, doc))
        tick = 1198
        assertTrue(s.expire().isEmpty(), "the heartbeat restarted the clock")
        tick = 1199
        val expired = s.expire()
        assertEquals(1, expired.size)
        assertEquals(alice, expired[0].holder)
        assertNull(s.holderOf(doc))
        assertEquals(Role.VIEWER, s.roleOf(alice, doc), "an expired holder is still watching")
        assertFalse(s.heartbeat(alice, doc), "a viewer's heartbeat does nothing")
    }

    @Test
    fun `leaving the server frees everything at once`() {
        val other = UUID.randomUUID()
        s.open(alice, "alice", doc, wantEdit = true)
        s.open(alice, "alice", other, wantEdit = true)
        s.open(bob, "bob", doc, wantEdit = false)
        s.subscribeLibrary(alice)
        val freed = s.playerLeft(alice)
        assertEquals(setOf(doc, other), freed.toSet())
        assertNull(s.holderOf(doc))
        assertEquals(setOf(bob), s.participantsOf(doc))
        assertNull(s[other], "a document nobody has open is forgotten")
        assertTrue(alice !in s.librarySubscribers)
    }

    @Test
    fun `close and delete`() {
        s.open(alice, "alice", doc, wantEdit = true)
        s.open(bob, "bob", doc, wantEdit = false)
        assertTrue(s.close(alice, doc), "closing as holder frees the lease")
        assertFalse(s.close(bob, doc))
        assertNull(s[doc])
        s.open(alice, "alice", doc, wantEdit = true)
        s.open(bob, "bob", doc, wantEdit = false)
        assertEquals(setOf(alice, bob), s.documentDeleted(doc))
        assertNull(s[doc])
        assertNull(s.roleOf(alice, doc))
    }
}
