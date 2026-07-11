package com.customlauncher.app.ui.widget

import android.content.Context
import android.util.AttributeSet
import androidx.recyclerview.widget.RecyclerView

class FadingEdgeRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {
    
    private var topFadeSize = 0
    private var bottomFadeSize = 0
    private var fadeEnabled = true
    
    init {
        // Set default fade sizes in pixels
        val density = context.resources.displayMetrics.density
        topFadeSize = (50 * density).toInt()
        bottomFadeSize = (40 * density).toInt()
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(maxOf(topFadeSize, bottomFadeSize))
    }
    
    fun setFadeSizes(top: Int, bottom: Int) {
        topFadeSize = top
        bottomFadeSize = bottom
        setFadingEdgeLength(maxOf(topFadeSize, bottomFadeSize))
        invalidate()
    }
    
    fun setFadeEnabled(enabled: Boolean) {
        fadeEnabled = enabled
        isVerticalFadingEdgeEnabled = enabled
        invalidate()
    }

    override fun getTopFadingEdgeStrength(): Float =
        if (fadeEnabled && topFadeSize > 0) topFadeSize.toFloat() / maxOf(topFadeSize, bottomFadeSize) else 0f

    override fun getBottomFadingEdgeStrength(): Float =
        if (fadeEnabled && bottomFadeSize > 0) bottomFadeSize.toFloat() / maxOf(topFadeSize, bottomFadeSize) else 0f
}
