package bpm.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The tier curve of `docs/DESIGN_TIERS_AND_FABRICATION.md` §2, read back through the config's defaults —
 * every tier doubles what the one below it reaches and holds, and the top one has no horizon.
 */
class CoreTierTest {

    @Test
    fun `reach and breadth double up the tiers, and the coherent core has no horizon`() {
        assertEquals(listOf(16.0, 32.0, 64.0, 128.0), CoreTier.entries.dropLast(1).map { it.linkRange })
        assertEquals(listOf(8, 16, 32, 64, 128), CoreTier.entries.map { it.maxLinks })
        assertEquals(listOf(1, 1, 2, 4, 8), CoreTier.entries.map { it.maxPlayerLinks })

        assertTrue(CoreTier.COHERENT.linkRange.isInfinite())
        assertTrue(CoreTier.COHERENT.unlimitedRange)
        assertFalse(CoreTier.ENTANGLED.unlimitedRange)
    }

    @Test
    fun `an unlimited reach reads as a symbol, never as a number`() {
        assertEquals("∞", CoreTier.COHERENT.rangeText)
        assertEquals("128", CoreTier.ENTANGLED.rangeText)
    }

    @Test
    fun `an unknown or missing tier key falls back to stable, so an old save still loads`() {
        assertEquals(CoreTier.STABLE, CoreTier.byKey(null))
        assertEquals(CoreTier.STABLE, CoreTier.byKey("no-such-core"))
        assertEquals(CoreTier.COHERENT, CoreTier.byKey("coherent"))
    }
}
