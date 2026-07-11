package com.customlauncher.app.data.repository

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.ComponentName
import android.os.Process
import android.os.UserManager
import com.customlauncher.app.LauncherApplication
import com.customlauncher.app.data.database.HiddenApp
import com.customlauncher.app.data.database.HiddenAppDao
import com.customlauncher.app.data.model.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import android.os.Trace

class AppRepository(
    private val context: Context,
    private val hiddenAppDao: HiddenAppDao
) {
    
    private val packageManager = context.packageManager
    private val preferences = LauncherApplication.instance.preferences
    private var cachedHiddenApps: Set<String> = emptySet()
    private var lastCacheUpdate: Long = 0
    private val CACHE_VALIDITY_MS = 1000L // 1 second cache
    
    // App list caching for performance
    private val catalogPrefs = context.getSharedPreferences("app_catalog_v1", Context.MODE_PRIVATE)
    private val refreshMutex = Mutex()
    private val _catalog = MutableStateFlow(loadPersistedCatalog())
    val catalog: StateFlow<List<AppInfo>> = _catalog
    @Volatile private var catalogDirty = _catalog.value.isEmpty()
    
    private companion object {
        const val CACHE_DURATION_MS = 30000L // 30 seconds
    }
    
    @Synchronized
    private fun getHiddenPackages(): Set<String> {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastCacheUpdate > CACHE_VALIDITY_MS) {
            // Refresh cache from preferences
            cachedHiddenApps = preferences.getHiddenApps()
            lastCacheUpdate = currentTime
        }
        return cachedHiddenApps
    }
    
    @Synchronized
    fun invalidateCache() {
        lastCacheUpdate = 0
        catalogDirty = true
    }

    fun getHiddenAppsFlow(): Flow<List<HiddenApp>> {
        return hiddenAppDao.getAllHiddenApps()
    }
    
    suspend fun getAllInstalledApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val cached = _catalog.value
        if (!catalogDirty && cached.isNotEmpty()) return@withContext applyHiddenState(cached)

        refreshMutex.withLock {
            if (!catalogDirty && _catalog.value.isNotEmpty()) return@withLock applyHiddenState(_catalog.value)
            val refreshed = queryCatalog()
            _catalog.value = refreshed
            catalogDirty = false
            persistCatalog(refreshed)
            refreshed
        }
    }

    /** Synchronous snapshot for first-frame publishing; empty when catalog is dirty. */
    fun warmCatalog(): List<AppInfo> {
        val cached = _catalog.value
        return if (!catalogDirty && cached.isNotEmpty()) applyHiddenState(cached) else emptyList()
    }

    suspend fun refreshCatalog(): List<AppInfo> {
        catalogDirty = true
        return getAllInstalledApps()
    }

    private fun queryCatalog(): List<AppInfo> {
        val started = android.os.SystemClock.elapsedRealtime()
        Trace.beginSection("AppCatalog.scan")
        try {
        
        val intent = Intent(Intent.ACTION_MAIN, null)
        intent.addCategory(Intent.CATEGORY_LAUNCHER)
        
        val apps = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ requires ResolveInfoFlags
            packageManager.queryIntentActivities(
                intent,
                android.content.pm.PackageManager.ResolveInfoFlags.of(0L)
            )
        } else {
            // Android 12 and below
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, 0)
        }
        
        val hiddenPackages = getHiddenPackages()
        
        // Use default icon initially, load real icons lazily
        val defaultIcon = context.packageManager.defaultActivityIcon
        
        val result = apps.mapNotNull { resolveInfo ->
            try {
                val appName = resolveInfo.loadLabel(packageManager).toString()
                val packageName = resolveInfo.activityInfo.packageName
                val isHidden = hiddenPackages.contains(packageName)
                val component = ComponentName(packageName, resolveInfo.activityInfo.name)
                val fingerprint = try {
                    packageManager.getPackageInfo(packageName, 0).lastUpdateTime
                } catch (_: Exception) { 0L }
                
                // Use placeholder icon first, real icon will be loaded in adapter
                AppInfo(appName, packageName, defaultIcon, isHidden,
                    componentName = component.flattenToString(), packageFingerprint = fingerprint)
            } catch (e: Exception) {
                null // Skip apps that cause errors
            }
        }.sortedBy { it.appName.lowercase() }
        
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            android.util.Log.d("AppCatalogPerf", "scan count=${result.size} ms=${android.os.SystemClock.elapsedRealtime() - started}")
        }
        return result
        } finally {
            Trace.endSection()
        }
    }

    private fun applyHiddenState(apps: List<AppInfo>): List<AppInfo> {
        val hidden = getHiddenPackages()
        return apps.map { it.copy(isHidden = it.packageName in hidden) }
    }

    private fun loadPersistedCatalog(): List<AppInfo> {
        return try {
            val raw = catalogPrefs.getString("entries", null) ?: return emptyList()
            val array = JSONArray(raw)
            val icon = packageManager.defaultActivityIcon
            buildList(array.length()) {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    add(AppInfo(item.getString("label"), item.getString("package"), icon,
                        componentName = item.optString("component").takeIf(String::isNotEmpty),
                        packageFingerprint = item.optLong("fingerprint")))
                }
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun persistCatalog(apps: List<AppInfo>) {
        val array = JSONArray()
        apps.forEach { app -> array.put(JSONObject().apply {
            put("label", app.appName); put("package", app.packageName)
            put("component", app.componentName ?: ""); put("fingerprint", app.packageFingerprint)
        }) }
        catalogPrefs.edit().putString("entries", array.toString()).apply()
    }
    
    // Force cache refresh on package changes
    fun invalidateAppCache() {
        catalogDirty = true
    }
    
    suspend fun getVisibleApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        getAllInstalledApps().filter { !it.isHidden }
    }
    
    suspend fun getHiddenApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        getAllInstalledApps().filter { it.isHidden }
    }
    
    suspend fun hideApp(packageName: String) {
        hiddenAppDao.insert(HiddenApp(packageName))
    }
    
    suspend fun hideApps(packageNames: List<String>) {
        hiddenAppDao.insertAll(packageNames.map { HiddenApp(it) })
    }
    
    suspend fun unhideApp(packageName: String) {
        hiddenAppDao.deleteByPackageName(packageName)
    }
    
    suspend fun unhideApps(packageNames: List<String>) {
        packageNames.forEach { hiddenAppDao.deleteByPackageName(it) }
    }
    
    suspend fun isAppHidden(packageName: String): Boolean {
        return hiddenAppDao.isAppHidden(packageName)
    }
    
    fun launchApp(packageName: String) {
        // Special handling for our own launcher
        if (packageName == "com.customlauncher.app") {
            // Explicitly launch AppListActivity for our launcher
            val intent = Intent(context, com.customlauncher.app.ui.AppListActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            android.util.Log.d("AppRepository", "Launching our launcher's AppListActivity")
        } else {
            // Launch other apps normally
            val component = _catalog.value.firstOrNull { it.packageName == packageName }
                ?.componentName?.let(ComponentName::unflattenFromString)
            val intent = component?.let { Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setComponent(it)
            } } ?: context.packageManager.getLaunchIntentForPackage(packageName)
            intent?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(it)
                android.util.Log.d("AppRepository", "Launching app: $packageName")
            }
        }
    }
}
