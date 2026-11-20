package com.ntt.crosstest.util

import android.util.Log
import coil.EventListener
import coil.decode.DataSource
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Instrumentation đo lường chi phí mạng của ảnh (E00).
 *
 * Tiêu chí an toàn thông tin & độ tin cậy:
 * - Mặc định tắt (opt-in/debug); chỉ bật khi kiểm thử hoặc kích hoạt có kiểm soát.
 * - onError tôn trọng cờ isEnabled.
 * - Băm object ID thay vì ghi log filename/local/content path; không in e.message thô.
 * - Đếm response-body bytes chuẩn xác (không gán nhãn sai là wire/billable bytes).
 * - Tách bạch IO failure và cancellation, tránh double-count.
 */
object ImageInstrumentation {

    private const val TAG = "ImageInstrumentation"

    @Volatile
    var isEnabled: Boolean = false

    val networkRequests = AtomicInteger(0)
    val responseBodyBytes = AtomicLong(0L)
    val memoryHits = AtomicInteger(0)
    val diskHits = AtomicInteger(0)
    val canceledRequests = AtomicInteger(0)
    val ioFailures = AtomicInteger(0)
    val statusCodes = ConcurrentHashMap<Int, AtomicInteger>()
    val requestsByPurpose = ConcurrentHashMap<String, AtomicInteger>()

    data class EgressSnapshot(
        val networkRequests: Int,
        val responseBodyBytes: Long,
        val memoryHits: Int,
        val diskHits: Int,
        val canceledRequests: Int,
        val ioFailures: Int,
        val statusCodes: Map<Int, Int>,
        val requestsByPurpose: Map<String, Int>,
    )

    fun reset() {
        networkRequests.set(0)
        responseBodyBytes.set(0L)
        memoryHits.set(0)
        diskHits.set(0)
        canceledRequests.set(0)
        ioFailures.set(0)
        statusCodes.clear()
        requestsByPurpose.clear()
    }

    fun snapshot(): EgressSnapshot {
        return EgressSnapshot(
            networkRequests = networkRequests.get(),
            responseBodyBytes = responseBodyBytes.get(),
            memoryHits = memoryHits.get(),
            diskHits = diskHits.get(),
            canceledRequests = canceledRequests.get(),
            ioFailures = ioFailures.get(),
            statusCodes = HashMap(statusCodes.mapValues { it.value.get() }),
            requestsByPurpose = HashMap(requestsByPurpose.mapValues { it.value.get() }),
        )
    }

    /**
     * Băm định danh đối tượng (object ID) thành hash hex cố định.
     * TUYỆT ĐỐI không giữ filename, local URI, user ID hay token.
     */
    fun sanitizeUrl(url: String): String {
        val noQuery = url.substringBefore('?')
        val hash = (noQuery.hashCode().toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0')
        return when {
            noQuery.contains("/reports/") || noQuery.contains("reports%2F") -> "reports/obj_$hash"
            noQuery.contains("/app_icons/") || noQuery.contains("app_icons%2F") -> "app_icons/obj_$hash"
            else -> "image/obj_$hash"
        }
    }

    fun recordMemoryHit() {
        if (!isEnabled) return
        memoryHits.incrementAndGet()
    }

    fun recordDiskHit() {
        if (!isEnabled) return
        diskHits.incrementAndGet()
    }

    fun recordPurpose(purpose: String) {
        if (!isEnabled) return
        requestsByPurpose.computeIfAbsent(purpose) { AtomicInteger(0) }.incrementAndGet()
    }

    fun recordStatusCode(code: Int) {
        if (!isEnabled) return
        statusCodes.computeIfAbsent(code) { AtomicInteger(0) }.incrementAndGet()
    }

    /**
     * OkHttp Interceptor đo số request HTTP GET và response-body bytes thực đọc.
     */
    fun createTrackingInterceptor(): Interceptor = Interceptor { chain ->
        val request = chain.request()
        if (!isEnabled) {
            return@Interceptor chain.proceed(request)
        }

        val purpose = request.header("X-Image-Purpose") ?: "unspecified"
        recordPurpose(purpose)
        networkRequests.incrementAndGet()

        val isFailureRecorded = AtomicBoolean(false)
        val call = chain.call()

        val response: Response
        try {
            response = chain.proceed(request)
        } catch (e: IOException) {
            if (isFailureRecorded.compareAndSet(false, true)) {
                if (call.isCanceled()) {
                    canceledRequests.incrementAndGet()
                } else {
                    ioFailures.incrementAndGet()
                }
            }
            throw e
        }

        val code = response.code
        recordStatusCode(code)

        val originalBody = response.body
        val countingSource = object : ForwardingSource(originalBody.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                try {
                    val bytesRead = super.read(sink, byteCount)
                    if (bytesRead > 0) {
                        responseBodyBytes.addAndGet(bytesRead)
                    }
                    return bytesRead
                } catch (e: IOException) {
                    if (isFailureRecorded.compareAndSet(false, true)) {
                        if (call.isCanceled()) {
                            canceledRequests.incrementAndGet()
                        } else {
                            ioFailures.incrementAndGet()
                        }
                    }
                    throw e
                }
            }
        }

        val wrappedBody = countingSource.buffer().asResponseBody(
            originalBody.contentType(),
            originalBody.contentLength()
        )

        response.newBuilder().body(wrappedBody).build()
    }

    /**
     * Coil EventListener theo dõi nguồn dữ liệu phục vụ (MEMORY_CACHE, DISK, NETWORK).
     */
    fun createEventListener(): EventListener = object : EventListener {
        override fun onSuccess(request: ImageRequest, result: SuccessResult) {
            if (!isEnabled) return
            when (result.dataSource) {
                DataSource.MEMORY_CACHE, DataSource.MEMORY -> {
                    val data = request.data
                    if (data !is String || !ScreenshotManager.isReportScreenshotUrl(data)) {
                        memoryHits.incrementAndGet()
                    }
                }
                DataSource.DISK -> diskHits.incrementAndGet()
                DataSource.NETWORK -> {
                    // Đã ghi nhận ở tầng Network/Interceptor
                }
            }
        }

        override fun onCancel(request: ImageRequest) {
            if (!isEnabled) return
            canceledRequests.incrementAndGet()
        }

        override fun onError(request: ImageRequest, result: ErrorResult) {
            if (!isEnabled) return
            // Log an toàn: không ghi e.message thô vì có thể chứa URL token; chỉ ghi sanitized ID và class name
            val errClass = result.throwable.javaClass.simpleName
            Log.w(TAG, "Image load error for ${sanitizeUrl(request.data.toString())}: $errClass")
        }
    }
}
