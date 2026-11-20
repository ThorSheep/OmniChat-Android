package com.example.meetingtranscript.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.meetingtranscript.domain.*
import com.example.meetingtranscript.presentation.AccountUiState
import com.example.meetingtranscript.presentation.MeetingStatus
import com.example.meetingtranscript.presentation.MeetingUiState
import java.util.Locale

/** Navigation changes visibility. Membership and microphone changes require explicit actions. */
@Composable
fun RemoteMeetingScreen(
    account: AccountUiState, meeting: MeetingUiState, discussionVisible: Boolean,
    onSettings: () -> Unit, onMinimize: () -> Unit, onOpenDiscussion: () -> Unit, onLegacy: () -> Unit,
    onReload: () -> Unit, onJoin: (String) -> Unit, onLeave: () -> Unit,
    onAdd: (String) -> Unit, onRemove: (String) -> Unit,
    discussion: RemoteDiscussionState, transcript: DiscussionTranscriptState,
    onStart: () -> Unit, onStop: () -> Unit, onRetry: () -> Unit,
    onConfirmChange: () -> Unit, onCancelChange: () -> Unit, onHistory: () -> Unit,
) {
    val user = (account.auth as? AuthState.SignedIn)?.user
    val identity = "${account.selectedId}:${user?.id}"
    var inputVisible by remember(identity) { mutableStateOf(false) }
    var pendingAdd by remember(identity) { mutableStateOf(false) }
    var code by remember(identity) { mutableStateOf("") }
    var menuId by remember(identity) { mutableStateOf<String?>(null) }
    var confirmJoin by remember(identity) { mutableStateOf<SavedDiscussionRoom?>(null) }
    var confirmRemove by remember(identity) { mutableStateOf<SavedDiscussionRoom?>(null) }
    var confirmLeave by remember(identity) { mutableStateOf(false) }
    var membersVisible by remember(identity) { mutableStateOf(false) }
    val identityMatches = user != null && account.selectedId == meeting.serverId && user.id == meeting.userId
    val ready = identityMatches && !account.busy && !meeting.busy && !meeting.requiresLogin
    val room = meeting.room
    val matchingTarget = identityMatches && room != null && discussion.target?.roomId == room.id &&
        discussion.target?.serverId == meeting.serverId && discussion.target?.userId == meeting.userId
    val canStart = ready && matchingTarget

    BackHandler(enabled = discussionVisible && !transcript.visible) { onMinimize() }
    if (discussionVisible && identityMatches && room != null) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onMinimize) {
                    Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null)
                    Text("收起")
                }
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(if (matchingTarget) discussion.room?.name ?: room.name else room.name,
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("代碼 ${room.code}", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(enabled = ready, onClick = { confirmLeave = true }) { Text("退出", color = MaterialTheme.colorScheme.error) }
            }
            HorizontalDivider()
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(discussionConnectionLabel(discussion), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                if (discussion.syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("${user?.displayName.orEmpty()}（你）", style = MaterialTheme.typography.titleMedium)
                OutlinedCard(onClick = { membersVisible = true }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("在線成員 · ${discussion.online.size} 人", style = MaterialTheme.typography.titleMedium)
                        Text(discussion.online.take(4).joinToString("、") { it.displayName }.ifEmpty { "等待在線名單" })
                        Text("查看成員", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("AI · ${discussion.room?.settings?.ai?.name ?: "AI"}", style = MaterialTheme.typography.titleMedium)
                        Text(if (discussion.room?.settings?.ai?.enabled == false) "已停用" else when (discussion.aiState) {
                            "thinking" -> "思考中"; "speaking" -> "回覆中"; "error" -> "處理失敗"; else -> "待命"
                        })
                    }
                }
                FilledTonalButton(onClick = { transcript.visible = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    val unread = transcript.unreadCount(discussion.messages)
                    Text(if (unread > 0) "逐字稿 · $unread 則新訊息" else "開啟逐字稿")
                }
                Text("成員發言與 AI 回覆會顯示在右側逐字稿面板。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onHistory) { Text("查看討論紀錄") }
                discussion.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                meeting.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = !discussion.blocked, onClick = onRetry) { Text("重試連線／待送") }
                    TextButton(enabled = ready, onClick = onReload) { Text("更新狀態") }
                }
            }
            DiscussionCaptureControls(discussion, canStart, onStart, onStop)
        }
        if (transcript.visible && matchingTarget) DiscussionTranscriptPanel(discussion, transcript, user?.id, canStart, onStart, onStop)
    } else {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 20.dp)) {
            item {
                Text("討論室", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("選擇討論室，開始下一次討論。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when {
                account.selected == null -> item { Button(onClick = onSettings) { Text("設定伺服器") } }
                user == null || meeting.requiresLogin -> item { Button(onClick = onSettings) { Text("登入帳號") } }
                identityMatches -> {
                    if (room != null) item {
                        Card(onClick = onOpenDiscussion, modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("目前已加入", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                                    Box {
                                        IconButton(enabled = ready, onClick = { menuId = "current" }) { Icon(Icons.Outlined.MoreVert, contentDescription = "目前討論室選單") }
                                        DropdownMenu(expanded = menuId == "current", onDismissRequest = { menuId = null }) {
                                            DropdownMenuItem(text = { Text("退出討論室") }, onClick = { menuId = null; confirmLeave = true })
                                        }
                                    }
                                }
                                Text(if (matchingTarget) discussion.room?.name ?: room.name else room.name, style = MaterialTheme.typography.titleLarge)
                                Text("代碼 ${room.code}")
                                Button(onClick = onOpenDiscussion) { Text("回到討論") }
                            }
                        }
                    }
                    item {
                        Button(enabled = ready, onClick = { code = ""; inputVisible = true }) { Text("新增討論室入口") }
                        Text("保存常用代碼，一次只加入一個討論室。", style = MaterialTheme.typography.bodySmall)
                    }
                    val entries = meeting.saved.entries.filterNot { room != null && (room.id == it.roomId || room.code == it.code) }
                        .sortedByDescending { it.id == meeting.saved.recentId }
                    if (entries.isNotEmpty()) item { Text("已保存討論室", style = MaterialTheme.typography.titleMedium) }
                    if (entries.isEmpty() && room == null && !meeting.busy) item { Text("還沒有保存的討論室，可先新增六碼代碼。") }
                    items(entries, key = { it.id }) { entry ->
                        OutlinedCard(onClick = { confirmJoin = entry }, enabled = ready,
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(entry.name ?: "尚未加入的討論室", style = MaterialTheme.typography.titleMedium)
                                    Text("代碼 ${entry.code}")
                                    Text(if (entry.id == meeting.saved.recentId) "最近使用" else "點擊確認加入",
                                        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                                }
                                IconButton(enabled = ready, onClick = { confirmRemove = entry }) {
                                    Icon(Icons.Outlined.DeleteOutline, contentDescription = "移除本機討論室入口",
                                        tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
            item {
                if (account.busy || meeting.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                meeting.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (identityMatches) TextButton(enabled = ready, onClick = onReload) { Text("更新狀態") }
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                TextButton(onClick = onLegacy) { Text("舊測試討論室") }
            }
        }
    }
    if (membersVisible && discussionVisible) AlertDialog(onDismissRequest = { membersVisible = false },
        title = { Text("在線成員（${discussion.online.size}）") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (discussion.online.isEmpty()) Text("目前沒有在線名單，請確認連線狀態。")
                discussion.online.forEach { Text(it.displayName + if (it.userId == user?.id) "（你）" else "") }
                HorizontalDivider()
                Text("加入時成員快照：${discussion.members.size} 人，包含不在線的成員。", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(onClick = { membersVisible = false }) { Text("關閉") } })
    if (inputVisible && identityMatches) AlertDialog(onDismissRequest = { if (!meeting.busy) inputVisible = false },
        title = { Text("新增討論室入口") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(code, { code = it.trim().uppercase(Locale.ROOT) }, label = { Text("六碼代碼") }, singleLine = true, enabled = ready)
                Text("僅保存在本機；首次加入後取得正式名稱。")
                meeting.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(enabled = ready, onClick = { pendingAdd = true; onAdd(code) }) { Text("儲存") } },
        dismissButton = { TextButton(enabled = !meeting.busy, onClick = { inputVisible = false }) { Text("取消") } })
    LaunchedEffect(meeting.status, meeting.saved.entries, meeting.message, pendingAdd) {
        if (pendingAdd && meeting.status == MeetingStatus.Idle) {
            if (meeting.message == null && meeting.saved.entries.any { it.code == code.trim().uppercase(Locale.ROOT) }) inputVisible = false
            pendingAdd = false
        }
    }
    confirmJoin?.let { entry ->
        if (ready) AlertDialog(onDismissRequest = { confirmJoin = null }, title = { Text(entry.name ?: "加入討論室") },
            text = { Text("代碼：${entry.code}\n" + if (room != null) "確認後將停止收音，退出「${room.name}」並加入此討論室。" else "加入後可自行開啟收音，正式名稱以伺服器回應為準。") },
            confirmButton = { TextButton(onClick = { confirmJoin = null; onJoin(entry.code) }) { Text("確認加入") } },
            dismissButton = { TextButton(onClick = { confirmJoin = null }) { Text("取消") } })
    }
    confirmRemove?.let { entry ->
        if (ready) AlertDialog(onDismissRequest = { confirmRemove = null }, title = { Text("移除本機入口？") },
            text = { Text("將移除 ${entry.name ?: entry.code} 的本機入口，保留伺服器及本機逐字稿。") },
            confirmButton = { TextButton(onClick = { confirmRemove = null; onRemove(entry.id) }) { Text("確認移除") } },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("取消") } })
    }
    if (confirmLeave && ready) AlertDialog(onDismissRequest = { confirmLeave = false }, title = { Text("退出討論室？") },
        text = { Text("將停止收音並離開討論室，保留入口及紀錄。若只要切換畫面，請使用「收起」。") },
        confirmButton = { TextButton(onClick = { confirmLeave = false; onLeave() }) { Text("確認退出") } },
        dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("取消") } })
    meeting.pendingChangeCount?.let { count ->
        AlertDialog(onDismissRequest = onCancelChange, title = { Text("還有 $count 段文字未送出") },
            text = { Text("收音已停止。繼續後文字保留於原房間，重新加入原討論室後才能重送。") },
            confirmButton = { TextButton(onClick = onConfirmChange) { Text("保留資料並繼續") } },
            dismissButton = { TextButton(onClick = onCancelChange) { Text("取消") } })
    }
}

@Composable
fun CompactDiscussionBar(roomName: String, state: RemoteDiscussionState, onExpand: () -> Unit) {
    Surface(onClick = onExpand, color = MaterialTheme.colorScheme.primaryContainer, tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text(roomName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (state.capture == RemoteCaptureState.RECORDING) "收音中 · ${discussionElapsed(state)}" else "仍在討論室 · 收音已停止",
                    style = MaterialTheme.typography.bodySmall)
            }
            Text("回到討論", style = MaterialTheme.typography.labelLarge)
        }
    }
}
