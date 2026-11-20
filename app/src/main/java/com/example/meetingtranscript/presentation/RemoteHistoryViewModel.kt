package com.example.meetingtranscript.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.meetingtranscript.domain.*
import com.example.meetingtranscript.data.platform.AuthApiException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class RemoteHistoryState(
    val rooms: List<RemoteMeetingRoom> = emptyList(),
    val room: RemoteMeetingRoom? = null,
    val messages: List<MeetingMessage> = emptyList(),
    val cursor: String? = null,
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val requiresLogin: Boolean = false,
)

/** Read-only browsing never changes REST membership or the active Socket subscription. */
class RemoteHistoryViewModel(
    private val repository: MeetingRepository,
    private val auth: AuthRepository,
    private val profiles: ServerProfileRepository,
    private val actionScope: CoroutineScope? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(RemoteHistoryState())
    val state = mutableState.asStateFlow()
    private var identity: Triple<String, String, String>? = null
    private var revision = 0
    private var request: Job? = null

    fun bind(serverId: String?, userId: String?, baseUrl: String?) {
        val next = if (serverId != null && userId != null && baseUrl != null) Triple(serverId, userId, baseUrl) else null
        if (identity == next) return
        identity = next
        revision++
        request?.cancel()
        mutableState.value = RemoteHistoryState()
    }

    fun showRooms() { reset(null); load() }
    fun showRoom(room: RemoteMeetingRoom) { reset(room); load() }
    fun refresh() { reset(mutableState.value.room); load() }

    private fun reset(room: RemoteMeetingRoom?) {
        revision++
        request?.cancel()
        mutableState.value = RemoteHistoryState(room = room)
    }

    fun load() {
        val target = identity ?: return
        val previous = mutableState.value
        if (previous.busy || (previous.loaded && previous.cursor == null)) return
        val version = revision
        mutableState.update { it.copy(busy = true, error = null) }
        request = (actionScope ?: viewModelScope).launch(Dispatchers.IO) {
            try {
                val profile = profiles.list().firstOrNull { it.id == target.first }
                if (profile?.baseUrl != target.third) throw AuthApiException("SERVER_CHANGED")
                val account = auth.current(target.first) as? AuthState.SignedIn ?: throw AuthApiException("UNAUTHORIZED")
                if (account.user.id != target.second) throw AuthApiException("ACCOUNT_CHANGED")
                val room = previous.room
                if (room == null) {
                    val page = repository.history(target.first, previous.cursor)
                    if (version == revision && identity == target) mutableState.update { it.copy(
                        rooms = (it.rooms + page.items).distinctBy { item -> item.id }, cursor = page.nextCursor, loaded = true) }
                } else {
                    val page = repository.messages(target.first, room.id, previous.cursor)
                    val timeline = MessageTimeline(target.first, room.id).apply { merge(previous.messages) }
                    val merged = timeline.merge(page.items)
                    if (version == revision && identity == target) mutableState.update { it.copy(messages = merged, cursor = page.nextCursor, loaded = true) }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (version == revision && identity == target) {
                    val code = (error as? AuthApiException)?.code
                    val revoked = code in setOf("UNAUTHORIZED", "SESSION_REVOKED", "TOKEN_INVALID", "USER_DISABLED", "ACCOUNT_DISABLED")
                    mutableState.update { it.copy(error = when (code) {
                        "FORBIDDEN", "NOT_ROOM_MEMBER" -> "沒有讀取這個討論室紀錄的權限。"
                        "NOT_FOUND" -> "找不到此討論室或紀錄。"
                        else -> "無法載入紀錄${code?.let { value -> "（$value）" }.orEmpty()}，請稍後重試。"
                    }, requiresLogin = revoked) }
                }
            } finally {
                if (version == revision && identity == target) mutableState.update { it.copy(busy = false) }
            }
        }
    }
}

class RemoteHistoryViewModelFactory(private val repository: MeetingRepository, private val auth: AuthRepository,
    private val profiles: ServerProfileRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = RemoteHistoryViewModel(repository, auth, profiles) as T
}
