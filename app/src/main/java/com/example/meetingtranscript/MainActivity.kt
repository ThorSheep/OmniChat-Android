package com.example.meetingtranscript

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.meetingtranscript.data.platform.TranscriptionForegroundService
import com.example.meetingtranscript.data.platform.SharedPreferencesTranscriptRepository
import com.example.meetingtranscript.data.platform.SharedPreferencesMeetingRoomRepository
import com.example.meetingtranscript.data.platform.AppSettingsRepository
import com.example.meetingtranscript.data.platform.ThemeMode
import com.example.meetingtranscript.presentation.TranscriptUiState
import com.example.meetingtranscript.presentation.TranscriptViewModel
import com.example.meetingtranscript.presentation.TranscriptViewModelFactory
import com.example.meetingtranscript.ui.MeetingRoomScreen
import com.example.meetingtranscript.ui.SettingsScreen
import com.example.meetingtranscript.ui.MeetingAppTheme
import com.example.meetingtranscript.ui.HistoryScreen
import com.example.meetingtranscript.data.platform.TranscriptOutbox
import com.example.meetingtranscript.data.platform.AccountServices
import com.example.meetingtranscript.presentation.AccountViewModel
import com.example.meetingtranscript.presentation.AccountViewModelFactory
import com.example.meetingtranscript.ui.ServerShortcut
import com.example.meetingtranscript.domain.AuthState
import com.example.meetingtranscript.presentation.MeetingViewModel
import com.example.meetingtranscript.presentation.MeetingViewModelFactory
import com.example.meetingtranscript.ui.RemoteMeetingScreen
import com.example.meetingtranscript.ui.CompactDiscussionBar
import com.example.meetingtranscript.ui.rememberDiscussionTranscriptState
import com.example.meetingtranscript.domain.RemoteCaptureState
import com.example.meetingtranscript.domain.TranscriptDestination

