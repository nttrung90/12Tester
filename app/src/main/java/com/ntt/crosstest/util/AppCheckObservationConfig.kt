package com.ntt.crosstest.util

import android.content.Context
import android.util.Log
import com.ntt.crosstest.BuildConfig
import java.util.concurrent.atomic.AtomicLong

/**
 * Chế độ thực thi Firebase App Check (E04).
 */
enum class AppCheckEnforcementMode {
    /** Chưa bật */
    DISABLED,
    /**
     * Chế độ quan sát (Monitor/Observation Only).
     * Thu thập metric kiểm chứng token hợp lệ từ Play Integrity mà không chặn request.
     * Đây là chế độ bắt buộc trong giai đoạn chuẩn bị rollout để tránh làm gãy app cũ.
     */
    MONITOR_ONLY,
    /** Thực thi chặn cứng (Chỉ kích hoạt sau khi đã kiểm tra an toàn 100%) */
    ENFORCED
}

/**
 * Trạng thái quan sát thực tế tại runtime.
 */
enum class AppCheckObservationState {
    UNCONFIGURED,
    MONITORING,
    ENFORCING
}

/**
 * Cấu hình App Check quan sát và chiến lược kiểm soát truy cập media (E04).
 *
 * Ràng buộc bảo mật & dữ liệu nghiêm ngặt (F03 / R2-03):
 * 1. App Check ở client bảo vệ các cuộc gọi SDK Firebase, NHƯNG các URL download token
 *    trực tiếp (chứa ?token=...) vẫn có thể truy cập được từ trình duyệt/ngoài app
 *    trừ khi được revoke hoặc chuyển qua Gateway có xác thực quyền.
 * 2. Tuyệt đối KHÔNG chứa Service Account Key hoặc Supabase service_role key trong code Android.
 * 3. Ở chế độ MONITOR_ONLY, quan sát và ghi nhận tỷ lệ token hợp lệ/thiếu nhưng TUYỆT ĐỐI KHÔNG chặn request.
 * 4. Không tự ý ép enforcement hay giả định token tồn tại khi HTTP thành công;
 *    xử lý trạng thái UNCONFIGURED rõ ràng khi thiếu SDK/provider.
 */
object AppCheckObservationConfig {

    private const val TAG = "AppCheckObservation"

    @Volatile
    var currentMode: AppCheckEnforcementMode = AppCheckEnforcementMode.MONITOR_ONLY

    @Volatile
    var observationState: AppCheckObservationState = AppCheckObservationState.UNCONFIGURED

    val validTokensObserved = AtomicLong(0L)
    val missingOrInvalidTokensObserved = AtomicLong(0L)

    /**
     * Khởi tạo quan sát App Check an toàn qua AppCheckListener thực tế.
     * Xử lý trạng thái thiếu cấu hình (UNCONFIGURED) rõ ràng.
     */
    fun initObservation(context: Context) {
        if (currentMode == AppCheckEnforcementMode.DISABLED) {
            observationState = AppCheckObservationState.UNCONFIGURED
            return
        }
        try {
            val firebaseApp = try {
                com.google.firebase.FirebaseApp.getInstance()
            } catch (_: IllegalStateException) {
                try {
                    com.google.firebase.FirebaseApp.initializeApp(context)
                } catch (_: Exception) {
                    null
                }
            }

            if (firebaseApp != null) {
                val appCheck = com.google.firebase.appcheck.FirebaseAppCheck.getInstance(firebaseApp)
                val factory = if (BuildConfig.DEBUG) {
                    com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory.getInstance()
                } else {
                    com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory.getInstance()
                }
                appCheck.installAppCheckProviderFactory(factory)
                appCheck.addAppCheckListener { token ->
                    val isValid = !token.token.isNullOrBlank()
                    recordTokenObservation(isValid, "AppCheckListener")
                }
                observationState = if (currentMode == AppCheckEnforcementMode.ENFORCED) {
                    AppCheckObservationState.ENFORCING
                } else {
                    AppCheckObservationState.MONITORING
                }
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "FirebaseAppCheck provider factory and listener registered, state: $observationState")
                }
                return
            }
            observationState = AppCheckObservationState.UNCONFIGURED
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "FirebaseAppCheck unconfigured (FirebaseApp not initialized)")
            }
        } catch (e: Exception) {
            observationState = AppCheckObservationState.UNCONFIGURED
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "AppCheck observation init non-fatal: ${e.message}")
            }
        }
    }

    /**
     * Ghi nhận telemetry kiểm tra token hợp lệ.
     */
    fun recordTokenObservation(isValidTokenPresent: Boolean, provider: String = "PlayIntegrity") {
        if (currentMode == AppCheckEnforcementMode.DISABLED) return

        if (isValidTokenPresent) {
            validTokensObserved.incrementAndGet()
        } else {
            missingOrInvalidTokensObserved.incrementAndGet()
        }

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "app_check_observation valid=$isValidTokenPresent provider=$provider mode=$currentMode validCount=${validTokensObserved.get()} invalidCount=${missingOrInvalidTokensObserved.get()}")
        }
    }

    /**
     * Xác định request có được phép tiếp tục hay không.
     * Trong mode MONITOR_ONLY, luôn luôn trả về true (không chặn người dùng thật).
     */
    fun isRequestAllowed(hasValidToken: Boolean): Boolean {
        recordTokenObservation(hasValidToken)
        return when (currentMode) {
            AppCheckEnforcementMode.DISABLED -> true
            AppCheckEnforcementMode.MONITOR_ONLY -> true
            AppCheckEnforcementMode.ENFORCED -> hasValidToken
        }
    }

    fun reset() {
        validTokensObserved.set(0L)
        missingOrInvalidTokensObserved.set(0L)
        observationState = AppCheckObservationState.UNCONFIGURED
        currentMode = AppCheckEnforcementMode.MONITOR_ONLY
    }
}
