package com.customlauncher.app.utils

import android.content.Context
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object SystemWideStatusBarController {
    private const val TAG = "SystemWideStatusBar"
    private const val PREFS_NAME = "system_wide_status_bar"
    private const val KEY_APPLIED = "applied"
    private const val KEY_PREVIOUS_POLICY = "previous_policy_control"
    private const val NULL_POLICY = "__NULL__"
    private const val IMMERSIVE_STATUS_POLICY = "immersive.status=*"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun applyHiddenMode(context: Context, enabled: Boolean) {
        val appContext = context.applicationContext
        scope.launch {
            runCatching {
                if (applyDirectPolicyControl(appContext, enabled)) {
                    return@launch
                }

                val helper = ShizukuHelper(appContext)
                if (!helper.isShizukuInstalled() || !helper.isShizukuRunning() || !helper.hasShizukuPermission()) {
                    Log.d(TAG, "Shizuku unavailable, skipping system-wide status bar change")
                    return@launch
                }

                if (enabled) {
                    hideWithPolicyControl(appContext, helper)
                } else {
                    restorePolicyControl(appContext, helper)
                }
            }.onFailure { error ->
                Log.e(TAG, "Failed to apply system-wide status bar mode", error)
            }
        }
    }

    private fun applyDirectPolicyControl(context: Context, enabled: Boolean): Boolean {
        return try {
            val prefs = prefs(context)
            if (enabled) {
                if (!prefs.getBoolean(KEY_APPLIED, false)) {
                    val currentPolicy = Settings.Global.getString(
                        context.contentResolver,
                        "policy_control"
                    )
                    prefs.edit()
                        .putString(KEY_PREVIOUS_POLICY, currentPolicy ?: NULL_POLICY)
                        .putBoolean(KEY_APPLIED, true)
                        .apply()
                }

                Settings.Global.putString(
                    context.contentResolver,
                    "policy_control",
                    IMMERSIVE_STATUS_POLICY
                )
                Log.d(TAG, "Applied system-wide immersive status policy directly")
            } else {
                if (!prefs.getBoolean(KEY_APPLIED, false)) {
                    return true
                }

                val previousPolicy = prefs.getString(KEY_PREVIOUS_POLICY, NULL_POLICY) ?: NULL_POLICY
                Settings.Global.putString(
                    context.contentResolver,
                    "policy_control",
                    if (previousPolicy == NULL_POLICY) null else previousPolicy
                )
                prefs.edit()
                    .remove(KEY_PREVIOUS_POLICY)
                    .putBoolean(KEY_APPLIED, false)
                    .apply()
                Log.d(TAG, "Restored previous policy_control directly")
            }
            true
        } catch (e: SecurityException) {
            Log.d(TAG, "Direct policy_control write denied, will try Shizuku")
            false
        } catch (e: Exception) {
            Log.w(TAG, "Direct policy_control write failed, will try Shizuku", e)
            false
        }
    }

    private suspend fun hideWithPolicyControl(context: Context, helper: ShizukuHelper) {
        val prefs = prefs(context)
        if (!prefs.getBoolean(KEY_APPLIED, false)) {
            val (_, currentPolicyRaw) = helper.executeCommand("settings get global policy_control")
            val currentPolicy = currentPolicyRaw.trim().ifEmpty { "null" }
            prefs.edit()
                .putString(KEY_PREVIOUS_POLICY, if (currentPolicy == "null") NULL_POLICY else currentPolicy)
                .putBoolean(KEY_APPLIED, true)
                .apply()
        }

        val (success, output) = helper.executeCommand("settings put global policy_control '$IMMERSIVE_STATUS_POLICY'")
        if (success) {
            Log.d(TAG, "Applied system-wide immersive status policy")
        } else {
            Log.w(TAG, "Failed to apply immersive status policy: $output")
        }
    }

    private suspend fun restorePolicyControl(context: Context, helper: ShizukuHelper) {
        val prefs = prefs(context)
        if (!prefs.getBoolean(KEY_APPLIED, false)) {
            return
        }

        val previousPolicy = prefs.getString(KEY_PREVIOUS_POLICY, NULL_POLICY) ?: NULL_POLICY
        val command = if (previousPolicy == NULL_POLICY) {
            "settings delete global policy_control"
        } else {
            "settings put global policy_control '$previousPolicy'"
        }

        val (success, output) = helper.executeCommand(command)
        if (success) {
            prefs.edit()
                .remove(KEY_PREVIOUS_POLICY)
                .putBoolean(KEY_APPLIED, false)
                .apply()
            Log.d(TAG, "Restored previous policy_control")
        } else {
            Log.w(TAG, "Failed to restore policy_control: $output")
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