class MainActivity : ComponentActivity() {
    private var latestViewModel: TranscriptViewModel? = null
    private var remoteStartTarget: TranscriptDestination? = null
    private var remoteRoomName: String? = null
    private var remoteUserName: String? = null
    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            latestViewModel?.onServiceEvent(
                intent.getStringExtra(TranscriptionForegroundService.EXTRA_TYPE),
                intent.getStringExtra(TranscriptionForegroundService.EXTRA_TEXT),
                intent.getStringExtra(TranscriptionForegroundService.EXTRA_DEVICE),
                intent.getStringExtra(TranscriptionForegroundService.EXTRA_MESSAGE),
            )
        }
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startTranscription()
        else AccountServices.get(applicationContext).discussion.reportMessage("請允許麥克風權限後再開始收音。")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val roomRepository = remember { SharedPreferencesMeetingRoomRepository(applicationContext) }
            val settingsRepository = remember { AppSettingsRepository(applicationContext) }
            val viewModel: TranscriptViewModel = viewModel(factory = TranscriptViewModelFactory(roomRepository, settingsRepository))
            latestViewModel = viewModel
            val state by viewModel.uiState.collectAsState()
            val services = remember { AccountServices.get(applicationContext) }
            val accountModel: AccountViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = AccountViewModelFactory(
                services.profiles, services.auth, services.discussion::pause, services.discussion::retryCurrent))
            val accountState by accountModel.state.collectAsState()
            val meetingModel: MeetingViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = MeetingViewModelFactory(services.meetings, services.savedRooms, services.discussion))
            val meetingState by meetingModel.state.collectAsState()
            val discussionState by meetingModel.discussionState.collectAsState()
            val signedIn = accountState.auth as? AuthState.SignedIn
            val historyModel: com.example.meetingtranscript.presentation.RemoteHistoryViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
                factory = com.example.meetingtranscript.presentation.RemoteHistoryViewModelFactory(services.meetings, services.auth, services.profiles))
            val remoteHistory by historyModel.state.collectAsState()
            LaunchedEffect(accountState.selectedId, signedIn?.user?.id, accountState.selected?.baseUrl) {
                if (signedIn == null) services.discussion.pause()
                meetingModel.bind(
                    if (signedIn == null) null else accountState.selectedId,
                    signedIn?.user?.id, accountState.selected?.baseUrl,
                )
                historyModel.bind(if (signedIn == null) null else accountState.selectedId, signedIn?.user?.id, accountState.selected?.baseUrl)
            }
            LaunchedEffect(discussionState.room) { discussionState.room?.let(meetingModel::roomUpdated) }
            LaunchedEffect(remoteHistory.requiresLogin) { if (remoteHistory.requiresLogin) accountModel.reloadAccount() }
            LaunchedEffect(meetingState.requiresLogin) {
                if (meetingState.requiresLogin) accountModel.reloadAccount()
            }
            LaunchedEffect(discussionState.blocked) {
                if (discussionState.blocked) {
                    meetingModel.reload()
                    accountModel.reloadAccount()
                }
            }
            var destination by rememberSaveable { mutableStateOf("rooms") }
            val activeRoom = meetingState.room?.takeIf {
                signedIn != null && signedIn.user.id == meetingState.userId &&
                    accountState.selectedId == meetingState.serverId && !meetingState.requiresLogin
            }
            val transcript = rememberDiscussionTranscriptState(discussionState)
            val openDiscussion: () -> Unit = { transcript.visible = false; destination = "discussion" }
            val minimizeDiscussion: () -> Unit = { transcript.visible = false; destination = "rooms" }
            var handledJoinId by rememberSaveable { mutableStateOf<String?>(null) }
            LaunchedEffect(meetingState.joinedNavigationId) {
                val id = meetingState.joinedNavigationId
                if (id != null && id != handledJoinId) {
                    handledJoinId = id
                    destination = "discussion"
                }
            }
            var historyRoomId by rememberSaveable { mutableStateOf<String?>(null) }
            var historyReturn by rememberSaveable { mutableStateOf("remote_history") }
            var remoteHistoryReturn by rememberSaveable { mutableStateOf("rooms") }
            LaunchedEffect(destination, activeRoom?.id, meetingState.busy) {
                if (destination == "home" || (destination == "discussion" && activeRoom == null && !meetingState.busy)) destination = "rooms"
            }
            LaunchedEffect(destination, accountState.selectedId, signedIn?.user?.id, accountState.selected?.baseUrl) {
                if (destination == "remote_history" && signedIn != null && !remoteHistory.loaded) historyModel.load()
            }
            val backFromHistory: () -> Unit = {
                if (remoteHistory.room != null) historyModel.showRooms()
                else destination = if (remoteHistoryReturn == "discussion" && activeRoom == null) "rooms" else remoteHistoryReturn
            }
            BackHandler(enabled = destination != "rooms" && destination != "discussion") {
                when (destination) {
                    "remote_history" -> backFromHistory()
                    "history" -> destination = historyReturn
                    else -> destination = "rooms"
                }
            }
            MeetingAppTheme(state.themeMode) {
                Scaffold(bottomBar = {
                    if (destination != "discussion") Column {
                    if (activeRoom != null) CompactDiscussionBar(
                        discussionState.room?.takeIf { it.id == activeRoom.id }?.name ?: activeRoom.name, discussionState, openDiscussion)
                    NavigationBar {
                        listOf("rooms" to "討論室", "remote_history" to "紀錄", "settings" to "我的").forEach { (id, label) ->
                            val icon = when (id) { "remote_history" -> Icons.Outlined.History; "rooms" -> Icons.Outlined.Groups; else -> Icons.Outlined.Settings }
                            NavigationBarItem(selected = destination == id || (id == "remote_history" && destination == "history"), onClick = {
                                if (id == "remote_history" && destination != "remote_history") {
                                    remoteHistoryReturn = "rooms"
                                    historyModel.showRooms()
                                }
                                destination = id
                            }, icon = { Icon(icon, label) }, label = { Text(label) })
                        }
                    }
                    }
                }) { padding ->
                    Column(Modifier.padding(padding)) {
                        if (destination == "rooms") ServerShortcut(accountState) { destination = "settings" }
                        Box(Modifier.weight(1f)) { when (destination) {
                        "remote_history" -> if (signedIn == null) Column(Modifier.padding(24.dp)) {
                            Text("討論紀錄", style = MaterialTheme.typography.headlineMedium)
                            Text("登入目前伺服器以查看遠端討論紀錄。", Modifier.padding(vertical = 16.dp))
                            Button(onClick = { destination = "settings" }) { Text("前往登入") }
                            OutlinedButton(onClick = { historyRoomId = null; historyReturn = "remote_history"; destination = "history" }) { Text("本機逐字稿") }
                        } else androidx.compose.runtime.key(accountState.selectedId, signedIn.user.id, remoteHistory.room?.id) {
                        com.example.meetingtranscript.ui.RemoteHistoryScreen(
                            remoteHistory,
                            onBack = backFromHistory,
                            onRoom = historyModel::showRoom, onMore = historyModel::load, onRefresh = historyModel::refresh,
                            onLocal = { historyRoomId = null; historyReturn = "remote_history"; destination = "history" },
                            currentUserId = signedIn.user.id,
                        )
                        }
                        "history" -> HistoryScreen(
                            SharedPreferencesTranscriptRepository(applicationContext).history().filter { historyRoomId == null || it.meetingRoomId == historyRoomId },
                            { destination = historyReturn },
                        )
                        "discussion", "rooms" -> RemoteMeetingScreen(
                            account = accountState, meeting = meetingState, discussionVisible = destination == "discussion",
                            onSettings = { destination = "settings" }, onMinimize = minimizeDiscussion,
                            onOpenDiscussion = openDiscussion,
                            onLegacy = { destination = "legacy_rooms" }, onReload = meetingModel::reload,
                            onJoin = meetingModel::join, onLeave = meetingModel::leave,
                            onAdd = meetingModel::add, onRemove = meetingModel::remove,
                            discussion = discussionState, transcript = transcript,
                            onStart = {
                                if (state.isTranscribing && discussionState.capture != RemoteCaptureState.RECORDING) {
                                    services.discussion.reportMessage("請先停止舊測試討論室的收音。")
                                } else {
                                    remoteStartTarget = discussionState.target
                                    remoteRoomName = meetingState.room?.name
                                    remoteUserName = signedIn?.user?.displayName
                                    requestAndStart()
                                }
                            },
                            onStop = ::stopTranscription, onRetry = meetingModel::retrySubmissions,
                            onConfirmChange = meetingModel::confirmChange, onCancelChange = meetingModel::cancelChange,
                            onHistory = {
                                val currentRoom = discussionState.room ?: meetingState.room
                                remoteHistoryReturn = destination
                                transcript.visible = false
                                if (destination == "discussion" && currentRoom != null) historyModel.showRoom(currentRoom)
                                else historyModel.showRooms()
                                destination = "remote_history"
                            },
                        )
                        "legacy_rooms" -> Column(Modifier.fillMaxSize()) {
                            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { destination = "rooms" }) { Text("返回討論室") }
                                OutlinedButton(onClick = { historyRoomId = null; historyReturn = "legacy_rooms"; destination = "history" }) { Text("本機歷史逐字稿") }
                            }
                            Box(Modifier.weight(1f)) { if (state.activeRoomId != null) {
                            TranscriptScreen(state, { remoteStartTarget = null; requestAndStart() }, ::stopTranscription, {}, true, onLeave = {
                                if (state.isTranscribing) stopTranscription()
                                viewModel.leaveRoom()
                            })
                        } else
                    MeetingRoomScreen(
                        rooms = state.rooms,
                        selectedRoomId = state.selectedRoomId,
                        message = state.message,
                        onSelect = viewModel::enterRoom,
                        onAdd = viewModel::addRoom,
                        onDelete = viewModel::deleteRoom,
                        onEdit = viewModel::editRoom,
                        onHistory = { historyRoomId = it.id; historyReturn = "legacy_rooms"; destination = "history" },
                    )
                            }
                        }
                        "settings" -> SettingsScreen(state.themeMode, viewModel::setThemeMode,
                            { Thread { TranscriptOutbox(applicationContext).flush() }.start() }, accountState, accountModel)
                        else -> Text("請選擇下方功能。", Modifier.padding(24.dp))
                    } } }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Thread { TranscriptOutbox(applicationContext).flush() }.start()
        ContextCompat.registerReceiver(
            this, serviceReceiver, IntentFilter(TranscriptionForegroundService.ACTION_EVENT), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStop() {
        unregisterReceiver(serviceReceiver)
        super.onStop()
    }

    private fun requestAndStart() {
        val required = buildList {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (required.isEmpty()) startTranscription() else permissionLauncher.launch(required.toTypedArray())
    }

    private fun startTranscription() {
        val target = remoteStartTarget
        if (target != null) {
            val services = AccountServices.get(applicationContext)
            if (services.discussion.state.value.target != target || services.discussion.state.value.blocked) return
            val intent = Intent(this, TranscriptionForegroundService::class.java)
                .setAction(TranscriptionForegroundService.ACTION_START)
                .putExtra("remote_server_id", target.serverId).putExtra("remote_user_id", target.userId)
                .putExtra("meeting_room_id", target.roomId)
                .putExtra("room_display_name", remoteRoomName).putExtra("user_display_name", remoteUserName)
                .putExtra(TranscriptionForegroundService.EXTRA_SERVER_URL, target.baseUrl)
            ContextCompat.startForegroundService(this, intent)
            return
        }
        // Legacy capture cannot overlap an active remote microphone session.
        if (AccountServices.get(applicationContext).discussion.state.value.capture == RemoteCaptureState.RECORDING) return
        val room = latestViewModel?.activeRoom() ?: return
        val intent = Intent(this, TranscriptionForegroundService::class.java)
            .setAction(TranscriptionForegroundService.ACTION_START)
            .putExtra(TranscriptionForegroundService.EXTRA_SERVER_URL, latestViewModel?.selectedServerUrl())
            .putExtra("meeting_room_id", room.id)
            .putExtra("room_display_name", room.displayName)
            .putExtra("user_display_name", room.userDisplayName)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopTranscription() {
        startService(Intent(this, TranscriptionForegroundService::class.java).setAction(TranscriptionForegroundService.ACTION_STOP))
    }
}

@Composable
private fun TranscriptScreen(
    state: TranscriptUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onManageRooms: () -> Unit,
    isMeeting: Boolean,
    onLeave: () -> Unit,
    onHistory: () -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text(if (isMeeting) "討論" else "首頁", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        val selectedRoom = state.rooms.firstOrNull { it.id == state.selectedRoomId }
        Text(selectedRoom?.let { "上一次使用：${it.displayName}" } ?: "尚未選擇討論室")
        if (!isMeeting) {
            Button(onClick = onManageRooms) { Text(if (state.activeRoomId != null) "回到進行中的討論" else "前往討論室") }
            OutlinedButton(onClick = onHistory) { Text("查看歷史逐字稿") }
            Text("進入討論室後即可開始討論收音。", Modifier.padding(top = 16.dp))
            return@Column
        }
        Text(selectedRoom?.serverUrl.orEmpty(), style = MaterialTheme.typography.bodySmall)
        Text("參與者：${selectedRoom?.userDisplayName ?: "使用者"}（你）\n其他成員同步尚未啟用；伺服器狀態尚未檢查。", Modifier.padding(vertical = 12.dp))
        OutlinedButton(onClick = onLeave) { Text("退出討論並停止收音") }
        if (selectedRoom == null) OutlinedButton(onClick = onManageRooms) { Text("前往新增討論室") }
        Text(if (state.isTranscribing) "正在收音：${state.inputDevice}" else "尚未開始收音")
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.isTranscribing) OutlinedButton(onClick = onStop) { Text("停止收音") }
            else Button(onClick = onStart) { Text("開啟討論收音") }
        }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 16.dp)) }
        if (state.partialText.isNotBlank()) {
            Card(modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
                Text("辨識中：${state.partialText}", modifier = Modifier.padding(16.dp))
            }
        }
        Spacer(Modifier.height(20.dp))
        Text("已確認文字", style = MaterialTheme.typography.titleMedium)
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.finalSegments) { text -> Card(modifier = Modifier.fillMaxWidth()) { Text(text, modifier = Modifier.padding(14.dp)) } }
        }
    }
}
