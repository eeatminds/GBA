package com.swordfish.lemuroid.app.mobile.feature.online

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.swordfish.lemuroid.app.shared.online.LinkMode
import com.swordfish.lemuroid.app.shared.online.OnlineSettings
import com.swordfish.lemuroid.app.shared.online.RoomInfo
import com.swordfish.lemuroid.app.shared.online.RoomsApi
import com.swordfish.lemuroid.lib.library.SystemID
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.lib.library.db.entity.Game
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

class OnlineViewModel(
    appContext: Context,
    private val retrogradeDb: RetrogradeDatabase,
    private val roomsApi: RoomsApi = RoomsApi(),
) : ViewModel() {
    class Factory(
        private val appContext: Context,
        private val retrogradeDb: RetrogradeDatabase,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return OnlineViewModel(appContext, retrogradeDb) as T
        }
    }

    sealed interface Dialog {
        data object Host : Dialog

        data class JoinCode(
            val code: String = "",
            val checking: Boolean = false,
            val error: JoinError? = null,
        ) : Dialog

        data class PickGame(val room: RoomInfo) : Dialog

        data object Server : Dialog

        data class Report(val room: RoomInfo) : Dialog
    }

    enum class JoinError { INVALID_CODE, NOT_FOUND, FULL, NETWORK }

    enum class RoomsStatus { IDLE, LOADING, ERROR }

    data class UiState(
        val playerName: String = "",
        val serverUrl: String = "",
        val publicRooms: List<RoomInfo> = emptyList(),
        val roomsStatus: RoomsStatus = RoomsStatus.IDLE,
        val gbaGames: List<Game> = emptyList(),
        val dialog: Dialog? = null,
        val message: Message? = null,
    ) {
        val serverConfigured: Boolean get() = serverUrl.isNotBlank()
    }

    enum class Message { REPORT_SENT, REPORT_FAILED }

    private val settings = OnlineSettings(appContext)

    private val mutableState =
        MutableStateFlow(
            UiState(playerName = settings.playerName, serverUrl = settings.serverUrl),
        )
    val state: StateFlow<UiState> = mutableState.asStateFlow()

    private var refreshJob: Job? = null

    init {
        loadGames()
        refreshRooms()
    }

    fun loadGames() {
        viewModelScope.launch {
            val games =
                runCatching { retrogradeDb.gameDao().asyncSelectBySystem(SystemID.GBA.dbname) }
                    .onFailure { Timber.e(it, "Could not load GBA games") }
                    .getOrDefault(emptyList())
            mutableState.update { it.copy(gbaGames = games) }
        }
    }

    fun refreshRooms() {
        val serverUrl = mutableState.value.serverUrl
        if (serverUrl.isBlank()) return
        refreshJob?.cancel()
        refreshJob =
            viewModelScope.launch {
                mutableState.update { it.copy(roomsStatus = RoomsStatus.LOADING) }
                val result = runCatching { roomsApi.listPublicRooms(serverUrl) }
                mutableState.update {
                    it.copy(
                        publicRooms = result.getOrDefault(it.publicRooms),
                        roomsStatus = if (result.isSuccess) RoomsStatus.IDLE else RoomsStatus.ERROR,
                    )
                }
            }
    }

    fun setPlayerName(name: String) {
        val trimmed = name.take(OnlineSettings.MAX_NAME_LENGTH)
        settings.playerName = trimmed
        mutableState.update { it.copy(playerName = trimmed) }
    }

    fun setServerUrl(url: String?) {
        if (url == null) settings.resetServerUrl() else settings.serverUrl = url
        mutableState.update { it.copy(serverUrl = settings.serverUrl, publicRooms = emptyList(), dialog = null) }
        refreshRooms()
    }

    fun showDialog(dialog: Dialog?) {
        mutableState.update { it.copy(dialog = dialog) }
        if (dialog is Dialog.Host || dialog is Dialog.PickGame) loadGames()
    }

    fun clearMessage() {
        mutableState.update { it.copy(message = null) }
    }

    /** Opens the join flow for an invite link or a typed code. */
    fun openInvite(code: String) {
        showDialog(Dialog.JoinCode(code = code))
        lookupRoom(code)
    }

    fun updateJoinCode(code: String) {
        val cleaned = code.uppercase().filter { it.isLetterOrDigit() }.take(OnlineSettings.ROOM_CODE_LENGTH)
        mutableState.update { it.copy(dialog = Dialog.JoinCode(code = cleaned)) }
    }

    fun lookupRoom(code: String) {
        val cleaned = OnlineSettings.parseRoomCode(code)
        if (cleaned == null) {
            mutableState.update { it.copy(dialog = Dialog.JoinCode(code, error = JoinError.INVALID_CODE)) }
            return
        }
        val serverUrl = mutableState.value.serverUrl
        mutableState.update { it.copy(dialog = Dialog.JoinCode(cleaned, checking = true)) }
        viewModelScope.launch {
            val result = runCatching { roomsApi.findRoom(serverUrl, cleaned) }
            val room = result.getOrNull()
            val dialog =
                when {
                    result.isFailure -> Dialog.JoinCode(cleaned, error = JoinError.NETWORK)
                    room == null -> Dialog.JoinCode(cleaned, error = JoinError.NOT_FOUND)
                    room.players >= room.maxPlayers -> Dialog.JoinCode(cleaned, error = JoinError.FULL)
                    else -> Dialog.PickGame(room)
                }
            mutableState.update { it.copy(dialog = dialog) }
            if (dialog is Dialog.PickGame) loadGames()
        }
    }

    fun reportRoom(
        room: RoomInfo,
        reason: String,
    ) {
        val serverUrl = mutableState.value.serverUrl
        mutableState.update { it.copy(dialog = null) }
        viewModelScope.launch {
            val result = runCatching { roomsApi.reportRoom(serverUrl, room.code, reason) }
            mutableState.update {
                it.copy(
                    message = if (result.isSuccess) Message.REPORT_SENT else Message.REPORT_FAILED,
                    publicRooms = it.publicRooms.filterNot { r -> r.code == room.code },
                )
            }
        }
    }

    companion object {
        val HOST_MODES = listOf(LinkMode.LINK_CABLE, LinkMode.WIRELESS, LinkMode.AUTO, LinkMode.ADVANCE_WARS, LinkMode.ADVANCE_WARS_2)
    }
}
