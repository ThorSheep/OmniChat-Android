package com.example.meetingtranscript.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.meetingtranscript.domain.*
import com.example.meetingtranscript.presentation.RemoteHistoryState
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun RemoteHistoryScreen(state: RemoteHistoryState, onBack: () -> Unit, onRoom: (RemoteMeetingRoom) -> Unit,
    onMore: () -> Unit, onRefresh: () -> Unit, onLocal: () -> Unit, currentUserId: String?) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 20.dp)) {
        item {
            TextButton(onClick = onBack) { Text("返回") }
            Text(state.room?.name ?: "討論紀錄", style = MaterialTheme.typography.headlineMedium)
            Text(if (state.room == null) "目前伺服器中，你有權讀取的討論室。" else "依伺服器時間由舊到新排列。查看紀錄不會切換討論室。")
            Row {
                TextButton(onClick = onRefresh, enabled = !state.busy) { Text("重新載入") }
                TextButton(onClick = onLocal) { Text("本機逐字稿") }
            }
        }
        if (state.room == null) {
            items(state.rooms, key = { it.id }) { room ->
                OutlinedCard(onClick = { onRoom(room) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(room.name, style = MaterialTheme.typography.titleMedium)
                        Text("${room.code} · ${when (room.status) { RemoteRoomStatus.OPEN -> "開放中"; RemoteRoomStatus.CLOSED -> "已關閉"; else -> "已封存" }}")
                    }
                }
            }
        } else items(state.messages, key = { it.id }) { DiscussionMessageCard(it, state.room?.settings, currentUserId) }
        item {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.loaded && (if (state.room == null) state.rooms.isEmpty() else state.messages.isEmpty())) Text("尚無紀錄。")
            if (!state.busy && (state.error != null || !state.loaded || state.cursor != null)) {
                OutlinedButton(onClick = onMore) { Text(if (state.error != null) "重試" else if (state.room == null) "載入更多討論室" else "載入後續訊息") }
            }
        }
    }
}

@Composable
fun DiscussionMessageCard(message: MeetingMessage, settings: RemoteRoomSettings? = null, currentUserId: String?) {
    val own = currentUserId != null && message.senderType == MessageSenderType.USER && message.sender?.userId == currentUserId
    val colors = MaterialTheme.colorScheme
    val background = when {
        own -> colors.primaryContainer
        message.senderType == MessageSenderType.AI -> colors.tertiaryContainer
        message.senderType == MessageSenderType.SYSTEM -> colors.surface
        else -> colors.surfaceContainer
    }
    val foreground = when {
        own -> colors.onPrimaryContainer
        message.senderType == MessageSenderType.AI -> colors.onTertiaryContainer
        message.senderType == MessageSenderType.SYSTEM -> colors.onSurfaceVariant
        else -> colors.onSurface
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = background, contentColor = foreground)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(when (message.senderType) {
                MessageSenderType.AI -> "AI · ${settings?.ai?.name ?: "助理"}"
                MessageSenderType.SYSTEM -> "系統"
                MessageSenderType.USER -> (message.sender?.displayName ?: "使用者") + if (own) "（你）" else ""
            }, style = MaterialTheme.typography.labelLarge)
            val contents = message.contents
            if (!contents.isNullOrEmpty()) {
                val order = settings?.languages.orEmpty()
                contents.sortedBy { order.indexOf(it.lang).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }.forEach {
                    Text(it.lang, style = MaterialTheme.typography.labelSmall)
                    Text(it.text)
                }
            } else Text(message.text)
            Text(DateTimeFormatter.ofPattern("MM/dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(message.serverTs),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
