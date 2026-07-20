package com.customlauncher.app.manager

import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.customlauncher.app.utils.ShizukuHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Отключает Wi-Fi / Bluetooth и скрывает мобильный слот SystemUI при входе
 * в скрытый режим, затем восстанавливает прежнее состояние при выходе.
 *
 * Основной путь — команды `svc` через Shizuku (работают на всех версиях Android).
 * Fallback без Shizuku: WifiManager (до Android 10) и BluetoothAdapter (до Android 13).
 * Мобильные данные намеренно остаются включёнными.
 *
 * Состояние радиомодулей сохраняется в device-protected storage, чтобы
 * восстановление работало и после перезагрузки до разблокировки.
 */
object NetworkControlManager {
    private const val TAG = "NetworkControlManager"
    private const val PREFS_NAME = "launcher_network_control_state"
    private const val KEY_CAPTURED = "radios_captured"
    private const val KEY_WIFI_WAS_ON = "wifi_was_on"
    private const val KEY_BT_WAS_ON = "bt_was_on"
    private const val KEY_DATA_WAS_ON = "data_was_on"
    private const val KEY_SAVER_CAPTURED = "saver_captured"
    private const val KEY_SAVER_WAS_ON = "saver_was_on"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Один helper на процесс: биндинг Shizuku UserService переиспользуется,
    // новый инстанс на каждый вызов пересоздавал биндинг и часто не успевал.
    @Volatile
    private var shizukuHelper: ShizukuHelper? = null

    private fun helper(context: Context): ShizukuHelper {
        return shizukuHelper ?: synchronized(this) {
            shizukuHelper ?: ShizukuHelper(context.applicationContext).also { shizukuHelper = it }
        }
    }

