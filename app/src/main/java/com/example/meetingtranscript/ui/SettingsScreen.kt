package com.example.meetingtranscript.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.meetingtranscript.data.platform.ThemeMode
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.example.meetingtranscript.presentation.AccountUiState
import com.example.meetingtranscript.presentation.AccountViewModel

@Composable
fun SettingsScreen(mode: ThemeMode, onModeChange: (ThemeMode) -> Unit, retry: () -> Unit,
    accountState: AccountUiState, accountModel: AccountViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("使用者設定", style = MaterialTheme.typography.headlineMedium)
        ServerAccountSection(accountState, accountModel)
        HorizontalDivider(Modifier.padding(top = 20.dp, bottom = 12.dp))
        Text("外觀", style = MaterialTheme.typography.titleLarge)
        ThemeMode.entries.forEach { item ->
            TextButton(onClick = { onModeChange(item) }) {
                RadioButton(selected = mode == item, onClick = null)
                Text(when (item) { ThemeMode.SYSTEM -> "跟隨系統"; ThemeMode.LIGHT -> "淺色模式"; ThemeMode.DARK -> "深色模式" }, modifier = Modifier.padding(start = 8.dp))
            }
        }
        Button(retry) { Text("重試待上傳逐字稿") }
    }
}
