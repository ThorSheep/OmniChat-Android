package com.example.meetingtranscript.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.meetingtranscript.data.platform.TranscriptionForegroundService
import com.example.meetingtranscript.data.platform.AppSettingsRepository
import com.example.meetingtranscript.data.platform.ThemeMode
import com.example.meetingtranscript.domain.MeetingRoom
import com.example.meetingtranscript.domain.MeetingRoomRepository
import com.example.meetingtranscript.domain.TranscriptSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI

data class TranscriptUiState(
    val isTranscribing: Boolean = false,
    val inputDevice: String = "尚未開始收音",
    val partialText: String = "",
    val finalSegments: List<String> = emptyList(),
    val rooms: List<MeetingRoom> = emptyList(),
    val selectedRoomId: String? = null,
    val activeRoomId: String? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val message: String? = null,
)

class TranscriptViewModel(private val meetingRooms: MeetingRoomRepository, private val settings: AppSettingsRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(TranscriptUiState())
    val uiState: StateFlow<TranscriptUiState> = _uiState.asStateFlow()

    init { _uiState.value = _uiState.value.copy(rooms = meetingRooms.list(), themeMode = settings.themeMode(), selectedRoomId = settings.lastRoomId()) }

    fun setThemeMode(mode: ThemeMode) { settings.setThemeMode(mode); _uiState.value = _uiState.value.copy(themeMode = mode) }

    fun addRoom(displayName: String, serverUrl: String, userDisplayName: String): Boolean {
        val name = displayName.trim()
        val normalizedUrl = serverUrl.trim().removeSuffix("/")
        if (name.isBlank()) return fail("請輸入要顯示的名稱。")
        if (userDisplayName.isBlank()) return fail("請輸入使用者顯示名稱。")
        val parsedUrl = runCatching { URI(normalizedUrl) }.getOrNull()
        if (parsedUrl == null || parsedUrl.scheme != "https" || parsedUrl.host.isNullOrBlank() || parsedUrl.userInfo != null) {
            return fail("請輸入有效的 HTTPS 伺服器網址。")
        }
        val room = MeetingRoom(displayName = name, serverUrl = normalizedUrl, userDisplayName = userDisplayName.trim())
        meetingRooms.save(room)
        _uiState.value = _uiState.value.copy(rooms = meetingRooms.list(), selectedRoomId = room.id, message = null)
        return true
    }

    fun selectRoom(roomId: String) { _uiState.value = _uiState.value.copy(selectedRoomId = roomId, message = null) }
    fun enterRoom(roomId: String) {
        if (_uiState.value.activeRoomId != null && _uiState.value.activeRoomId != roomId) return
        settings.setLastRoomId(roomId)
        _uiState.value = _uiState.value.copy(selectedRoomId = roomId, activeRoomId = roomId, finalSegments = emptyList(), partialText = "")
    }
    fun leaveRoom() { _uiState.value = _uiState.value.copy(activeRoomId = null) }

    fun activeRoom(): MeetingRoom? = _uiState.value.rooms.firstOrNull { it.id == _uiState.value.activeRoomId }
    fun selectedServerUrl(): String? = activeRoom()?.serverUrl
    fun editRoom(room: MeetingRoom, name: String, url: String, userDisplayName: String): Boolean {
        if (_uiState.value.activeRoomId == room.id) return fail("請先退出討論再編輯。")
        val uri = runCatching { URI(url.trim()) }.getOrNull()
        if (name.isBlank() || userDisplayName.isBlank() || uri == null || uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) return fail("請填寫討論室名稱、使用者名稱與有效的 HTTPS 網址。")
        meetingRooms.save(room.copy(displayName = name.trim(), serverUrl = url.trim().trimEnd('/'), userDisplayName = userDisplayName.trim()))
        _uiState.value = _uiState.value.copy(rooms = meetingRooms.list(), message = null)
        return true
    }
    fun deleteRoom(roomId: String) {
        if (_uiState.value.activeRoomId == roomId) { fail("請先退出討論再刪除。"); return }
        meetingRooms.delete(roomId)
        val next = meetingRooms.list()
        _uiState.value = _uiState.value.copy(rooms = next, selectedRoomId = _uiState.value.selectedRoomId?.takeIf { id -> next.any { it.id == id } })
    }

    private fun fail(message: String): Boolean { _uiState.value = _uiState.value.copy(message = message); return false }

    fun loadLatestSession(session: TranscriptSession?) {
        if (session == null || _uiState.value.finalSegments.isNotEmpty()) return
        _uiState.value = _uiState.value.copy(finalSegments = session.segments.map { it.text })
    }

    fun onServiceEvent(type: String?, text: String?, device: String?, message: String?) {
        _uiState.value = when (type) {
            TranscriptionForegroundService.EVENT_STARTED -> _uiState.value.copy(
                isTranscribing = true, inputDevice = device ?: "系統預設麥克風", partialText = "", finalSegments = emptyList(), message = null,
            )
            TranscriptionForegroundService.EVENT_PARTIAL -> _uiState.value.copy(partialText = text.orEmpty())
            TranscriptionForegroundService.EVENT_FINAL -> _uiState.value.copy(
                partialText = "", finalSegments = _uiState.value.finalSegments + text.orEmpty(),
            )
            TranscriptionForegroundService.EVENT_STOPPED -> _uiState.value.copy(isTranscribing = false, partialText = "")
            TranscriptionForegroundService.EVENT_ERROR -> _uiState.value.copy(message = message)
            else -> _uiState.value
        }
    }
}

class TranscriptViewModelFactory(private val meetingRooms: MeetingRoomRepository, private val settings: AppSettingsRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = TranscriptViewModel(meetingRooms, settings) as T
}