    fun disableRadios(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            try {
                val prefs = statePrefs(appContext)

                // Снимок состояния делаем только один раз за сессию скрытого режима:
                // при повторном применении после перезагрузки радио уже выключены,
                // и перезапись затёрла бы исходное состояние пользователя.
                if (!prefs.getBoolean(KEY_CAPTURED, false)) {
                    val wifiOn = isWifiEnabled(appContext)
                    val btOn = isBluetoothEnabled(appContext)
                    prefs.edit()
                        .putBoolean(KEY_CAPTURED, true)
                        .putBoolean(KEY_WIFI_WAS_ON, wifiOn)
                        .putBoolean(KEY_BT_WAS_ON, btOn)
                        .remove(KEY_DATA_WAS_ON)
                        .commit()

                    Log.d(TAG, "Captured radio states: wifi=$wifiOn, bt=$btOn")
                }

                setWifi(appContext, false)
                setBluetooth(appContext, false)
                MobileStatusIconController.hideMobileSlot(appContext)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to disable radios", e)
            }
        }
    }

    fun restoreRadios(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            try {
                val prefs = statePrefs(appContext)
                if (!prefs.getBoolean(KEY_CAPTURED, false)) {
                    MobileStatusIconController.restoreMobileSlot(appContext)
                    Log.d(TAG, "No captured radio states, nothing to restore")
                    return@launch
                }

                val wifiWasOn = prefs.getBoolean(KEY_WIFI_WAS_ON, false)
                val btWasOn = prefs.getBoolean(KEY_BT_WAS_ON, false)
                // Compatibility with version 5.0: if it disabled mobile data and
                // left an active snapshot, restore it once and retire the key.
                val restoreLegacyMobileData = prefs.contains(KEY_DATA_WAS_ON) &&
                    prefs.getBoolean(KEY_DATA_WAS_ON, false)

                if (wifiWasOn) setWifi(appContext, true)
                if (btWasOn) setBluetooth(appContext, true)
                if (restoreLegacyMobileData) setMobileDataForLegacyRestore(appContext)
                MobileStatusIconController.restoreMobileSlot(appContext)

                prefs.edit()
                    .putBoolean(KEY_CAPTURED, false)
                    .remove(KEY_DATA_WAS_ON)
                    .commit()
                Log.d(TAG, "Restored radio states: wifi=$wifiWasOn, bt=$btWasOn, legacyData=$restoreLegacyMobileData")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore radios", e)
            }
        }
    }

    // ---------- Энергосбережение ----------

    /** Включает режим энергосбережения через Shizuku, сохранив прежнее состояние. */
    fun enableBatterySaver(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            try {
                val prefs = statePrefs(appContext)
                if (!prefs.getBoolean(KEY_SAVER_CAPTURED, false)) {
                    val saverOn = isBatterySaverOn(appContext)
                    prefs.edit()
                        .putBoolean(KEY_SAVER_CAPTURED, true)
                        .putBoolean(KEY_SAVER_WAS_ON, saverOn)
                        .commit()
                    Log.d(TAG, "Captured battery saver state: $saverOn")
                }
                if (!runShizuku(context, "settings put global low_power 1")) {
                    Log.w(TAG, "Battery saver enable requires Shizuku, skipped")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to enable battery saver", e)
            }
        }
    }

    /** Возвращает энергосбережение в прежнее состояние при выходе из скрытого режима. */
    fun restoreBatterySaver(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            try {
                val prefs = statePrefs(appContext)
                if (!prefs.getBoolean(KEY_SAVER_CAPTURED, false)) return@launch
                val wasOn = prefs.getBoolean(KEY_SAVER_WAS_ON, false)
                // Возвращаем, только если сами меняли: если было выключено — выключаем.
                runShizuku(context, "settings put global low_power ${if (wasOn) 1 else 0}")
                prefs.edit().putBoolean(KEY_SAVER_CAPTURED, false).commit()
                Log.d(TAG, "Restored battery saver state: $wasOn")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore battery saver", e)
            }
        }
    }

    private fun isBatterySaverOn(context: Context): Boolean {
        return try {
            android.provider.Settings.Global.getInt(
                context.contentResolver, "low_power", 0
            ) == 1
        } catch (e: Exception) {
            false
        }
    }

    // ---------- Чтение состояния ----------

    private fun isWifiEnabled(context: Context): Boolean {
        return try {
            val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifi?.isWifiEnabled == true
        } catch (e: Exception) {
            false
        }
    }

    private fun isBluetoothEnabled(context: Context): Boolean {
        return try {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            manager?.adapter?.isEnabled == true
        } catch (e: Exception) {
            false
        }
    }

    // ---------- Переключение ----------

    private suspend fun setWifi(context: Context, enabled: Boolean) {
        // Нативный путь первым: с targetSdk 28 setWifiEnabled работает на любом
        // Android без Shizuku. Возвращает false, если система запретила.
        try {
            val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val ok = wifi?.setWifiEnabled(enabled) == true
            Log.d(TAG, "WifiManager.setWifiEnabled($enabled) -> $ok")
            if (ok) return
        } catch (e: Exception) {
            Log.w(TAG, "WifiManager path failed", e)
        }

        val arg = if (enabled) "enable" else "disable"
        if (!runShizuku(context, "svc wifi $arg")) {
            Log.w(TAG, "Wi-Fi toggle failed: both native and Shizuku paths unavailable")
        }
    }

    private suspend fun setBluetooth(context: Context, enabled: Boolean) {
        try {
            val adapter =
                (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            if (adapter != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION", "MissingPermission")
                val ok = if (enabled) adapter.enable() else adapter.disable()
                Log.d(TAG, "BluetoothAdapter ($enabled) -> $ok")
                if (ok) return
            }
        } catch (e: Exception) {
            Log.w(TAG, "Bluetooth native path failed", e)
        }

        val arg = if (enabled) "enable" else "disable"
        if (runShizuku(context, "svc bluetooth $arg")) return
        if (!runShizuku(context, "cmd bluetooth_manager $arg")) {
            Log.w(TAG, "Bluetooth toggle failed: all paths unavailable")
        }
    }

    private suspend fun setMobileDataForLegacyRestore(context: Context) {
        if (!runShizuku(context, "svc data enable")) {
            Log.w(TAG, "Unable to restore mobile data disabled by an earlier version")
        }
    }

    private suspend fun runShizuku(context: Context, command: String): Boolean {
        return try {
            val helper = helper(context)
            if (!helper.hasShizukuPermission()) {
                Log.d(TAG, "Shizuku permission not granted, skipping '$command'")
                return false
            }
            val (success, output) = helper.executeCommand(command)
            Log.d(TAG, "Shizuku '$command' -> success=$success, output=$output")
            success
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku command failed: $command", e)
            false
        }
    }

    private fun statePrefs(context: Context) =
        context.applicationContext
            .createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
