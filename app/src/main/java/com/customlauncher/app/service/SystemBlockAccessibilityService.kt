package com.customlauncher.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.content.Intent
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import com.customlauncher.app.LauncherApplication
import android.os.Handler
import android.os.Looper
import com.customlauncher.app.receiver.CustomKeyListener
import com.customlauncher.app.data.model.CustomKeyCombination
import android.app.KeyguardManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.UserManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.customlauncher.app.manager.DirectBootStateStore
import com.customlauncher.app.manager.HiddenModeStateManager

class SystemBlockAccessibilityService : AccessibilityService() {
    
    companion object {
        private const val TAG = "SystemBlockAccessibility"
        // Отключает подробные логи в горячих путях (каждое нажатие клавиши /
        // каждое accessibility-событие) — заметная экономия на слабом железе.
        private const val VERBOSE = false
        const val ACTION_BLOCK_TOUCHES = "com.customlauncher.app.BLOCK_TOUCHES"
        const val ACTION_UNBLOCK_TOUCHES = "com.customlauncher.app.UNBLOCK_TOUCHES"
        const val ACTION_CLOSE_ALL_APPS = "com.customlauncher.app.CLOSE_ALL_APPS"
        const val ACTION_REFRESH_KEYS = "com.customlauncher.app.REFRESH_KEYS"
        private const val ACTION_TOGGLE_APPS = "com.customlauncher.app.TOGGLE_APPS"
        var instance: SystemBlockAccessibilityService? = null
        private const val KEYGUARD_CHECK_DELAY = 50L
        private const val OVERLAY_RETRY_DELAY_MS = 1_000L
        private const val OVERLAY_MAX_RETRIES = 3
    }
    
    // For key combination detection
    private var customKeyListener: CustomKeyListener? = null
    private val keyPressHandler = Handler(Looper.getMainLooper())
    private val keyguardCheckRunnable = Runnable { checkScreenLockState() }
    private var lastKeyEventTime = 0L
    private val KEY_EVENT_TIMEOUT = 100L // milliseconds
    private var isScreenLocked = false
    private lateinit var keyguardManager: KeyguardManager
    private var isTouchBlockingConfigured = false
    private var lastSystemUiBlockTime = 0L
    private var touchBlockOverlay: View? = null
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        
        // Initialize KeyguardManager
        keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        
        checkScreenLockState()
        
        applyKeyboardSafeServiceInfo(notificationTimeout = 100)
        Log.d(TAG, "Accessibility service connected - keyboard-safe mode active")

        setupCustomKeyListener()
        
        // Check initial lock state
        checkScreenLockState()
        
