package com.example.meetingtranscript.data.platform

import com.example.meetingtranscript.domain.AuthRepository
import com.example.meetingtranscript.domain.AuthState
import com.example.meetingtranscript.domain.AuthenticatedUser
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Deliberately not a data class: credentials must never appear in generated toString output. */
class LoginTokens(val accessToken: String, val refreshToken: String, val expiresIn: Long, val user: AuthenticatedUser)

interface RefreshTokenStore {
    fun read(serverId: String): String?
    fun write(serverId: String, token: String)
    fun clear(serverId: String)
}

interface AuthApi {
    suspend fun register(serverId: String, email: String, password: String, displayName: String): AuthenticatedUser
    suspend fun rename(serverId: String, accessToken: String, displayName: String): AuthenticatedUser
    suspend fun changePassword(serverId: String, accessToken: String, currentPassword: String, newPassword: String)
    suspend fun login(serverId: String, email: String, password: String): LoginTokens
    suspend fun refresh(serverId: String, refreshToken: String): LoginTokens
    suspend fun me(serverId: String, accessToken: String): AuthenticatedUser
    suspend fun logout(serverId: String, accessToken: String)
}

class AuthApiException(val code: String, val requestId: String? = null) : Exception(code)

/** One application instance owns all in-memory access tokens and refresh serialization. */
class RemoteAuthRepository(private val api: AuthApi, private val store: RefreshTokenStore) : AuthRepository {
    private class Session(val token: String, val state: AuthState.SignedIn)
    private val sessions = mutableMapOf<String, Session>()
    private val mutex = Mutex()
    private val revokedCodes = setOf("SESSION_REVOKED", "TOKEN_INVALID", "ACCOUNT_DISABLED", "USER_DISABLED", "UNAUTHORIZED")

    /** Only data adapters receive the credential, never UI or ViewModels. */
    suspend fun <T> authorized(serverId: String, expectedUserId: String? = null, action: suspend (String) -> T): T = mutex.withLock {
        try {
            if (ensureSession(serverId) !is AuthState.SignedIn) throw AuthApiException("UNAUTHORIZED")
            if (expectedUserId != null && sessions.getValue(serverId).state.user.id != expectedUserId) throw AuthApiException("ACCOUNT_CHANGED")
            val attemptedToken = sessions.getValue(serverId).token
            try {
                action(attemptedToken)
            } catch (error: AuthApiException) {
                if (error.code != "TOKEN_EXPIRED") throw error
                if (rotate(serverId) !is AuthState.SignedIn) throw AuthApiException("UNAUTHORIZED")
                action(sessions.getValue(serverId).token)
            }
        } catch (error: AuthApiException) {
            if (error.code in revokedCodes) clear(serverId)
            throw error
        }
    }

    suspend fun revoke(serverId: String) = mutex.withLock { clear(serverId) }

    override suspend fun register(serverId: String, email: String, password: String, displayName: String): AuthenticatedUser {
        require(displayName.trim().length in 1..50) { "顯示名稱須為 1～50 字。" }
        require(password.length in 8..128) { "密碼須為 8～128 字。" }
        require(email.trim().isNotEmpty()) { "請輸入電子郵件。" }
        return api.register(serverId, email.trim(), password, displayName.trim())
    }

    override suspend fun rename(serverId: String, displayName: String): AuthState {
        require(displayName.trim().length in 1..50) { "顯示名稱須為 1～50 字。" }
        return authorized(serverId) { token ->
            val user = api.rename(serverId, token, displayName.trim())
            val session = sessions.getValue(serverId)
            val updated = session.state.copy(user = user)
            sessions[serverId] = Session(token, updated)
            updated
        }
    }

    override suspend fun changePassword(serverId: String, currentPassword: String, newPassword: String) {
        require(currentPassword.length in 1..128) { "目前密碼須為 1～128 字。" }
        require(newPassword.length in 8..128) { "新密碼須為 8～128 字。" }
        authorized(serverId) { token -> api.changePassword(serverId, token, currentPassword, newPassword) }
    }

    override suspend fun login(serverId: String, email: String, password: String): AuthState = mutex.withLock {
        accept(serverId, api.login(serverId, email.trim(), password))
    }

    /** Concurrent expiration checks reuse the session produced by the first refresh. */
    override suspend fun refresh(serverId: String): AuthState = mutex.withLock {
        guarded(serverId) { ensureSession(serverId) }
    }

    override suspend fun current(serverId: String): AuthState = mutex.withLock {
        guarded(serverId) {
            val state = ensureSession(serverId)
            if (state !is AuthState.SignedIn) return@guarded state
            val token = sessions.getValue(serverId).token
            val user = try {
                api.me(serverId, token)
            } catch (error: AuthApiException) {
                if (error.code != "TOKEN_EXPIRED") throw error
                val rotated = rotate(serverId)
                if (rotated !is AuthState.SignedIn) return@guarded rotated
                api.me(serverId, sessions.getValue(serverId).token)
            }
            val updated = sessions.getValue(serverId).state.copy(user = user)
            sessions[serverId] = Session(sessions.getValue(serverId).token, updated)
            updated
        }
    }

    override suspend fun logout(serverId: String) = mutex.withLock {
        guarded(serverId) {
            if (ensureSession(serverId) is AuthState.SignedIn) {
                try {
                    api.logout(serverId, sessions.getValue(serverId).token)
                } catch (error: AuthApiException) {
                    if (error.code != "TOKEN_EXPIRED") throw error
                    rotate(serverId)
                    api.logout(serverId, sessions.getValue(serverId).token)
                }
            }
            clear(serverId)
            AuthState.SignedOut
        }
        Unit
    }

    private fun accept(serverId: String, tokens: LoginTokens): AuthState.SignedIn {
        val state = AuthState.SignedIn(serverId, tokens.user, Instant.now().plusSeconds(tokens.expiresIn))
        try {
            store.write(serverId, tokens.refreshToken)
        } catch (error: Exception) {
            sessions.remove(serverId)
            runCatching { store.clear(serverId) }
            throw error
        }
        sessions[serverId] = Session(tokens.accessToken, state)
        return state
    }

    private suspend fun ensureSession(serverId: String): AuthState {
        val existing = sessions[serverId]
        if (existing != null && existing.state.expiresAt.isAfter(Instant.now().plusSeconds(30))) return existing.state
        return rotate(serverId)
    }

    private suspend fun rotate(serverId: String): AuthState {
        val token = store.read(serverId) ?: return AuthState.SignedOut
        return accept(serverId, api.refresh(serverId, token))
    }

    private suspend fun guarded(serverId: String, action: suspend () -> AuthState): AuthState = try {
        action()
    } catch (error: AuthApiException) {
        if (error.code !in revokedCodes) throw error
        clear(serverId)
        AuthState.Revoked(error.code)
    }

    private fun clear(serverId: String) {
        sessions.remove(serverId)
        store.clear(serverId)
    }
}
