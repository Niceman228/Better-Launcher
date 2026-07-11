package com.customlauncher.app

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.UserManager
import android.util.Log
import com.customlauncher.app.data.database.LauncherDatabase
import com.customlauncher.app.data.preferences.LauncherPreferences
import com.customlauncher.app.data.repository.AppRepository
import com.customlauncher.app.manager.DirectBootStateStore
import com.customlauncher.app.manager.PermissionManager
import com.customlauncher.app.manager.HiddenModeStateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import com.customlauncher.app.data.model.GridConfiguration
import com.customlauncher.app.utils.AdaptiveSizeCalculator
import com.customlauncher.app.utils.IconCache
import java.util.concurrent.atomic.AtomicBoolean

class LauncherApplication : Application() {
    
    val database by lazy { LauncherDatabase.getDatabase(this) }
    val repository by lazy { AppRepository(this, database.hiddenAppDao()) }
    val preferences by lazy { LauncherPreferences(this) }
    
    // BroadcastReceiver for package changes
    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_PACKAGE_REMOVED,
                Intent.ACTION_PACKAGE_ADDED,
                Intent.ACTION_PACKAGE_REPLACED,
                Intent.ACTION_PACKAGE_CHANGED -> {
                    val packageName = intent.data?.schemeSpecificPart
                    Log.d("LauncherApplication", "Package change detected: ${intent.action} for $packageName")
                    if (isUserUnlocked()) {
                        repository.invalidateAppCache()
                        applicationScope.launch { repository.getAllInstalledApps() }
                    }
                    
                    // Send broadcast to notify all components
                    sendBroadcast(Intent("com.customlauncher.PACKAGE_CHANGED").apply {
                        putExtra("action", intent.action)
                        putExtra("package", packageName)
                    })
                }
            }
        }
    }
    
    private val userUnlockedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_UNLOCKED) {
                startCredentialProtectedWorkOnce()
                unregisterUserUnlockedReceiver()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        
        // Register package change receiver
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        // Use RECEIVER_EXPORTED for all Android 12+ versions to ensure we receive system broadcasts
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                registerReceiver(packageChangeReceiver, filter, Context.RECEIVER_EXPORTED)
            } catch (e: Exception) {
                Log.e("LauncherApplication", "Failed to register package receiver with EXPORTED flag", e)
                // Fallback to no flag
                registerReceiver(packageChangeReceiver, filter)
            }
        } else {
            registerReceiver(packageChangeReceiver, filter)
        }
        
        // Accessibility protection runs during Direct Boot. Room and regular
        // SharedPreferences must wait until credential storage is unlocked.
        if (isUserUnlocked()) {
            startCredentialProtectedWorkOnce()
        } else {
            registerUserUnlockedReceiver()
        }
    }

    private fun startCredentialProtectedWorkOnce() {
        if (!credentialWorkStarted.compareAndSet(false, true)) return

        applicationScope.launch {
            val cached = runCatching { repository.getAllInstalledApps() }.getOrDefault(emptyList())
            IconCache.preloadForStartup(this@LauncherApplication, cached, currentMenuIconSizes())
            runCatching { repository.refreshCatalog() }
        }

        syncDirectBootStateIfUnlocked()
        checkPermissionsOnStartup()
        checkAndHandleAppUpdate()
    }

    private fun registerUserUnlockedReceiver() {
        val filter = IntentFilter(Intent.ACTION_USER_UNLOCKED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(userUnlockedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(userUnlockedReceiver, filter)
        }
    }

    private fun unregisterUserUnlockedReceiver() {
        runCatching { unregisterReceiver(userUnlockedReceiver) }
    }
    
    private fun checkPermissionsOnStartup() {
        // Log current permission status
        PermissionManager.logPermissionStatus(this)
        
        // Check critical permissions after a delay to ensure system is ready
        CoroutineScope(Dispatchers.Main).launch {
            delay(1000) // Wait for system to stabilize
            
            val status = PermissionManager.checkAllPermissions(this@LauncherApplication)
            
            // Log permission status changes
            Log.d("LauncherApplication", "Permission check complete:"
                + " overlay=${status.overlay}"
                + " notification=${status.notificationPolicy}"
                + " accessibility=${status.accessibility}"
                + " usageStats=${status.usageStats}"
                + " writeSettings=${status.writeSettings}")
            
            if (!status.allGranted) {
                Log.w("LauncherApplication", "Some permissions are missing after startup")
                // We can't automatically request them, but we track the state
            }
        }
    }

    private fun syncDirectBootStateIfUnlocked() {
        try {
            if (isUserUnlocked()) {
                val prefs = getSharedPreferences("launcher_preferences", Context.MODE_PRIVATE)
                val credentialState = preferences.appsHidden
                val credentialChangedAt = prefs.getLong("apps_hidden_changed_at", 0L)
                val directBootState = DirectBootStateStore.isHiddenModeEnabled(this)
                val directBootChangedAt = DirectBootStateStore.hiddenModeChangedAt(this)

                if (directBootChangedAt > credentialChangedAt && directBootState != credentialState) {
                    Log.d("LauncherApplication", "Direct Boot hidden mode state is newer, syncing to credential storage: $directBootState")
                    preferences.appsHidden = directBootState
                } else {
                    DirectBootStateStore.saveHiddenMode(this, credentialState, preferences)
                }
            }
        } catch (e: Exception) {
            Log.w("LauncherApplication", "Failed to sync Direct Boot state", e)
        }
    }

    private fun isUserUnlocked(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val userManager = getSystemService(Context.USER_SERVICE) as UserManager
            userManager.isUserUnlocked
        } else {
            true
        }
    }
    
    private fun checkAndHandleAppUpdate() {
        try {
            if (!isUserUnlocked()) {
                Log.d("LauncherApplication", "Skipping app update check until user unlock")
                return
            }

            val packageInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageManager.getPackageInfo(packageName, 0)
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            
            val currentVersionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode
            }
            
            val prefs = getSharedPreferences("launcher_preferences", Context.MODE_PRIVATE)
            val savedVersionCode = prefs.getInt("app_version_code", -1)
            
            if (savedVersionCode != -1 && savedVersionCode < currentVersionCode) {
                Log.d("LauncherApplication", "App updated from version $savedVersionCode to $currentVersionCode")
                
                // Сбрасываем состояние accessibility service после обновления
                CoroutineScope(Dispatchers.Main).launch {
                    delay(3000) // Ждем стабилизации системы
                    
                    // Переинициализируем скрытый режим если он был включен
                    if (preferences.appsHidden) {
                        Log.d("LauncherApplication", "Reinitializing hidden mode after update")
                        HiddenModeStateManager.restorePersistedStateAfterBoot(
                            this@LauncherApplication,
                            lockedBoot = false
                        )
                    }
                }
            }
            
            // Сохраняем текущую версию
            prefs.edit().putInt("app_version_code", currentVersionCode).apply()
            
        } catch (e: Exception) {
            Log.e("LauncherApplication", "Failed to check app version", e)
        }
    }
    
    override fun onTerminate() {
        super.onTerminate()
        try {
            unregisterReceiver(packageChangeReceiver)
        } catch (e: Exception) {
            Log.e("LauncherApplication", "Failed to unregister receiver", e)
        }
        unregisterUserUnlockedReceiver()
    }
    
    companion object {
        lateinit var instance: LauncherApplication
            private set
    }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val credentialWorkStarted = AtomicBoolean(false)

    private fun currentMenuIconSizes(): Set<Int> {
        val configs = mutableSetOf<GridConfiguration>()
        val columns = preferences.gridColumnCount.takeIf { it > 0 } ?: 4
        configs += GridConfiguration(columns, when (columns) { 3 -> 5; 4 -> 6; 5 -> 7; else -> 6 }, false)
        if (preferences.hasButtonGridSelection && preferences.buttonPhoneGridSize.isNotEmpty()) {
            val (cols, rows) = when (preferences.buttonPhoneGridSize) {
                "3x3" -> 3 to 3; "3x4" -> 3 to 4; "3x5" -> 3 to 5; "4x5" -> 4 to 5
                else -> 4 to 5
            }
            configs += GridConfiguration(cols, rows, preferences.buttonPhoneMode)
        }
        return configs.map { AdaptiveSizeCalculator.calculateIconSize(this, it) }.toSet()
    }
}
