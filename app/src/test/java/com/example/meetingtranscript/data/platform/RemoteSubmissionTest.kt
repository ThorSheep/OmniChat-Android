package com.example.meetingtranscript.data.platform

import com.example.meetingtranscript.domain.*
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class RemoteSubmissionTest {
    private val target = TranscriptDestination("server", "user", "room", "https://api.example.invalid")
    private val segment = TranscriptSegment(id = "fixed-id", text = "測試 review", language = "zh-TW",
        startedAt = Instant.EPOCH, endedAt = Instant.EPOCH.plusSeconds(1))

    private class MemoryStore : RemoteDeliveryStore {
        private val rows = linkedMapOf<Pair<TranscriptDestination, String>, RemoteDeliveryRecord>()
        @Synchronized override fun all() = rows.values.toList()
        @Synchronized override fun write(record: RemoteDeliveryRecord) { rows[record.target to record.segment.id] = record }
    }

    private class FakeClient(private val submitAction: suspend (TranscriptSubmission) -> SubmissionAck) : RealtimeMeetingClient {
        var observer: (MeetingEvent) -> Unit = {}
        var recent: List<MeetingMessage> = emptyList()
        var syncAction: suspend (String?) -> MessageSync = { MessageSync(emptyList(), false) }
        override suspend fun connect(serverId: String) = Unit
        override suspend fun join(roomId: String) = RoomSnapshot(
            RemoteMeetingRoom(roomId, "ANMS8R", "測試", RemoteRoomStatus.OPEN, Instant.EPOCH, null), emptyList(), recent)
        override suspend fun leave() = Unit
        override suspend fun submit(submission: TranscriptSubmission) = submitAction(submission)
        override suspend fun sync(roomId: String, lastMessageId: String?) = syncAction(lastMessageId)
        override fun observe(listener: (MeetingEvent) -> Unit) { observer = listener }
        override fun disconnect() = Unit
    }

    private suspend fun RemoteTranscriptSubmissionRepository.awaitState(predicate: (RemoteDiscussionState) -> Boolean) =
        withTimeout(5000) { state.first(predicate) }

    @Test fun partialNeverEnqueuesAndAckPersistsReceipt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = MemoryStore()
        val submissions = java.util.concurrent.CopyOnWriteArrayList<TranscriptSubmission>()
        val sender = RemoteTranscriptSubmissionRepository(store, { FakeClient { value ->
            submissions += value
            SubmissionAck("server-id", Instant.EPOCH, false)
        } }, {}, {}, scope, 10)
        try {
            sender.open(target)
            sender.awaitState { it.connection is SocketState.Joined }
            sender.partial("辨識中的文字")
            assertTrue(store.all().isEmpty())
            sender.capture(target, segment)
            sender.awaitState { it.records.any { row -> row.status == SyncStatus.SYNCED } }
            assertEquals("fixed-id", submissions.single().clientMsgId)
            assertEquals("server-id", store.all().single().ack?.messageId)
            assertEquals(0, sender.state.value.pendingCount)
            sender.capture(target, segment)
            assertEquals(1, store.all().size)
        } finally { sender.pause(); scope.cancel() }
    }

    @Test fun lostAckResendsSameIdAndAcceptsDuplicate() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = MemoryStore()
        val ids = java.util.concurrent.CopyOnWriteArrayList<String>()
        val serverMessages = java.util.concurrent.ConcurrentHashMap<String, String>()
        val sender = RemoteTranscriptSubmissionRepository(store, { FakeClient { value ->
            ids += value.clientMsgId
            val duplicate = serverMessages.putIfAbsent(value.clientMsgId, "message") != null
            if (!duplicate) throw AuthApiException("DISCONNECTED")
            SubmissionAck("message", Instant.EPOCH, true)
        } }, {}, {}, scope, 10)
        try {
            sender.capture(target, segment)
            sender.open(target)
            sender.awaitState { it.records.any { row -> row.ack?.duplicate == true } }
            assertEquals(listOf("fixed-id", "fixed-id"), ids.toList())
            assertEquals(1, serverMessages.size)
            assertEquals(SyncStatus.SYNCED, store.all().single().status)
        } finally { sender.pause(); scope.cancel() }
    }

    @Test fun rateLimitedSubmissionRetainsConnectionAndQueue() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = MemoryStore()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val connections = java.util.concurrent.atomic.AtomicInteger()
        val sender = RemoteTranscriptSubmissionRepository(store, {
            connections.incrementAndGet()
            FakeClient {
                if (calls.incrementAndGet() == 1) throw AuthApiException("RATE_LIMITED")
                SubmissionAck("receipt", Instant.EPOCH, false)
            }
        }, {}, {}, scope, 10)
        try {
            sender.capture(target, segment)
            sender.open(target)
            sender.awaitState { it.records.any { row -> row.status == SyncStatus.SYNCED } }
            assertEquals(2, calls.get())
            assertEquals(1, connections.get())
        } finally { sender.pause(); scope.cancel() }
    }

    @Test fun restartedSenderResumesUploadingRecordWithOriginalIdentity() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = MemoryStore().apply { write(RemoteDeliveryRecord(target, segment, SyncStatus.UPLOADING)) }
        val sender = RemoteTranscriptSubmissionRepository(store, { FakeClient { value ->
            assertEquals(segment.id, value.clientMsgId)
            SubmissionAck("receipt", Instant.EPOCH, true)
        } }, {}, {}, scope, 10)
        try {
            sender.open(target)
            sender.awaitState { it.records.any { row -> row.status == SyncStatus.SYNCED } }
            assertEquals(target, store.all().single().target)
        } finally { sender.pause(); scope.cancel() }
    }

    @Test fun foreignAccountRoomAndUrlNeverGetReassigned() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = MemoryStore()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val sender = RemoteTranscriptSubmissionRepository(store, { FakeClient {
            calls.incrementAndGet(); SubmissionAck("receipt", Instant.EPOCH, false)
        } }, {}, {}, scope, 10)
        try {
            sender.capture(target, segment)
            for (foreign in listOf(target.copy(userId = "other"), target.copy(roomId = "other"), target.copy(baseUrl = "https://other.invalid"))) {
                sender.open(foreign)
                sender.awaitState { it.target == foreign && it.connection is SocketState.Joined }
                assertTrue(sender.state.value.records.isEmpty())
                sender.pause()
            }
            assertEquals(0, calls.get())
            assertEquals(target, store.all().single().target)
        } finally { sender.pause(); scope.cancel() }
    }

    @Test fun overlongSegmentRetainsOriginalAndDoesNotSubmit() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = MemoryStore()
        val sender = RemoteTranscriptSubmissionRepository(store, { FakeClient { error("must not submit") } }, {}, {}, scope, 10)
        try {
            val original = segment.copy(text = "字".repeat(2001))
            sender.capture(target, original)
            sender.open(target)
            sender.awaitState { it.connection is SocketState.Joined }
            assertEquals(original.text, store.all().single().segment.text)
            assertEquals(SyncStatus.FAILED, store.all().single().status)
            assertEquals(1, sender.state.value.pendingCount)
        } finally { sender.pause(); scope.cancel() }
    }

    @Test fun revokedEventBlocksSessionAndStopsReconnect() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val revoked = CompletableDeferred<String>()
        val fake = FakeClient { SubmissionAck("receipt", Instant.EPOCH, false) }
        val sender = RemoteTranscriptSubmissionRepository(MemoryStore(), { fake }, {}, { revoked.complete(it); Unit }, scope, 10)
        try {
            sender.open(target)
            sender.awaitState { it.connection is SocketState.Joined }
            fake.observer(MeetingEvent.SessionRevoked("LOGGED_IN_ELSEWHERE"))
            sender.awaitState { it.blocked }
            assertEquals("server", withTimeout(3000) { revoked.await() })
        } finally { sender.pause(); scope.cancel() }
    }

    private fun message(id: String, seconds: Long) = MeetingMessage(id, target.roomId, MessageSenderType.USER,
        MeetingMember("someone", "同學"), "內容 $id", "zh-TW", null, Instant.EPOCH.plusSeconds(seconds))

    @Test fun truncatedSyncRetainsOldAnchorAndMergesLiveEventsAcrossAllRestPages() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cache = DiscussionMessageStore()
        cache.merge(target, listOf(message("old", 1)))
        cache.complete(target)
        val fake = FakeClient { error("No submissions") }.apply {
            recent = listOf(message("recent", 5))
            syncAction = { anchor -> assertEquals("old", anchor); MessageSync(listOf(message("partial", 2)), true) }
        }
        val pages = java.util.concurrent.CopyOnWriteArrayList<String?>()
        val sender = RemoteTranscriptSubmissionRepository(MemoryStore(), { fake }, {}, {}, scope, 10,
            messageStore = cache, loadMessages = { _, cursor ->
                pages += cursor
                assertEquals("old", cache.read(target).completeAnchor)
                if (cursor == null) RemotePage(listOf(message("old", 1), message("gap", 3)), "opaque-next")
                else {
                    assertEquals("opaque-next", cursor)
                    fake.observer(MeetingEvent.MessageReceived(message("live", 6)))
                    RemotePage(listOf(message("recent", 5)), null)
                }
            })
        try {
            sender.open(target)
            sender.awaitState { it.connection is SocketState.Joined }
            assertEquals(listOf(null, "opaque-next"), pages.toList())
            assertEquals(listOf("old", "gap", "recent", "live"), sender.state.value.messages.map { it.id })
            assertEquals("live", cache.read(target).completeAnchor)
            fake.observer(MeetingEvent.PresenceChanged("room", listOf(MeetingMember("online", "在線"))))
            fake.observer(MeetingEvent.AiStatusChanged("room", "thinking"))
            assertEquals("thinking", sender.state.value.aiState)
            assertEquals("online", sender.state.value.online.single().userId)
        } finally { sender.pause(); scope.cancel() }
    }

    @Test fun cacheIsolationAndAckMergeUseIdsRatherThanText() {
        val cache = DiscussionMessageStore()
        val text = message("receipt", 1)
        cache.merge(target, listOf(text, text))
        cache.complete(target)
        for (other in listOf(target.copy(serverId = "other"), target.copy(userId = "other"),
            target.copy(roomId = "other"), target.copy(baseUrl = "https://other.invalid"))) {
            assertTrue(cache.read(other).messages.isEmpty())
            assertNull(cache.read(other).completeAnchor)
        }
        val record = RemoteDeliveryRecord(target, segment.copy(text = text.text), SyncStatus.SYNCED,
            SubmissionAck("receipt", text.serverTs, false))
        val state = RemoteDiscussionState(target = target, records = listOf(record), messages = listOf(text))
        assertTrue(state.unmergedRecords.isEmpty())
        assertEquals(1, state.copy(records = listOf(record.copy(ack = null))).unmergedRecords.size)
        assertEquals(1, state.copy(messages = listOf(text.copy(id = "different"))).unmergedRecords.size)
    }

    @Test fun failedFallbackKeepsCompleteAnchorEvenWhenRecentMessagesAreNewer() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cache = DiscussionMessageStore()
        cache.merge(target, listOf(message("old", 1)))
        cache.complete(target)
        val retryEntered = CompletableDeferred<Unit>()
        val syncCalls = java.util.concurrent.atomic.AtomicInteger()
        val fake = FakeClient { error("No submissions") }.apply {
            recent = listOf(message("newest", 99))
            syncAction = { anchor ->
                assertEquals("old", anchor)
                if (syncCalls.incrementAndGet() > 1) { retryEntered.complete(Unit); awaitCancellation() }
                throw AuthApiException("NOT_FOUND")
            }
        }
        val sender = RemoteTranscriptSubmissionRepository(MemoryStore(), { fake }, {}, {}, scope, 10,
            messageStore = cache, loadMessages = { _, cursor ->
                if (cursor == null) RemotePage(listOf(message("gap", 3)), "next")
                else throw java.io.IOException("fixture offline")
            })
        try {
            sender.open(target)
            withTimeout(5000) { retryEntered.await() }
            assertEquals("old", cache.read(target).completeAnchor)
            assertEquals(listOf("old", "gap", "newest"), cache.read(target).messages.map { it.id })
            assertFalse(sender.state.value.connection is SocketState.Joined)
        } finally { sender.pause(); scope.cancel() }
    }
}
