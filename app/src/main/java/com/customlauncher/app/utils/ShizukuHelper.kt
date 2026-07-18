package com.customlauncher.app.utils

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.customlauncher.app.IUserService
import com.customlauncher.app.UserService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import kotlin.coroutines.resume

class ShizukuHelper(private val context: Context) {
    
    companion object {
        private const val TAG = "ShizukuHelper"
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val PERMISSION_CODE = 0
    }
    
    interface ShizukuCallback {
        fun onSuccess(message: String)
        fun onError(error: String)
    }
    
    // Check if Shizuku is installed
    fun isShizukuInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
    
    // Check if Shizuku is running
    fun isShizukuRunning(): Boolean {
        return try {
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_DENIED ||
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }
    
    // Check if we have Shizuku permission
    fun hasShizukuPermission(): Boolean {
        return try {
            if (!isShizukuRunning()) return false
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }
    
    // Request Shizuku permission
    fun requestShizukuPermission() {
        try {
            if (isShizukuRunning() && !hasShizukuPermission()) {
                Shizuku.requestPermission(PERMISSION_CODE)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request permission", e)
        }
    }
    
    private var userService: IUserService? = null
    
    // Create user service connection
    private suspend fun getUserService(): IUserService? = suspendCancellableCoroutine { continuation ->
        if (userService != null) {
            continuation.resume(userService)
            return@suspendCancellableCoroutine
        }
        
        val userServiceArgs = Shizuku.UserServiceArgs(
            ComponentName(context, UserService::class.java)
        ).daemon(false)
            .processNameSuffix("shizuku")
            // Must match the APK: a release (non-debuggable) build cannot start a
            // UserService process flagged debuggable — the bind fails silently and
            // svc data toggling never runs. Mirror BuildConfig.DEBUG.
            .debuggable(com.customlauncher.app.BuildConfig.DEBUG)
            .version(1)
        
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                userService = IUserService.Stub.asInterface(ShizukuBinderWrapper(service))
                continuation.resume(userService)
            }
            
            override fun onServiceDisconnected(name: ComponentName) {
                userService = null
            }
        }
        
        try {
            Shizuku.bindUserService(userServiceArgs, connection)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind user service", e)
            continuation.resume(null)
        }
    }
    
    // Execute shell command through Shizuku
    suspend fun executeCommand(command: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (!hasShizukuPermission()) {
            return@withContext Pair(false, "Нет разрешения Shizuku")
        }

        // Primary path: Shizuku.newProcess. It runs the command directly on the
        // Shizuku server without spawning a UserService process, so it avoids the
        // v13.6.0 makeApplication NPE that breaks bindUserService on MediaTek/OEM
        // firmware (Shizuku issues #2048, #1198) — exactly our Qin F22 / MT6739 case.
        runCatching { executeViaNewProcess(command) }
            .getOrNull()
            ?.let { return@withContext it }

        // Fallback: classic UserService (works where newProcess is unavailable).
        try {
            Log.d(TAG, "newProcess unavailable, falling back to UserService: $command")
            val service = getUserService()
                ?: return@withContext Pair(false, "Не удалось получить Shizuku UserService")
            val result = service.executeCommand(command)
            if (result.startsWith("ERROR:")) {
                Pair(false, result.substring(6))
            } else {
                Pair(true, if (result == "SUCCESS" || result.isEmpty()) "Команда выполнена" else result)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception executing command: $command", e)
            Pair(false, "Ошибка выполнения: ${e.message}")
        }
    }

    // Shizuku.newProcess is a hidden API (@RestrictTo); call it reflectively.
    // Returns a java.lang.Process (ShizukuRemoteProcess) with the usual streams.
    private fun executeViaNewProcess(command: String): Pair<Boolean, String> {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        ).apply { isAccessible = true }

        val process = method.invoke(
            null,
            arrayOf("sh", "-c", command),
            null,
            null
        ) as Process

        val output = process.inputStream.bufferedReader().use { it.readText() }
        val error = process.errorStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()

        return if (exitCode == 0) {
            Pair(true, output.trim().ifEmpty { "Команда выполнена" })
        } else {
            Pair(false, error.trim().ifEmpty { "Exit code: $exitCode" })
        }
    }
    
    // Enable accessibility service through Shizuku
    suspend fun enableAccessibilityService(callback: ShizukuCallback) {
        val commands = listOf(
            "settings put secure enabled_accessibility_services com.customlauncher.app/com.customlauncher.app.service.SystemBlockAccessibilityService",
            "settings put secure accessibility_enabled 1"
        )
        
        for (command in commands) {
            val (success, output) = executeCommand(command)
            if (!success) {
                callback.onError("Ошибка при выполнении: $command\n$output")
                return
            }
        }
        
        callback.onSuccess("Специальные возможности успешно активированы!")
    }
    
    // Enable overlay permission through Shizuku
    suspend fun enableOverlayPermission(callback: ShizukuCallback) {
        val command = "appops set com.customlauncher.app SYSTEM_ALERT_WINDOW allow"
        val (success, output) = executeCommand(command)
        
        if (success) {
            callback.onSuccess("Разрешение 'Поверх других окон' успешно активировано!")
        } else {
            callback.onError("Ошибка при активации разрешения: $output")
        }
    }
    
    // Enable all permissions
    suspend fun enableAllPermissions(callback: ShizukuCallback) {
        val commands = listOf(
            "settings put secure enabled_accessibility_services com.customlauncher.app/com.customlauncher.app.service.SystemBlockAccessibilityService",
            "settings put secure accessibility_enabled 1",
            "appops set com.customlauncher.app SYSTEM_ALERT_WINDOW allow"
        )
        
        val results = mutableListOf<String>()
        val errors = mutableListOf<String>()
        
        for (command in commands) {
            val (success, output) = executeCommand(command)
            if (success) {
                val cmdName = when {
                    command.contains("enabled_accessibility_services") -> "Специальные возможности"
                    command.contains("accessibility_enabled") -> "Accessibility"
                    command.contains("SYSTEM_ALERT_WINDOW") -> "Поверх других окон"
                    else -> "Команда"
                }
                results.add("✓ $cmdName")
                Log.d(TAG, "$cmdName активировано успешно")
            } else {
                errors.add("✗ Ошибка: $output")
            }
        }
        
        if (errors.isEmpty()) {
            callback.onSuccess("✅ Все разрешения активированы!\n${results.joinToString("\n")}")
        } else {
            callback.onError("Некоторые команды не выполнились:\n${errors.joinToString("\n")}")
        }
    }
    
    // Setup Shizuku listener
    fun setupShizukuListener(onPermissionResult: (granted: Boolean) -> Unit) {
        val listener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == PERMISSION_CODE) {
                onPermissionResult(grantResult == PackageManager.PERMISSION_GRANTED)
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
    }
    
    // Remove Shizuku listener
    fun removeShizukuListener(listener: Shizuku.OnRequestPermissionResultListener) {
        Shizuku.removeRequestPermissionResultListener(listener)
    }
}
