package com.ntt.crosstest.data

import android.content.Context
import com.ntt.crosstest.util.PreparedScreenshotCache
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

@Serializable
data class PendingAttachment(
    val id: String,
    val position: Int,
    val screenshotPath: String,
    val preparedPath: String? = null,
    val preparedThumbnailPath: String? = null,
    val thumbnailPath: String? = null,
    val imageHash: String,
    val sha256: String? = null,
    val uploadedUrl: String? = null,
    val uploadedThumbnailUrl: String? = null,
    val uploadedHash: String? = null,
)

/**
 * Request nộp báo cáo được lưu xuống đĩa để WorkManager có thể retry
 * kể cả khi process bị kill.
 */
@Serializable
data class PendingReportFailure(
    val message: String,
    val code: String = "report_submit_failed",
    val stage: String = "unknown",
    val attempt: Int = 1,
    val originalCode: String? = null,
    val httpStatus: Int? = null,
) {
    fun asException(requestId: String) = ReportSubmissionFailure(
        code, stage, requestId, message, originalCode, httpStatus,
    )
}

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
    val preparedThumbnailPath: String? = null,
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
    val thumbnailPath: String? = null,
    val uploadedThumbnailUrl: String? = null,
    /** Durable server receipt. Never remove it before WorkManager commits success. */
    val completedResult: SubmitTesterReportResult? = null,
    val missionId: String? = null,
    val missionTitle: String? = null,
    val submissionContext: String? = null, // null identifies an old, ambiguous queued request
    val expectedDate: String? = null,
    val isOnceMode: Boolean = false,
    val scopedKey: String? = null,
    val attachments: List<PendingAttachment> = emptyList(),
    val terminalFailure: PendingReportFailure? = null,
    val failureNotificationConsumed: Boolean = false,
) {
    val allAttachments: List<PendingAttachment>
        get() {
            if (attachments.isNotEmpty()) return attachments
            val path = screenshotPath ?: return emptyList()
            val hash = imageHash ?: return emptyList()
            return listOf(
                PendingAttachment(
                    id = id,
                    position = 0,
                    screenshotPath = path,
                    preparedPath = preparedPath,
                    preparedThumbnailPath = preparedThumbnailPath,
                    thumbnailPath = thumbnailPath,
                    imageHash = hash,
                    sha256 = screenshotSha256,
                    uploadedUrl = uploadedUrl,
                    uploadedThumbnailUrl = uploadedThumbnailUrl,
                    uploadedHash = uploadedHash,
                )
            )
        }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }
}

object PendingReportStore {

    @Synchronized
    fun markTerminalFailure(context: Context, id: String, failure: PendingReportFailure) {
        val report = read(context, id) ?: return
        if (report.completedResult == null) write(context, report.copy(terminalFailure = failure))
    }

    /** Claim once before emitting UI feedback; survives process death and auth restoration. */
    @Synchronized
    fun claimFailureNotification(context: Context, id: String, failure: PendingReportFailure): Boolean {
        val report = read(context, id) ?: return true
        if (report.completedResult != null || report.failureNotificationConsumed) return false
        write(context, report.copy(terminalFailure = failure, failureNotificationConsumed = true))
        return true
    }

    @Synchronized
    fun prepareExplicitRetry(context: Context, id: String): PendingReport? {
        val report = read(context, id) ?: return null
        return report.copy(terminalFailure = null, failureNotificationConsumed = false).also { write(context, it) }
    }

    private const val DIRECTORY = "pending_report_uploads"
    private const val MAX_AGE_MS = 48L * 60L * 60L * 1000L
    private const val MAX_FILES = 20
    private const val MAX_TOTAL_BYTES = 20L * 1024L * 1024L

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun serializedBytes(report: PendingReport): Long {
        return json.encodeToString(report).toByteArray(Charsets.UTF_8).size.toLong()
    }

    fun directory(context: Context): File =
        File(context.noBackupFilesDir, DIRECTORY).apply { mkdirs() }

    fun newScreenshotFile(context: Context, id: String, extension: String = "jpg"): File =
        File(directory(context), "$id.$extension")

    fun newThumbnailFile(context: Context, id: String, extension: String = "jpg"): File =
        File(directory(context), "${id}_thumb.$extension")

    fun newAttachmentScreenshotFile(context: Context, id: String, attachmentId: String, extension: String = "jpg"): File =
        File(directory(context), "${id}_${attachmentId}.$extension")

