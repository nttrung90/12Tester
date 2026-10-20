package com.ntt.crosstest.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.okhttp.OkHttp

/**
 * Single SupabaseClient for the whole app, created once in [com.ntt.crosstest.TestCheoApp].
 * The client is also reachable from [com.ntt.crosstest.work.ReminderWorker].
 */
object SupabaseHolder {

    @Volatile
    private var client: SupabaseClient? = null

    fun isInitialized(): Boolean = client != null

    @Synchronized
    fun init(url: String, key: String): SupabaseClient {
        client?.let { return it }
        require(url.isNotBlank() && key.isNotBlank()) {
            "Missing SUPABASE_URL or SUPABASE_ANON_KEY in local.properties"
        }
        val created = createSupabaseClient(
            supabaseUrl = url,
            supabaseKey = key,
        ) {
            install(Auth) {
                // Persist the session on device and refresh it automatically.
                autoSaveToStorage = true
                autoLoadFromStorage = true
                alwaysAutoRefresh = true
            }
            install(Postgrest)
            // Explicit engine: supabase-kt 3.x is built on Ktor 3.6.0.
            // Tuning: khi tải Trang chủ có nhiều truy vấn chạy song song → nâng giới hạn
            // kết nối đồng thời (mặc định OkHttp chỉ 5 request/host) và đặt timeout hợp lý.
            httpEngine = OkHttp.create {
                config {
                    dispatcher(
                        okhttp3.Dispatcher().apply {
                            maxRequests = 32
                            maxRequestsPerHost = 16
                        }
                    )
                    connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    retryOnConnectionFailure(true)
                }
            }
        }
        client = created
        return created
    }

    fun get(): SupabaseClient =
        client ?: throw IllegalStateException(
            "Supabase isn't initialized yet. Add SUPABASE_URL and SUPABASE_ANON_KEY to local.properties."
        )
}
