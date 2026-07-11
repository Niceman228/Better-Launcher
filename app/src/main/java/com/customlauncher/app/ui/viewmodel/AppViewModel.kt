package com.customlauncher.app.ui.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.customlauncher.app.LauncherApplication
import com.customlauncher.app.data.SelectionManager
import com.customlauncher.app.data.model.AppInfo
import com.customlauncher.app.data.repository.AppRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import android.util.Log
import com.customlauncher.app.utils.IconCache

class AppViewModel : ViewModel() {
    
    private val repository: AppRepository = LauncherApplication.instance.repository
    
    private val _allApps = MutableLiveData<List<AppInfo>>()
    val allApps: LiveData<List<AppInfo>> = _allApps
    
    private val _visibleApps = MutableLiveData<List<AppInfo>>()
    val visibleApps: LiveData<List<AppInfo>> = _visibleApps
    
    private val _hiddenApps = MutableLiveData<List<AppInfo>>()
    val hiddenApps: LiveData<List<AppInfo>> = _hiddenApps
    
    private val _filteredApps = MutableLiveData<List<AppInfo>>()
    val filteredApps: LiveData<List<AppInfo>> = _filteredApps
    
    // Optimization for weak devices
    private var loadJob: Job? = null
    private var saveJob: Job? = null
    
    init {
        // First-frame path: catalog snapshot and preloaded icons are ready right after
        // startup, so the very first drawer open renders without an IO round trip.
        publishWarmSnapshot()
        loadApps()
    }

    private fun publishWarmSnapshot() {
        if (!IconCache.isStartupPreloadComplete()) return
        val apps = repository.warmCatalog()
        if (apps.isEmpty()) return
        val (allList, visibleList, hiddenList) = partitionWithSelection(apps)
        _allApps.value = allList
        _visibleApps.value = visibleList
        _hiddenApps.value = hiddenList
        _filteredApps.value = allList
    }

    private fun partitionWithSelection(
        apps: List<AppInfo>
    ): Triple<List<AppInfo>, List<AppInfo>, List<AppInfo>> {
        val tempSelection = SelectionManager.getSelection()
        val savedHiddenApps = if (tempSelection.isEmpty()) {
            LauncherApplication.instance.preferences.getHiddenApps()
        } else {
            tempSelection
        }
        val allList = ArrayList<AppInfo>(apps.size)
        val visibleList = ArrayList<AppInfo>(apps.size)
        val hiddenList = ArrayList<AppInfo>()
        apps.forEach { app ->
            val appWithSelection = app.copy(isSelected = savedHiddenApps.contains(app.packageName))
            allList.add(appWithSelection)
            if (appWithSelection.isHidden) hiddenList.add(appWithSelection) else visibleList.add(appWithSelection)
        }
        return Triple(allList, visibleList, hiddenList)
    }
    
    fun invalidateCache() {
        repository.invalidateCache()
    }
    
    fun onPackageRemoved(packageName: String) {
        Log.d("AppViewModel", "Package removed: $packageName, forcing immediate update")
        // Force immediate cache invalidation
        repository.invalidateCache()
        // Force reload with a slight delay to ensure system updates
        viewModelScope.launch {
            delay(100)
            loadApps()
        }
    }
    
