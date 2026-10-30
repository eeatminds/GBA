package com.swordfish.lemuroid.app.mobile.feature.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.swordfish.lemuroid.app.shared.library.PendingOperationsMonitor
import com.swordfish.lemuroid.app.shared.settings.GameFilePickerLauncher
import com.swordfish.lemuroid.app.shared.settings.StorageFrameworkPickerLauncher
import com.swordfish.lemuroid.lib.core.CoresSelection
import com.swordfish.lemuroid.lib.library.CoreID
import com.swordfish.lemuroid.lib.library.SystemID
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.lib.library.db.entity.Game
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class HomeViewModel(
    private val appContext: Context,
    private val retrogradeDb: RetrogradeDatabase,
    private val coresSelection: CoresSelection,
) : ViewModel() {
    companion object {
        const val RECENT_MAX_ITEMS = 12
        const val DEBOUNCE_TIME = 100L
        const val SEARCH_DEBOUNCE_TIME = 150L
    }

    class Factory(
        val appContext: Context,
        val retrogradeDb: RetrogradeDatabase,
        val coresSelection: CoresSelection,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HomeViewModel(appContext, retrogradeDb, coresSelection) as T
        }
    }

    data class UIState(
        // Recently played, shown when the player is not searching or filtering.
        val recentGames: List<Game> = emptyList(),
        // All games that match the search and console filter.
        val games: List<Game> = emptyList(),
        // Consoles that have at least one game, for the filter chips.
        val systemIds: List<String> = emptyList(),
        val selectedSystemId: String? = null,
        val isFiltering: Boolean = false,
        val hasGames: Boolean = false,
        val indexInProgress: Boolean = true,
        val showNoNotificationPermissionCard: Boolean = false,
        val showNoMicrophonePermissionCard: Boolean = false,
        val showNoGamesCard: Boolean = false,
        val showDesmumeDeprecatedCard: Boolean = false,
    )

    private data class Library(
        val recentGames: List<Game>,
        val games: List<Game>,
        val systemIds: List<String>,
        val selectedSystemId: String?,
        val isFiltering: Boolean,
        val hasGames: Boolean,
    )

    private val microphonePermissionEnabledState = MutableStateFlow(true)
    private val notificationsPermissionEnabledState = MutableStateFlow(true)
    private val searchQuery = MutableStateFlow("")
    private val selectedSystem = MutableStateFlow<String?>(null)
    private val uiStates = MutableStateFlow(UIState())

    fun getViewStates(): Flow<UIState> = uiStates

    fun setSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun selectSystem(systemId: String?) {
        selectedSystem.value = systemId
    }

    fun changeLocalStorageFolder(context: Context) {
        StorageFrameworkPickerLauncher.pickFolder(context)
    }

    fun addGames(context: Context) {
        GameFilePickerLauncher.pickFiles(context)
    }

    fun updatePermissions(context: Context) {
        notificationsPermissionEnabledState.value = isNotificationsPermissionGranted(context)
        microphonePermissionEnabledState.value = isMicrophonePermissionGranted(context)
    }

    private fun isNotificationsPermissionGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        val permissionResult = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        return permissionResult == PackageManager.PERMISSION_GRANTED
    }

    private fun isMicrophonePermissionGranted(context: Context): Boolean {
        val permissionResult = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
        return permissionResult == PackageManager.PERMISSION_GRANTED
    }

    private fun libraryFlow(): Flow<Library> {
        val dao = retrogradeDb.gameDao()
        return combine(
            dao.selectAllGames(),
            dao.selectRecentlyPlayed(RECENT_MAX_ITEMS),
            searchQuery.debounce(SEARCH_DEBOUNCE_TIME),
            selectedSystem,
        ) { allGames, recentGames, query, systemId ->
            val systemIds = allGames.map { it.systemId }.distinct()
            // Forget a console filter once its last game is gone.
            val activeSystem = systemId?.takeIf { it in systemIds }
            val trimmedQuery = query.trim()
            val filtered =
                allGames.filter { game ->
                    (activeSystem == null || game.systemId == activeSystem) &&
                        (trimmedQuery.isEmpty() || (game.customName ?: game.title).contains(trimmedQuery, ignoreCase = true))
                }
            Library(
                recentGames = recentGames,
                games = filtered,
                systemIds = systemIds,
                selectedSystemId = activeSystem,
                isFiltering = activeSystem != null || trimmedQuery.isNotEmpty(),
                hasGames = allGames.isNotEmpty(),
            )
        }
    }

    init {
        viewModelScope.launch {
            combine(
                libraryFlow(),
                indexingInProgress(appContext),
                notificationsPermissionEnabledState,
                microphoneNotification(retrogradeDb),
                desmumeWarningNotification(),
            ) { library, indexInProgress, notificationsEnabled, showMicrophoneCard, showDesmumeWarning ->
                UIState(
                    recentGames = library.recentGames,
                    games = library.games,
                    systemIds = library.systemIds,
                    selectedSystemId = library.selectedSystemId,
                    isFiltering = library.isFiltering,
                    hasGames = library.hasGames,
                    indexInProgress = indexInProgress,
                    showNoNotificationPermissionCard = !notificationsEnabled,
                    showNoMicrophonePermissionCard = showMicrophoneCard,
                    showNoGamesCard = !library.hasGames,
                    showDesmumeDeprecatedCard = showDesmumeWarning,
                )
            }
                .debounce(DEBOUNCE_TIME)
                .flowOn(Dispatchers.IO)
                .collect { uiStates.value = it }
        }
    }

    private fun indexingInProgress(appContext: Context) = PendingOperationsMonitor(appContext).anyLibraryOperationInProgress()

    private fun dsGamesCount(retrogradeDb: RetrogradeDatabase): Flow<Int> {
        return retrogradeDb.gameDao().selectSystemsWithCount()
            .map { systems -> systems.firstOrNull { it.systemId == SystemID.NDS.dbname }?.count ?: 0 }
            .distinctUntilChanged()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun microphoneNotification(db: RetrogradeDatabase): Flow<Boolean> {
        return microphonePermissionEnabledState
            .flatMapLatest { isMicrophoneEnabled ->
                if (isMicrophoneEnabled) {
                    flowOf(false)
                } else {
                    combine(coresSelection.getSelectedCores(), dsGamesCount(db)) { cores, dsCount ->
                        cores.any { it.coreConfig.supportsMicrophone } && dsCount > 0
                    }
                }.distinctUntilChanged()
            }
    }

    private fun desmumeWarningNotification(): Flow<Boolean> {
        return coresSelection.getSelectedCores()
            .map { cores -> cores.any { it.coreConfig.coreID == CoreID.DESMUME } }
            .distinctUntilChanged()
    }
}
