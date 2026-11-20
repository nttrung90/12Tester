package com.ntt.crosstest.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * High-performance in-memory cache for installed packages and application icons.
 * Eliminates repetitive synchronous IPC queries to Android's PackageManager on the UI thread,
 * ensuring smooth 60-120fps scrolling and tab transitions.
 */
object InstalledAppCache {

    private const val NEGATIVE_TTL_MS = 15_000L

    val version = AtomicLong(1L)
    val generation = AtomicLong(1L)

    private val _versionFlow = MutableStateFlow(1L)
    val versionFlow: StateFlow<Long> = _versionFlow.asStateFlow()

    @Volatile
    private var isReceiverRegistered = false

    @Volatile
    private var isInitialized = false

    @Volatile
    private var installedPackages: Set<String> = emptySet()

    @Volatile
    private var lastRefreshAt: Long = 0L

    @Volatile
    private var cachedInstalledUserApps: List<PlayStoreHelper.InstalledAppItem> = emptyList()

    @Volatile
    private var isUserAppsLoaded = false

    /** Gói đã xác nhận là CHƯA cài (pkg -> thời điểm kiểm tra) — cùng cửa sổ
     *  15s với [ensureFresh] để tránh lặp IPC PackageManager mỗi lần refresh. */
    private val notInstalledCache = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // Cache up to 120 app icons in memory
    private val iconCache = object : LruCache<String, Drawable>(120) {}

    fun registerPackageReceiver(context: Context) {
        if (isReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val pkgName = intent?.data?.schemeSpecificPart ?: return
                when (intent.action) {
                    Intent.ACTION_PACKAGE_ADDED -> onPackageAdded(pkgName)
                    Intent.ACTION_PACKAGE_REMOVED -> onPackageRemoved(pkgName)
                    Intent.ACTION_PACKAGE_REPLACED -> onPackageReplaced(pkgName)
                }
            }
        }
        try {
            context.registerReceiver(receiver, filter)
            isReceiverRegistered = true
        } catch (_: Exception) {}
    }

    fun init(context: Context, scope: CoroutineScope = CoroutineScope(Dispatchers.IO)) {
        registerPackageReceiver(context)
        scope.launch(Dispatchers.IO) {
            refresh(context)
        }
    }

    private fun notifyVersionChanged() {
        val v = version.incrementAndGet()
        _versionFlow.value = v
    }

    fun refresh(context: Context) {
        val currentGen = generation.get()
        refreshPackages(context)
        val userApps = PlayStoreHelper.getInstalledUserApps(context)
        if (currentGen == generation.get()) {
            cachedInstalledUserApps = userApps
            isUserAppsLoaded = true
            notifyVersionChanged()
        }
    }

    fun onPackageAdded(packageName: String) {
        synchronized(this) {
            installedPackages = installedPackages + packageName
            notInstalledCache.remove(packageName)
            cachedInstalledUserApps = emptyList()
            isUserAppsLoaded = false
            notifyVersionChanged()
        }
    }

    fun onPackageRemoved(packageName: String) {
        synchronized(this) {
            installedPackages = installedPackages - packageName
            notInstalledCache.remove(packageName)
            synchronized(iconCache) { iconCache.remove(packageName) }
            cachedInstalledUserApps = emptyList()
            isUserAppsLoaded = false
            notifyVersionChanged()
        }
    }

    fun onPackageReplaced(packageName: String) {
        synchronized(this) {
            synchronized(iconCache) { iconCache.remove(packageName) }
            cachedInstalledUserApps = emptyList()
            isUserAppsLoaded = false
            notifyVersionChanged()
        }
    }

    /**
     * Lightweight refresh of the installed-package set only (no app icons/labels).
     */
    fun refreshPackages(context: Context) {
        val currentGen = generation.get()
        try {
            val pm = context.packageManager
            val packages = pm.getInstalledPackages(0)
            val set = HashSet<String>(packages.size)
            for (pkg in packages) {
                set.add(pkg.packageName)
            }
            if (currentGen == generation.get()) {
                installedPackages = set
                // Trạng thái cài/không cài có thể đã đổi — bỏ mọi kết quả âm cũ.
                notInstalledCache.clear()
                isInitialized = true
                lastRefreshAt = SystemClock.elapsedRealtime()
                notifyVersionChanged()
            }
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
        val age = SystemClock.elapsedRealtime() - lastRefreshAt
        if (!isInitialized || age > maxAgeMs) {
            refreshPackages(context)
        }
    }

    /**
     * Retrieves cached user installed launcher apps (< 1ms).
     */
    fun peekInstalledUserApps(): List<PlayStoreHelper.InstalledAppItem>? =
        if (isUserAppsLoaded) cachedInstalledUserApps else null

    fun getInstalledUserApps(context: Context): List<PlayStoreHelper.InstalledAppItem> {
        val curGen = generation.get()
        if (isUserAppsLoaded) {
            return cachedInstalledUserApps
        }
        val apps = PlayStoreHelper.getInstalledUserApps(context)
        if (curGen == generation.get()) {
            cachedInstalledUserApps = apps
            isUserAppsLoaded = true
        }
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
        val now = SystemClock.elapsedRealtime()
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
        val curGen = generation.get()
        synchronized(iconCache) {
            val cached = iconCache.get(packageName)
            if (cached != null) return cached
        }

        return try {
            val icon = context.packageManager.getApplicationIcon(packageName)
            if (curGen == generation.get()) {
                synchronized(iconCache) {
                    iconCache.put(packageName, icon)
                }
            }
            icon
        } catch (_: Exception) {
            null
        }
    }

    fun peekInstalledAppDrawable(packageName: String): Drawable? {
        if (packageName.isBlank()) return null
        synchronized(iconCache) {
            return iconCache.get(packageName)
        }
    }

    fun clear() {
        generation.incrementAndGet()
        notifyVersionChanged()
        synchronized(iconCache) {
            iconCache.evictAll()
        }
        installedPackages = emptySet()
        cachedInstalledUserApps = emptyList()
        isUserAppsLoaded = false
        notInstalledCache.clear()
        isInitialized = false
        lastRefreshAt = 0L
    }
}
