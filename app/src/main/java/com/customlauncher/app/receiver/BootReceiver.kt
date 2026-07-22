package com.customlauncher.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.customlauncher.app.LauncherApplication
import com.customlauncher.app.manager.DirectBootStateStore
import com.customlauncher.app.manager.HiddenModeStateManager
import com.customlauncher.app.ui.HomeScreenActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.customlauncher.app.utils.SystemWideStatusBarController
import com.customlauncher.app.service.SystemBlockAccessibilityService

/**
 * Receiver for handling device boot and package updates
 * Ensures launcher remains the default and permissions are maintained
 */
class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
        private const val HOME_START_COOLDOWN_MS = 5_000L
        private var lastHomeStartTime = 0L
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Received intent: ${intent.action}")
        
        when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                handleBootCompleted(context, lockedBoot = true)
            }
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_USER_UNLOCKED -> {
                handleBootCompleted(context, lockedBoot = false)
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                handlePackageReplaced(context)
            }
        }
    }
    
    private fun handleBootCompleted(context: Context, lockedBoot: Boolean) {
        Log.d(TAG, "Device boot completed, initializing launcher. lockedBoot=$lockedBoot")

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val directBootHidden = DirectBootStateStore.isHiddenModeEnabled(appContext)
        if (directBootHidden) {
            SystemWideStatusBarController.applyHiddenMode(appContext, true)
        }

        CoroutineScope(Dispatchers.Main).launch {
            try {
                // Apply quickly, then retry once after the framework has settled.
                delay(if (lockedBoot) 100 else 350)
                HiddenModeStateManager.restorePersistedStateAfterBoot(appContext, lockedBoot)
                refreshAccessibilityKeys(appContext)

                if (!lockedBoot && directBootHidden) {
                    delay(1200)
                    HiddenModeStateManager.restorePersistedStateAfterBoot(appContext, lockedBoot = false)
                    refreshAccessibilityKeys(appContext)
                }

                if (shouldStartHomeScreen(appContext, lockedBoot)) {
                    startHomeScreenOnce(appContext)
                }

                checkAndRequestAccessibilityService(appContext)
            } finally {
                pendingResult.finish()
            }
        }
    }
    
    private fun handlePackageReplaced(context: Context) {
        Log.d(TAG, "Package updated, restoring permissions and state")

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.Main).launch {
            try {
                delay(700)

                checkAndRequestAccessibilityService(appContext)
                HiddenModeStateManager.restorePersistedStateAfterBoot(appContext, lockedBoot = false)
                refreshAccessibilityKeys(appContext)

                if (shouldStartHomeScreen(appContext, lockedBoot = false)) {
                    startHomeScreenOnce(appContext)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
    
    private fun checkAndRequestAccessibilityService(context: Context) {
        // Check if accessibility service is enabled using PermissionManager
        if (!com.customlauncher.app.manager.PermissionManager.isAccessibilityServiceEnabled(context)) {
            Log.w(TAG, "Accessibility service is not enabled after boot/update")
            // We can't automatically enable it, but we can notify the user
            // Consider showing a notification to remind the user to re-enable it
        } else {
            Log.d(TAG, "Accessibility service is already enabled")
        }
    }

    private fun shouldStartHomeScreen(context: Context, lockedBoot: Boolean): Boolean {
        if (lockedBoot) {
            return false
        }

        return try {
            LauncherApplication.instance.preferences.showHomeScreen
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read launcher preferences, falling back to Direct Boot state", e)
            DirectBootStateStore.isHiddenModeEnabled(context)
        }
    }

    private fun startHomeScreenOnce(context: Context) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastHomeStartTime < HOME_START_COOLDOWN_MS) {
            Log.d(TAG, "Skipping duplicate home start during boot cooldown")
            return
        }

        lastHomeStartTime = now
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            setClass(context, HomeScreenActivity::class.java)
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        context.startActivity(homeIntent)
    }

    private fun refreshAccessibilityKeys(context: Context) {
        try {
            context.startService(Intent(context, SystemBlockAccessibilityService::class.java).apply {
                action = SystemBlockAccessibilityService.ACTION_REFRESH_KEYS
            })
        } catch (e: Exception) {
            Log.w(TAG, "Unable to refresh accessibility key listener", e)
        }
    }
}
