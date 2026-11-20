package com.ntt.crosstest.util

import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Shared OkHttp configuration and DNS/connection-pool helpers resilient to 5G Dual-Stack
 * IPv6 blackholes and stale radio sockets across network transitions.
 */
object NetworkHttpFactory {

    val sharedConnectionPool: ConnectionPool = ConnectionPool(5, 5, TimeUnit.MINUTES)

    val ipv4PreferredDns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            return sortAddressesIpv4First(Dns.SYSTEM.lookup(hostname))
        }
    }

    /**
     * Orders IPv4 addresses ahead of IPv6 addresses while preserving relative order within each
     * family so 5G Dual-Stack avoids IPv6 blackhole stalls without breaking IPv6-only/NAT64 networks.
     */
    fun sortAddressesIpv4First(addresses: List<InetAddress>): List<InetAddress> {
        return addresses.sortedBy { it is Inet6Address }
    }

    fun evictAllConnections() {
        try {
            sharedConnectionPool.evictAll()
        } catch (_: Throwable) {
        }
    }

    fun OkHttpClient.Builder.applyResilient5GConfig(
        connectTimeoutSeconds: Long = 8L,
        readTimeoutSeconds: Long = 30L,
        writeTimeoutSeconds: Long = 30L,
        pingIntervalSeconds: Long = 15L,
    ): OkHttpClient.Builder {
        return dns(ipv4PreferredDns)
            .connectionPool(sharedConnectionPool)
            .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(writeTimeoutSeconds, TimeUnit.SECONDS)
            .pingInterval(pingIntervalSeconds, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
    }
}
