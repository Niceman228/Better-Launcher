package com.customlauncher.app.manager

import android.content.Context
import android.content.Intent
import android.app.NotificationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserManager
import android.util.Log
import com.customlauncher.app.LauncherApplication
import com.customlauncher.app.service.TouchBlockService
import com.customlauncher.app.service.SystemBlockAccessibilityService
import com.customlauncher.app.utils.SystemWideStatusBarController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Centralized state manager for hidden mode
 * Ensures consistent state across all components
 */
object HiddenModeStateManager {
    private const val TAG = "HiddenModeStateManager"
    private const val TOGGLE_COOLDOWN_MS = 700L
    private const val BOOT_CLOSE_APPS_GUARD_MS = 30_000L
    private const val ENABLE_TOUCH_OVERLAY_BLOCKING = false
    private const val PREF_PREVIOUS_DND_FILTER = "hidden_mode_previous_dnd_filter"
    private const val PREF_DND_CHANGED_BY_LAUNCHER = "hidden_mode_dnd_changed_by_launcher"
    private const val PREF_APPS_HIDDEN_CHANGED_AT = "apps_hidden_changed_at"

    private val transitionLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isTransitioning = false
    private var pendingState: Boolean? = null
    private var lastToggleTime = 0L

    private data class HiddenModeSettings(
        val closeApps: Boolean,
        val blockTouch: Boolean,
        val enableDnd: Boolean,
        val hideApps: Boolean,
        val blockScreenshots: Boolean,
        val disableNetwork: Boolean,
        val powerSave: Boolean,
        val preferences: com.customlauncher.app.data.preferences.LauncherPreferences?
    )
    
    // Observable state flow for reactive updates
    private val _isHiddenMode = MutableStateFlow(false)
    val isHiddenMode: StateFlow<Boolean> = _isHiddenMode
    
    // Current state getter
    val currentState: Boolean
        get() = _isHiddenMode.value
    
    /**
     * Toggle hidden mode state
     * This is the single source of truth for state changes
     */
    fun toggleHiddenMode(context: Context) {
        val now = SystemClock.elapsedRealtime()
        synchronized(transitionLock) {
            if (now - lastToggleTime < TOGGLE_COOLDOWN_MS) {
                Log.d(TAG, "Ignoring hidden mode toggle inside cooldown window")
                return
            }
            lastToggleTime = now
        }

        val newState = !currentState
        setHiddenMode(context, newState)
    }
    
    /**
     * Set hidden mode to specific state
     */
    fun setHiddenMode(context: Context, enabled: Boolean) {
        val appContext = context.applicationContext
        synchronized(transitionLock) {
            if (isTransitioning) {
                pendingState = enabled
                Log.d(TAG, "Hidden mode transition in progress, queued state: $enabled")
                return
            }

            if (currentState == enabled) {
                syncStoredState(appContext, enabled, null)
                SystemWideStatusBarController.applyHiddenMode(appContext, enabled)
                sendHiddenModeChangedBroadcast(appContext, enabled)
                Log.d(TAG, "Hidden mode already in requested state: $enabled")
                return
            }

            isTransitioning = true
        }

        try {
            performSetHiddenMode(appContext, enabled)
        } finally {
            val nextState = synchronized(transitionLock) {
                isTransitioning = false
                pendingState.also { pendingState = null }
            }

            if (nextState != null && nextState != currentState) {
                mainHandler.post { setHiddenMode(appContext, nextState) }
            }
        }
    }

    fun forceSetHiddenMode(context: Context, enabled: Boolean) {
        val appContext = context.applicationContext
        synchronized(transitionLock) {
            if (isTransitioning) {
                pendingState = enabled
                Log.d(TAG, "Hidden mode transition in progress, force-queued state: $enabled")
                return
            }

            isTransitioning = true
        }

        try {
            performSetHiddenMode(appContext, enabled)
        } finally {
            val nextState = synchronized(transitionLock) {
                isTransitioning = false
                pendingState.also { pendingState = null }
            }

            if (nextState != null && nextState != currentState) {
                mainHandler.post { forceSetHiddenMode(appContext, nextState) }
            }
        }
    }

