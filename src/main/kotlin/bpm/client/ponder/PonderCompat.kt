package bpm.client.ponder

import bpm.Bpm

/**
 * The gate that keeps Ponder optional.
 *
 * **Nothing in this file may name a Ponder type.** That is the whole point of it. [BpmPonder] implements
 * `PonderPlugin`, so the JVM has to load that interface before it can load the class — which means a
 * try/catch *inside* [BpmPonder] cannot save us, because the failure happens while loading the class the
 * catch block lives in. The first build had exactly that guard and it would have thrown
 * `NoClassDefFoundError` straight past it on any pack without Ponder.
 *
 * Checking `ModList` here, from a class the loader can always resolve, means [BpmPonder] is only ever
 * touched once Ponder is known to be present. It is the same shape as JEI's arrangement, where a
 * `@JeiPlugin` class is loaded by JEI itself and therefore never loaded at all when JEI is absent — Ponder
 * just has no equivalent hook, so the check is ours to make.
 */
object PonderCompat {

    const val PONDER = "ponder"

    fun install() {
        if (!bpm.platform.Platform.isModLoaded(PONDER)) {
            Bpm.LOGGER.info("bpm: Ponder is not installed, scene tutorials are off")
            return
        }
        // Only NOW is it safe to mention the class: this call site is what triggers loading it.
        BpmPonder.install()
    }
}
