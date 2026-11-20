package com.example.meetingtranscript.presentation

import com.example.meetingtranscript.data.platform.AuthApiException
import com.example.meetingtranscript.data.platform.normalizeRoomCode
import com.example.meetingtranscript.data.platform.RemoteTranscriptSubmissionRepository
import com.example.meetingtranscript.domain.*
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class MeetingViewModelTest {
    private class FakeSaved : SavedDiscussionRoomRepository {
        val values = mutableMapOf<Pair<String, String>, SavedDiscussionRooms>()
        override suspend fun load(serverId: String, userId: String) = values[serverId to userId] ?: SavedDiscussionRooms()
        override suspend fun save(serverId: String, userId: String, rooms: SavedDiscussionRooms) {
            values[serverId to userId] = rooms
        }
    }

    private class FakeMeetings : MeetingRepository {
        val rooms = mutableMapOf<String, RemoteMeetingRoom>()
        var joins = 0
        var lastCode: String? = null
        var joinError: Exception? = null
        var leaveError: Exception? = null
        var currentAction: (suspend (String) -> RemoteMeetingRoom?)? = null
        var joinAction: ((String, String) -> RemoteMeetingRoom)? = null
        override suspend fun current(serverId: String): RemoteMeetingRoom? =
            currentAction?.invoke(serverId) ?: rooms[serverId]
        override suspend fun join(serverId: String, code: String): RemoteMeetingRoom {
            joins++
            lastCode = code
            joinError?.let { throw it }
            return (joinAction?.invoke(serverId, code) ?: room("room-$serverId")).also { rooms[serverId] = it }
        }
        override suspend fun leave(serverId: String, roomId: String) {
            leaveError?.let { throw it }
            rooms.remove(serverId)
        }
        override suspend fun history(serverId: String, cursor: String?): RemotePage<RemoteMeetingRoom> = error("unused")
        override suspend fun messages(serverId: String, roomId: String, cursor: String?): RemotePage<MeetingMessage> = error("unused")
    }

    @Test fun codeValidationMatchesBackendAlphabet() {
        assertEquals("ANMS8R", normalizeRoomCode(" anms8r "))
        for (code in listOf("", "123456", "OOOOOO", "ABC", "AB CDE", "ABCDEFG")) {
            assertThrows(IllegalArgumentException::class.java) { normalizeRoomCode(code) }
        }
    }

    @Test fun restoresJoinsAndLeavesUsingServerIdentity() = runBlocking {
        val repository = FakeMeetings().apply { rooms["a"] = room("restored") }
        val model = MeetingViewModel(repository, FakeSaved(), this)
        model.bind("a", "user")
        model.awaitIdle()
        assertEquals("restored", model.state.value.room?.id)
        model.join(" anms8r ")
        model.awaitIdle()
        assertEquals("ANMS8R", repository.lastCode)
        model.join("ANMS8R")
        model.awaitIdle()
        assertEquals("room-a", model.state.value.room?.id)
        model.leave()
        model.awaitIdle()
        assertNull(model.state.value.room)
        assertNull(repository.rooms["a"])
    }

    @Test fun invalidCodeNeverCallsJoin() = runBlocking {
        val repository = FakeMeetings()
        val model = MeetingViewModel(repository, FakeSaved(), this)
        model.bind("a", "user")
        model.awaitIdle()
        model.join("000000")
        assertEquals(0, repository.joins)
        assertNotNull(model.state.value.message)
    }

    @Test fun navigationSignalAppearsOnlyAfterSuccessfulJoinAndNotReloadOrFailedSwitch() = runBlocking {
        val repository = FakeMeetings()
        val model = MeetingViewModel(repository, FakeSaved(), this)
        model.bind("a", "user")
        model.awaitIdle()
        assertNull(model.state.value.joinedNavigationId)
        model.join("ANMS8R")
        model.awaitIdle()
        val joinedId = model.state.value.joinedNavigationId
        assertNotNull(joinedId)
        model.reload()
        model.awaitIdle()
        assertEquals(joinedId, model.state.value.joinedNavigationId)
        repository.joinError = AuthApiException("ROOM_CLOSED")
        model.join("BCH789")
        model.awaitIdle()
        assertEquals(joinedId, model.state.value.joinedNavigationId)
        model.leave()
        model.awaitIdle()
        assertNull(model.state.value.room)
        assertEquals(joinedId, model.state.value.joinedNavigationId)
    }

    @Test fun closedAndMissingRoomsHaveActionableErrorsAndKeepPreviousRoom() = runBlocking {
        val repository = FakeMeetings().apply { rooms["a"] = room("original") }
        val model = MeetingViewModel(repository, FakeSaved(), this)
        model.bind("a", "user")
        model.awaitIdle()
        for (code in listOf("ROOM_NOT_FOUND", "ROOM_CLOSED")) {
            repository.joinError = AuthApiException(code)
            model.join("ANMS8R")
            model.awaitIdle()
            assertEquals("original", model.state.value.room?.id)
            assertNotNull(model.state.value.message)
        }
    }

    @Test fun failedLeaveKeepsRoomAndReloadReconcilesServerResult() = runBlocking {
        val repository = FakeMeetings().apply { rooms["a"] = room("original"); leaveError = IOException("offline") }
        val model = MeetingViewModel(repository, FakeSaved(), this)
        model.bind("a", "user")
        model.awaitIdle()
        model.leave()
        model.awaitIdle()
        assertEquals("original", model.state.value.room?.id)
        assertNotNull(model.state.value.message)
        repository.rooms.remove("a")
        model.reload()
        model.awaitIdle()
        assertNull(model.state.value.room)
    }

    @Test fun revokedSessionClearsRoomAndBlocksFurtherRequests() = runBlocking {
        val repository = FakeMeetings().apply { currentAction = { throw AuthApiException("SESSION_REVOKED") } }
        val model = MeetingViewModel(repository, FakeSaved(), this)
        model.bind("a", "user")
        model.awaitIdle()
        assertTrue(model.state.value.requiresLogin)
        assertNull(model.state.value.room)
        model.join("ANMS8R")
        assertEquals(0, repository.joins)
    }

    @Test fun lateResponseCannotOverwriteDifferentServerOrSignedOutScreen() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val repository = FakeMeetings().apply {
            currentAction = { server ->
                if (server == "a") withContext(NonCancellable) {
                    started.complete(Unit)
                    release.await()
                    finished.complete(Unit)
                    room("stale-a")
                } else room("fresh-b")
            }
        }
        val model = MeetingViewModel(repository, FakeSaved(), this)
        model.bind("a", "user-a")
        withTimeout(3000) { started.await() }
        model.bind("b", "user-b")
        model.awaitIdle()
        assertEquals("fresh-b", model.state.value.room?.id)
        model.bind(null, null)
        release.complete(Unit)
        withTimeout(3000) { finished.await() }
        assertNull(model.state.value.serverId)
        assertNull(model.state.value.room)
    }

    private suspend fun MeetingViewModel.awaitIdle() {
        withTimeout(3000) { state.first { !it.busy } }
    }

    @Test fun addingBookmarksDoesNotJoinAndSurvivesViewModelRecreation() = runBlocking {
        val remote = FakeMeetings()
        val saved = FakeSaved()
        val model = MeetingViewModel(remote, saved, this)
        model.bind("a", "user")
        model.awaitIdle()
        model.add(" anms8r ")
        model.awaitIdle()
        model.add("BCH789")
        model.awaitIdle()
        assertEquals(0, remote.joins)
        assertNull(model.state.value.room)
        assertEquals(2, model.state.value.saved.entries.size)
        assertNull(model.state.value.saved.recent)
        val restored = MeetingViewModel(remote, saved, this)
        restored.bind("a", "user")
        restored.awaitIdle()
        assertEquals(listOf("ANMS8R", "BCH789"), restored.state.value.saved.entries.map { it.code })
    }

    @Test fun duplicateBookmarksAreRejectedAndAccountsAreIsolated() = runBlocking {
        val model = MeetingViewModel(FakeMeetings(), FakeSaved(), this)
        model.bind("a", "user-one")
        model.awaitIdle()
        model.add("ANMS8R")
        model.awaitIdle()
        model.add("anms8r")
        model.awaitIdle()
        assertEquals(1, model.state.value.saved.entries.size)
        assertNotNull(model.state.value.message)
        model.bind("a", "user-two")
        model.awaitIdle()
        assertTrue(model.state.value.saved.entries.isEmpty())
        model.bind("b", "user-one")
        model.awaitIdle()
        assertTrue(model.state.value.saved.entries.isEmpty())
        model.bind("a", "user-one")
        model.awaitIdle()
        assertEquals(1, model.state.value.saved.entries.size)
    }

    @Test fun switchingRoomsKeepsBothBookmarksAndLastUsedAfterLeaving() = runBlocking {
        val remote = FakeMeetings().apply {
            joinAction = { _, code -> room("id-$code").copy(code = code, name = "名稱-$code") }
        }
        val model = MeetingViewModel(remote, FakeSaved(), this)
        model.bind("a", "user")
        model.awaitIdle()
        model.add("ANMS8R")
        model.awaitIdle()
        model.add("BCH789")
        model.awaitIdle()
        model.join("ANMS8R")
        model.awaitIdle()
        assertEquals("名稱-ANMS8R", model.state.value.saved.recent?.name)
        model.join("BCH789")
        model.awaitIdle()
        assertEquals("id-BCH789", model.state.value.room?.id)
        assertEquals(2, model.state.value.saved.entries.size)
        val activeEntry = requireNotNull(model.state.value.saved.recent)
        model.remove(activeEntry.id)
        assertNotNull(model.state.value.message)
        assertEquals(2, model.state.value.saved.entries.size)
        model.leave()
        model.awaitIdle()
        assertNull(model.state.value.room)
        assertEquals("BCH789", model.state.value.saved.recent?.code)
        model.remove(activeEntry.id)
        model.awaitIdle()
        assertEquals(1, model.state.value.saved.entries.size)
        assertNull(model.state.value.saved.recent)
    }

    @Test fun failedSwitchKeepsOriginalRoomAndRecentBookmark() = runBlocking {
        val remote = FakeMeetings().apply { rooms["a"] = room("original") }
        val model = MeetingViewModel(remote, FakeSaved(), this)
        model.bind("a", "user")
        model.awaitIdle()
        val originalRecent = model.state.value.saved.recentId
        remote.joinError = AuthApiException("ROOM_CLOSED")
        model.join("BCH789")
        model.awaitIdle()
        assertEquals("original", model.state.value.room?.id)
        assertEquals(originalRecent, model.state.value.saved.recentId)
    }

    @Test fun rememberedRoomUpdatesNameAndCodeWithoutDuplicatingIdentity() {
        val first = SavedDiscussionRooms().remember(room("same-room"))
        val updated = first.remember(room("same-room").copy(code = "BCH789", name = "更新名稱"))
        assertEquals(1, updated.entries.size)
        assertEquals(first.recentId, updated.recentId)
        assertEquals("BCH789", updated.recent?.code)
        assertEquals("更新名稱", updated.recent?.name)
    }

    @Test fun leavingWithPendingTextWaitsForConfirmationAndKeepsOriginalDestination() = runBlocking {
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val target = TranscriptDestination("a", "user", "original", "https://api.invalid")
        val rows = java.util.concurrent.ConcurrentHashMap<String, RemoteDeliveryRecord>()
        val store = object : RemoteDeliveryStore {
            override fun all() = rows.values.toList()
            override fun write(record: RemoteDeliveryRecord) { rows[record.segment.id] = record }
        }
        val sender = RemoteTranscriptSubmissionRepository(store, { error("offline") },
            { throw IOException("offline") }, {}, ioScope, 10)
        val repository = FakeMeetings().apply { rooms["a"] = room("original") }
        try {
            val model = MeetingViewModel(repository, FakeSaved(), this, sender)
            model.bind("a", "user", target.baseUrl)
            model.awaitIdle()
            sender.capture(target, TranscriptSegment(text = "未送文字", startedAt = Instant.EPOCH, endedAt = Instant.EPOCH))
            model.leave()
            model.awaitIdle()
            assertEquals(1, model.state.value.pendingChangeCount)
            assertNotNull(repository.rooms["a"])
            model.confirmChange()
            model.awaitIdle()
            assertNull(repository.rooms["a"])
            assertEquals(target, store.all().single().target)
            assertEquals(SyncStatus.PENDING, store.all().single().status)
        } finally { sender.pause(); ioScope.cancel() }
    }

    companion object {
        private fun room(id: String) = RemoteMeetingRoom(id, "ANMS8R", "測試討論室", RemoteRoomStatus.OPEN, Instant.EPOCH, null)
    }
}
