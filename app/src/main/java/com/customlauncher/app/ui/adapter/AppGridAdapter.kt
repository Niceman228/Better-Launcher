package com.customlauncher.app.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.customlauncher.app.R
import com.customlauncher.app.data.model.AppInfo
import com.customlauncher.app.utils.IconCache
import com.customlauncher.app.utils.AdaptiveSizeCalculator
import com.customlauncher.app.data.model.GridConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import android.util.Log
import android.widget.PopupWindow
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.graphics.Color
import android.os.Build
import com.customlauncher.app.LauncherApplication
import android.content.ClipData
import android.content.ClipDescription

class AppGridAdapter(
    private val onAppClick: (AppInfo) -> Unit,
    private val onAppLongClick: ((AppInfo, View) -> Boolean)? = null,
    private val isDragEnabled: Boolean = false,
    private val onDragStarted: (() -> Unit)? = null
) : ListAdapter<AppInfo, AppGridAdapter.ViewHolder>(AppDiffCallback()) {
    
    companion object {
        private const val PAYLOAD_SELECTION = "selection"
    }

    // Single scope for all icon loading tasks with supervisor job
    private val adapterScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var attachedRecyclerView: RecyclerView? = null

    // Full list of apps for filtering
    private var fullAppsList = listOf<AppInfo>()
    private var currentFilter = ""

    // Выделенная позиция (D-pad навигация). Единственный источник подсветки:
    // ресайкл view больше не размножает селектор, т.к. bind всегда
    // выставляет isSelected по позиции.
    var selectedPosition: Int = RecyclerView.NO_POSITION
        private set

    // Размеры/сетка одинаковы для всех ячеек — считаем один раз на датасет,
    // а не в каждом bind (дорого на слабом железе).
    private var cachedLayout: CachedLayout? = null

    private data class CachedLayout(
        val iconSize: Int,
        val padding: Int,
        val showText: Boolean,
        val textSize: Float,
        val maxLines: Int
    )

    fun setSelectedPosition(position: Int) {
        if (position == selectedPosition) return
        val old = selectedPosition
        selectedPosition = position
        val recycler = attachedRecyclerView
        var oldHolder: ViewHolder? = null
        var newHolder: ViewHolder? = null
        // Normalize every attached view. This also clears stale selection left by an
        // interrupted RecyclerView payload/layout from an earlier key event.
        if (recycler != null) {
            for (index in 0 until recycler.childCount) {
                val holder = recycler.getChildViewHolder(recycler.getChildAt(index)) as? ViewHolder ?: continue
                val holderPosition = holder.bindingAdapterPosition
                holder.applySelection(holderPosition == position)
                if (holderPosition == old) oldHolder = holder
                if (holderPosition == position) newHolder = holder
            }
        }
        if (old != RecyclerView.NO_POSITION && oldHolder == null) notifyItemChanged(old, PAYLOAD_SELECTION)
        if (position != RecyclerView.NO_POSITION && newHolder == null) notifyItemChanged(position, PAYLOAD_SELECTION)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app_grid, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        try {
            holder.bind(getItem(position))
        } catch (e: Exception) {
            Log.e("AppGridAdapter", "Error binding item at position $position", e)
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_SELECTION)) {
            holder.applySelection(position == selectedPosition)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }
    
    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.cancelIconLoad()
    }
    
    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        attachedRecyclerView = null
        // Cancel all coroutines when adapter is detached
        adapterScope.cancel()
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecyclerView = recyclerView
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val appIcon: ImageView = itemView.findViewById(R.id.appIcon)
        private val appName: TextView = itemView.findViewById(R.id.appName)
        private var iconLoadJob: Job? = null
        private var boundApp: AppInfo? = null
        private var isLongPressed = false
        private var isDragging = false
        private var startX = 0f
        private var startY = 0f
        private var menuRunnable: Runnable? = null

        init {
            itemView.setOnClickListener { boundApp?.let(onAppClick) }
            if (isDragEnabled) {
                itemView.setOnTouchListener { view, event -> handleTouch(view, event) }
            }
            itemView.setOnLongClickListener { view -> handleLongClick(view) }
        }
        
        fun bind(app: AppInfo) {
            boundApp = app
            val context = itemView.context

            val layout = getCachedLayout(context)

            // Apply adaptive icon size
            val layoutParams = appIcon.layoutParams
            if (layoutParams.width != layout.iconSize || layoutParams.height != layout.iconSize) {
                layoutParams.width = layout.iconSize
                layoutParams.height = layout.iconSize
                appIcon.layoutParams = layoutParams
            }

            // Apply adaptive padding
            itemView.setPadding(layout.padding, layout.padding, layout.padding, layout.padding)

            // Apply adaptive text size and visibility
            if (layout.showText) {
                appName.text = app.appName
                appName.visibility = View.VISIBLE
                appName.textSize = layout.textSize
                appName.maxLines = layout.maxLines
            } else {
                appName.visibility = View.GONE
            }

            applySelection(bindingAdapterPosition == selectedPosition)

            // Cancel previous icon load
            cancelIconLoad()

            // Warm launches bind persisted icons synchronously: no placeholder flash,
            // coroutine, disk read or second ImageView invalidation for known apps.
            val readyIcon = IconCache.getIconNow(context, app, layout.iconSize)
            if (readyIcon != null) {
                appIcon.setImageDrawable(readyIcon)
                return
            }

            // Only new/updated apps use placeholder and background queue.
            appIcon.setImageDrawable(app.icon)

            // Load real icon asynchronously using adapter scope
            iconLoadJob = adapterScope.launch {
                try {
                    val icon = withContext(Dispatchers.IO) {
                        IconCache.loadIcon(
                            itemView.context,
                            app.packageName,
                            itemView.context.packageManager,
                            app.componentName?.let(android.content.ComponentName::unflattenFromString),
                            layout.iconSize,
                            app.packageFingerprint
                        )
                    }
                    // Check if job is still active before updating UI
                    if (iconLoadJob?.isActive == true) {
                        appIcon.setImageDrawable(icon)
                    }
                } catch (e: Exception) {
                    Log.d("AppGridAdapter", "Error loading icon for ${app.packageName}")
                    // Use placeholder on error - already set
                }
            }
            
        }

        private fun handleTouch(view: View, event: android.view.MotionEvent): Boolean {
            val app = boundApp ?: return false
            return when (event.action) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            startX = event.rawX
                            startY = event.rawY
                            isLongPressed = false
                            isDragging = false
                            false // Let the click/long click handlers work
                        }
                        android.view.MotionEvent.ACTION_MOVE -> {
                            if (!isDragging && isLongPressed) {
                                val deltaX = kotlin.math.abs(event.rawX - startX)
                                val deltaY = kotlin.math.abs(event.rawY - startY)
                                
                                // Use larger threshold to prevent accidental drags
                                val dragThreshold = 20f // Increased from 5 to 20 pixels
                                
                                // Start drag only if moved significantly
                                if (deltaX > dragThreshold || deltaY > dragThreshold) {
                                    // Cancel the menu if it was scheduled
                                    menuRunnable?.let {
                                        view.removeCallbacks(it)
                                        menuRunnable = null
                                    }
                                    
                                    // Already disabled scrolling in long click, keep it disabled
                                    view.parent?.requestDisallowInterceptTouchEvent(true)
                                    
                                    isDragging = true
                                    startDragForApp(view, app)
                                    return true
                                }
                            }
                            
                            // Keep scrolling disabled during long press
                            if (isLongPressed) {
                                view.parent?.requestDisallowInterceptTouchEvent(true)
                                return true
                            }
                            
                            false
                        }
                        android.view.MotionEvent.ACTION_UP,
                        android.view.MotionEvent.ACTION_CANCEL -> {
                            // Re-enable parent scrolling
                            view.parent?.requestDisallowInterceptTouchEvent(false)
                            
                            // Clean up
                            menuRunnable?.let {
                                view.removeCallbacks(it)
                                menuRunnable = null
                            }
                            isLongPressed = false
                            isDragging = false
                            false
                        }
                        else -> false
            }
        }

        private fun handleLongClick(view: View): Boolean {
            val app = boundApp ?: return false
            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            if (isDragEnabled) {
                    // If drag is enabled, set flag and wait for movement
                    isLongPressed = true
                    
                    // Immediately prevent parent from intercepting (stops scrolling on long press)
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    
                    // Show context menu after a delay if not dragging
                    menuRunnable = Runnable {
                        if (isLongPressed && !isDragging) {
                            // Re-enable scrolling if showing menu (no drag started)
                            view.parent?.requestDisallowInterceptTouchEvent(false)
                            onAppLongClick?.invoke(app, view)
                        }
                    }
                    menuRunnable?.let { view.postDelayed(it, 100) } // Wait 100ms to see if drag starts
            } else {
                onAppLongClick?.invoke(app, view)
            }
            return true
        }
        
        fun applySelection(selected: Boolean) {
            itemView.isSelected = selected
            val scale = if (selected) 1.05f else 1.0f
            itemView.scaleX = scale
            itemView.scaleY = scale
        }

        fun cancelIconLoad() {
            iconLoadJob?.cancel()
            menuRunnable?.let(itemView::removeCallbacks)
            menuRunnable = null
            isLongPressed = false
            isDragging = false
        }
        
        private fun startDragForApp(view: View, app: AppInfo) {
            // Create clip data with app information
            val clipData = ClipData.newPlainText(
                "app_from_drawer",
                "${app.packageName}|${app.appName}"
            )
            
            // Create drag shadow
            val shadowBuilder = View.DragShadowBuilder(view)
            
            // Make view semi-transparent during drag
            view.alpha = 0.5f
            
            // Start drag operation
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                view.startDragAndDrop(clipData, shadowBuilder, app, View.DRAG_FLAG_GLOBAL)
            } else {
                @Suppress("DEPRECATION")
                view.startDrag(clipData, shadowBuilder, app, 0)
            }
            
            // Notify that drag has started - this will close the app drawer
            onDragStarted?.invoke()
            
            // Restore opacity after a short delay
            view.postDelayed({ view.alpha = 1.0f }, 100)
        }
    }
    
    private fun getCachedLayout(context: Context): CachedLayout {
        cachedLayout?.let { return it }

        val preferences = LauncherApplication.instance.preferences

        // Get grid configuration from preferences
        // Use button grid if selected in menu settings
        val useButtonGrid =
            preferences.hasButtonGridSelection && preferences.buttonPhoneGridSize.isNotEmpty()

        val gridConfig = if (useButtonGrid) {
            val (cols, rows) = when (preferences.buttonPhoneGridSize) {
                "3x3" -> 3 to 3
                "3x4" -> 3 to 4
                "3x5" -> 3 to 5
                "4x5" -> 4 to 5
                else -> 4 to 5
            }
            GridConfiguration(cols, rows, preferences.buttonPhoneMode)
        } else {
            val columns = preferences.gridColumnCount.takeIf { it > 0 } ?: 4
            val rows = when (columns) {
                3 -> 5
                4 -> 6
                5 -> 7
                else -> 6
            }
            GridConfiguration(columns = columns, rows = rows, isButtonMode = false)
        }

        val showText = preferences.showAppLabels && AdaptiveSizeCalculator.shouldShowText(gridConfig)
        val layout = CachedLayout(
            iconSize = AdaptiveSizeCalculator.calculateIconSize(context, gridConfig),
            padding = AdaptiveSizeCalculator.calculatePadding(context, gridConfig),
            showText = showText,
            textSize = if (showText) AdaptiveSizeCalculator.calculateTextSize(context, gridConfig) else 0f,
            maxLines = if (showText) AdaptiveSizeCalculator.calculateMaxTextLines(gridConfig) else 1
        )
        cachedLayout = layout
        return layout
    }

    // Override submitList to store full list
    override fun submitList(list: List<AppInfo>?) {
        fullAppsList = list ?: emptyList()
        // Настройки сетки/подписей могли измениться — пересчитаем при следующем bind
        cachedLayout = null
        applyFilter()
    }
    
    // Get item at specific position
    fun getItemAt(position: Int): AppInfo? {
        return if (position >= 0 && position < itemCount) {
            getItem(position)
        } else {
            null
        }
    }
    
    // Filter method for search
    fun filter(query: String) {
        currentFilter = query
        applyFilter()
    }
    
    // Apply filter to the list
    fun applyFilter() {
        val filteredList = if (currentFilter.isEmpty()) {
            fullAppsList
        } else {
            fullAppsList.filter { app ->
                app.appName.contains(currentFilter, ignoreCase = true) ||
                app.packageName.contains(currentFilter, ignoreCase = true)
            }
        }
        super.submitList(filteredList)
    }
    
    private class AppDiffCallback : DiffUtil.ItemCallback<AppInfo>() {
        override fun areItemsTheSame(oldItem: AppInfo, newItem: AppInfo): Boolean {
            return oldItem.packageName == newItem.packageName
        }
        
        override fun areContentsTheSame(oldItem: AppInfo, newItem: AppInfo): Boolean {
            return oldItem == newItem
        }
    }
}
