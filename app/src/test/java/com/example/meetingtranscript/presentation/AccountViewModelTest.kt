package com.example.meetingtranscript.presentation

import com.example.meetingtranscript.data.platform.ServerProfileSettings
import com.example.meetingtranscript.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class AccountViewModelTest {
    private class FakeProfiles : ServerProfileSettings {
        var selected: String? = "a"
        override suspend fun list() = listOf(ServerProfile("a", "測試一", "https://a.invalid"), ServerProfile("b", "測試二", "https://b.invalid"))
        override suspend fun save(profile: ServerProfile) = Unit
        override suspend fun delete(profileId: String) = Unit
        override fun selectedId() = selected
        override fun select(id: String?) { selected = id }
    }
    private class FakeAuth : AuthRepository {
        override suspend fun register(serverId: String, email: String, password: String, displayName: String): AuthenticatedUser = error("Not used by server-selection test")
        override suspend fun rename(serverId: String, displayName: String): AuthState = error("Not used by server-selection test")
        override suspend fun changePassword(serverId: String, currentPassword: String, newPassword: String) = error("Not used by server-selection test")
        val restores = mutableListOf<String>()
        override suspend fun current(serverId: String): AuthState { restores += serverId; return AuthState.SignedOut }
        override suspend fun login(serverId: String, email: String, password: String): AuthState = AuthState.SignedOut
        override suspend fun refresh(serverId: String): AuthState = AuthState.SignedOut
        override suspend fun logout(serverId: String) = Unit
    }

    @Test fun selectingServerRestoresOnlyItsOwnAccount() = runBlocking {
        val profiles = FakeProfiles()
        val auth = FakeAuth()
        val model = AccountViewModel(profiles, auth, this)
        withTimeout(3000) { model.state.first { !it.busy } }
        assertEquals("a", model.state.value.selectedId)
        model.select("b")
        withTimeout(3000) { model.state.first { !it.busy && it.selectedId == "b" } }
        assertEquals(listOf("a", "b"), auth.restores)
        assertEquals("b", profiles.selected)
    }
}
