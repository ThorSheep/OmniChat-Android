package com.example.meetingtranscript.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.meetingtranscript.domain.*
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** UI-only state lives above navigation, so closing the panel preserves its reading position. */
@Stable
class DiscussionTranscriptState {
    val list = LazyListState()
    var visible by mutableStateOf(false)
    var followingLatest by mutableStateOf(true)
    private var baselineReady by mutableStateOf(false)
    private var readIds by mutableStateOf(emptySet<String>())

    fun initialize(messages: List<MeetingMessage>) {
        if (!baselineReady) { markRead(messages); baselineReady = true }
    }
    fun markRead(messages: List<MeetingMessage>) { readIds = readIds + messages.map { it.id } }
    fun unreadCount(messages: List<MeetingMessage>): Int = if (!baselineReady) 0 else messages.count { it.id !in readIds }
}

@Composable
fun rememberDiscussionTranscriptState(state: RemoteDiscussionState): DiscussionTranscriptState {
    val panel = remember(state.target) { DiscussionTranscriptState() }
    LaunchedEffect(state.target, state.connection, state.syncing, state.messages) {
        if (state.connection is SocketState.Joined && !state.syncing) panel.initialize(state.messages)
    }
    return panel
}

@Composable
fun DiscussionTranscriptPanel(state: RemoteDiscussionState, panel: DiscussionTranscriptState, currentUserId: String?,
    canStart: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    val transition = remember { MutableTransitionState(false).apply { targetState = true } }
    val overlayTransition = updateTransition(transition, label = "Transcript panel")
    val scope = rememberCoroutineScope()
    var autoScrolling by remember { mutableStateOf(false) }
    val currentMessages by rememberUpdatedState(state.messages)
    val local = state.unmergedRecords
    // One trailing item provides a stable destination even when partial text is empty.
    val lastIndex = state.messages.size + local.size
    val currentLastIndex by rememberUpdatedState(lastIndex)
    suspend fun showLatest() {
        autoScrolling = true
        val displayedMessages = currentMessages
        try {
            panel.list.scrollToItem(currentLastIndex)
            panel.followingLatest = true
            panel.markRead(displayedMessages)
        } finally { autoScrolling = false }
    }
    LaunchedEffect(transition.isIdle, transition.currentState) {
        if (transition.isIdle && !transition.currentState && !transition.targetState) panel.visible = false
    }
    LaunchedEffect(state.messages.map { it.id }, local.map { it.segment.id }, state.partialText) {
        if (panel.followingLatest) showLatest()
    }
    LaunchedEffect(panel.list) {
        snapshotFlow {
            val layout = panel.list.layoutInfo
            val atEnd = !panel.list.canScrollForward && layout.totalItemsCount == currentLastIndex + 1 &&
                layout.visibleItemsInfo.lastOrNull()?.key == "latest"
            panel.list.isScrollInProgress to atEnd
        }.collect { (scrolling, atEnd) ->
            if (!autoScrolling) {
                if (scrolling) panel.followingLatest = atEnd
                if (!scrolling && atEnd) {
                    panel.followingLatest = true
                    panel.markRead(currentMessages)
                }
            }
        }
    }
    fun close() { transition.targetState = false }
    BackHandler { close() }
    // The host page already applies Scaffold's system-bar padding. Inherit its exact bounds;
    // a separate Dialog window would measure against a different available height.
    Box(Modifier.fillMaxSize().clipToBounds().semantics { paneTitle = "逐字稿" }) {
        overlayTransition.AnimatedVisibility(visible = { it }, modifier = Modifier.fillMaxSize(),
            enter = fadeIn(tween(250)), exit = fadeOut(tween(250))) {
            Surface(color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f), modifier = Modifier.fillMaxSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = ::close)) { }
        }
        overlayTransition.AnimatedVisibility(visible = { it }, modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                enter = slideInHorizontally(animationSpec = tween(250), initialOffsetX = { it }),
                exit = slideOutHorizontally(animationSpec = tween(250), targetOffsetX = { it })) {
                Surface(modifier = Modifier.fillMaxWidth(0.9f).fillMaxHeight()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
                    shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp),
                    shadowElevation = 12.dp) {
                    Column(Modifier.fillMaxSize()) {
                        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("逐字稿", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text("${state.messages.size} 則訊息 · ${discussionConnectionLabel(state)}", style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = ::close) { Icon(Icons.Outlined.Close, contentDescription = "關閉逐字稿") }
                        }
                        val unread = panel.unreadCount(state.messages)
                        if (!panel.followingLatest || unread > 0) TextButton(onClick = { scope.launch { showLatest() } }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                            Text(if (unread > 0) "$unread 則新訊息 · 回到最新" else "回到最新")
                        }
                        HorizontalDivider()
                        LazyColumn(state = panel.list, modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(state.messages, key = { "message:${it.id}" }) { DiscussionMessageCard(it, state.room?.settings, currentUserId) }
                            items(local, key = { "local:${it.segment.id}" }) { record ->
                                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("你 · ${when (record.status) {
                                            SyncStatus.SYNCED -> "已送出，等待訊息同步"
                                            SyncStatus.UPLOADING -> "正在送出"
                                            SyncStatus.FAILED -> "無法送出（${record.errorCode}），原文已保留"
                                            else -> "等待送出"
                                        }}", style = MaterialTheme.typography.labelLarge)
                                        Text(record.segment.text)
                                    }
                                }
                            }
                            item(key = "latest") {
                                if (state.partialText.isNotBlank()) {
                                    Text("你 · 辨識中", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                    Text(state.partialText, Modifier.padding(top = 6.dp))
                                } else if (state.messages.isEmpty() && local.isEmpty()) Text("尚無文字，開始收音後會在這裡顯示逐字稿。")
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                        DiscussionCaptureControls(state, canStart, onStart, onStop)
                    }
                }
            }
    }
}

internal fun discussionConnectionLabel(state: RemoteDiscussionState): String = if (state.syncing) "正在同步訊息" else when (state.connection) {
    is SocketState.Joined -> "已連線"
    SocketState.Connecting -> "正在連線"
    SocketState.Connected -> "正在訂閱討論室"
    is SocketState.Failed -> "連線需要處理"
    SocketState.Disconnected -> "等待重新連線"
}

@Composable
internal fun discussionElapsed(state: RemoteDiscussionState): String {
    var elapsed by remember(state.startedAt) { mutableStateOf(0L) }
    LaunchedEffect(state.startedAt, state.capture) {
        while (state.capture == RemoteCaptureState.RECORDING) {
            elapsed = state.startedAt?.let { Duration.between(it, Instant.now()).seconds.coerceAtLeast(0) } ?: 0
            delay(1_000)
        }
    }
    return "${elapsed / 60}:${(elapsed % 60).toString().padStart(2, '0')}"
}

@Composable
fun DiscussionCaptureControls(state: RemoteDiscussionState, canStart: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    val recording = state.capture == RemoteCaptureState.RECORDING
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (recording) "${state.input} · ${discussionElapsed(state)}" else "收音已停止", style = MaterialTheme.typography.bodyMedium)
            if (state.pendingCount > 0) Text("待處理 ${state.pendingCount} 段 · 原文保留於本機", style = MaterialTheme.typography.bodySmall)
            Button(onClick = if (recording) onStop else onStart, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                enabled = recording || (canStart && state.connection is SocketState.Joined && !state.syncing && !state.blocked),
                colors = ButtonDefaults.buttonColors(containerColor = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)) {
                Icon(if (recording) Icons.Outlined.Stop else Icons.Outlined.Mic, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (recording) "停止收音" else "開始收音")
            }
        }
    }
}