    fun loadApps() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            val started = android.os.SystemClock.elapsedRealtime()
            try {
                // Never publish known apps before their persisted icons are in memory.
                // New/updated apps are absent from disk and still load lazily.
                IconCache.awaitStartupPreload()
                val apps = repository.getAllInstalledApps()
                if (!isActive) return@launch
                val (allList, visibleList, hiddenList) = partitionWithSelection(apps)
                withContext(Dispatchers.Main) {
                    if (isActive) {
                                _allApps.value = allList
                                _visibleApps.value = visibleList
                                _hiddenApps.value = hiddenList
                                _filteredApps.value = allList
                        if (LauncherApplication.instance.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                            Log.d("AppCatalogPerf", "viewmodel count=${allList.size} ms=${android.os.SystemClock.elapsedRealtime() - started}")
                        }
                            }
                        }
            } catch (e: Exception) {
                Log.e("AppViewModel", "Error loading apps", e)
                
                // Set empty lists on error to prevent crash but keep existing data if possible
                withContext(Dispatchers.Main) {
                    if (isActive && _allApps.value.isNullOrEmpty()) {
                        _allApps.value = emptyList()
                        _visibleApps.value = emptyList()
                        _hiddenApps.value = emptyList()
                        _filteredApps.value = emptyList()
                    }
                }
            }
        }
    }
    
    fun searchApps(query: String) {
        val apps = _allApps.value ?: return
        if (query.isEmpty()) {
            _filteredApps.value = apps
        } else {
            _filteredApps.value = apps.filter { app ->
                app.appName.contains(query, ignoreCase = true) || 
                app.packageName.contains(query, ignoreCase = true)
            }
        }
    }
    
    fun toggleAppSelection(app: AppInfo) {
        val newSelection = !app.isSelected
        
        // Update temporary selection
        if (newSelection) {
            SelectionManager.addSelection(app.packageName)
        } else {
            SelectionManager.removeSelection(app.packageName)
        }
        
        // Update filtered apps
        val apps = _filteredApps.value?.map {
            if (it.packageName == app.packageName) {
                it.copy(isSelected = newSelection)
            } else {
                it
            }
        } ?: return
        _filteredApps.value = apps
        
        // Also update in allApps
        val allAppsList = _allApps.value?.map {
            if (it.packageName == app.packageName) {
                it.copy(isSelected = newSelection)
            } else {
                it
            }
        } ?: return
        _allApps.value = allAppsList
    }
    
    fun selectAllApps(select: Boolean) {
        // Update SelectionManager
        if (select) {
            val allPackageNames = _filteredApps.value?.map { it.packageName }?.toSet() ?: emptySet()
            SelectionManager.setSelection(allPackageNames)
        } else {
            SelectionManager.clearSelection()
        }
        
        val apps = _filteredApps.value?.map { it.copy(isSelected = select) } ?: return
        _filteredApps.value = apps
        
        val allAppsList = _allApps.value?.map { it.copy(isSelected = select) } ?: return
        _allApps.value = allAppsList
    }
    
    fun saveSelectedAppsAsHidden() {
        // Cancel previous save job if still running
        saveJob?.cancel()
        
        saveJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val selectedApps = getSelectedApps()
                val selectedPackageNames = selectedApps.map { it.packageName }.toSet()
                
                // Use batch update for better reliability
                val preferences = LauncherApplication.instance.preferences
                preferences.setHiddenApps(selectedPackageNames)
                
                // Clear temporary selection after saving
                SelectionManager.clearSelection()
                
                // Invalidate repository cache
                repository.invalidateCache()
                
                // Small delay for Android 11 to ensure save completes
                delay(100)
                
                // Also update repository for consistency
                val allHiddenApps = repository.getHiddenApps()
                allHiddenApps.forEach { app ->
                    if (!selectedPackageNames.contains(app.packageName)) {
                        repository.unhideApp(app.packageName)
                    }
                }
                selectedPackageNames.forEach { packageName ->
                    repository.hideApp(packageName)
                }
                
                // Keep selection and reload
                val currentSelection = _filteredApps.value?.filter { it.isSelected }?.map { it.packageName } ?: emptyList()
                val apps = repository.getAllInstalledApps()
                val appsWithSelection = apps.map { app ->
                    app.copy(isSelected = currentSelection.contains(app.packageName))
                }
                
                // Update UI on main thread
                launch(Dispatchers.Main) {
                    _allApps.value = appsWithSelection
                    _visibleApps.value = appsWithSelection.filter { !it.isHidden }
                    _hiddenApps.value = appsWithSelection.filter { it.isHidden }
                    _filteredApps.value = appsWithSelection
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    fun hideSelectedApps() {
        viewModelScope.launch {
            val selectedApps = _allApps.value?.filter { it.isSelected } ?: return@launch
            val packageNames = selectedApps.map { it.packageName }
            repository.hideApps(packageNames)
            loadApps()
        }
    }
    
    fun unhideSelectedApps() {
        viewModelScope.launch {
            val selectedApps = _allApps.value?.filter { it.isSelected } ?: return@launch
            val packageNames = selectedApps.map { it.packageName }
            repository.unhideApps(packageNames)
            loadApps()
        }
    }
    
    fun launchApp(packageName: String) {
        repository.launchApp(packageName)
    }
    
    fun getSelectedApps(): List<AppInfo> {
        return _allApps.value?.filter { it.isSelected } ?: emptyList()
    }
}
