package com.example.meetingtranscript.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.meetingtranscript.data.platform.AuthApiException
import com.example.meetingtranscript.data.platform.ServerProfileSettings
import com.example.meetingtranscript.domain.AuthRepository
import com.example.meetingtranscript.domain.AuthState
import com.example.meetingtranscript.domain.ServerProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AccountUiState(
    val profiles: List<ServerProfile> = emptyList(),
    val selectedId: String? = null,
    val auth: AuthState = AuthState.SignedOut,
    val busy: Boolean = false,
    val message: String? = null,
    val pendingChangeCount: Int? = null,
) {
    val selected: ServerProfile? get() = profiles.firstOrNull { it.id == selectedId }
}

class AccountViewModel(
    private val profiles: ServerProfileSettings,
    private val auth: AuthRepository,
    private val actionScope: CoroutineScope? = null,
    private val beforeChange: suspend () -> Int = { 0 },
    private val resumeSession: () -> Unit = {},
) : ViewModel() {
    private val mutableState = MutableStateFlow(AccountUiState())
    val state = mutableState.asStateFlow()
    private var pendingChange: (suspend () -> Unit)? = null

    init { runAction { reload(); restore() } }

    fun select(id: String) = change {
        withContext(Dispatchers.IO) { profiles.select(id) }
        mutableState.value = mutableState.value.copy(selectedId = id, auth = AuthState.SignedOut)
        restore()
    }

    fun save(existing: ServerProfile?, name: String, url: String, onSaved: () -> Unit) = runAction {
        val profile = existing?.copy(displayName = name, baseUrl = url) ?: ServerProfile(displayName = name, baseUrl = url)
        if (existing != null && existing.baseUrl != url.trim().trimEnd('/')) {
            // A different endpoint must never receive an existing endpoint's refresh token.
            check(auth.current(existing.id) !is AuthState.SignedIn) { "請先登出此伺服器，再修改網址。" }
        }
        profiles.save(profile)
        if (mutableState.value.selectedId == null) withContext(Dispatchers.IO) { profiles.select(profile.id) }
        reload()
        withContext(Dispatchers.Main) { onSaved() }
    }

    fun remove(profile: ServerProfile) = change {
        auth.logout(profile.id)
        profiles.delete(profile.id)
        reload()
        restore()
    }

    fun login(email: String, password: String, onFinished: () -> Unit) = runAction {
        try {
            val id = requireNotNull(mutableState.value.selectedId) { "請先選擇伺服器。" }
            require(email.isNotBlank() && password.isNotEmpty()) { "請輸入電子郵件與密碼。" }
            mutableState.value = mutableState.value.copy(auth = auth.login(id, email, password))
            mutableState.value = mutableState.value.copy(auth = auth.current(id))
        } finally { withContext(Dispatchers.Main) { onFinished() } }
    }

    fun logout() = change {
        mutableState.value.selectedId?.let { auth.logout(it) }
        mutableState.value = mutableState.value.copy(auth = AuthState.SignedOut)
    }

    fun reloadAccount() = runAction { restore() }

    fun register(email: String, password: String, name: String, onFinished: (Boolean) -> Unit) = runAction {
        var success = false
        try {
            auth.register(requireNotNull(mutableState.value.selectedId), email, password, name)
            mutableState.value = mutableState.value.copy(message = "註冊成功，請登入。")
            success = true
        } finally { withContext(Dispatchers.Main) { onFinished(success) } }
    }

    fun rename(name: String, onFinished: (Boolean) -> Unit) = runAction {
        var success = false
        // Stop and persist the current segment; reconnect refreshes the Socket's cached sender name.
        beforeChange()
        try {
            val id = requireNotNull(mutableState.value.selectedId)
            mutableState.value = mutableState.value.copy(auth = auth.rename(id, name), message = "顯示名稱已更新；收音維持停止。")
            success = true
        } finally {
            resumeSession()
            withContext(Dispatchers.Main) { onFinished(success) }
        }
    }

    fun changePassword(current: String, replacement: String, onFinished: (Boolean) -> Unit) = runAction {
        var success = false
        try {
            try { auth.changePassword(requireNotNull(mutableState.value.selectedId), current, replacement) }
            catch (error: AuthApiException) {
                if (error.code == "INVALID_CREDENTIALS") throw IllegalArgumentException("目前密碼錯誤。")
                throw error
            }
            mutableState.value = mutableState.value.copy(message = "密碼已修改，目前裝置維持登入。")
            success = true
        } finally { withContext(Dispatchers.Main) { onFinished(success) } }
    }

    private fun change(action: suspend () -> Unit) = runAction {
        val count = beforeChange()
        if (count > 0) {
            pendingChange = action
            mutableState.value = mutableState.value.copy(pendingChangeCount = count)
        } else action()
    }

    fun confirmChange() {
        val action = pendingChange ?: return
        pendingChange = null
        mutableState.value = mutableState.value.copy(pendingChangeCount = null)
        runAction { action() }
    }

    fun cancelChange() {
        pendingChange = null
        mutableState.value = mutableState.value.copy(pendingChangeCount = null)
        resumeSession()
    }

    private suspend fun reload() {
        val items = profiles.list()
        val selected = profiles.selectedId()?.takeIf { id -> items.any { it.id == id } }
        mutableState.value = mutableState.value.copy(profiles = items, selectedId = selected)
    }

    private suspend fun restore() {
        val id = mutableState.value.selectedId
        val result = if (id == null) AuthState.SignedOut else auth.current(id)
        mutableState.value = mutableState.value.copy(auth = result)
        if (result is AuthState.Revoked) mutableState.value = mutableState.value.copy(message = "登入已失效，請重新登入。")
    }

    private fun runAction(action: suspend () -> Unit) {
        if (mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(busy = true, message = null)
        (actionScope ?: viewModelScope).launch(Dispatchers.IO) {
            try { action() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (error is AuthApiException && error.code in setOf("SESSION_REVOKED", "TOKEN_INVALID", "UNAUTHORIZED", "ACCOUNT_DISABLED", "USER_DISABLED")) {
                    mutableState.value = mutableState.value.copy(auth = AuthState.Revoked(error.code))
                }
                val message = when (error) {
                    is AuthApiException -> when (error.code) {
                        "INVALID_CREDENTIALS" -> "電子郵件或密碼錯誤。"
                        "EMAIL_TAKEN", "EMAIL_ALREADY_EXISTS", "EMAIL_EXISTS" -> "此電子郵件已註冊。"
                        "ACCOUNT_DISABLED", "USER_DISABLED" -> "帳號已停用，請聯絡管理員。"
                        "ACCOUNT_LOCKED" -> "登入失敗次數過多，請稍後再試。"
                        "RATE_LIMITED" -> "操作太頻繁，請稍後重試。"
                        "SECURE_STORAGE_INVALID" -> "安全儲存已失效，請重新登入。"
                        else -> "伺服器操作失敗（${error.code}）。"
                    }
                    is java.io.IOException -> "無法連線，請確認網路與 API 網址後重試。"
                    is IllegalArgumentException, is IllegalStateException -> error.message ?: "設定無效。"
                    else -> "操作失敗，請稍後重試。"
                }
                mutableState.value = mutableState.value.copy(message = message)
            } finally { mutableState.value = mutableState.value.copy(busy = false) }
        }
    }
}

class AccountViewModelFactory(private val profiles: ServerProfileSettings, private val auth: AuthRepository,
    private val beforeChange: suspend () -> Int = { 0 }, private val resumeSession: () -> Unit = {}) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = AccountViewModel(profiles, auth,
        beforeChange = beforeChange, resumeSession = resumeSession) as T
}
