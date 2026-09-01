package bpm

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * The mod's identity: its id and its logger, and nothing else.
 *
 * Both are named from over a hundred places across the codebase, none of which care which loader is
 * running. Keeping them here rather than on the loader entry point is what lets that shared code stay
 * shared — the alternative was a hundred call sites that each had to know whether they were on NeoForge
 * or Fabric to find a logger.
 *
 * The entry points themselves live with their loaders: `BpmNeoForge` in `src/neoforge`, and Fabric's in
 * `src/fabric`. Log4j needs no seam — Minecraft ships it on both.
 */
object Bpm {
    const val ID = "bpm"
    val LOGGER: Logger = LogManager.getLogger(ID)
}
