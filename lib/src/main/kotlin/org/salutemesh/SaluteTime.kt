package org.salutemesh

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

object SaluteTime {
    val DISPLAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun nowLocal(zone: ZoneId = ZoneId.systemDefault()): String =
        LocalDateTime.now(zone).format(DISPLAY)

    fun formatEpoch(epochSeconds: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochSecond(epochSeconds).atZone(zone).format(DISPLAY)

    fun parseToEpochSeconds(display: String, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val trimmed = display.trim()
        if (trimmed.isEmpty()) return null
        return try {
            LocalDateTime.parse(trimmed, DISPLAY).atZone(zone).toEpochSecond()
        } catch (_: DateTimeParseException) {
            trimmed.toLongOrNull()
        }
    }
}
