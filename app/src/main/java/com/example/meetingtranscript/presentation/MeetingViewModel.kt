package com.example.meetingtranscript.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.meetingtranscript.data.platform.AuthApiException
import com.example.meetingtranscript.data.platform.normalizeRoomCode
import com.example.meetingtranscript.domain.MeetingRepository
import com.example.meetingtranscript.domain.RemoteMeetingRoom
import com.example.meetingtranscript.domain.SavedDiscussionRoom
import com.example.meetingtranscript.domain.SavedDiscussionRooms
import com.example.meetingtranscript.domain.SavedDiscussionRoomRepository
import com.example.meetingtranscript.domain.TranscriptDestination
import com.example.meetingtranscript.data.platform.RemoteTranscriptSubmissionRepository
import com.example.meetingtranscript.domain.RemoteDiscussionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

sealed interface MeetingStatus {
    data object Idle : MeetingStatus
    data object Loading : MeetingStatus
    data object Joining : MeetingStatus
    data object Leaving : MeetingStatus
    data object Saving : MeetingStatus
}

data class MeetingUiState(
    val serverId: String? = null,
    val userId: String? = null,
    val room: RemoteMeetingRoom? = null,
    val status: MeetingStatus = MeetingStatus.Idle,
    val message: String? = null,
    val requiresLogin: Boolean = false,
    val saved: SavedDiscussionRooms = SavedDiscussionRooms(),
    val pendingChangeCount: Int? = null,
    val joinedNavigationId: String? = null,
) {
    val busy: Boolean get() = status != MeetingStatus.Idle
}