    private fun performSetHiddenMode(context: Context, enabled: Boolean) {
        Log.d(TAG, "Setting hidden mode: $enabled (was: $currentState)")

        _isHiddenMode.value = enabled

        val settings = readHiddenModeSettings(context)
        val shouldCloseApps = settings.closeApps
        val shouldBlockTouch = settings.blockTouch
        val shouldEnableDnd = settings.enableDnd
        val shouldHideApps = settings.hideApps
        val shouldBlockScreenshots = settings.blockScreenshots
        val shouldDisableNetwork = settings.disableNetwork
        val shouldPowerSave = settings.powerSave

        syncStoredState(context, enabled, settings.preferences)

        Log.d(TAG, "Feature settings - Close apps: $shouldCloseApps, Block touch: $shouldBlockTouch, DND: $shouldEnableDnd, Hide apps: $shouldHideApps, Block screenshots: $shouldBlockScreenshots, Disable network: $shouldDisableNetwork")

        safely("apply system-wide status bar mode") {
            SystemWideStatusBarController.applyHiddenMode(context, enabled)
        }

        if (enabled) {
            if (shouldCloseApps) {
                safely("close apps and go home") { closeAllAppsAndGoHome(context) }
            }

            if (shouldBlockTouch) {
                safely("request keyboard-safe touch block") { requestAccessibilityTouchBlock(context) }
                if (ENABLE_TOUCH_OVERLAY_BLOCKING) {
                    safely("start touch block service") { startTouchBlockService(context) }
                } else {
                    Log.w(TAG, "Touch overlay blocking disabled on this build for Qin F22 stability")
                    safely("stop any stale touch block service") { stopTouchBlockService(context) }
                }
            }

            if (shouldEnableDnd) {
                safely("enable DND") { enableDoNotDisturb(context) }
            }

            if (shouldBlockScreenshots) {
                safely("enable screenshot blocking") { enableScreenshotBlocking(context) }
            }

            if (shouldDisableNetwork) {
                safely("disable network radios") { NetworkControlManager.disableRadios(context) }
            }

            if (shouldPowerSave) {
                safely("enable battery saver") { NetworkControlManager.enableBatterySaver(context) }
            }
        } else {
            if (shouldBlockTouch) {
                safely("request keyboard-safe touch unblock") { requestAccessibilityTouchUnblock(context) }
            }
            mainHandler.post {
                safely("stop touch block service") { stopTouchBlockService(context) }
            }

            if (shouldEnableDnd) {
                mainHandler.postDelayed({
                    safely("restore DND") { disableDoNotDisturb(context) }
                }, 100)
            }

            if (shouldBlockScreenshots) {
                mainHandler.postDelayed({
                    safely("disable screenshot blocking") { disableScreenshotBlocking(context) }
                }, 150)
            }

            // Восстанавливаем радио всегда: если снимка нет, вызов — no-op.
            // Так радио не останутся выключенными, если пользователь
            // отключил настройку, пока скрытый режим был активен.
            safely("restore network radios") { NetworkControlManager.restoreRadios(context) }

            // Аналогично энергосбережению: восстанавливаем всегда, no-op без снимка.
            safely("restore battery saver") { NetworkControlManager.restoreBatterySaver(context) }
        }

        sendHiddenModeChangedBroadcast(context, enabled)

        Log.d(TAG, "Hidden mode set to: $enabled, broadcast sent")
    }

    private fun syncStoredState(
        context: Context,
        enabled: Boolean,
        preferences: com.customlauncher.app.data.preferences.LauncherPreferences?
    ) {
        if (isUserUnlocked(context)) {
            val committed = context.getSharedPreferences("launcher_preferences", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("apps_hidden", enabled)
                .putLong(PREF_APPS_HIDDEN_CHANGED_AT, System.currentTimeMillis())
                .commit()

            if (!committed) {
                Log.w(TAG, "Failed to commit apps_hidden=$enabled synchronously")
            }
        } else {
            Log.d(TAG, "Skipping credential-protected apps_hidden write until user unlock")
        }

        DirectBootStateStore.saveHiddenMode(context, enabled, preferences)
    }

    private inline fun safely(operation: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to $operation", e)
        }
    }

