package com.ntt.crosstest.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.concurrent.atomic.AtomicReference

/**
 * Real-time network connectivity monitor.
 * Provides both synchronous checks and reactive Flow streams.
 */
object NetworkMonitor {

    internal fun hasInternetCapability(capabilities: NetworkCapabilities?): Boolean =
        capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)

    private fun hasUnrestrictedInternetCapability(capabilities: NetworkCapabilities?): Boolean =
        capabilities != null &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)

    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val activeNetwork = cm.activeNetwork
        if (activeNetwork != null && hasInternetCapability(cm.getNetworkCapabilities(activeNetwork))) {
            return true
        }
        // Fallback cho một số máy 5G Dual-SIM / chuyển vùng NSA-SA khi activeNetwork tạm thời null
        // hoặc trỏ sang slice phụ trong vài trăm ms.
        @Suppress("DEPRECATION")
        val networks = try {
            cm.allNetworks
        } catch (_: Exception) {
            emptyArray()
        }
        return networks.any { network ->
            hasUnrestrictedInternetCapability(cm.getNetworkCapabilities(network))
        }
    }

    fun observeNetwork(context: Context): Flow<Boolean> = callbackFlow {
        val appContext = context.applicationContext
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            trySend(true)
            close()
            return@callbackFlow
        }

        val lastNetwork = AtomicReference<Network?>(cm.activeNetwork)

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val previous = lastNetwork.getAndSet(network)
                if (previous != null && previous != network) {
                    NetworkHttpFactory.evictAllConnections()
                }
                if (hasInternetCapability(cm.getNetworkCapabilities(network))) {
                    trySend(true)
                }
            }

            override fun onLost(network: Network) {
                NetworkHttpFactory.evictAllConnections()
                val active = cm.activeNetwork
                val activeOnline = active != null &&
                    active != network &&
                    hasInternetCapability(cm.getNetworkCapabilities(active))
                @Suppress("DEPRECATION")
                val fallbackOnline = if (!activeOnline) {
                    val networks = try {
                        cm.allNetworks
                    } catch (_: Exception) {
                        emptyArray()
                    }
                    networks.any { candidate ->
                        candidate != network &&
                            hasUnrestrictedInternetCapability(cm.getNetworkCapabilities(candidate))
                    }
                } else {
                    false
                }
                val stillOnline = activeOnline || fallbackOnline
                if (activeOnline) {
                    lastNetwork.set(active)
                } else if (!stillOnline) {
                    lastNetwork.compareAndSet(network, null)
                }
                trySend(stillOnline)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val previous = lastNetwork.getAndSet(network)
                if (previous != null && previous != network) {
                    NetworkHttpFactory.evictAllConnections()
                }
                trySend(hasInternetCapability(capabilities))
            }
        }

        // Emit current initial status immediately
        trySend(isOnline(appContext))

        try {
            // Observe the route actually used by HTTP requests, not every Wi-Fi,
            // mobile or VPN network. API 26+ delivers capabilities after available.
            cm.registerDefaultNetworkCallback(callback)
        } catch (_: Exception) {
            trySend(isOnline(appContext))
        }

        awaitClose {
            try {
                cm.unregisterNetworkCallback(callback)
            } catch (_: Exception) {}
        }
    }.distinctUntilChanged()
}
