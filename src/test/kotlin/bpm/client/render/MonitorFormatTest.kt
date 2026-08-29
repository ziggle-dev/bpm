package bpm.client.render

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MonitorFormatTest {
    @Test
    fun fullNumbersGetSeparatorsAndOneDecimalWhenFractional() {
        assertEquals("360,000", MonitorFormat.full(360000.0))
        assertEquals("12", MonitorFormat.full(12.0))
        assertEquals("2.5", MonitorFormat.full(2.5))
    }

    @Test
    fun shortNumbersStayUnderFiveCharacters() {
        assertEquals("999", MonitorFormat.short(999.0))
        assertEquals("1k", MonitorFormat.short(1000.0))
        assertEquals("1.3k", MonitorFormat.short(1296.0))
        assertEquals("12k", MonitorFormat.short(12500.0))
        assertEquals("360k", MonitorFormat.short(360000.0))
        assertEquals("1.2M", MonitorFormat.short(1_200_000.0))
        assertEquals("4G", MonitorFormat.short(4e9))
    }

    @Test
    fun ratiosAndPercentages() {
        assertEquals("360,000 / 360,000 FE", MonitorFormat.ratio(360000.0, 360000.0, "FE"))
        assertEquals("360k/360k FE", MonitorFormat.shortRatio(360000.0, 360000.0, "FE"))
        assertEquals("1.3k/16k", MonitorFormat.shortRatio(1296.0, 16000.0, ""))
        assertEquals("100%", MonitorFormat.percent(360000.0, 360000.0))
        assertEquals("8%", MonitorFormat.percent(1296.0, 16000.0))
        assertEquals("0%", MonitorFormat.percent(5.0, 0.0))
    }
}
