package com.ntt.crosstest.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

@Serializable
data class DraftAttachment(
    val uploadId: String,
    val filePath: String,
    val thumbnailPath: String? = null,
    val imageHash: String,
    val sizeBytes: Long,
    val contentType: String = "image/jpeg",
)

@Serializable
data class MissionReportDraft(
    val userId: String,
    val campaignId: String,
    val missionId: String,
    val reportDate: String,
    val rating: Int = 5,
    val comment: String = "",
    val preparedImages: List<DraftAttachment> = emptyList(),
    val testStartedAtMs: Long? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

object MissionReportDraftStore {
    private const val DIRECTORY = "mission_report_drafts"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun directory(context: Context): File =
        File(context.noBackupFilesDir, DIRECTORY).apply { mkdirs() }

    private fun draftFile(context: Context, userId: String, campaignId: String, missionId: String, reportDate: String): File {
        val safeName = "${userId}_${campaignId}_${missionId}_${reportDate}.json".replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(directory(context), safeName)
    }

    @Synchronized
    fun saveDraft(context: Context, draft: MissionReportDraft) {
        if (draft.userId.isBlank()) return
        val target = draftFile(context, draft.userId, draft.campaignId, draft.missionId, draft.reportDate)
        val temp = File(target.parentFile, "${target.name}.tmp")
        try {
            FileOutputStream(temp).use { stream ->
                stream.write(json.encodeToString(draft).toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            Files.move(temp.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
    }

    @Synchronized
    fun loadDraft(context: Context, userId: String, campaignId: String, missionId: String, reportDate: String): MissionReportDraft? {
        if (userId.isBlank()) return null
        val target = draftFile(context, userId, campaignId, missionId, reportDate)
        if (!target.isFile) return null
        return try {
            json.decodeFromString<MissionReportDraft>(target.readText())
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun clearDraft(context: Context, userId: String, campaignId: String, missionId: String, reportDate: String) {
        if (userId.isBlank()) return
        val target = draftFile(context, userId, campaignId, missionId, reportDate)
        if (target.isFile) {
            target.delete()
        }
    }

    @Synchronized
    fun allReferencedFilePaths(context: Context): Set<String> {
        val paths = mutableSetOf<String>()
        val dir = directory(context)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptySet()
        val cutoff = System.currentTimeMillis() - 48L * 60L * 60L * 1000L
        for (f in files) {
            try {
                val draft = json.decodeFromString<MissionReportDraft>(f.readText())
                if (draft.updatedAt >= cutoff) {
                    for (img in draft.preparedImages) {
                        paths.add(File(img.filePath).absolutePath)
                        img.thumbnailPath?.let { paths.add(File(it).absolutePath) }
                    }
                } else {
                    f.delete()
                }
            } catch (_: Exception) {}
        }
        return paths
    }

    @Synchronized
    fun clearAll(context: Context) {
        directory(context).deleteRecursively()
        directory(context).mkdirs()
    }
}
