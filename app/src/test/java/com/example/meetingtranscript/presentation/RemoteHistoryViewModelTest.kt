package com.example.meetingtranscript.presentation

import com.example.meetingtranscript.domain.*
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class RemoteHistoryViewModelTest {
    private val room = RemoteMeetingRoom("room", "ANMS8R", "討論", RemoteRoomStatus.OPEN, Instant.EPOCH, null)
    private val user = AuthenticatedUser("user", "test@example.invalid", "測試", UserRole.STUDENT, AccountStatus.ACTIVE, Instant.EPOCH)
    private fun message(id: String, seconds: Long) = MeetingMessage(id, room.id, MessageSenderType.USER, null,
        id, "zh-TW", null, Instant.EPOCH.plusSeconds(seconds))
    private val profiles = object : ServerProfileRepository {
        override suspend fun list() = listOf(ServerProfile("a", "A", "https://a.invalid"), ServerProfile("b", "B", "https://b.invalid"))
        override suspend fun save(profile: ServerProfile) = Unit
        override suspend fun delete(profileId: String) = Unit
    }
    private val auth = object : AuthRepository {
        override suspend fun register(serverId: String, email: String, password: String, displayName: String): AuthenticatedUser = error("History must not register")
        override suspend fun rename(serverId: String, displayName: String): AuthState = error("History must not rename")
        override suspend fun changePassword(serverId: String, currentPassword: String, newPassword: String) = error("History must not change password")
        override suspend fun current(serverId: String) = AuthState.SignedIn(serverId, user, Instant.now().plusSeconds(900))
        override suspend fun login(serverId: String, email: String, password: String) = current(serverId)
        override suspend fun refresh(serverId: String) = current(serverId)
        override suspend fun logout(serverId: String) = Unit
    }
    private open inner class FakeMeetings : MeetingRepository {
        override suspend fun join(serverId: String, code: String): RemoteMeetingRoom = error("History must not join")
        override suspend fun current(serverId: String): RemoteMeetingRoom? = error("History must not restore membership")
        override suspend fun leave(serverId: String, roomId: String) = error("History must not leave")
        override suspend fun history(serverId: String, cursor: String?) = RemotePage(listOf(room), null)
        override suspend fun messages(serverId: String, roomId: String, cursor: String?): RemotePage<MeetingMessage> =
            if (cursor == null) RemotePage(listOf(message("old", 1)), "opaque")
            else { assertEquals("opaque", cursor); RemotePage(listOf(message("old", 1), message("new", 2)), null) }
    }

    @Test fun readOnlyHistoryFollowsServerCursorAndDeduplicates() = runBlocking {
        val model = RemoteHistoryViewModel(FakeMeetings(), auth, profiles, this)
        model.bind("a", "user", "https://a.invalid")
        model.showRooms()
        withTimeout(3000) { model.state.first { it.loaded && !it.busy } }
        assertEquals(room, model.state.value.rooms.single())
        model.showRoom(room)
        withTimeout(3000) { model.state.first { it.loaded && !it.busy } }
        assertEquals("opaque", model.state.value.cursor)
        model.load()
        withTimeout(3000) { model.state.first { !it.busy && it.messages.size == 2 } }
        assertEquals(listOf("old", "new"), model.state.value.messages.map { it.id })
        assertNull(model.state.value.cursor)
        model.bind("b", "user", "https://b.invalid")
        assertTrue(model.state.value.messages.isEmpty())
        assertTrue(model.state.value.rooms.isEmpty())
    }

    @Test fun lateResponseCannotPopulateAnotherServersScreen() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = object : FakeMeetings() {
            override suspend fun history(serverId: String, cursor: String?): RemotePage<RemoteMeetingRoom> = withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
                RemotePage(listOf(room), null)
            }
        }
        val model = RemoteHistoryViewModel(repository, auth, profiles, this)
        model.bind("a", "user", "https://a.invalid")
        model.showRooms()
        withTimeout(3000) { entered.await() }
        model.bind("b", "user", "https://b.invalid")
        release.complete(Unit)
        coroutineContext[Job]?.children?.toList()?.joinAll()
        assertTrue(model.state.value.rooms.isEmpty())
        assertFalse(model.state.value.loaded)
    }
}
