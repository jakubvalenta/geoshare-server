package net.geoshare_app.lib

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val hourFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH")

fun formatHour(localDateTime: LocalDateTime = LocalDateTime.now()): String = localDateTime.format(hourFormatter)

fun LocalDateTime.listHours(hours: LongProgression): List<LocalDateTime> =
    hours.map { hour ->
        plusHours(hour)
    }
