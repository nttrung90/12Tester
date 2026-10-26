package com.ntt.crosstest.util

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * High-performance in-memory cache for installed packages and application icons.
 * Eliminates repetitive synchronous IPC queries to Android's PackageManager on the UI thread,
 * ensuring smooth 60-120fps scrolling and tab transitions.
 */
object InstalledAppCache {

    private const val NEGATIVE_TTL_MS = 15_000L

    @Volatile
    private var isInitialized = false

    @Volatile
    private var installedPackages: Set<String> = emptySet()

    @Volatile
    private var lastRefreshAt: Long = 0L

    @Volatile
    private var cachedInstalledUserApps: List<PlayStoreHelper.InstalledAppItem> = emptyList()

    /** Gói đã xác nhận là CHƯA cài (pkg -> thời điểm kiểm tra) — cùng cửa sổ
     *  15s với [ensureFresh] để tránh lặp IPC PackageManager mỗi lần refresh. */
    private val notInstalledCache = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // Cache up to 120 app icons in memory
    private val iconCache = object : LruCache<String, Drawable>(120) {}

    fun init(context: Context, scope: CoroutineScope = CoroutineScope(Dispatchers.IO)) {
        scope.launch(Dispatchers.IO) {
            refresh(context)
        }
    }

    fun refresh(context: Context) {
        refreshPackages(context)
        cachedInstalledUserApps = PlayStoreHelper.getInstalledUserApps(context)
    }

    /**
     * Lightweight refresh of the installed-package set only (no app icons/labels).
     */
    fun refreshPackages(context: Context) {
        try {
            val pm = context.packageManager
            val packages = pm.getInstalledPackages(0)
            val set = HashSet<String>(packages.size)
            for (pkg in packages) {
                set.add(pkg.packageName)
            }
            installedPackages = set
            // Trạng thái cài/không cài có thể đã đổi — bỏ mọi kết quả âm cũ.
            notInstalledCache.clear()
            isInitialized = true
            lastRefreshAt = System.currentTimeMillis()
        } catch (_: Exception) {
            // Keep previous set on failure
        }
    }

    /**
     * Refreshes the installed-package snapshot if it is missing or older than [maxAgeMs].
     * Ensures uninstalled apps are detected promptly (the memory set alone goes stale).
     * Call from a background thread (PackageManager enumeration is synchronous).
     */
    fun ensureFresh(context: Context, maxAgeMs: Long = 15_000L) {
        val age = System.currentTimeMillis() - lastRefreshAt
        if (!isInitialized || age > maxAgeMs) {
            refreshPackages(context)
        }
    }

    /**
     * Retrieves cached user installed launcher apps (< 1ms).
     */
    fun getInstalledUserApps(context: Context): List<PlayStoreHelper.InstalledAppItem> {
        if (cachedInstalledUserApps.isNotEmpty()) {
            return cachedInstalledUserApps
        }
        val apps = PlayStoreHelper.getInstalledUserApps(context)
        cachedInstalledUserApps = apps
        return apps
    }

    /**
     * Accurate multi-tier check to verify if an application is installed on device.
     */
    fun isInstalled(context: Context, packageName: String): Boolean {
        val cleanPkg = packageName.trim()
        if (cleanPkg.isBlank()) return false
        // 1. Fast O(1) in-memory set check
        if (isInitialized && installedPackages.contains(cleanPkg)) {
            return true
        }
        // 2. Negative cache ngắn hạn — kết quả "chưa cài" mới kiểm tra trong
        //    15s thì khỏi lặp lại IPC bên dưới cho cùng một package.
        val now = System.currentTimeMillis()
        if (notInstalledCache[cleanPkg]?.let { now - it < NEGATIVE_TTL_MS } == true) {
            return false
        }
        // 3. Official Android PackageManager query for the specific package (bypasses list filters)
        val byPackageInfo = try {
            context.packageManager.getPackageInfo(cleanPkg, 0) != null
        } catch (_: Exception) {
            false
        }
        // 4. Launch intent verification
        val byLaunchIntent = try {
            context.packageManager.getLaunchIntentForPackage(cleanPkg) != null
        } catch (_: Exception) {
            false
        }
        if (byPackageInfo || byLaunchIntent) {
            synchronized(this) {
                installedPackages = installedPackages + cleanPkg
            }
            notInstalledCache.remove(cleanPkg)
            return true
        }
        notInstalledCache[cleanPkg] = now
        return false
    }

    /**
     * Retrieves application icon with LruCache, avoiding IPC overhead on repeat requests.
     */
    fun getInstalledAppDrawable(context: Context, packageName: String): Drawable? {
        if (packageName.isBlank()) return null
        synchronized(iconCache) {
            val cached = iconCache.get(packageName)
            if (cached != null) return cached
        }

        return try {
            val icon = context.packageManager.getApplicationIcon(packageName)
            synchronized(iconCache) {
                iconCache.put(packageName, icon)
            }
            icon
        } catch (_: Exception) {
            null
        }
    }

    fun clear() {
        synchronized(iconCache) {
            iconCache.evictAll()
        }
        installedPackages = emptySet()
        cachedInstalledUserApps = emptyList()
        notInstalledCache.clear()
        isInitialized = false
        lastRefreshAt = 0L
    }
}
