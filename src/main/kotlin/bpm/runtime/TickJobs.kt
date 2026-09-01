package bpm.runtime

import dev.ziggle.vscript.vm.HostAwait

/**
 * Work that takes more than one tick, done a tick at a time on the server thread.
 *
 * A blocking node starts one and returns its [await]; the fiber parks (vscript's `HostAwait`) and the
 * controller advances every live job once per tick, before the scheduler pass, so a job that finishes on
 * this tick resumes its fiber on this tick. No thread anywhere: "mine this block" is thirty calls to
 * [advance], each on the thread that owns the world.
 */
abstract class TickJob(val label: String) {
    val await = HostAwait()

    /** How many ticks this job has been advanced. */
    var ticks: Int = 0
        private set

    /** Called once per server tick. Return true when finished — after [finish] or [fail]. */
    protected abstract fun advance(): Boolean

    /** Something outside the job ended it: a stop, a break, a redeploy. Release whatever it holds. */
    open fun cancel() {}

    protected fun finish(result: Any?) = await.complete(result)

    protected fun fail(message: String) = await.fail(message)

    internal fun step(): Boolean {
        ticks++
        val done = try {
            advance()
        } catch (t: Throwable) {
            fail(t.message ?: "$label failed")
            true
        }
        return done || await.isDone
    }
}

/** The jobs one controller has in flight. */
class TickJobs {
    private val active = ArrayList<TickJob>()

    val size: Int get() = active.size

    /** Start [job]; the value to return from the node body. */
    fun <T : TickJob> start(job: T): HostAwait {
        active += job
        return job.await
    }

    /** One tick for every live job. */
    fun advance() {
        if (active.isEmpty()) return
        active.removeAll { it.step() }
    }

    fun cancelAll() {
        active.forEach { job ->
            runCatching { job.cancel() }
            job.await.fail("cancelled")
        }
        active.clear()
    }
}

/** Wait a number of ticks. `controller.wait`. */
class CountdownJob(private val forTicks: Int) : TickJob("wait") {
    override fun advance(): Boolean {
        if (ticks >= forTicks) {
            finish(null)
            return true
        }
        return false
    }
}

/**
 * Wait until [condition] holds, or [timeoutTicks] pass (0 = forever). Answers whether it held.
 *
 * The condition is asked once per tick from the server thread, so it may read the world freely.
 */
class PredicateJob(label: String, private val timeoutTicks: Int, private val condition: () -> Boolean) : TickJob(label) {
    override fun advance(): Boolean {
        if (condition()) {
            finish(true)
            return true
        }
        if (timeoutTicks > 0 && ticks >= timeoutTicks) {
            finish(false)
            return true
        }
        return false
    }
}
