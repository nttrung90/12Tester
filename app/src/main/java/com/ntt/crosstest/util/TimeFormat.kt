package com.ntt.crosstest.util

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val LOCAL_DATE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

/**
 * Chuyển chuỗi thời gian ISO-8601 từ server (thường là UTC) sang giờ của máy,
 * định dạng dd/MM/yyyy HH:mm. Nếu không parse được thì trả về chuỗi rút gọn như cũ.
 */
fun formatIsoUtcToLocal(isoString: String): String {
    val zoned = try {
        OffsetDateTime.parse(isoString).atZoneSameInstant(ZoneId.systemDefault())
    } catch (_: Exception) {
        try {
            Instant.parse(isoString).atZone(ZoneId.systemDefault())
        } catch (_: Exception) {
            null
        }
    }
    return zoned?.let { LOCAL_DATE_TIME_FORMATTER.format(it) }
        ?: isoString.take(16).replace("T", " ")
}
