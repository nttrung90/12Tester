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
            "Thiếu SUPABASE_URL hoặc SUPABASE_ANON_KEY trong local.properties"
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
            httpEngine = OkHttp.create()
        }
        client = created
        return created
    }

    fun get(): SupabaseClient =
        client ?: throw IllegalStateException(
            "Supabase chưa được khởi tạo. Hãy điền SUPABASE_URL và SUPABASE_ANON_KEY vào local.properties."
        )
}