    fun newAttachmentThumbnailFile(context: Context, id: String, attachmentId: String, extension: String = "jpg"): File =
        File(directory(context), "${id}_${attachmentId}_thumb.$extension")

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
    fun markUploaded(context: Context, id: String, url: String, hash: String?, thumbnailUrl: String? = null) {
        val current = read(context, id) ?: throw IOException("pending_request_not_found")
        write(context, current.copy(
            uploadedUrl = url,
            uploadedHash = hash,
            uploadedThumbnailUrl = thumbnailUrl ?: current.uploadedThumbnailUrl
        ))
    }

    @Synchronized
    fun markAttachmentUploaded(
        context: Context,
        id: String,
        attachmentId: String,
        url: String,
        hash: String?,
        thumbnailUrl: String? = null
    ) {
        val current = read(context, id) ?: throw IOException("pending_request_not_found")
        val updatedAttachments = if (current.attachments.isNotEmpty()) {
            current.attachments.map { att ->
                if (att.id == attachmentId) {
                    att.copy(
                        uploadedUrl = url,
                        uploadedHash = hash ?: att.uploadedHash,
                        uploadedThumbnailUrl = thumbnailUrl ?: att.uploadedThumbnailUrl
                    )
                } else att
            }
        } else {
            emptyList()
        }
        val isFirst = if (current.attachments.isNotEmpty()) {
            current.attachments.first().id == attachmentId
        } else {
            true
        }
        write(context, current.copy(
            attachments = updatedAttachments,
            uploadedUrl = if (isFirst) url else current.uploadedUrl,
            uploadedHash = if (isFirst) (hash ?: current.uploadedHash) else current.uploadedHash,
            uploadedThumbnailUrl = if (isFirst) (thumbnailUrl ?: current.uploadedThumbnailUrl) else current.uploadedThumbnailUrl
        ))
    }

    @Synchronized
    fun markCompleted(context: Context, id: String, result: SubmitTesterReportResult) {
        require(result.success && result.reportId.isNotBlank()) { "invalid_report_result" }
        val current = read(context, id) ?: throw IOException("pending_request_not_found")
        write(context, current.copy(completedResult = result))
        val draftReferenced = MissionReportDraftStore.allReferencedFilePaths(context)
        fun shouldDeletePrepared(path: String?): Boolean {
            if (path.isNullOrBlank()) return false
            val abs = File(path).absolutePath
            return !PreparedScreenshotCache.isLeased(abs) && abs !in draftReferenced
        }

        // The receipt is sufficient for replay, so pending request copy image files can now be removed.
        deleteInsideAppStorage(context, current.screenshotPath)
        deleteInsideAppStorage(context, current.thumbnailPath)
        if (shouldDeletePrepared(current.preparedPath)) {
            deleteInsideAppStorage(context, current.preparedPath)
        }
        if (shouldDeletePrepared(current.preparedThumbnailPath)) {
            deleteInsideAppStorage(context, current.preparedThumbnailPath)
        }
        for (att in current.attachments) {
            deleteInsideAppStorage(context, att.screenshotPath)
            deleteInsideAppStorage(context, att.thumbnailPath)
            if (shouldDeletePrepared(att.preparedPath)) {
                deleteInsideAppStorage(context, att.preparedPath)
            }
            if (shouldDeletePrepared(att.preparedThumbnailPath)) {
                deleteInsideAppStorage(context, att.preparedThumbnailPath)
            }
        }
        val orphanThumb = File(context.noBackupFilesDir, "prepared_report_images/thumb_${id}.jpg")
        if (orphanThumb.isFile && shouldDeletePrepared(orphanThumb.absolutePath)) {
            orphanThumb.delete()
        }
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
            val draftReferenced = MissionReportDraftStore.allReferencedFilePaths(context)
            fun shouldDeletePrepared(path: String?): Boolean {
                if (path.isNullOrBlank()) return false
                val abs = File(path).absolutePath
                return !PreparedScreenshotCache.isLeased(abs) && abs !in draftReferenced
            }

            deleteInsideAppStorage(context, report.screenshotPath)
            deleteInsideAppStorage(context, report.thumbnailPath)
            if (shouldDeletePrepared(report.preparedPath)) {
                deleteInsideAppStorage(context, report.preparedPath)
            }
            if (shouldDeletePrepared(report.preparedThumbnailPath)) {
                deleteInsideAppStorage(context, report.preparedThumbnailPath)
            }
            for (att in report.attachments) {
                deleteInsideAppStorage(context, att.screenshotPath)
                deleteInsideAppStorage(context, att.thumbnailPath)
                if (shouldDeletePrepared(att.preparedPath)) {
                    deleteInsideAppStorage(context, att.preparedPath)
                }
                if (shouldDeletePrepared(att.preparedThumbnailPath)) {
                    deleteInsideAppStorage(context, att.preparedThumbnailPath)
                }
            }
            val orphanThumb = File(context.noBackupFilesDir, "prepared_report_images/thumb_${id}.jpg")
            if (orphanThumb.isFile && shouldDeletePrepared(orphanThumb.absolutePath)) {
                orphanThumb.delete()
            }
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

        fun isWorkUnresolved(id: String): Boolean {
            return try {
                val wm = androidx.work.WorkManager.getInstance(context)
                val infos = wm.getWorkInfosForUniqueWork("report_submit_$id").get()
                infos.any { !it.state.isFinished }
            } catch (_: Exception) {
                false
            }
        }

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

            val paths = mutableSetOf<String>()
            report.screenshotPath?.let { paths += File(it).absolutePath }
            report.thumbnailPath?.let { paths += File(it).absolutePath }
            for (att in report.attachments) {
                paths += File(att.screenshotPath).absolutePath
                att.thumbnailPath?.let { paths += File(it).absolutePath }
            }
            referenced.addAll(paths)

            val unresolved = isWorkUnresolved(report.id)
            if (report.createdAt < cutoff && !unresolved) {
                deleteInsideAppStorage(context, report.screenshotPath)
                deleteInsideAppStorage(context, report.thumbnailPath)
                for (att in report.attachments) {
                    deleteInsideAppStorage(context, att.screenshotPath)
                    deleteInsideAppStorage(context, att.thumbnailPath)
                }
                file.delete()
            } else {
                val bytes = file.length() + paths.sumOf { File(it).length() }
                entries += Triple(file, report, bytes)
            }
        }

