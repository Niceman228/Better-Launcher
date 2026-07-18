package com.customlauncher.app.manager

import android.content.Context
import com.customlauncher.app.data.preferences.LauncherPreferences

object DirectBootStateStore {
    private const val PREFS_NAME = "launcher_direct_boot_state"
    private const val KEY_HIDDEN_MODE = "hidden_mode"
    private const val KEY_CLOSE_APPS = "close_apps_on_hidden"
    private const val KEY_BLOCK_TOUCH = "block_touch_in_hidden"
    private const val KEY_ENABLE_DND = "enable_dnd_in_hidden"
    private const val KEY_HIDE_APPS = "hide_apps_in_hidden"
    private const val KEY_BLOCK_SCREENSHOTS = "block_screenshots_in_hidden"
    private const val KEY_DISABLE_NETWORK = "disable_network_in_hidden"
    private const val KEY_POWER_SAVE = "power_save_in_hidden"
    private const val KEY_USE_CUSTOM_KEYS = "use_custom_keys"
    private const val KEY_CUSTOM_KEY_COMBINATION = "custom_key_combination"
    private const val KEY_HIDDEN_MODE_CHANGED_AT = "hidden_mode_changed_at"

    data class FeatureSnapshot(
        val closeApps: Boolean,
        val blockTouch: Boolean,
        val enableDnd: Boolean,
        val hideApps: Boolean,
        val blockScreenshots: Boolean,
        val disableNetwork: Boolean,
        val powerSave: Boolean
    )

    data class KeySnapshot(
        val useCustomKeys: Boolean,
        val customKeyCombination: String?
    )

    fun saveHiddenMode(context: Context, enabled: Boolean, preferences: LauncherPreferences? = null) {
        val prefs = directBootPrefs(context)
        val editor = prefs.edit()
            .putBoolean(KEY_HIDDEN_MODE, enabled)
            .putLong(KEY_HIDDEN_MODE_CHANGED_AT, System.currentTimeMillis())

        if (preferences != null) {
            editor
                .putBoolean(KEY_CLOSE_APPS, preferences.closeAppsOnHiddenMode)
                .putBoolean(KEY_BLOCK_TOUCH, preferences.blockTouchInHiddenMode)
                .putBoolean(KEY_ENABLE_DND, preferences.enableDndInHiddenMode)
                .putBoolean(KEY_HIDE_APPS, preferences.hideAppsInHiddenMode)
                .putBoolean(KEY_BLOCK_SCREENSHOTS, preferences.blockScreenshotsInHiddenMode)
                .putBoolean(KEY_DISABLE_NETWORK, preferences.disableNetworkInHiddenMode)
                .putBoolean(KEY_POWER_SAVE, preferences.powerSaveInHiddenMode)
                .putBoolean(KEY_USE_CUSTOM_KEYS, preferences.useCustomKeys)
                .putString(KEY_CUSTOM_KEY_COMBINATION, preferences.customKeyCombination)
        }

        editor.commit()
    }

    fun saveCustomKeySettings(
        context: Context,
        useCustomKeys: Boolean,
        customKeyCombination: String?
    ) {
        directBootPrefs(context)
            .edit()
            .putBoolean(KEY_USE_CUSTOM_KEYS, useCustomKeys)
            .putString(KEY_CUSTOM_KEY_COMBINATION, customKeyCombination)
            .commit()
    }

    fun isHiddenModeEnabled(context: Context): Boolean {
        return directBootPrefs(context).getBoolean(KEY_HIDDEN_MODE, false)
    }

    fun hiddenModeChangedAt(context: Context): Long {
        return directBootPrefs(context).getLong(KEY_HIDDEN_MODE_CHANGED_AT, 0L)
    }

    fun getFeatureSnapshot(context: Context): FeatureSnapshot {
        val prefs = directBootPrefs(context)
        return FeatureSnapshot(
            closeApps = prefs.getBoolean(KEY_CLOSE_APPS, false),
            blockTouch = prefs.getBoolean(KEY_BLOCK_TOUCH, false),
            enableDnd = prefs.getBoolean(KEY_ENABLE_DND, false),
            hideApps = prefs.getBoolean(KEY_HIDE_APPS, true),
            blockScreenshots = prefs.getBoolean(KEY_BLOCK_SCREENSHOTS, false),
            disableNetwork = prefs.getBoolean(KEY_DISABLE_NETWORK, false),
            powerSave = prefs.getBoolean(KEY_POWER_SAVE, false)
        )
    }

    fun getKeySnapshot(context: Context): KeySnapshot {
        val prefs = directBootPrefs(context)
        return KeySnapshot(
            useCustomKeys = prefs.getBoolean(KEY_USE_CUSTOM_KEYS, false),
            customKeyCombination = prefs.getString(KEY_CUSTOM_KEY_COMBINATION, null)
        )
    }

    private fun directBootPrefs(context: Context) =
        context.applicationContext
            .createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
