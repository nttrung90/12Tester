package com.ntt.crosstest.util

import android.os.SystemClock
import android.util.Log
import com.ntt.crosstest.BuildConfig
import com.ntt.crosstest.data.SupabaseHolder
import com.ntt.crosstest.util.NetworkHttpFactory.applyResilient5GConfig
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Helper tải ảnh chụp màn hình báo cáo lên Supabase Private Storage.
 *
 * Tiêu chí:
 * 1. Upload qua Supabase Storage API REST chuẩn với Bearer JWT token của phiên người dùng.
 * 2. Header "x-upsert: false" để không cho phép ghi đè (append-only/immutable).
 * 3. Nếu object đã tồn tại (HTTP 409 Conflict) từ lần upload trước bị mất mạng -> khôi phục idempotent.
 * 4. Retry có exponential backoff cho lỗi mạng tạm thời (429, 5xx).
 * 5. URL trả về là đường dẫn chuẩn authenticated: /storage/v1/object/authenticated/<bucket>/<path>.
 */
object SupabaseStorageHelper {

    private const val TAG = "SupabaseStorageHelper"
    private const val DEFAULT_BUCKET = "report-screenshots"
    private val JPEG_MEDIA_TYPE = "image/jpeg".toMediaType()

    @androidx.annotation.VisibleForTesting
    internal var clientOverride: OkHttpClient? = null

    internal val httpClient: OkHttpClient
        get() = clientOverride ?: defaultHttpClient