        // Giới hạn tổng dung lượng/số request: xoá request cũ nhất trước, nhưng giữ work chưa giải quyết
        var totalBytes = entries.sumOf { it.third }
        var remaining = entries.size
        entries.sortedBy { it.second.createdAt }.forEach { (file, report, bytes) ->
            if (remaining <= MAX_FILES && totalBytes <= MAX_TOTAL_BYTES) return@forEach
            if (isWorkUnresolved(report.id)) return@forEach
            deleteInsideAppStorage(context, report.screenshotPath)
            deleteInsideAppStorage(context, report.thumbnailPath)
            for (att in report.attachments) {
                deleteInsideAppStorage(context, att.screenshotPath)
                deleteInsideAppStorage(context, att.thumbnailPath)
            }
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
     * Kiểm tra budget bộ nhớ trước khi tiếp nhận request mới.
     * Ngăn chặn tình trạng vượt trần 20MB/20 request dẫn đến việc tự ý evict các công việc đang chạy.
     */
    @Synchronized
    fun canAdmit(context: Context, estimatedBytes: Long, newRequestsCount: Int = 1): Boolean {
        cleanup(context)
        val dir = directory(context)
        val currentBytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val currentRequests = list(context).count { it.completedResult == null }
        if (currentRequests + newRequestsCount > MAX_FILES) return false
        if (currentBytes + estimatedBytes > MAX_TOTAL_BYTES) return false
        return true
    }

    /**
     * Campaign id của các báo cáo đang chờ upload (chưa lên server).
     * Dùng để không gợi ý lại app vừa test trong lúc báo cáo còn trong hàng đợi.
     * Hỗ trợ contextFilter để nhiệm vụ không làm mất lượt của app_test thường.
     */
    @Synchronized
    fun pendingCampaignIds(context: Context, contextFilter: String? = null): Set<String> {
        val files = directory(context).listFiles { file ->
            file.isFile && file.name.endsWith(".json")
        } ?: return emptySet()
        return files.mapNotNull { file ->
            try {
                val report = read(context, file.name.removeSuffix(".json"))
                if (report != null && report.completedResult == null) {
                    if (contextFilter == null || report.submissionContext == contextFilter) {
                        report.campaignId
                    } else null
                } else null
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
