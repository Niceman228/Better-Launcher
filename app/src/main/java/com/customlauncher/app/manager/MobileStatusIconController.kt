package com.customlauncher.app.manager

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * Adds/removes only the SystemUI `mobile` blacklist slot while preserving
 * every slot owned by the user or another application.
 */
object MobileStatusIconController {
    private const val TAG = "MobileStatusIcon"
    private const val PREFS_NAME = "mobile_status_icon_state"
    private const val KEY_CAPTURED = "captured"
    private const val KEY_ADDED_BY_LAUNCHER = "added_by_launcher"
    private const val MOBILE_SLOT = "mobile"
    private const val ICON_BLACKLIST = "icon_blacklist"

    suspend fun hideMobileSlot(context: Context): Boolean {
        val appContext = context.applicationContext
        val prefs = statePrefs(appContext)

        if (!hasWriteSecureSettings(appContext)) {
            Log.w(TAG, "WRITE_SECURE_SETTINGS is not granted; mobile status slot was not hidden")
            return false
        }

        if (prefs.getBoolean(KEY_CAPTURED, false)) {
            val current = readSlots(appContext) ?: return false
            if (MOBILE_SLOT in current) {
                Log.d(TAG, "Mobile status slot state already captured and applied")
                return true
            }

            // The setting may have been cleared externally while hidden mode was
            // active. Re-apply it and take ownership so restore removes exactly
            // the slot repaired by the launcher.
            if (!writeSlots(appContext, current + MOBILE_SLOT)) return false
            prefs.edit().putBoolean(KEY_ADDED_BY_LAUNCHER, true).commit()
            Log.d(TAG, "Re-applied missing mobile slot; previous=$current")
            return true
        }

        val current = readSlots(appContext) ?: return false
        val wasAlreadyHidden = MOBILE_SLOT in current
        if (!wasAlreadyHidden) {
            val updated = current + MOBILE_SLOT
            if (!writeSlots(appContext, updated)) return false
        }

        prefs.edit()
            .putBoolean(KEY_CAPTURED, true)
            .putBoolean(KEY_ADDED_BY_LAUNCHER, !wasAlreadyHidden)
            .commit()
        Log.d(TAG, "Mobile slot hidden; addedByLauncher=${!wasAlreadyHidden}, previous=$current")
        return true
    }

    suspend fun restoreMobileSlot(context: Context): Boolean {
        val appContext = context.applicationContext
        val prefs = statePrefs(appContext)
        if (!prefs.getBoolean(KEY_CAPTURED, false)) return true

        if (!prefs.getBoolean(KEY_ADDED_BY_LAUNCHER, false)) {
            clearState(prefs)
            Log.d(TAG, "Mobile slot pre-existed; leaving it unchanged")
            return true
        }

        if (!hasWriteSecureSettings(appContext)) {
            Log.w(TAG, "WRITE_SECURE_SETTINGS is not granted; keeping restore state for retry")
            return false
        }

        val current = readSlots(appContext) ?: return false
        val updated = current - MOBILE_SLOT
        if (!writeSlots(appContext, updated)) return false

        clearState(prefs)
        Log.d(TAG, "Mobile slot restored; remaining=$updated")
        return true
    }

    fun hasWriteSecureSettings(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    private fun readSlots(context: Context): LinkedHashSet<String>? {
        return try {
            parseSlots(Settings.Secure.getString(context.contentResolver, ICON_BLACKLIST).orEmpty())
        } catch (error: Exception) {
            Log.w(TAG, "Unable to read icon_blacklist", error)
            return null
        }
    }

    internal fun parseSlots(raw: String): LinkedHashSet<String> {
        return raw.trim()
            .takeUnless { it.isEmpty() || it == "null" }
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toCollection(linkedSetOf())
            ?: linkedSetOf()
    }

    private fun writeSlots(context: Context, slots: Set<String>): Boolean {
        return try {
            val value = slots.takeIf { it.isNotEmpty() }?.joinToString(",")
            val success = Settings.Secure.putString(context.contentResolver, ICON_BLACKLIST, value)
            if (!success) Log.w(TAG, "Settings provider rejected icon_blacklist update")
            success
        } catch (error: Exception) {
            Log.w(TAG, "Unable to update icon_blacklist", error)
            false
        }
    }

    private fun clearState(prefs: android.content.SharedPreferences) {
        prefs.edit()
            .remove(KEY_CAPTURED)
            .remove(KEY_ADDED_BY_LAUNCHER)
            .commit()
    }

    private fun statePrefs(context: Context) =
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
