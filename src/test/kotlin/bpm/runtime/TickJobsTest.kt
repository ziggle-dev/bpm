package bpm.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Jobs advance a tick at a time and complete the promise a parked fiber is waiting on. */
class TickJobsTest {

    @Test
    fun `a countdown completes after its ticks`() {
        val jobs = TickJobs()
        val await = jobs.start(CountdownJob(3))
        repeat(2) { jobs.advance() }
        assertFalse(await.isDone, "two ticks are not three")
        jobs.advance()
        assertTrue(await.isDone)
        assertEquals(0, jobs.size, "a finished job is dropped")
    }

    @Test
    fun `a predicate completes when it holds, or false on the timeout`() {
        val jobs = TickJobs()
        var ready = false
        val a = jobs.start(PredicateJob("a", timeoutTicks = 0) { ready })
        val b = jobs.start(PredicateJob("b", timeoutTicks = 2) { false })
        jobs.advance()
        assertFalse(a.isDone); assertFalse(b.isDone)
        jobs.advance()
        assertEquals(false, b.box.get()?.getOrNull(), "the timeout answers false")
        ready = true
        jobs.advance()
        assertEquals(true, a.box.get()?.getOrNull())
        assertEquals(0, jobs.size)
    }

    @Test
    fun `a job that throws fails its promise with the message`() {
        val jobs = TickJobs()
        val await = jobs.start(object : TickJob("boom") {
            override fun advance(): Boolean = throw IllegalStateException("no block there")
        })
        jobs.advance()
        assertTrue(await.isDone)
        assertTrue(await.box.get()!!.isFailure)
        assertTrue("no block there" in await.box.get()!!.exceptionOrNull()!!.message.orEmpty())
    }

    @Test
    fun `cancelling fails every live job and empties the list`() {
        val jobs = TickJobs()
        val a = jobs.start(CountdownJob(100))
        var cancelled = false
        jobs.start(object : TickJob("c") {
            override fun advance(): Boolean = false
            override fun cancel() { cancelled = true }
        })
        jobs.cancelAll()
        assertTrue(a.isDone && a.box.get()!!.isFailure)
        assertTrue(cancelled)
        assertEquals(0, jobs.size)
    }
}
