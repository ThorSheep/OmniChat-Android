package com.example.meetingtranscript.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.meetingtranscript.domain.TranscriptSession

@Composable
fun HistoryScreen(sessions: List<TranscriptSession>, onBack: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(20.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("歷史逐字稿", style = MaterialTheme.typography.headlineMedium); TextButton(onBack) { Text("返回討論室") } }
        if (sessions.isEmpty()) item { Text("這個討論室還沒有本機逐字稿。") }
        items(sessions, key = { it.id }) { session ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                Text(session.roomDisplayName ?: "未分類舊紀錄", style = MaterialTheme.typography.titleMedium)
                Text(session.serverUrl ?: "無伺服器資料", style = MaterialTheme.typography.bodySmall)
                Text(session.startedAt.toString(), style = MaterialTheme.typography.bodySmall)
                SelectionContainer { Text(session.segments.joinToString("\n") { it.text }.ifBlank { "沒有已確認文字" }, Modifier.padding(top = 12.dp)) }
            } }
        }
    }
}