    private fun sendHiddenModeChangedBroadcast(context: Context, enabled: Boolean) {
        val intent = Intent("com.customlauncher.HIDDEN_MODE_CHANGED")
        intent.putExtra("is_hidden", enabled)
        intent.setPackage(context.packageName)
        context.sendBroadcast(intent)
    }
    
    /**
     * Initialize state from preferences
     */
    fun initializeState(context: Context? = null) {
        val appContext = context?.applicationContext ?: runCatching {
            LauncherApplication.instance.applicationContext
        }.getOrNull()

        val savedState = try {
            if (appContext != null && !isUserUnlocked(appContext)) {
                DirectBootStateStore.isHiddenModeEnabled(appContext)
            } else {
                LauncherApplication.instance.preferences.appsHidden
            }
        } catch (e: Exception) {
            if (appContext != null) {
                Log.w(TAG, "Falling back to Direct Boot hidden mode state during initializeState", e)
                DirectBootStateStore.isHiddenModeEnabled(appContext)
            } else {
                Log.w(TAG, "Unable to initialize hidden mode state, defaulting to false", e)
                false
            }
        }
        
        _isHiddenMode.value = savedState
        Log.d(TAG, "Initialized hidden mode state: $savedState")
    }

    fun restorePersistedStateAfterBoot(context: Context, lockedBoot: Boolean) {
        val directBootState = DirectBootStateStore.isHiddenModeEnabled(context)
        val savedState = if (lockedBoot) {
            directBootState
        } else {
            try {
                val credentialState = LauncherApplication.instance.preferences.appsHidden
                if (credentialState != directBootState) {
                    Log.d(TAG, "Credential state differs from Direct Boot state after unlock, using Direct Boot state: $directBootState")
                    syncStoredState(context.applicationContext, directBootState, LauncherApplication.instance.preferences)
                    directBootState
                } else {
                    credentialState
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read credential protected state, using Direct Boot state", e)
                directBootState
            }
        }

        Log.d(TAG, "Restoring hidden mode after boot. lockedBoot=$lockedBoot, savedState=$savedState")

        if (lockedBoot) {
            _isHiddenMode.value = savedState
            sendHiddenModeChangedBroadcast(context.applicationContext, savedState)
            return
        }

        _isHiddenMode.value = !savedState
        setHiddenMode(context.applicationContext, savedState)
    }
    
    /**
     * Сброс и переинициализация после обновления приложения
     */
    fun resetAndReinitialize(context: Context) {
        Log.d(TAG, "Resetting and reinitializing state after update...")
        val preferences = LauncherApplication.instance.preferences
        val savedState = preferences.appsHidden
        
        // Сначала сбрасываем состояние
        _isHiddenMode.value = false
        
        // Ждем немного для стабилизации
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            // Восстанавливаем состояние из preferences
            if (savedState) {
                Log.d(TAG, "Restoring hidden mode after update")
                setHiddenMode(context, true)
            } else {
                Log.d(TAG, "Hidden mode was off, keeping it off")
                _isHiddenMode.value = false
            }
        }, 500)
    }
    
    /**
     * Force refresh the current state
     */
    fun refreshState(context: Context) {
        val currentPrefState = try {
            if (isUserUnlocked(context)) {
                LauncherApplication.instance.preferences.appsHidden
            } else {
                DirectBootStateStore.isHiddenModeEnabled(context)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Falling back to Direct Boot state during refreshState", e)
            DirectBootStateStore.isHiddenModeEnabled(context)
        }
        
        if (currentPrefState != currentState) {
            Log.d(TAG, "State mismatch detected. Preferences: $currentPrefState, Manager: $currentState")
            setHiddenMode(context, currentPrefState)
        }
    }

    private fun readHiddenModeSettings(context: Context): HiddenModeSettings {
        return try {
            if (isUserUnlocked(context)) {
                val preferences = LauncherApplication.instance.preferences
                HiddenModeSettings(
                    closeApps = preferences.closeAppsOnHiddenMode,
                    blockTouch = preferences.blockTouchInHiddenMode,
                    enableDnd = preferences.enableDndInHiddenMode,
                    hideApps = preferences.hideAppsInHiddenMode,
                    blockScreenshots = preferences.blockScreenshotsInHiddenMode,
                    disableNetwork = preferences.disableNetworkInHiddenMode,
                    powerSave = preferences.powerSaveInHiddenMode,
                    preferences = preferences
                )
            } else {
                val snapshot = DirectBootStateStore.getFeatureSnapshot(context)
                HiddenModeSettings(
                    closeApps = snapshot.closeApps,
                    blockTouch = snapshot.blockTouch,
                    enableDnd = snapshot.enableDnd,
                    hideApps = snapshot.hideApps,
                    blockScreenshots = snapshot.blockScreenshots,
                    disableNetwork = snapshot.disableNetwork,
                    powerSave = snapshot.powerSave,
                    preferences = null
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Falling back to Direct Boot feature snapshot", e)
            val snapshot = DirectBootStateStore.getFeatureSnapshot(context)
            HiddenModeSettings(
                closeApps = snapshot.closeApps,
                blockTouch = snapshot.blockTouch,
                enableDnd = snapshot.enableDnd,
                hideApps = snapshot.hideApps,
                blockScreenshots = snapshot.blockScreenshots,
                disableNetwork = snapshot.disableNetwork,
                powerSave = snapshot.powerSave,
                preferences = null
            )
        }
    }

    private fun isUserUnlocked(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
            userManager.isUserUnlocked
        } else {
            true
        }
    }
    
    private fun closeAllAppsAndGoHome(context: Context) {
        try {
            if (SystemClock.elapsedRealtime() < BOOT_CLOSE_APPS_GUARD_MS) {
                Log.d(TAG, "Skipping close apps during early boot guard")
                return
            }

            Log.d(TAG, "Closing all apps and going to home screen")
            
            val preferences = LauncherApplication.instance.preferences
            val showHomeScreen = preferences.showHomeScreen
            val shouldCloseApps = preferences.closeAppsOnHiddenMode
            
            // Check if we should close apps at all
            if (!shouldCloseApps) {
                Log.d(TAG, "Close apps is disabled, not closing anything")
                return
            }
            
            // If home screen is disabled, also don't close anything
            if (!showHomeScreen) {
                Log.d(TAG, "Home screen is disabled, not closing apps or dialogs")
                return
            }
            
            // First, close the app drawer if it's open
            val closeDrawerIntent = Intent("com.customlauncher.CLOSE_APP_DRAWER")
            closeDrawerIntent.setPackage(context.packageName)
            context.sendBroadcast(closeDrawerIntent)
            Log.d(TAG, "Sent close app drawer broadcast")
            
            // Method 1: Send intent to accessibility service
            val accessibilityIntent = Intent(context, SystemBlockAccessibilityService::class.java)
            accessibilityIntent.action = SystemBlockAccessibilityService.ACTION_CLOSE_ALL_APPS
            context.startService(accessibilityIntent)
            
            // Method 2: Broadcast to close system dialogs (deprecated in Android 12+)
            // Only use on older Android versions
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                val closeIntent = Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
                context.sendBroadcast(closeIntent)
            }
            
            // Method 3: Send home intent as backup (with small delay)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                           Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                context.startActivity(homeIntent)
            }, 150)
            
            Log.d(TAG, "Home screen activated, apps closing")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing apps and going home", e)
        }
    }
    
    private fun startTouchBlockService(context: Context) {
        Log.d(TAG, "Starting touch block service")
        val intent = Intent(context, TouchBlockService::class.java)
        intent.action = TouchBlockService.ACTION_BLOCK_TOUCH
        
        try {
            // Use regular startService since we're not using foreground anymore
            context.startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start TouchBlockService", e)
        }
    }
    
    private fun stopTouchBlockService(context: Context) {
        Log.d(TAG, "Stopping touch block service")
        
        // First send unblock action
        val intent = Intent(context, TouchBlockService::class.java)
        intent.action = TouchBlockService.ACTION_UNBLOCK_TOUCH
        
        try {
            // Use regular startService since we're not using foreground anymore
            context.startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send unblock action", e)
        }
        
        // Stop service immediately after sending unblock action
        // The service will handle cleanup in its onDestroy
        try {
            context.stopService(Intent(context, TouchBlockService::class.java))
            Log.d(TAG, "TouchBlockService stopped")
        } catch (e: Exception) {
            // Service might already be stopped
        }
    }
    
    private fun requestAccessibilityTouchBlock(context: Context) {
        Log.d(TAG, "Requesting keyboard-safe touch block")

        val accessibilityIntent = Intent(context, SystemBlockAccessibilityService::class.java).apply {
            action = SystemBlockAccessibilityService.ACTION_BLOCK_TOUCHES
        }
        safely("start accessibility touch block action") { context.startService(accessibilityIntent) }
    }
    
    private fun requestAccessibilityTouchUnblock(context: Context) {
        Log.d(TAG, "Requesting keyboard-safe touch unblock")

        val accessibilityIntent = Intent(context, SystemBlockAccessibilityService::class.java).apply {
            action = SystemBlockAccessibilityService.ACTION_UNBLOCK_TOUCHES
        }
        safely("start accessibility touch unblock action") { context.startService(accessibilityIntent) }
    }
    
    private fun enableDoNotDisturb(context: Context) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // Check if we have DND access
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                if (!notificationManager.isNotificationPolicyAccessGranted) {
                    Log.w(TAG, "No DND access permission")
                    // Request permission
                    val intent = Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                    return
                }
                
                val prefs = context.getSharedPreferences("launcher_preferences", Context.MODE_PRIVATE)
                if (!prefs.getBoolean(PREF_DND_CHANGED_BY_LAUNCHER, false)) {
                    prefs.edit()
                        .putInt(PREF_PREVIOUS_DND_FILTER, notificationManager.currentInterruptionFilter)
                        .putBoolean(PREF_DND_CHANGED_BY_LAUNCHER, true)
                        .apply()
                }

                // Enable DND - Priority only mode
                notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                Log.d(TAG, "Do Not Disturb enabled")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable DND", e)
        }
    }
    
    private fun disableDoNotDisturb(context: Context) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                if (notificationManager.isNotificationPolicyAccessGranted) {
                    val prefs = context.getSharedPreferences("launcher_preferences", Context.MODE_PRIVATE)
                    val previousFilter = prefs.getInt(
                        PREF_PREVIOUS_DND_FILTER,
                        NotificationManager.INTERRUPTION_FILTER_ALL
                    )
                    notificationManager.setInterruptionFilter(previousFilter)
                    prefs.edit()
                        .remove(PREF_PREVIOUS_DND_FILTER)
                        .putBoolean(PREF_DND_CHANGED_BY_LAUNCHER, false)
                        .apply()
                    Log.d(TAG, "Do Not Disturb restored to filter: $previousFilter")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable DND", e)
        }
    }
    
    private fun enableScreenshotBlocking(context: Context) {
        try {
            // Send broadcast to MainActivity to add FLAG_SECURE
            val intent = Intent("com.customlauncher.SCREENSHOT_BLOCKING")
            intent.putExtra("block_screenshots", true)
            context.sendBroadcast(intent)
            Log.d(TAG, "Screenshot blocking enabled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable screenshot blocking", e)
        }
    }
    
    private fun disableScreenshotBlocking(context: Context) {
        try {
            // Send broadcast to MainActivity to remove FLAG_SECURE
            val intent = Intent("com.customlauncher.SCREENSHOT_BLOCKING")
            intent.putExtra("block_screenshots", false)
            context.sendBroadcast(intent)
            Log.d(TAG, "Screenshot blocking disabled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable screenshot blocking", e)
        }
    }
}