/** Results are fenced by identity and revision, including requests that ignore cancellation. */
class MeetingViewModel(
    private val repository: MeetingRepository,
    private val savedRooms: SavedDiscussionRoomRepository,
    private val actionScope: CoroutineScope? = null,
    private val discussion: RemoteTranscriptSubmissionRepository? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(MeetingUiState())
    val state = mutableState.asStateFlow()
    private var revision = 0L
    private var request: Job? = null
    private var pendingChange: (() -> Unit)? = null
    private var baseUrl: String? = null
    val discussionState = discussion?.state ?: MutableStateFlow(RemoteDiscussionState()).asStateFlow()

    fun bind(serverId: String?, userId: String?, serverUrl: String? = null) {
        if (mutableState.value.serverId == serverId && mutableState.value.userId == userId) return
        revision++
        request?.cancel()
        baseUrl = serverUrl
        pendingChange = null
        if (serverId == null || userId == null) discussion?.clearView()
        mutableState.value = MeetingUiState(serverId = serverId, userId = userId)
        if (serverId != null && userId != null) reload()
    }

    fun reload() = runRequest(MeetingStatus.Loading) { server -> repository.current(server) }

    fun roomUpdated(room: RemoteMeetingRoom) {
        if (mutableState.value.room?.id != room.id || mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(room = room)
        updateSaved { saved -> saved.copy(entries = saved.entries.map {
            if (it.roomId == room.id) it.copy(name = room.name, code = room.code) else it
        }) }
    }

    fun add(code: String) {
        val normalized = validatedCode(code) ?: return
        updateSaved { rooms ->
            require(rooms.entries.none { it.code == normalized }) { "此代碼已在討論室列表中。" }
            rooms.copy(entries = rooms.entries + SavedDiscussionRoom(code = normalized))
        }
    }

    fun remove(id: String) {
        val entry = mutableState.value.saved.entries.firstOrNull { it.id == id } ?: return
        val active = mutableState.value.room
        if (active != null && (entry.roomId == active.id || entry.code == active.code)) {
            mutableState.value = mutableState.value.copy(message = "請先退出此討論室，再移除本機入口。")
            return
        }
        updateSaved { rooms -> rooms.copy(entries = rooms.entries.filterNot { it.id == id },
            recentId = rooms.recentId?.takeUnless { it == id }) }
    }

    private fun validatedCode(code: String): String? = try { normalizeRoomCode(code) }
    catch (error: IllegalArgumentException) {
        mutableState.value = mutableState.value.copy(message = error.message)
        null
    }

    private fun updateSaved(transform: (SavedDiscussionRooms) -> SavedDiscussionRooms) {
        val previous = mutableState.value
        val server = previous.serverId ?: return
        val user = previous.userId ?: return
        if (previous.busy || previous.requiresLogin) return
        val ticket = ++revision
        mutableState.value = previous.copy(status = MeetingStatus.Saving, message = null)
        request = (actionScope ?: viewModelScope).launch {
            try {
                val updated = transform(savedRooms.load(server, user))
                savedRooms.save(server, user, updated)
                if (ticket == revision) mutableState.value = mutableState.value.copy(saved = updated, status = MeetingStatus.Idle)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (ticket == revision) mutableState.value = mutableState.value.copy(status = MeetingStatus.Idle,
                    message = if (error is IllegalArgumentException) error.message else "無法保存討論室列表，請重試。")
            }
        }
    }

    fun join(code: String) {
        if (mutableState.value.busy) return
        val normalized = validatedCode(code) ?: return
        runRequest(MeetingStatus.Joining) { server -> repository.join(server, normalized) }
    }

    fun leave() {
        val room = mutableState.value.room ?: return
        runRequest(MeetingStatus.Leaving) { server -> repository.leave(server, room.id); null }
    }

    fun openDiscussion() {
        val state = mutableState.value
        val server = state.serverId ?: return
        val user = state.userId ?: return
        val room = state.room ?: return
        val url = baseUrl ?: return
        discussion?.open(TranscriptDestination(server, user, room.id, url))
    }
    fun retrySubmissions() { discussion?.retryCurrent() }
    fun confirmChange() {
        val action = pendingChange ?: return
        pendingChange = null
        mutableState.value = mutableState.value.copy(pendingChangeCount = null)
        action()
    }
    fun cancelChange() {
        pendingChange = null
        mutableState.value = mutableState.value.copy(pendingChangeCount = null)
        openDiscussion()
    }

    private fun runRequest(status: MeetingStatus, approved: Boolean = false, action: suspend (String) -> RemoteMeetingRoom?) {
        val previous = mutableState.value
        val serverId = previous.serverId ?: return
        if (previous.userId == null || previous.busy || previous.requiresLogin) return
        val ticket = ++revision
        mutableState.value = previous.copy(status = status, message = null)
        request = (actionScope ?: viewModelScope).launch {
            try {
                if (status != MeetingStatus.Loading && !approved) {
                    val count = discussion?.pause() ?: 0
                    if (ticket != revision) return@launch
                    if (count > 0) {
                        pendingChange = { runRequest(status, true, action) }
                        mutableState.value = mutableState.value.copy(status = MeetingStatus.Idle, pendingChangeCount = count)
                        return@launch
                    }
                }
                val userId = requireNotNull(previous.userId)
                val saved = savedRooms.load(serverId, userId)
                if (ticket != revision) return@launch
                mutableState.value = mutableState.value.copy(saved = saved)
                val room = action(serverId)
                if (ticket != revision) return@launch
                // Remote membership is authoritative even if saving the local bookmark fails.
                mutableState.value = mutableState.value.copy(room = room)
                if (room == null) discussion?.clearView()
                if (room != null) {
                    val updated = saved.remember(room)
                    try {
                        savedRooms.save(serverId, userId, updated)
                        if (ticket == revision) mutableState.value = mutableState.value.copy(saved = updated)
                    } catch (error: CancellationException) { throw error }
                    catch (_: Exception) {
                        if (ticket == revision) mutableState.value = mutableState.value.copy(saved = updated,
                            message = "已加入討論室，但本機入口保存失敗，請按更新狀態重試。")
                    }
                }
                if (ticket == revision) {
                    if (room != null) openDiscussion()
                    mutableState.value = mutableState.value.copy(status = MeetingStatus.Idle,
                        joinedNavigationId = if (status == MeetingStatus.Joining && room != null) UUID.randomUUID().toString()
                            else mutableState.value.joinedNavigationId)
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (ticket != revision) return@launch
                val code = (error as? AuthApiException)?.code
                val needsLogin = code in setOf("SESSION_REVOKED", "TOKEN_INVALID", "UNAUTHORIZED", "ACCOUNT_DISABLED", "USER_DISABLED")
                val message = when (code) {
                    "ROOM_NOT_FOUND" -> "找不到討論室，請確認代碼。"
                    "ROOM_CLOSED" -> "討論室已關閉，請向老師取得新的代碼。"
                    "VALIDATION_FAILED" -> "代碼格式不正確，請確認後重試。"
                    "RATE_LIMITED" -> "操作太頻繁，請稍後重試。"
                    else -> when {
                        needsLogin -> "登入已失效，請前往使用者設定重新登入。"
                        error is java.io.IOException -> "連線失敗。若剛才已送出加入或退出，請按「更新狀態」確認伺服器結果。"
                        else -> "無法更新討論室，請稍後重試。"
                    }
                }
                mutableState.value = mutableState.value.copy(
                    room = if (needsLogin) null else previous.room,
                    status = MeetingStatus.Idle, message = message, requiresLogin = needsLogin,
                )
                if (needsLogin) {
                    discussion?.pause()
                    discussion?.clearView()
                } else openDiscussion()
            }
        }
    }
}

class MeetingViewModelFactory(private val repository: MeetingRepository, private val savedRooms: SavedDiscussionRoomRepository,
    private val discussion: RemoteTranscriptSubmissionRepository? = null) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = MeetingViewModel(repository, savedRooms, discussion = discussion) as T
}
