package com.ntt.crosstest.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Request nộp báo cáo được lưu xuống đĩa để WorkManager có thể retry
 * kể cả khi process bị kill.
 */
@Serializable
data class PendingReport(
    val id: String,
    val campaignId: String,
    val appName: String,
    val userId: String,
    val rating: Int,
    val comment: String,
    val screenshotPath: String? = null,
    val preparedPath: String? = null,
    val imageHash: String? = null,
    val contentType: String = "image/jpeg",
    val fallbackScreenshotUrl: String? = null,
    val fallbackImageHash: String? = null,
    val needsJoin: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    /** URL đã upload thành công; retry RPC sẽ dùng lại, không upload thêm file. */
    val uploadedUrl: String? = null,
    val uploadedHash: String? = null,
) {
    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }
}

object PendingReportStore {

    private const val DIRECTORY = "pending_report_uploads"
    private const val MAX_AGE_MS = 48L * 60L * 60L * 1000L
    private const val MAX_FILES = 20
    private const val MAX_TOTAL_BYTES = 20L * 1024L * 1024L

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun directory(context: Context): File =
        File(context.noBackupFilesDir, DIRECTORY).apply { mkdirs() }

    fun newScreenshotFile(context: Context, id: String, extension: String = "jpg"): File =
        File(directory(context), "$id.$extension")

    @Synchronized
    fun write(context: Context, report: PendingReport) {
        val target = jsonFile(context, report.id)
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeText(json.encodeToString(report))
        if (!temp.renameTo(target)) {
            target.writeText(temp.readText())
            temp.delete()
        }
    }

    @Synchronized
    fun read(context: Context, id: String): PendingReport? {
        val file = jsonFile(context, id)
        if (!file.isFile) return null
        return try {
            json.decodeFromString<PendingReport>(file.readText())
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun markUploaded(context: Context, id: String, url: String, hash: String?) {
        val current = read(context, id) ?: return
        write(context, current.copy(uploadedUrl = url, uploadedHash = hash))
    }

    @Synchronized
    fun delete(context: Context, id: String) {
        val report = read(context, id)
        if (report != null) {
            deleteInsideAppStorage(context, report.screenshotPath)
            deleteInsideAppStorage(context, report.preparedPath)
        }
        jsonFile(context, id).delete()
    }

    /** Dọn request/ảnh cũ và giới hạn 20 file / 20MB. */
    @Synchronized
    fun cleanup(context: Context) {
        val now = System.currentTimeMillis()
        val cutoff = now - MAX_AGE_MS
        val dir = directory(context)
        val referenced = mutableSetOf<String>()
        val entries = mutableListOf<Triple<File, PendingReport, Long>>()

        dir.listFiles { file -> file.isFile && file.name.endsWith(".json") }?.forEach { file ->
            val report = try {
                json.decodeFromString<PendingReport>(file.readText())
            } catch (_: Exception) {
                null
            }
            if (report == null) {
                file.delete()
                return@forEach
            }
            if (report.createdAt < cutoff) {
                deleteInsideAppStorage(context, report.screenshotPath)
                deleteInsideAppStorage(context, report.preparedPath)
                file.delete()
            } else {
                report.screenshotPath?.let { referenced += File(it).absolutePath }
                report.preparedPath?.let { referenced += File(it).absolutePath }
                val bytes = file.length() +
                    (report.screenshotPath?.let { File(it).length() } ?: 0L)
                entries += Triple(file, report, bytes)
            }
        }

        // Giới hạn tổng dung lượng/số request: xoá request cũ nhất trước.
        var totalBytes = entries.sumOf { it.third }
        var remaining = entries.size
        entries.sortedBy { it.second.createdAt }.forEach { (file, report, bytes) ->
            if (remaining <= MAX_FILES && totalBytes <= MAX_TOTAL_BYTES) return@forEach
            deleteInsideAppStorage(context, report.screenshotPath)
            deleteInsideAppStorage(context, report.preparedPath)
            file.delete()
            remaining -= 1
            totalBytes -= bytes
        }

        // File ảnh không còn JSON tham chiếu (ví dụ process chết trước khi ghi JSON).
        dir.listFiles { file -> file.isFile && !file.name.endsWith(".json") }?.forEach { file ->
            if (file.absolutePath !in referenced && file.lastModified() < cutoff) {
                file.delete()
            }
        }
    }

    @Synchronized
    fun clear(context: Context) {
        directory(context).deleteRecursively()
        directory(context).mkdirs()
    }

    private fun jsonFile(context: Context, id: String): File =
        File(directory(context), "$id.json")

    private fun deleteInsideAppStorage(context: Context, path: String?) {
        if (path.isNullOrBlank()) return
        val file = File(path)
        val appRoot = context.noBackupFilesDir.canonicalFile
        val target = try {
            file.canonicalFile
        } catch (_: Exception) {
            return
        }
        if (target.path.startsWith(appRoot.path + File.separator) && target.isFile) {
            target.delete()
        }
    }
}
