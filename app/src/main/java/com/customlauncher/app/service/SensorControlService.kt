package com.customlauncher.app.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

class SensorControlService : Service() {
    
    companion object {
        private const val TAG = "SensorControlService"
        const val ACTION_DISABLE_SENSOR = "com.customlauncher.app.DISABLE_SENSOR"
        const val ACTION_ENABLE_SENSOR = "com.customlauncher.app.ENABLE_SENSOR"
        
        private var isDisabled = false
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "SensorControlService created as no-op compatibility service")
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISABLE_SENSOR -> {
                isDisabled = true
                Log.d(TAG, "Ignoring legacy disable sensor request; touch is blocked by overlay")
            }
            ACTION_ENABLE_SENSOR -> {
                isDisabled = false
                Log.d(TAG, "Ignoring legacy enable sensor request; no system sensor state was changed")
            }
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        isDisabled = false
    }
}
