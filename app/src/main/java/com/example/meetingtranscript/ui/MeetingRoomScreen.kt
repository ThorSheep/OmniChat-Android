package com.example.meetingtranscript.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.example.meetingtranscript.domain.MeetingRoom

@Composable
fun MeetingRoomScreen(rooms: List<MeetingRoom>, selectedRoomId: String?, message: String?,
    onSelect: (String) -> Unit, onAdd: (String, String, String) -> Boolean,
    onEdit: (MeetingRoom, String, String, String) -> Boolean, onDelete: (String) -> Unit, onHistory: (MeetingRoom) -> Unit) {
    var actionId by rememberSaveable { mutableStateOf<String?>(null) }
    var dialog by rememberSaveable { mutableStateOf("") }
    val room = rooms.firstOrNull { it.id == actionId }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 24.dp, 20.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("討論室", style = MaterialTheme.typography.headlineMedium); Text("選擇討論室，準備開始討論。") }
            if (rooms.isEmpty()) item { Text("還沒有討論室，點右下角 ＋ 新增。") }
            items(rooms, key = { it.id }) { item ->
                Card(Modifier.fillMaxWidth().clickable { actionId = item.id; dialog = "actions" }, shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(20.dp)) {
                        Text(item.displayName, style = MaterialTheme.typography.titleMedium)
                        Text(item.serverUrl, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (selectedRoomId == item.id) Text("最近使用", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        FloatingActionButton(onClick = { actionId = null; dialog = "add" }, modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)) { Text("＋") }
    }
    if (dialog == "add" || (dialog == "edit" && room != null)) {
        RoomEditor(if (dialog == "edit") room else null, message, { dialog = "" }) { name, server, user ->
            val saved = if (dialog == "edit" && room != null) onEdit(room, name, server, user) else onAdd(name, server, user)
            if (saved) dialog = ""
        }
    }
    if (room != null && dialog == "actions") AlertDialog(
        onDismissRequest = { dialog = "" }, title = { Text(room.displayName) },
        text = { Column { Text(room.serverUrl); TextButton({ dialog = "edit" }) { Text("編輯") }; TextButton({ dialog = "delete" }) { Text("刪除") }; TextButton({ dialog = ""; onHistory(room) }) { Text("歷史逐字稿") } } },
        confirmButton = { TextButton({ dialog = "enter" }) { Text("進入") } }, dismissButton = { TextButton({ dialog = "" }) { Text("取消") } })
    if (room != null && dialog in listOf("enter", "delete")) AlertDialog(
        onDismissRequest = { dialog = "" }, title = { Text(if (dialog == "enter") "確定進入 ${room.displayName}？" else "確定刪除討論室？") },
        text = { Text(if (dialog == "enter") "進入後可開啟討論收音。成員同步尚未啟用。" else "只刪除本機設定，保留歷史逐字稿。") },
        confirmButton = { TextButton({ if (dialog == "enter") onSelect(room.id) else onDelete(room.id); dialog = "" }) { Text("確定") } },
        dismissButton = { TextButton({ dialog = "" }) { Text("取消") } })
}

@Composable
private fun RoomEditor(room: MeetingRoom?, error: String?, dismiss: () -> Unit, save: (String, String, String) -> Unit) {
    var name by rememberSaveable(room?.id) { mutableStateOf(room?.displayName ?: "") }
    var user by rememberSaveable(room?.id) { mutableStateOf(room?.userDisplayName ?: "使用者") }
    var url by rememberSaveable(room?.id) { mutableStateOf(room?.serverUrl ?: "") }
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (room == null) "新增討論室" else "編輯討論室") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("討論室名稱") }, singleLine = true)
            OutlinedTextField(user, { user = it }, label = { Text("使用者顯示名稱") }, singleLine = true)
            OutlinedTextField(url, { url = it }, label = { Text("伺服器 HTTPS 網址") }, singleLine = true)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton({ save(name, url, user) }) { Text("儲存") } }, dismissButton = { TextButton(dismiss) { Text("取消") } })
}
