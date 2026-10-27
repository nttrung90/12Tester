package com.ntt.crosstest.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
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
    /** Full-byte fingerprint; null keeps requests from older app versions readable. */
    val screenshotSha256: String? = null,
    /** Durable server receipt. Never remove it before WorkManager commits success. */
    val completedResult: SubmitTesterReportResult? = null,
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
        try {
            FileOutputStream(temp).use { stream ->
                stream.write(json.encodeToString(report).toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            // Same-directory atomic replacement is supported on our API 26+ storage.
            // Unlike File.renameTo/AtomicFile.finishWrite, failure is never silently ignored.
            Files.move(temp.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
    }

    @Synchronized
    fun read(context: Context, id: String): PendingReport? {
        return try {
            val target = jsonFile(context, id)
            val legacyBackup = File(target.path + ".bak")
            if (legacyBackup.isFile) {
                Files.move(legacyBackup.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            }
            val text = target.readText()
            json.decodeFromString<PendingReport>(text)
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun markUploaded(context: Context, id: String, url: String, hash: String?) {
        val current = read(context, id) ?: throw IOException("pending_request_not_found")
        write(context, current.copy(uploadedUrl = url, uploadedHash = hash))
    }

    @Synchronized
    fun markCompleted(context: Context, id: String, result: SubmitTesterReportResult) {
        require(result.success && result.reportId.isNotBlank()) { "invalid_report_result" }
        val current = read(context, id) ?: throw IOException("pending_request_not_found")
        write(context, current.copy(completedResult = result))
        // The receipt is sufficient for replay, so large image files can now be removed.
        deleteInsideAppStorage(context, current.screenshotPath)
        deleteInsideAppStorage(context, current.preparedPath)
    }

    @Synchronized
    fun list(context: Context): List<PendingReport> = directory(context).listFiles().orEmpty()
        .filter { it.name.endsWith(".json") || it.name.endsWith(".json.bak") }
        .map { it.name.removeSuffix(".bak").removeSuffix(".json") }
        .distinct()
        .mapNotNull { read(context, it) }

    fun screenshotSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8_192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Synchronized
    fun delete(context: Context, id: String) {
        val report = read(context, id)
        if (report != null) {
            deleteInsideAppStorage(context, report.screenshotPath)
            deleteInsideAppStorage(context, report.preparedPath)
        }
        val target = jsonFile(context, id)
        listOf(target, File(target.path + ".tmp"), File(target.path + ".new"), File(target.path + ".bak"))
            .forEach { it.delete() }
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
                read(context, file.name.removeSuffix(".json"))
            } catch (_: Exception) {
                null
            }
            if (report == null) {
                file.delete()
                return@forEach
            }
            // WorkManager may still need this receipt after a crash. Its reconciler
            // removes completed records only after SUCCEEDED (or work pruning).
            if (report.completedResult != null) return@forEach
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

    /**
     * Campaign id của các báo cáo đang chờ upload (chưa lên server).
     * Dùng để không gợi ý lại app vừa test trong lúc báo cáo còn trong hàng đợi.
     */
    @Synchronized
    fun pendingCampaignIds(context: Context): Set<String> {
        val files = directory(context).listFiles { file ->
            file.isFile && file.name.endsWith(".json")
        } ?: return emptySet()
        return files.mapNotNull { file ->
            try {
                val report = read(context, file.name.removeSuffix(".json"))
                report?.takeIf { it.completedResult == null }?.campaignId
            } catch (_: Exception) {
                null
            }
        }.toSet()
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
