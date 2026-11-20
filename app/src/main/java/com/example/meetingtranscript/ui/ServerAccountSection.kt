package com.example.meetingtranscript.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.meetingtranscript.domain.AuthState
import com.example.meetingtranscript.domain.ServerProfile
import com.example.meetingtranscript.domain.UserRole
import com.example.meetingtranscript.presentation.AccountUiState
import com.example.meetingtranscript.presentation.AccountViewModel

/** Optional form shortcuts; selecting one never creates a profile or changes an existing endpoint. */
private data class ServerPreset(val name: String, val baseUrl: String)

private val serverPresets = listOf(
    ServerPreset("OmniChat 測試伺服器", "https://ours-dover-slots-convenience.trycloudflare.com/"),
)

@Composable
fun ServerAccountSection(state: AccountUiState, model: AccountViewModel) {
    var editorVisible by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ServerProfile?>(null) }
    var actions by remember { mutableStateOf<ServerProfile?>(null) }
    var deleting by remember { mutableStateOf<ServerProfile?>(null) }
    var loginVisible by remember { mutableStateOf(false) }
    var accountEditor by remember(state.selectedId, (state.auth as? AuthState.SignedIn)?.user?.id) { mutableStateOf<String?>(null) }
    var loginEmail by remember(state.selectedId) { mutableStateOf("") }
    state.pendingChangeCount?.let { count ->
        AlertDialog(onDismissRequest = model::cancelChange, title = { Text("還有 $count 段文字未送出") },
            text = { Text("收音已停止。繼續後文字仍保留於原帳號、伺服器與討論室；重新加入原討論室後才能重送。") },
            confirmButton = { TextButton(onClick = model::confirmChange) { Text("保留資料並繼續") } },
            dismissButton = { TextButton(onClick = model::cancelChange) { Text("取消") } })
    }
    val signedIn = state.auth as? AuthState.SignedIn
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 12.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(56.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        val name = signedIn?.user?.displayName.orEmpty().trim()
                        if (name.isNotEmpty()) Text(String(Character.toChars(name.codePointAt(0))),
                            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        else Icon(Icons.Outlined.Person, contentDescription = null, modifier = Modifier.size(28.dp))
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (signedIn != null) "目前使用者" else "我的帳號", style = MaterialTheme.typography.labelMedium)
                    Text(signedIn?.user?.displayName ?: "尚未登入", style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold)
                    signedIn?.user?.email?.let { Text(it, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    signedIn?.user?.role?.let { role ->
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary) {
                            Text("身分 · ${when (role) {
                                UserRole.STUDENT -> "學生"
                                UserRole.TEACHER -> "老師"
                                UserRole.ADMIN -> "管理員"
                            }}", modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            Text(state.selected?.let { "目前伺服器 · ${it.displayName}" } ?: "先在下方新增並選用伺服器，即可登入。",
                style = MaterialTheme.typography.bodySmall)
            if (signedIn != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(enabled = !state.busy, onClick = { accountEditor = "name" }) { Text("修改名稱") }
                    FilledTonalButton(enabled = !state.busy, onClick = { accountEditor = "password" }) { Text("修改密碼") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(enabled = !state.busy, onClick = model::reloadAccount) { Text("更新帳號資料") }
                    TextButton(enabled = !state.busy, onClick = model::logout,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("登出") }
                }
            } else if (state.selected != null) {
                Button(enabled = !state.busy, onClick = { loginVisible = true }, modifier = Modifier.fillMaxWidth()) { Text("登入帳號") }
                OutlinedButton(enabled = !state.busy, onClick = { accountEditor = "register" }, modifier = Modifier.fillMaxWidth()) { Text("註冊帳號") }
                TextButton(enabled = !state.busy, onClick = model::reloadAccount) { Text("重新連線／恢復登入") }
            }
        }
    }
    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
    state.message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
    Text("伺服器", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    Text("每個伺服器分別保存登入狀態。", style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (state.profiles.isEmpty()) Text("尚未設定伺服器。", Modifier.padding(vertical = 12.dp))
    state.profiles.forEach { profile ->
        OutlinedCard(onClick = { if (!state.busy) actions = profile }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(profile.displayName, style = MaterialTheme.typography.titleMedium)
                Text(profile.baseUrl, style = MaterialTheme.typography.bodySmall)
                if (state.selectedId == profile.id) Text("目前選用", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
    TextButton(enabled = !state.busy, onClick = { editing = null; editorVisible = true }) { Text("新增伺服器") }
    Text("登入後可前往討論室，以六碼代碼加入並開始收音。", style = MaterialTheme.typography.bodySmall)

    actions?.let { profile ->
        AlertDialog(onDismissRequest = { actions = null }, title = { Text(profile.displayName) }, text = {
            Column {
                Text(profile.baseUrl)
                TextButton(onClick = { actions = null; model.select(profile.id) }) { Text("選用／登入") }
                TextButton(onClick = { actions = null; editing = profile; editorVisible = true }) { Text("編輯") }
                TextButton(onClick = { actions = null; deleting = profile }) { Text("移除") }
            }
        }, confirmButton = { TextButton(onClick = { actions = null }) { Text("關閉") } })
    }
    deleting?.let { profile ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("移除伺服器？") },
            text = { Text("將登出並移除「${profile.displayName}」的本機連線設定，保留本機逐字稿。") },
            confirmButton = { TextButton(onClick = { deleting = null; model.remove(profile) }) { Text("確認移除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
    if (editorVisible) {
        ServerEditor(editing, state.busy, state.message, { editorVisible = false }) { name, url ->
            model.save(editing, name, url) { editorVisible = false }
        }
    }
    val selectedProfile = state.selected
    if (loginVisible && selectedProfile != null) {
        LoginDialog(selectedProfile.displayName, state.busy, state.message, { loginVisible = false }, loginEmail,
            { loginVisible = false; accountEditor = "register" }) { email, password, clear ->
            model.login(email, password) { clear(); loginVisible = false }
        }
    }
    accountEditor?.let { mode ->
        if (selectedProfile != null) AccountEditDialog(mode, signedIn?.user?.displayName.orEmpty(), state.busy,
            state.message, { accountEditor = null }) { email, name, current, replacement, finished ->
            when (mode) {
                "register" -> model.register(email, replacement, name) { success ->
                    finished()
                    if (success) { loginEmail = email.trim(); accountEditor = null; loginVisible = true }
                }
                "name" -> model.rename(name) { success -> finished(); if (success) accountEditor = null }
                else -> model.changePassword(current, replacement) { success -> finished(); if (success) accountEditor = null }
            }
        }
    }
}

@Composable
private fun AccountEditDialog(mode: String, initialName: String, busy: Boolean, error: String?, dismiss: () -> Unit,
    submit: (String, String, String, String, () -> Unit) -> Unit) {
    var email by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(initialName) }
    var current by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    fun clearPasswords() { current = ""; replacement = ""; confirmation = "" }
    DisposableEffect(Unit) { onDispose { clearPasswords() } }
    AlertDialog(onDismissRequest = { if (!busy) { clearPasswords(); dismiss() } },
        title = { Text(when (mode) { "register" -> "註冊帳號"; "name" -> "修改顯示名稱"; else -> "修改密碼" }) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (mode == "register") {
                OutlinedTextField(email, { email = it }, label = { Text("電子郵件") }, singleLine = true, enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                Text("註冊為一般使用者；完成後請登入目前伺服器。")
            }
            if (mode != "password") OutlinedTextField(name, { name = it }, label = { Text("顯示名稱（1～50 字）") }, singleLine = true, enabled = !busy)
            if (mode == "password") OutlinedTextField(current, { current = it }, label = { Text("目前密碼") }, singleLine = true, enabled = !busy,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            if (mode != "name") {
                OutlinedTextField(replacement, { replacement = it }, label = { Text("${if (mode == "password") "新" else ""}密碼（8～128 字）") },
                    singleLine = true, enabled = !busy, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                OutlinedTextField(confirmation, { confirmation = it }, label = { Text("再次輸入密碼") }, singleLine = true, enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            } else Text("儲存前會停止收音並重新連線，以更新發言者名稱。")
            (localError ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(enabled = !busy, onClick = {
            localError = when {
                mode != "password" && name.trim().length !in 1..50 -> "顯示名稱須為 1～50 字。"
                mode != "name" && replacement.length !in 8..128 -> "密碼須為 8～128 字。"
                mode != "name" && replacement != confirmation -> "兩次輸入的密碼不一致。"
                mode == "password" && current.length !in 1..128 -> "目前密碼須為 1～128 字。"
                mode == "register" && email.trim().isEmpty() -> "請輸入電子郵件。"
                else -> null
            }
            if (localError == null) submit(email, name, current, replacement, ::clearPasswords)
        }) { Text(if (mode == "register") "註冊" else "儲存") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { clearPasswords(); dismiss() }) { Text("取消") } })
}

@Composable
private fun ServerEditor(profile: ServerProfile?, busy: Boolean, error: String?, dismiss: () -> Unit, save: (String, String) -> Unit) {
    var name by remember(profile?.id) { mutableStateOf(profile?.displayName.orEmpty()) }
    var url by remember(profile?.id) { mutableStateOf(profile?.baseUrl.orEmpty()) }
    var presetsExpanded by remember(profile?.id) { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text(if (profile == null) "新增伺服器" else "編輯伺服器") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (profile == null) {
                Box(Modifier.fillMaxWidth()) {
                    OutlinedButton(enabled = !busy, onClick = { presetsExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("選擇預設伺服器", Modifier.weight(1f))
                        Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(expanded = presetsExpanded && !busy, onDismissRequest = { presetsExpanded = false },
                        modifier = Modifier.widthIn(max = 320.dp)) {
                        serverPresets.forEach { preset ->
                            DropdownMenuItem(text = {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(preset.name)
                                    Text(preset.baseUrl, style = MaterialTheme.typography.bodySmall)
                                }
                            }, onClick = {
                                url = preset.baseUrl
                                if (name.isBlank()) name = preset.name
                                presetsExpanded = false
                            })
                        }
                    }
                }
                Text("也可自行輸入名稱及網址；預設測試網址可能隨 Tunnel 更新。", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(name, { name = it }, label = { Text("伺服器名稱") }, singleLine = true, enabled = !busy)
            OutlinedTextField(url, { url = it }, label = { Text("API HTTPS 網址") }, singleLine = true, enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            Text("請填 API 網址，勿填網頁前端網址。修改網址前請先登出。")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = { save(name, url) }) { Text("儲存") } },
        dismissButton = { TextButton(enabled = !busy, onClick = dismiss) { Text("取消") } })
}

@Composable
private fun LoginDialog(serverName: String, busy: Boolean, error: String?, dismiss: () -> Unit, initialEmail: String = "",
    register: () -> Unit, login: (String, String, () -> Unit) -> Unit) {
    // Password intentionally uses remember, never rememberSaveable or persistent state.
    var email by remember { mutableStateOf(initialEmail) }
    var password by remember { mutableStateOf("") }
    DisposableEffect(Unit) { onDispose { password = "" } }
    AlertDialog(onDismissRequest = { if (!busy) { password = ""; dismiss() } }, title = { Text("登入 $serverName") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(email, { email = it }, label = { Text("電子郵件") }, singleLine = true, enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
            OutlinedTextField(password, { password = it }, label = { Text("密碼") }, singleLine = true, enabled = !busy,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            TextButton(enabled = !busy, onClick = { password = ""; register() }) { Text("建立帳號") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = { login(email, password) { password = "" } }) { Text("登入") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { password = ""; dismiss() }) { Text("取消") } })
}

@Composable
fun ServerShortcut(state: AccountUiState, onSettings: () -> Unit) {
    TextButton(onClick = onSettings) {
        Text(state.selected?.let { "伺服器：${it.displayName}" } ?: "設定伺服器")
    }
}