    private val defaultHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .applyResilient5GConfig(
                connectTimeoutSeconds = 8L,
                readTimeoutSeconds = 30L,
                writeTimeoutSeconds = 30L,
                pingIntervalSeconds = 15L,
            )
            .addNetworkInterceptor(StripAuthOnCrossOriginRedirectInterceptor())
            .build()
    }

    /**
     * Lấy JWT token hiện tại của user từ SupabaseHolder.
     */
    fun resolveAuthToken(): String? {
        if (!SupabaseHolder.isInitialized()) return null
        return try {
            val supabase = SupabaseHolder.get()
            val status = supabase.auth.sessionStatus.value
            if (status is SessionStatus.Authenticated) {
                status.session.accessToken
            } else {
                supabase.auth.currentAccessTokenOrNull()
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Upload screenshot báo cáo lên Supabase Storage.
     */
    suspend fun uploadReportScreenshot(
        file: File,
        bucket: String = DEFAULT_BUCKET,
        objectPath: String,
        customToken: String? = null,
        maxRetries: Int = 3,
    ): String = withContext(Dispatchers.IO) {
        val token = customToken ?: resolveAuthToken()
            ?: throw IOException("supabase_auth_token_missing")

        val cleanPath = objectPath.trimStart('/')
        val baseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
        val anonKey = BuildConfig.SUPABASE_ANON_KEY

        val uploadUrl = "$baseUrl/storage/v1/object/$bucket/$cleanPath"
        val authenticatedDownloadUrl = "$baseUrl/storage/v1/object/authenticated/$bucket/$cleanPath"

        var attempt = 0
        var lastError: Exception? = null

        val started = SystemClock.elapsedRealtime()
        if (BuildConfig.DEBUG) Log.d(TAG, "upload_start: $cleanPath")

        while (attempt < maxRetries) {
            attempt++
            try {
                val requestBody = file.asRequestBody(JPEG_MEDIA_TYPE)
                val request = Request.Builder()
                    .url(uploadUrl)
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("apikey", anonKey)
                    .addHeader("Content-Type", "image/jpeg")
                    .addHeader("x-upsert", "false")
                    .post(requestBody)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val code = response.code

                    // 200 / 201: Upload thành công
                    if (code in 200..204) {
                        if (BuildConfig.DEBUG) Log.d(TAG, "upload_success: $cleanPath elapsed_ms=${SystemClock.elapsedRealtime() - started}")
                        return@withContext authenticatedDownloadUrl
                    }

                    // 409 Conflict: Object đã tồn tại từ lần upload trước (idempotent recovery)
                    // Bắt buộc xác minh toàn vẹn SHA256 streaming so với file local, ca cùng kích thước khác nội dung phải FAIL
                    if (code == 409) {
                        val isVerified = verifyRemoteObjectSha256(
                            file = file,
                            authenticatedDownloadUrl = authenticatedDownloadUrl,
                            customToken = token,
                        )

                        if (isVerified) {
                            if (BuildConfig.DEBUG) Log.d(TAG, "upload_conflict_recovered: $cleanPath elapsed_ms=${SystemClock.elapsedRealtime() - started}")
                            return@withContext authenticatedDownloadUrl
                        } else {
                            throw IOException("supabase_upload_conflict_verification_failed: remote content mismatch or inaccessible for $cleanPath")
                        }
                    }

                    // 429 hoặc 5xx: Lỗi tạm thời, retry có backoff
                    if ((code == 429 || code in 500..599) && attempt < maxRetries) {
                        val backoffMs = (Math.pow(2.0, attempt.toDouble()) * 300).toLong()
                        delay(backoffMs)
                        return@use
                    }

                    val errorBody = response.body.string()
                    throw IOException("supabase_upload_failed: HTTP $code - $errorBody")
                }
            } catch (e: Exception) {
                lastError = e
                if (attempt >= maxRetries) break
                val backoffMs = (Math.pow(2.0, attempt.toDouble()) * 300).toLong()
                delay(backoffMs)
            }
        }

        throw lastError ?: IOException("supabase_upload_retry_exhausted")
    }

    /**
     * Upload thumbnail ảnh báo cáo lên Supabase Storage.
     * Thất bại là non-fatal, trả về null để không chặn bài nộp chính.
     */
    suspend fun uploadReportThumbnail(
        thumbnailFile: File,
        bucket: String = DEFAULT_BUCKET,
        thumbnailPath: String,
        customToken: String? = null,
    ): String? = withContext(Dispatchers.IO) {
        try {
            uploadReportScreenshot(
                file = thumbnailFile,
                bucket = bucket,
                objectPath = thumbnailPath,
                customToken = customToken,
                maxRetries = 2,
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (BuildConfig.DEBUG) Log.w(TAG, "thumb_upload_failed non-fatal: ${e.message}")
            null
        }
    }

    const val MAX_VERIFY_BODY_BYTES = 5_000_000L // 5MB giới hạn dung lượng tải ảnh an toàn

    fun calculateSha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Xác minh object trên remote có khớp chính xác mã băm SHA256 với file local hay không.
     * Đọc streaming từ HTTP body, tôn trọng coroutine cancellation và giới hạn kích thước an toàn.
     */
    suspend fun verifyRemoteObjectSha256(
        file: File,
        authenticatedDownloadUrl: String,
        customToken: String? = null,
        maxBytes: Long = MAX_VERIFY_BODY_BYTES,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext false
        val localSha256 = try {
            calculateSha256(file)
        } catch (_: Exception) { return@withContext false }

        val token = customToken ?: resolveAuthToken() ?: return@withContext false
        val anonKey = BuildConfig.SUPABASE_ANON_KEY

        val request = Request.Builder()
            .url(authenticatedDownloadUrl)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("apikey", anonKey)
            .get()
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                val code = response.code
                if (!response.isSuccessful) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "verify_sha256_http_error: HTTP $code")
                    // Phân biệt: 404/410 là object chắc chắn không tồn tại hoặc đã xóa vĩnh viễn (false terminal)
                    if (code == 404 || code == 410) {
                        return@withContext false
                    }
                    // Lỗi tạm thời (429 rate limit, 5xx server error): ném IOException để worker retry, không biến thành lỗi terminal
                    if (code == 429 || code in 500..599) {
                        throw IOException("supabase_verify_temporary_error: HTTP $code")
                    }
                    // Các lỗi HTTP khác
                    throw IOException("supabase_verify_failed: HTTP $code")
                }

                val body = response.body
                val contentLength = body.contentLength()
                if (contentLength > 0 && contentLength != file.length()) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "verify_sha256_length_mismatch: remote=$contentLength local=${file.length()}")
                    return@withContext false
                }
                if (contentLength > maxBytes) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "verify_sha256_exceeded_limit: $contentLength > $maxBytes")
                    return@withContext false
                }

                val md = MessageDigest.getInstance("SHA-256")
                val stream = body.byteStream()
                val buffer = ByteArray(8192)
                var totalRead = 0L
                var read: Int

                while (stream.read(buffer).also { read = it } != -1) {
                    currentCoroutineContext().ensureActive()
                    totalRead += read
                    if (totalRead > maxBytes) {
                        if (BuildConfig.DEBUG) Log.w(TAG, "verify_sha256_stream_exceeded_limit: totalRead=$totalRead > $maxBytes")
                        return@withContext false
                    }
                    md.update(buffer, 0, read)
                }

                if (totalRead != file.length()) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "verify_sha256_stream_length_mismatch: streamBytes=$totalRead local=${file.length()}")
                    return@withContext false
                }

                val remoteSha256 = md.digest().joinToString("") { "%02x".format(it) }
                val matches = remoteSha256.equals(localSha256, ignoreCase = true)
                if (!matches && BuildConfig.DEBUG) {
                    Log.w(TAG, "verify_sha256_content_mismatch: remote=$remoteSha256 local=$localSha256")
                }
                matches
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is IOException) throw e
            if (BuildConfig.DEBUG) Log.w(TAG, "verify_sha256_failed: ${e.message}")
            throw IOException("supabase_verify_exception: ${e.message}", e)
        }
    }

    suspend fun verifyRemoteObject(
        file: File,
        bucket: String = DEFAULT_BUCKET,
        objectPath: String,
        customToken: String? = null,
    ): Boolean {
        val cleanPath = objectPath.trimStart('/')
        val baseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
        val authenticatedDownloadUrl = "$baseUrl/storage/v1/object/authenticated/$bucket/$cleanPath"
        return verifyRemoteObjectSha256(file, authenticatedDownloadUrl, customToken)
    }
}
