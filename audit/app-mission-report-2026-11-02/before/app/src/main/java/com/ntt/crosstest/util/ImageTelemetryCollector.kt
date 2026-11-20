package com.ntt.crosstest.util

import com.ntt.crosstest.BuildConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * ImageTelemetryCollector (E00).
 *
 * Thu thập và xuất số liệu viễn trắc (telemetry) có kiểm soát opt-in,
 * định dạng JSON tương thích với công cụ đối chiếu `reconcile-telemetry.mjs`.
 *
 * Tiêu chí an toàn & bảo mật:
 * - Mặc định TẮT (isOptedIn = false); chỉ hoạt động khi được người dùng/admin bật tường minh.
 * - Tuyệt đối KHÔNG thu thập hoặc xuất UID, token xác thực, URL nhạy cảm hay đường dẫn tệp nội bộ.
 * - Gắn nhãn phiên bản app, build, cohort và cửa sổ thời gian chuẩn UTC (ISO-8601).
 */
object ImageTelemetryCollector {

    @Volatile
    var isOptedIn: Boolean = false

    @Volatile
    var activeCohort: String = "default"

    @Volatile
    var samplingRate: Double = 1.0

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    @Serializable
    data class TelemetryExportSchema(
        val schemaVersion: String = "1.0.0",
        val clientVersion: String,
        val buildNumber: Long,
        val cohort: String,
        val windowStartUtc: String,
        val windowEndUtc: String,
        val samplingRate: Double,
        val coveragePercent: Double,
        val requestCount: Long,
        val bodyBytesRead: Long,
        val memoryHits: Long,
        val diskHits: Long,
        val ioFailures: Long,
        val cancellations: Long,
    )

    private var windowStartInstant: Instant = Instant.now()

    @Synchronized
    fun startNewWindow() {
        windowStartInstant = Instant.now()
        if (ImageInstrumentation.isEnabled) {
            ImageInstrumentation.reset()
        }
    }

    @Synchronized
    fun exportTelemetrySnapshot(): TelemetryExportSchema? {
        if (!isOptedIn) {
            return null
        }

        val now = Instant.now()
        val snapshot = ImageInstrumentation.snapshot()

        return TelemetryExportSchema(
            schemaVersion = "1.0.0",
            clientVersion = try { BuildConfig.VERSION_NAME } catch (_: Exception) { "unknown" },
            buildNumber = try { BuildConfig.VERSION_CODE.toLong() } catch (_: Exception) { 0L },
            cohort = activeCohort,
            windowStartUtc = DateTimeFormatter.ISO_INSTANT.format(windowStartInstant),
            windowEndUtc = DateTimeFormatter.ISO_INSTANT.format(now),
            samplingRate = samplingRate.coerceIn(0.0, 1.0),
            coveragePercent = (samplingRate.coerceIn(0.0, 1.0) * 100.0),
            requestCount = snapshot.networkRequests.toLong(),
            bodyBytesRead = snapshot.responseBodyBytes,
            memoryHits = snapshot.memoryHits.toLong(),
            diskHits = snapshot.diskHits.toLong(),
            ioFailures = snapshot.ioFailures.toLong(),
            cancellations = snapshot.canceledRequests.toLong(),
        )
    }

    @Synchronized
    fun exportTelemetryJson(): String? {
        val schema = exportTelemetrySnapshot() ?: return null
        return json.encodeToString(schema)
    }

    @Synchronized
    fun reset() {
        isOptedIn = false
        activeCohort = "default"
        samplingRate = 1.0
        windowStartInstant = Instant.now()
    }
}
