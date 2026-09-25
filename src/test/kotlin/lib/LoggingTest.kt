package net.geoshare_app.lib

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class LoggingTest {
    @Test
    fun `lastHours - returns a list of local date times`() {
        assertEquals(
            listOf(
                "2026-01-01-02",
                "2026-01-01-01",
                "2026-01-01-00",
                "2025-12-31-23",
                "2025-12-31-22",
            ),
            LocalDateTime.parse("2026-01-01T02:59:30")
                .listHours(0L downTo -4L)
                .map { formatHour(it) },
        )
    }
}
