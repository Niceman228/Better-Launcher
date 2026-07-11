package com.customlauncher.app.receiver

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import com.customlauncher.app.data.model.CustomKeyCombination

class CustomKeyListener(
    private val onCombinationDetected: () -> Unit
) {
    private val keySequence = mutableListOf<Int>()
    private var targetCombination: CustomKeyCombination? = null
    private val handler = Handler(Looper.getMainLooper())
    private val resetRunnable = Runnable { reset() }
    private var isProcessing = false
    private var lastMatchTime = 0L
    
    companion object {
        private const val TAG = "CustomKeyListener"
        private const val SUCCESS_COOLDOWN_MS = 700L
    }
    
    fun setCombination(combination: CustomKeyCombination?) {
        targetCombination = combination
        reset()
    }
    
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            return false
        }

        if (event.repeatCount > 0) {
            Log.d(TAG, "Ignoring repeated key event: ${event.keyCode}, repeat=${event.repeatCount}")
            return false
        }

        return onKeyCode(event.keyCode)
    }

    fun onKeyEvent(keyCode: Int): Boolean {
        return onKeyCode(keyCode)
    }

    private fun onKeyCode(keyCode: Int): Boolean {
        Log.d(TAG, "onKeyEvent called with keyCode: $keyCode")
        val combination = targetCombination
        if (combination == null) {
            Log.w(TAG, "No target combination set!")
            return false
        }
        
        // Cancel reset timer
        handler.removeCallbacks(resetRunnable)
        
        // Add key to sequence
        keySequence.add(keyCode)
        Log.d(TAG, "Key sequence: $keySequence, target: ${combination.keys}")
        
        // Check if sequence matches
        if (keySequence == combination.keys) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastMatchTime < SUCCESS_COOLDOWN_MS) {
                Log.d(TAG, "Combination matched inside cooldown, ignoring duplicate")
                reset()
                return false
            }
            lastMatchTime = now

            Log.d(TAG, "Custom combination detected")
            if (!isProcessing) {
                isProcessing = true
                Log.d(TAG, "Triggering callback in 50ms...")
                // Trigger combination after a small delay to let the key event complete
                handler.postDelayed({
                    try {
                        Log.d(TAG, "Executing onCombinationDetected callback")
                        onCombinationDetected()
                    } finally {
                        isProcessing = false
                    }
                }, 50) // Small delay to ensure key event is processed
            } else {
                Log.d(TAG, "Already processing, skipping...")
            }
            reset()
            // NEVER block keys - always return false
            return false
        }
        
        // Check if sequence is too long or doesn't match prefix
        if (keySequence.size >= combination.keys.size) {
            // Check if this could be the start of a new sequence
            if (keySequence.takeLast(1) == combination.keys.take(1)) {
                keySequence.clear()
                keySequence.add(keyCode)
            } else {
                reset()
            }
        } else {
            // Check if current sequence matches the prefix
            if (keySequence != combination.keys.take(keySequence.size)) {
                // Check if last key could be start of new sequence
                if (listOf(keyCode) == combination.keys.take(1)) {
                    keySequence.clear()
                    keySequence.add(keyCode)
                } else {
                    reset()
                }
            }
        }
        
        // Set timeout for reset
        handler.postDelayed(resetRunnable, combination.timeoutMs)
        
        // ALWAYS return false - never block any keys
        return false
    }
    
    private fun reset() {
        keySequence.clear()
        handler.removeCallbacks(resetRunnable)
    }
    
    fun destroy() {
        reset()
    }
}
