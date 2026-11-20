package com.example.meetingtranscript.data.platform

import com.example.meetingtranscript.domain.*
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Application-scoped owner of the remote connection, durable submissions and recording state. */
class RemoteTranscriptSubmissionRepository(
    private val store: RemoteDeliveryStore,
    private val createClient: (TranscriptDestination) -> RealtimeMeetingClient,
    private val verifyTarget: suspend (TranscriptDestination) -> Unit,
    private val revokeAuth: suspend (String) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val retryBaseMs: Long = 1_000,
    private val messageStore: DiscussionMessageStore? = null,
    private val loadMessages: (suspend (TranscriptDestination, String?) -> RemotePage<MeetingMessage>)? = null,
) : TranscriptSubmissionRepository {
    private val mutableState = MutableStateFlow(RemoteDiscussionState())
    val state = mutableState.asStateFlow()
    private var worker: Job? = null
    @Volatile private var stopCapture: (() -> Unit)? = null
    @Volatile private var client: RealtimeMeetingClient? = null

    fun open(target: TranscriptDestination) {
        if (mutableState.value.target == target && worker?.isActive == true) return
        if (mutableState.value.capture == RemoteCaptureState.RECORDING && mutableState.value.target != target) {
            scope.launch { stopRecording(); open(target) }
            return
        }
        worker?.cancel()
        client?.disconnect()
        mutableState.value = RemoteDiscussionState(target = target, messages = messageStore?.read(target)?.messages.orEmpty())
        worker = scope.launch { runConnection(target) }
    }

    /** Called on the service main thread after the microphone lifecycle is visible. */
    fun recordingStarted(target: TranscriptDestination, input: String, stop: () -> Unit) {
        check(mutableState.value.target == target && !mutableState.value.blocked)
        stopCapture = stop
        mutableState.update { it.copy(capture = RemoteCaptureState.RECORDING, input = input, startedAt = Instant.now(), partialText = "") }
    }

    fun partial(text: String) { mutableState.update { it.copy(partialText = text) } }
    fun reportMessage(message: String) { mutableState.update { it.copy(message = message) } }
    fun recordingStopped() {
        stopCapture = null
        mutableState.update { it.copy(capture = RemoteCaptureState.STOPPED, partialText = "") }
    }

    /** Synchronous commit completes before a stop/switch can finish. The original segment is also kept in local history. */
    fun capture(target: TranscriptDestination, segment: TranscriptSegment) {
        val invalid = runCatching { TranscriptSubmission.from(segment) }.isFailure
        val existing = store.all().firstOrNull { it.target == target && it.segment.id == segment.id }
        if (existing == null) store.write(RemoteDeliveryRecord(target, segment,
            if (invalid) SyncStatus.FAILED else SyncStatus.PENDING, errorCode = if (invalid) "MESSAGE_TOO_LONG" else null))
        refreshRecords(target)
        mutableState.update { it.copy(partialText = "") }
    }

    suspend fun stopRecording() {
        val stop = stopCapture
        if (stop != null) withContext(Dispatchers.Main) { stop() }
    }

    /** Stops recording first, waits for the old sender, then cancels its subscription. Does not leave REST membership. */
    suspend fun pause(): Int {
        stopRecording()
        worker?.cancelAndJoin()
        worker = null
        val oldClient = client
        try { withTimeoutOrNull(1_000) { oldClient?.leave() } }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { }
        oldClient?.disconnect()
        client = null
        mutableState.update { it.copy(connection = SocketState.Disconnected, online = emptyList(), aiState = "idle", syncing = false) }
        val target = mutableState.value.target ?: return 0
        refreshRecords(target)
        return mutableState.value.pendingCount
    }

    fun clearView() {
        if (mutableState.value.capture == RemoteCaptureState.RECORDING) {
            val previous = mutableState.value.target
            scope.launch { stopRecording(); if (mutableState.value.target == previous) clearView() }
            return
        }
        worker?.cancel()
        client?.disconnect()
        mutableState.value = RemoteDiscussionState()
    }

    fun retryCurrent() { mutableState.value.target?.let(::open) }
    private fun refreshRecords(target: TranscriptDestination) {
        val records = store.all().filter { it.target == target }
        mutableState.update { if (it.target == target) it.copy(records = records) else it }
    }

    private suspend fun runConnection(target: TranscriptDestination) {
        var attempt = 0
        while (currentCoroutineContext().isActive && mutableState.value.target == target) {
            var connection: RealtimeMeetingClient? = null
            try {
                refreshRecords(target)
                verifyTarget(target)
                mutableState.update { it.copy(connection = SocketState.Connecting, message = null) }
                val activeConnection = createClient(target)
                var synchronizedHistory = false
                var disconnected = false
                var latestRoom: RemoteMeetingRoom? = null
                val syncLock = messageStore ?: this
                // Capture before join: recentMessages must never replace the last complete checkpoint.
                val anchor = messageStore?.read(target)?.completeAnchor
                connection = activeConnection
                client = activeConnection
                activeConnection.observe { event ->
                    if (mutableState.value.target == target && client === activeConnection) when (event) {
                        is MeetingEvent.ConnectionChanged -> synchronized(syncLock) {
                            disconnected = event.state is SocketState.Disconnected || event.state is SocketState.Failed
                            if (disconnected) synchronizedHistory = false
                            mutableState.update { it.copy(connection = event.state, online = emptyList(), aiState = "idle") }
                        }
                        is MeetingEvent.RoomClosed -> if (event.roomId == target.roomId) protect(target, "ROOM_CLOSED")
                        is MeetingEvent.SessionRevoked -> protect(target, "SESSION_REVOKED")
                        is MeetingEvent.MessageReceived -> if (event.message.roomId == target.roomId) synchronized(syncLock) {
                            if (mutableState.value.target == target && client === activeConnection) {
                                val cached = messageStore?.merge(target, listOf(event.message))
                                if (synchronizedHistory) messageStore?.complete(target)
                                mutableState.update { it.copy(messages = cached?.messages ?: mergeMessages(target, it.messages, listOf(event.message))) }
                            }
                        }
                        is MeetingEvent.PresenceChanged -> if (event.roomId == target.roomId) mutableState.update { it.copy(online = event.online) }
                        is MeetingEvent.AiStatusChanged -> if (event.roomId == target.roomId) mutableState.update { it.copy(aiState = event.state) }
                        is MeetingEvent.RoomUpdated -> if (event.room.id == target.roomId) synchronized(syncLock) {
                            latestRoom = event.room
                            mutableState.update { it.copy(room = event.room) }
                            if (event.room.status != RemoteRoomStatus.OPEN) protect(target, "ROOM_CLOSED")
                        }
                    }
                }
                activeConnection.connect(target.serverId)
                val snapshot = activeConnection.join(target.roomId)
                if (mutableState.value.blocked) return
                if (snapshot.room.status != RemoteRoomStatus.OPEN) { protect(target, "ROOM_CLOSED"); return }
                synchronized(syncLock) {
                    val cached = messageStore?.merge(target, snapshot.recentMessages)
                    mutableState.update { it.copy(room = latestRoom ?: snapshot.room, members = snapshot.members,
                        messages = cached?.messages ?: mergeMessages(target, it.messages, snapshot.recentMessages), syncing = true) }
                }
                if (messageStore != null && loadMessages != null) {
                    val delta = if (anchor == null) null else try { activeConnection.sync(target.roomId, anchor) }
                    catch (error: AuthApiException) { if (error.code == "NOT_FOUND") null else throw error }
                    if (delta == null || delta.truncated) {
                        var cursor: String? = null
                        val visited = mutableSetOf<String>()
                        do {
                            val page = loadMessages.invoke(target, cursor)
                            messageStore.merge(target, page.items)
                            cursor = page.nextCursor
                            check(cursor == null || visited.add(cursor)) { "重複的歷史分頁游標。" }
                        } while (cursor != null)
                    } else messageStore.merge(target, delta.messages)
                    synchronized(syncLock) {
                        // Live events received during REST paging have been merged, but never advanced the anchor.
                        if (disconnected) throw AuthApiException("DISCONNECTED")
                        if (mutableState.value.blocked) throw AuthApiException("ROOM_CLOSED")
                        val complete = messageStore.complete(target)
                        synchronizedHistory = true
                        mutableState.update { it.copy(messages = complete.messages) }
                    }
                }
                if (mutableState.value.blocked || mutableState.value.target != target) return
                synchronized(syncLock) {
                    if (disconnected) throw AuthApiException("DISCONNECTED")
                    mutableState.update { it.copy(connection = SocketState.Joined(target.roomId), message = null, syncing = false) }
                }
                attempt = 0
                while (currentCoroutineContext().isActive && mutableState.value.target == target &&
                    mutableState.value.connection is SocketState.Joined && !mutableState.value.blocked) {
                    val queued = store.all().filter { it.target == target && it.status != SyncStatus.SYNCED && it.status != SyncStatus.FAILED }
                    for (record in queued) {
                        try {
                            store.write(record.copy(status = SyncStatus.UPLOADING, errorCode = null))
                            refreshRecords(target)
                            val ack = activeConnection.submit(TranscriptSubmission.from(record.segment))
                            store.write(record.copy(status = SyncStatus.SYNCED, ack = ack, errorCode = null))
                        } catch (error: CancellationException) { throw error }
                        catch (error: Exception) {
                            val code = (error as? AuthApiException)?.code
                            val terminal = code in setOf("VALIDATION_FAILED", "MESSAGE_TOO_LONG") || error is IllegalArgumentException
                            store.write(record.copy(status = if (terminal) SyncStatus.FAILED else SyncStatus.PENDING, errorCode = code ?: "ACK_TIMEOUT_OR_NETWORK"))
                            refreshRecords(target)
                            if (terminal) continue
                            if (code == "RATE_LIMITED") {
                                mutableState.update { it.copy(message = "伺服器忙碌，待送文字將稍後重試。") }
                                delay(retryBaseMs * 2)
                                break
                            }
                            throw error
                        }
                        refreshRecords(target)
                        // Keep submissions below the server's sustained rate limit.
                        delay(300)
                    }
                    delay(300)
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (mutableState.value.target != target) return
                val code = (error as? AuthApiException)?.code
                if (code in setOf("SESSION_REVOKED", "TOKEN_INVALID", "UNAUTHORIZED", "ACCOUNT_DISABLED", "USER_DISABLED", "ACCOUNT_CHANGED", "SERVER_CHANGED", "ROOM_CLOSED", "NOT_ROOM_MEMBER")) {
                    protect(target, code ?: "UNAUTHORIZED")
                    return
                }
                mutableState.update { it.copy(connection = SocketState.Disconnected, syncing = false, online = emptyList(), aiState = "idle",
                    messages = messageStore?.read(target)?.messages ?: it.messages,
                    message = "連線或同步暫時中斷，文字保留於本機，正在重試。") }
            } finally {
                connection?.disconnect()
                if (client === connection) client = null
            }
            delay((retryBaseMs * (1L shl attempt.coerceAtMost(5))).coerceAtMost(30_000))
            attempt++
        }
    }

    private fun mergeMessages(target: TranscriptDestination, previous: List<MeetingMessage>, incoming: List<MeetingMessage>): List<MeetingMessage> =
        MessageTimeline(target.serverId, target.roomId).apply { merge(previous) }.merge(incoming)

    private fun protect(target: TranscriptDestination, code: String) {
        if (mutableState.value.target != target || mutableState.value.blocked) return
        val protectedWorker = worker
        val protectedClient = client
        mutableState.update { it.copy(blocked = true, connection = SocketState.Failed(RemoteFailure(code)), online = emptyList(), aiState = "idle", syncing = false,
            message = "收音已停止（$code）。請更新討論室狀態或重新登入；未送文字保留於原討論室。") }
        scope.launch {
            stopRecording()
            if (code in setOf("SESSION_REVOKED", "TOKEN_INVALID", "UNAUTHORIZED", "ACCOUNT_DISABLED", "USER_DISABLED")) revokeAuth(target.serverId)
            protectedWorker?.cancel()
            protectedClient?.disconnect()
        }
    }

    override suspend fun enqueue(submission: PendingTranscript) {
        val target = requireNotNull(state.value.target)
        require(target.serverId == submission.serverId && target.userId == submission.userId && target.roomId == submission.roomId)
        val value = submission.submission
        val time = value.clientTs ?: Instant.now()
        capture(target, TranscriptSegment(value.clientMsgId, value.text, language = value.lang, startedAt = time, endedAt = time))
    }
    override suspend fun retry(serverId: String, userId: String, roomId: String) {
        val target = state.value.target ?: return
        if (target.serverId == serverId && target.userId == userId && target.roomId == roomId) retryCurrent()
    }
    override suspend fun pending(serverId: String, userId: String): List<PendingTranscript> = store.all()
        .filter { it.target.serverId == serverId && it.target.userId == userId && it.status != SyncStatus.SYNCED && it.status != SyncStatus.FAILED }
        .map { PendingTranscript(serverId, userId, it.target.roomId, TranscriptSubmission.from(it.segment), it.status) }
}
