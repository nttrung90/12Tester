package com.ntt.crosstest.data.telegram

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TdLibException(val error: TdApi.Error) : Exception("${error.code}: ${error.message}") {
    val code: Int get() = error.code
    val tdMessage: String get() = error.message
}

/**
 * Abstraction over TDLib [Client] so repository/business logic can be unit-tested
 * on JVM/Robolectric without loading native `libtdjni.so`.
 */
interface TdLibGateway {
    val updates: Flow<TdApi.Object>
    fun start()
    suspend fun <T : TdApi.Object> send(query: TdApi.Function<T>): T
    fun close()
}

class TdLibClientWrapper(
    private val logVerbosityLevel: Int = 1,
) : TdLibGateway {

    private val _updates = MutableSharedFlow<TdApi.Object>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val updates: Flow<TdApi.Object> = _updates.asSharedFlow()

    @Volatile
    private var client: Client? = null

    @Synchronized
    override fun start() {
        if (client != null) return
        try {
            Client.execute(TdApi.SetLogVerbosityLevel(logVerbosityLevel))
        } catch (_: Throwable) {
            // Best-effort verbosity config; do not fail startup if native execute throws.
        }
        client = Client.create(
            { obj ->
                if (obj != null) {
                    if (obj is TdApi.UpdateAuthorizationState &&
                        obj.authorizationState is TdApi.AuthorizationStateClosed
                    ) {
                        synchronized(this@TdLibClientWrapper) {
                            client = null
                        }
                    }
                    _updates.tryEmit(obj)
                }
            },
            null,
            null,
        )
    }

    override suspend fun <T : TdApi.Object> send(query: TdApi.Function<T>): T =
        suspendCancellableCoroutine { cont ->
            val currentClient = client ?: run {
                cont.resumeWithException(IllegalStateException("TDLib client not started"))
                return@suspendCancellableCoroutine
            }
            currentClient.send(
                query,
                { result ->
                    if (!cont.isActive) return@send
                    if (result is TdApi.Error) {
                        cont.resumeWithException(TdLibException(result))
                    } else {
                        @Suppress("UNCHECKED_CAST")
                        cont.resume(result as T)
                    }
                },
                { throwable ->
                    if (cont.isActive) {
                        cont.resumeWithException(throwable)
                    }
                },
            )
        }

    @Synchronized
    override fun close() {
        val currentClient = client ?: return
        client = null
        try {
            currentClient.send(TdApi.Close(), null)
        } catch (_: Throwable) {
            // Ignore shutdown errors.
        }
    }
}
