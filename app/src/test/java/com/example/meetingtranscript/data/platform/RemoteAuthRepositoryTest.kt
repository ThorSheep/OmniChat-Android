package com.example.meetingtranscript.data.platform

import com.example.meetingtranscript.domain.*
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RemoteAuthRepositoryTest {
    private class MemoryTokens : RefreshTokenStore {
        val values = mutableMapOf<String, String>()
        override fun read(serverId: String) = values[serverId]
        override fun write(serverId: String, token: String) { values[serverId] = token }
        override fun clear(serverId: String) { values.remove(serverId) }
    }

    private class FakeApi : AuthApi {
        var refreshCalls = 0
        var expiresIn = 900L
        var meError: String? = null
        var refreshError: String? = null
        var offlineLogout = false
        var passwordError: String? = null
        var registrations = 0
        val refreshInputs = mutableListOf<Pair<String, String>>()
        val user = AuthenticatedUser("user", "test@example.invalid", "測試使用者", UserRole.STUDENT, AccountStatus.ACTIVE, Instant.EPOCH)
        override suspend fun register(serverId: String, email: String, password: String, displayName: String): AuthenticatedUser {
            registrations++
            return user.copy(email = email, displayName = displayName)
        }
        override suspend fun rename(serverId: String, accessToken: String, displayName: String) = user.copy(displayName = displayName)
        override suspend fun changePassword(serverId: String, accessToken: String, currentPassword: String, newPassword: String) {
            passwordError?.let { throw AuthApiException(it) }
        }
        override suspend fun login(serverId: String, email: String, password: String) = response(serverId)
        override suspend fun refresh(serverId: String, refreshToken: String): LoginTokens {
            refreshInputs += serverId to refreshToken
            refreshCalls++
            delay(5)
            refreshError?.let { throw AuthApiException(it) }
            return response(serverId)
        }
        override suspend fun me(serverId: String, accessToken: String): AuthenticatedUser {
            meError?.let { meError = null; throw AuthApiException(it) }
            return user
        }
        override suspend fun logout(serverId: String, accessToken: String) {
            if (offlineLogout) throw IOException("offline")
        }
        private fun response(serverId: String) = LoginTokens("access-$serverId-$refreshCalls", "refresh-$serverId-$refreshCalls", expiresIn, user)
    }

    @Test fun loginPersistsOnlyRefreshAndFetchesProfile() = runBlocking {
        val store = MemoryTokens()
        val api = FakeApi()
        val auth = RemoteAuthRepository(api, store)
        auth.login("a", "test@example.invalid", "fixture")
        assertEquals("refresh-a-0", store.values["a"])
        assertEquals(api.user, (auth.current("a") as AuthState.SignedIn).user)
    }

    @Test fun concurrentExpiredRequestsRotateOnce() = runBlocking {
        val store = MemoryTokens()
        val api = FakeApi().apply { expiresIn = 0 }
        val auth = RemoteAuthRepository(api, store)
        auth.login("a", "test@example.invalid", "fixture")
        api.expiresIn = 900
        coroutineScope { (1..12).map { async { auth.refresh("a") } }.awaitAll() }
        assertEquals(1, api.refreshCalls)
        assertEquals("refresh-a-1", store.values["a"])
    }

    @Test fun applicationRestartRestoresUsingLatestRotatedToken() = runBlocking {
        val store = MemoryTokens()
        val api = FakeApi()
        RemoteAuthRepository(api, store).login("a", "test@example.invalid", "fixture")
        val restored = RemoteAuthRepository(api, store)
        assertTrue(restored.current("a") is AuthState.SignedIn)
        assertEquals("refresh-a-1", store.values["a"])
        RemoteAuthRepository(api, store).current("a")
        assertEquals("refresh-a-1", api.refreshInputs.last().second)
    }

    @Test fun serverSessionsAndLogoutAreIsolated() = runBlocking {
        val store = MemoryTokens()
        val auth = RemoteAuthRepository(FakeApi(), store)
        auth.login("a", "test@example.invalid", "fixture")
        auth.login("b", "test@example.invalid", "fixture")
        auth.logout("a")
        assertNull(store.values["a"])
        assertTrue(auth.current("a") is AuthState.SignedOut)
        assertTrue(auth.current("b") is AuthState.SignedIn)
        assertNotNull(store.values["b"])
    }

    @Test fun revokedSessionClearsRefreshToken() = runBlocking {
        val store = MemoryTokens()
        val api = FakeApi()
        val auth = RemoteAuthRepository(api, store)
        auth.login("a", "test@example.invalid", "fixture")
        api.meError = "SESSION_REVOKED"
        assertTrue(auth.current("a") is AuthState.Revoked)
        assertNull(store.values["a"])
        assertTrue(auth.current("a") is AuthState.SignedOut)
    }

    @Test fun expiredMeRetriesWithRotatedToken() = runBlocking {
        val api = FakeApi()
        val auth = RemoteAuthRepository(api, MemoryTokens())
        auth.login("a", "test@example.invalid", "fixture")
        api.meError = "TOKEN_EXPIRED"
        assertTrue(auth.current("a") is AuthState.SignedIn)
        assertEquals(1, api.refreshCalls)
    }

    @Test fun offlineLogoutPreservesSessionForRetry() = runBlocking {
        val store = MemoryTokens()
        val api = FakeApi()
        val auth = RemoteAuthRepository(api, store)
        auth.login("a", "test@example.invalid", "fixture")
        api.offlineLogout = true
        try { auth.logout("a"); fail("Expected network failure") } catch (_: IOException) { }
        assertNotNull(store.values["a"])
        api.offlineLogout = false
        auth.logout("a")
        assertNull(store.values["a"])
    }

    @Test fun invalidRefreshClearsOnlyItsServer() = runBlocking {
        val store = MemoryTokens().apply { values["a"] = "old-a"; values["b"] = "old-b" }
        val api = FakeApi().apply { refreshError = "TOKEN_INVALID" }
        assertTrue(RemoteAuthRepository(api, store).current("a") is AuthState.Revoked)
        assertNull(store.values["a"])
        assertEquals("old-b", store.values["b"])
    }

    @Test fun validatesHttpsAndRejectsCredentialUrls() {
        assertEquals("https://example.invalid", normalizeServerUrl(" https://example.invalid/ "))
        for (url in listOf("http://example.invalid", "https://u:p@example.invalid", "https://example.invalid?token=x", "invalid")) {
            assertThrows(IllegalArgumentException::class.java) { normalizeServerUrl(url) }
        }
    }

    @Test fun authorizedRequestRetriesExpiredTokenOnce() = runBlocking {
        val api = FakeApi()
        val store = MemoryTokens()
        val auth = RemoteAuthRepository(api, store)
        auth.login("a", "test@example.invalid", "fixture")
        val attempted = mutableListOf<String>()
        val result = auth.authorized("a") { token ->
            attempted += token
            if (attempted.size == 1) throw AuthApiException("TOKEN_EXPIRED")
            "joined"
        }
        assertEquals("joined", result)
        assertEquals(2, attempted.size)
        assertNotEquals(attempted[0], attempted[1])
        assertEquals(1, api.refreshCalls)
    }

    @Test fun unauthorizedRoomRequestClearsOnlyItsCredentials() = runBlocking {
        val api = FakeApi()
        val store = MemoryTokens()
        val auth = RemoteAuthRepository(api, store)
        auth.login("a", "test@example.invalid", "fixture")
        auth.login("b", "test@example.invalid", "fixture")
        try {
            auth.authorized<Unit>("a") { throw AuthApiException("SESSION_REVOKED") }
            fail("Expected revocation")
        } catch (error: AuthApiException) { assertEquals("SESSION_REVOKED", error.code) }
        assertNull(store.values["a"])
        assertNotNull(store.values["b"])
    }

    @Test fun socketHandshakeRejectsAnotherAccountsCredential() = runBlocking {
        val auth = RemoteAuthRepository(FakeApi(), MemoryTokens())
        auth.login("a", "test@example.invalid", "fixture")
        var called = false
        try {
            auth.authorized("a", "other-user") { called = true }
            fail("Expected account mismatch")
        } catch (error: AuthApiException) { assertEquals("ACCOUNT_CHANGED", error.code) }
        assertFalse(called)
        assertTrue(auth.current("a") is AuthState.SignedIn)
    }

    @Test fun registrationDoesNotCreateSessionOrSaveCredentials() = runBlocking {
        val api = FakeApi()
        val store = MemoryTokens()
        val auth = RemoteAuthRepository(api, store)
        val user = auth.register("a", " test@example.invalid ", "fixture-password", " 測試 ")
        assertEquals("測試", user.displayName)
        assertEquals("test@example.invalid", user.email)
        assertEquals(UserRole.STUDENT, user.role)
        assertTrue(auth.current("a") is AuthState.SignedOut)
        assertTrue(store.values.isEmpty())
    }

    @Test fun renameUpdatesSessionAndWrongCurrentPasswordDoesNotRevokeIt() = runBlocking {
        val api = FakeApi()
        val store = MemoryTokens()
        val auth = RemoteAuthRepository(api, store)
        auth.login("a", "test@example.invalid", "fixture-password")
        val changed = auth.rename("a", " 新名字 ") as AuthState.SignedIn
        assertEquals("新名字", changed.user.displayName)
        api.passwordError = "INVALID_CREDENTIALS"
        try { auth.changePassword("a", "incorrect", "fixture-password"); fail("Expected error") }
        catch (error: AuthApiException) { assertEquals("INVALID_CREDENTIALS", error.code) }
        assertNotNull(store.values["a"])
        assertTrue(auth.current("a") is AuthState.SignedIn)
        api.passwordError = null
        auth.changePassword("a", "old-fixture", "fixture-password")
        assertTrue(auth.current("a") is AuthState.SignedIn)
    }
}
