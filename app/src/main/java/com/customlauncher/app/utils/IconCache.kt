package com.customlauncher.app.utils

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import com.customlauncher.app.LauncherApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import com.customlauncher.app.data.model.AppInfo

object IconCache {
    // Cache size - adaptive based on available memory
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = (maxMemory / 8).coerceIn(8 * 1024, 24 * 1024)
    
    // Two-level cache: memory cache for fast access
    private val memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            // Return the size of the bitmap in kilobytes
            return bitmap.byteCount / 1024
        }
        
        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
            // Evictions are expected on low-memory devices; avoid log spam during scrolling.
        }
    }
    
    // Track cache statistics
    private var cacheHits = 0
    private var cacheMisses = 0
    // MT6739 has four slow A53 cores. Two decoders avoid an IPC/CPU stampede while
    // leaving headroom for UI, system_server and launcher services.
    private val loadSlots = Semaphore(2)
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Bitmap>>()
    @Volatile private var iconPackManager: IconPackManager? = null
    
    fun getCachedIcon(cacheKey: String): Bitmap? {
        val bitmap = memoryCache.get(cacheKey)
        if (bitmap != null) {
            cacheHits++
        } else {
            cacheMisses++
        }
        return bitmap
    }
    
    fun putIcon(cacheKey: String, icon: Bitmap) {
        memoryCache.put(cacheKey, icon)
    }

    fun getIconNow(
        context: Context,
        app: AppInfo,
        targetSizePx: Int
    ): Drawable? {
        val key = buildCacheKey(app.packageName, app.componentName, targetSizePx, app.packageFingerprint)
        return memoryCache.get(key)?.let { BitmapDrawable(context.resources, it) }
    }

    /**
     * Get cache statistics for debugging
     */
    fun getCacheStats(): String {
        val hitRate = if (cacheHits + cacheMisses > 0) {
            (cacheHits * 100.0 / (cacheHits + cacheMisses)).toInt()
        } else 0
        return "Cache: ${memoryCache.size()}/${memoryCache.maxSize()} KB, Hit rate: $hitRate% ($cacheHits/${cacheHits + cacheMisses})"
    }
    
    /**
     * Clear the entire cache
     */
    fun clearCache() {
        memoryCache.evictAll()
        cacheHits = 0
        cacheMisses = 0
        android.util.Log.d("IconCache", "Cache cleared")
    }
    
    suspend fun loadIcon(
        context: Context,
        packageName: String,
        packageManager: PackageManager,
        componentName: ComponentName? = null,
        targetSizePx: Int = 96,
        packageFingerprint: Long = 0L
    ): Drawable = withContext(Dispatchers.IO) {
        try {
            // Generate cache key including icon pack
            val componentKey = componentName?.flattenToShortString() ?: packageName
            val iconPackPackage = LauncherApplication.instance.preferences.iconPackPackageName
            val cacheKey = buildCacheKey(packageName, componentKey, targetSizePx, packageFingerprint)
            
            // Check cache first
            val cached = getCachedIcon(cacheKey)
            if (cached != null) {
                return@withContext BitmapDrawable(context.resources, cached)
            }

            val diskFile = persistentFile(context, cacheKey)
            val legacyFile = legacyFile(context, cacheKey)
            if (diskFile.exists()) BitmapFactory.decodeFile(diskFile.path)?.let {
                it.prepareToDraw()
                putIcon(cacheKey, it)
                return@withContext BitmapDrawable(context.resources, it)
            }
            if (legacyFile.exists()) BitmapFactory.decodeFile(legacyFile.path)?.let {
                it.prepareToDraw()
                putIcon(cacheKey, it)
                runCatching { diskFile.parentFile?.mkdirs(); legacyFile.copyTo(diskFile, true); legacyFile.delete() }
                return@withContext BitmapDrawable(context.resources, it)
            }

            val mine = kotlinx.coroutines.CompletableDeferred<Bitmap>()
            val existing = inFlight.putIfAbsent(cacheKey, mine)
            if (existing != null) {
                return@withContext BitmapDrawable(context.resources, existing.await())
            }

            try {
                val bitmap = loadSlots.withPermit {
                    var drawable: Drawable? = null

                    if (iconPackPackage != null && componentName != null) {
                        val manager = iconPackManager ?: synchronized(this@IconCache) {
                            iconPackManager ?: IconPackManager(context.applicationContext).also { iconPackManager = it }
                        }
                        drawable = manager.getIconFromPack(iconPackPackage, componentName)
                    }
            
            // Fall back to system icon
                    if (drawable == null) {
                        val appInfo = packageManager.getApplicationInfo(packageName, 0)
                        drawable = packageManager.getApplicationIcon(appInfo)
                    }
            
            // Convert to bitmap and cache
                    drawableToBitmap(drawable, targetSizePx)
                }
                putIcon(cacheKey, bitmap)
                bitmap.prepareToDraw()
                runCatching { FileOutputStream(diskFile).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                mine.complete(bitmap)
                BitmapDrawable(context.resources, bitmap)
            } catch (t: Throwable) {
                mine.completeExceptionally(t)
                throw t
            } finally {
                inFlight.remove(cacheKey, mine)
            }
        } catch (e: Exception) {
            // Return default icon on error
            context.packageManager.defaultActivityIcon
        }
    }
    
    private fun drawableToBitmap(drawable: Drawable, size: Int): Bitmap {
        val target = size.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(
            target, target,
            Bitmap.Config.ARGB_8888
        )
        
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        
        return bitmap
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun buildCacheKey(
        packageName: String,
        component: String?,
        size: Int,
        fingerprint: Long
    ): String {
        val normalized = component?.let(ComponentName::unflattenFromString)?.flattenToShortString()
            ?: component ?: packageName
        return "$normalized:${LauncherApplication.instance.preferences.iconPackPackageName.orEmpty()}:$size:$fingerprint"
    }

    private fun persistentFile(context: Context, key: String) =
        File(File(context.filesDir, "app_icons").apply { mkdirs() }, hash(key) + ".png")

    private fun legacyFile(context: Context, key: String) =
        File(File(context.cacheDir, "app_icons"), hash(key) + ".png")
    
    fun clear() {
        memoryCache.evictAll()
    }
}