        // Задержка для стабилизации после обновления приложения
        Handler(Looper.getMainLooper()).postDelayed({
            reinitializeAfterUpdate()
        }, 1000)
    }
    
    private fun reinitializeAfterUpdate() {
        Log.d(TAG, "Reinitializing after potential update...")
        
        // Сбрасываем состояние
        isTouchBlockingConfigured = false
        
        // Переинициализируем state manager
        HiddenModeStateManager.initializeState(this)
        Log.d(TAG, "Initial state - Hidden mode: ${HiddenModeStateManager.currentState}")
        
        val savedHiddenMode = readSavedHiddenMode()

        if (savedHiddenMode && readBlockTouchSetting()) {
            Log.d(TAG, "Restoring hidden mode touch blocking after boot/update")
            // Включаем блокировку с небольшой задержкой
            Handler(Looper.getMainLooper()).postDelayed({
                if (HiddenModeStateManager.currentState || readSavedHiddenMode()) {
                    enableTouchBlocking()
                }
            }, 500)
        }
        
        setupCustomKeyListener()
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (VERBOSE && HiddenModeStateManager.currentState && isTouchBlockingConfigured) {
                when (event?.eventType) {
                    AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
                    AccessibilityEvent.TYPE_TOUCH_INTERACTION_END,
                    AccessibilityEvent.TYPE_GESTURE_DETECTION_START,
                    AccessibilityEvent.TYPE_GESTURE_DETECTION_END,
                    AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START,
                    AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END,
                    AccessibilityEvent.TYPE_VIEW_HOVER_ENTER,
                    AccessibilityEvent.TYPE_VIEW_HOVER_EXIT -> {
                        Log.d(TAG, "Touch-related accessibility event observed in hidden mode: ${event.eventType}")
                    }
                }
            }

            // Check for screen lock state changes
            when (event?.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    val packageName = event.packageName?.toString()
                    
                    // Detect keyguard (lock screen) state
                    if (packageName == "com.android.keyguard" || 
                        packageName == "com.android.systemui.keyguard") {
                        checkScreenLockState()
                        Log.d(TAG, "Keyguard state changed, locked: $isScreenLocked")
                    }
                    
                    // Only block system UI when apps are actually hidden
                    if (HiddenModeStateManager.currentState) {
                        if (packageName == "com.android.systemui" && shouldBlockSystemUiNow()) {
                            // Only block system UI when not on lock screen and apps are hidden
                            performGlobalAction(GLOBAL_ACTION_HOME)
                            Log.d(TAG, "Blocked system UI: $packageName (hidden mode active)")
                        }
                    }
                }
                
                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                    if (HiddenModeStateManager.currentState && !isScreenLocked) {
                        // Just log notification event without triggering back action
                        // performGlobalAction(GLOBAL_ACTION_BACK) - removed to prevent unwanted back action
                        Log.d(TAG, "Notification shade event in hidden mode")
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in onAccessibilityEvent", e)
        }
    }
    
    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Only handle key down events
        if (event.action != KeyEvent.ACTION_DOWN) {
            return false
        }
        
        lastKeyEventTime = android.os.SystemClock.elapsedRealtime()
        
        // Check if screen is locked with delay to ensure accurate state
        // At most one pending check during rapid typing/D-pad navigation.
        keyPressHandler.removeCallbacks(keyguardCheckRunnable)
        keyPressHandler.postDelayed(keyguardCheckRunnable, KEYGUARD_CHECK_DELAY)
        
        if (VERBOSE) {
            Log.d(TAG, "KeyEvent: action=${event.action}, keyCode=${event.keyCode}, scanCode=${event.scanCode}, repeat=${event.repeatCount}, deviceId=${event.deviceId}, source=${event.source}, hidden=${HiddenModeStateManager.currentState}, locked=$isScreenLocked")
        }
        
        // Handle custom key combinations if enabled. Do not read credential
        // protected preferences here; after boot they may not be available yet.
        customKeyListener?.let { listener ->
            listener.onKeyEvent(event)
        }
        
        // Don't block any navigation keys - let them work normally
        // All keys should work as usual
        
        // Let all keys pass through
        return false // Let the system handle all keys
    }
    
    private fun setupCustomKeyListener() {
        // Clean up old listener first
        customKeyListener?.destroy()
        customKeyListener = null
        
        val keySnapshot = readKeySnapshot()

        if (keySnapshot.useCustomKeys) {
            val customKeysString = keySnapshot.customKeyCombination
            val keys = customKeysString?.split(",")?.mapNotNull { it.toIntOrNull() } ?: emptyList()
            if (keys.isNotEmpty()) {
                val combination = CustomKeyCombination(keys)
                customKeyListener = CustomKeyListener {
                    Log.d(TAG, "Custom key combination detected!")
                    sendKeyCombinationBroadcast()
                }
                customKeyListener?.setCombination(combination)
                Log.d(TAG, "Custom key listener setup with keys: $keys")
            } else {
                Log.w(TAG, "Custom keys enabled but no valid combination is available")
            }
        } else {
            Log.d(TAG, "Custom key listener disabled")
        }
    }

    private fun readKeySnapshot(): DirectBootStateStore.KeySnapshot {
        val directBootSnapshot = DirectBootStateStore.getKeySnapshot(this)

        return try {
            if (isUserUnlocked()) {
                val preferences = LauncherApplication.instance.preferences
                val snapshot = DirectBootStateStore.KeySnapshot(
                    useCustomKeys = preferences.useCustomKeys,
                    customKeyCombination = preferences.customKeyCombination
                )
                DirectBootStateStore.saveCustomKeySettings(
                    this,
                    snapshot.useCustomKeys,
                    snapshot.customKeyCombination
                )
                snapshot
            } else {
                directBootSnapshot
            }
        } catch (e: Exception) {
            Log.w(TAG, "Falling back to Direct Boot key settings", e)
            directBootSnapshot
        }
    }

    private fun readSavedHiddenMode(): Boolean {
        return try {
            if (isUserUnlocked()) {
                LauncherApplication.instance.preferences.appsHidden
            } else {
                DirectBootStateStore.isHiddenModeEnabled(this)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Falling back to Direct Boot hidden mode state", e)
            DirectBootStateStore.isHiddenModeEnabled(this)
        }
    }

    private fun readBlockTouchSetting(): Boolean {
        return try {
            if (isUserUnlocked()) {
                LauncherApplication.instance.preferences.blockTouchInHiddenMode
            } else {
                DirectBootStateStore.getFeatureSnapshot(this).blockTouch
            }
        } catch (e: Exception) {
            Log.w(TAG, "Falling back to Direct Boot block-touch setting", e)
            DirectBootStateStore.getFeatureSnapshot(this).blockTouch
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
    
    private fun sendKeyCombinationBroadcast() {
        Log.d(TAG, "Key combination detected, toggling hidden mode")
        
        val persistedHidden = DirectBootStateStore.isHiddenModeEnabled(this)
        val preferenceHidden = readSavedHiddenMode()
        val currentState = HiddenModeStateManager.currentState || persistedHidden || preferenceHidden
        val newState = !currentState
        
        Log.d(TAG, "Toggling from effectiveState=$currentState to $newState (manager=${HiddenModeStateManager.currentState}, directBoot=$persistedHidden, preferences=$preferenceHidden, touchBlocking=$isTouchBlockingConfigured)")
        
        HiddenModeStateManager.forceSetHiddenMode(this, newState)
    }
    
    private fun checkScreenLockState() {
        isScreenLocked = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                keyguardManager.isDeviceLocked
            } else {
                keyguardManager.isKeyguardLocked
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking screen lock state", e)
            false
        }
    }

    private fun shouldBlockSystemUiNow(): Boolean {
        if (isScreenLocked) {
            return false
        }

        val now = SystemClock.elapsedRealtime()
        if (now < 30_000L) {
            return false
        }

        if (now - lastSystemUiBlockTime < 1_200L) {
            return false
        }

        lastSystemUiBlockTime = now
        return true
    }
    
    private fun toggleHiddenMode() {
        Log.d(TAG, "Toggling hidden mode from accessibility service")
        
        val currentState = HiddenModeStateManager.currentState
        val newState = !currentState
        
        Log.d(TAG, "Current state: $currentState, new state: $newState")
        
        // Enable/disable touch blocking based on new state
        if (newState) {
            enableTouchBlocking()
        } else {
            disableTouchBlocking()
        }
        
        // Update the state through the manager
        HiddenModeStateManager.setHiddenMode(this, newState)
        
        // Send broadcast to notify other components
        val intent = Intent(ACTION_TOGGLE_APPS)
        val isNow = HiddenModeStateManager.currentState
        intent.putExtra("hidden", isNow)
        sendBroadcast(intent)
        
        Log.d(TAG, "Broadcast sent for state change")
    }
    
    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }
    
    override fun onGesture(gestureId: Int): Boolean {
        if (HiddenModeStateManager.currentState && isTouchBlockingConfigured) {
            Log.d(TAG, "Gesture observed in hidden mode: $gestureId")
        }
        return super.onGesture(gestureId)
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")
        
        when (intent?.action) {
            ACTION_BLOCK_TOUCHES -> {
                if (HiddenModeStateManager.currentState) {
                    enableTouchBlocking()
                    // Refresh key listener when entering hidden mode
                    setupCustomKeyListener()
                } else {
                    Log.d(TAG, "Ignoring touch block action because hidden mode is inactive")
                }
            }
            ACTION_UNBLOCK_TOUCHES -> {
                disableTouchBlocking()
                // Refresh key listener when exiting hidden mode
                setupCustomKeyListener()
            }
            ACTION_REFRESH_KEYS -> {
                Log.d(TAG, "Refreshing custom key listener")
                setupCustomKeyListener()
            }
            "com.customlauncher.CLOSE_ALL_APPS" -> {
                closeAllApps()
                // Also refresh keys after closing apps
                setupCustomKeyListener()
            }
            Intent.ACTION_SCREEN_OFF -> {
                // Optional: Can lock screen if needed
                // performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            }
        }
        
        return super.onStartCommand(intent, flags, startId)
    }
    
    private fun enableTouchBlocking() {
        try {
            Log.d(TAG, "Enabling touch blocking...")

            // Hide keyboard if visible
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val controller = softKeyboardController
                controller?.showMode = AccessibilityService.SHOW_MODE_HIDDEN
            }

            applyKeyboardSafeServiceInfo(notificationTimeout = 0)
            isTouchBlockingConfigured = true

            // TYPE_ACCESSIBILITY_OVERLAY: добавляется только accessibility-сервисом,
            // не требует разрешения SYSTEM_ALERT_WINDOW и живёт на токене самого
            // сервиса — безопасно сразу после загрузки устройства.
            keyPressHandler.post { addTouchBlockOverlay(OVERLAY_MAX_RETRIES) }
            Log.d(TAG, "Touch blocking requested - accessibility overlay pending")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable touch blocking", e)
        }
    }

    private fun addTouchBlockOverlay(retriesLeft: Int) {
        if (touchBlockOverlay != null) {
            Log.d(TAG, "Touch block overlay already attached")
            return
        }

        if (!isTouchBlockingConfigured) {
            Log.d(TAG, "Touch blocking no longer requested, skipping overlay")
            return
        }

        try {
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

            val overlay = object : FrameLayout(this) {
                override fun dispatchTouchEvent(ev: MotionEvent?): Boolean = true
                override fun onTouchEvent(event: MotionEvent?): Boolean = true
            }.apply {
                setBackgroundColor(Color.TRANSPARENT)
                isClickable = true
                isFocusable = false
                isFocusableInTouchMode = false
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // FLAG_NOT_FOCUSABLE: аппаратные клавиши продолжают работать,
                // комбинация выхода из скрытого режима остаётся доступной.
                // Окно остаётся touch-modal, поэтому все касания поглощаются.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    fitInsetsTypes = 0
                }
            }

            windowManager.addView(overlay, params)
            touchBlockOverlay = overlay
            Log.d(TAG, "Touch block accessibility overlay attached")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach touch block overlay, retriesLeft=$retriesLeft", e)
            if (retriesLeft > 0) {
                keyPressHandler.postDelayed(
                    { addTouchBlockOverlay(retriesLeft - 1) },
                    OVERLAY_RETRY_DELAY_MS
                )
            }
        }
    }

    private fun removeTouchBlockOverlay() {
        val overlay = touchBlockOverlay ?: return
        touchBlockOverlay = null
        try {
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            windowManager.removeViewImmediate(overlay)
            Log.d(TAG, "Touch block accessibility overlay removed")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to remove touch block overlay (may already be gone)", e)
        }
    }
    
    private fun closeAllApps() {
        try {
            Log.d(TAG, "Attempting to close all apps via accessibility service")
            
            // Method 1: Go directly to home screen (fastest)
            performGlobalAction(GLOBAL_ACTION_HOME)
            
            // Method 2: Send back button to ensure current app is closed
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                performGlobalAction(GLOBAL_ACTION_BACK)
                // One more home to ensure we're on launcher
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }, 50)
            }, 100)
            
            Log.d(TAG, "Close all apps commands sent")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close all apps", e)
        }
    }
    
    private fun disableTouchBlocking() {
        try {
            Log.d(TAG, "Disabling touch blocking...")

            isTouchBlockingConfigured = false
            keyPressHandler.post { removeTouchBlockOverlay() }

            // Restore keyboard to normal mode
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val controller = softKeyboardController
                controller?.showMode = AccessibilityService.SHOW_MODE_AUTO
                Log.d(TAG, "Keyboard restored to auto mode")
            }

            applyKeyboardSafeServiceInfo(notificationTimeout = 100)
            Log.d(TAG, "Touch blocking DISABLED - normal mode restored")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable touch blocking", e)
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        keyPressHandler.removeCallbacksAndMessages(null)
        removeTouchBlockOverlay()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        keyPressHandler.removeCallbacksAndMessages(null)
        removeTouchBlockOverlay()
        customKeyListener?.destroy()
        customKeyListener = null
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    private fun applyKeyboardSafeServiceInfo(notificationTimeout: Long) {
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
            flags = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            this.notificationTimeout = notificationTimeout
        }

        serviceInfo = info
    }
}
