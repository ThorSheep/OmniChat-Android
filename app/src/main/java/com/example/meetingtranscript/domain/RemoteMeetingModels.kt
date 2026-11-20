package com.example.meetingtranscript.domain

import java.time.Instant
import java.util.UUID

data class ServerProfile(val id: String = UUID.randomUUID().toString(), val displayName: String, val baseUrl: String)
enum class UserRole { STUDENT, TEACHER, ADMIN }
enum class AccountStatus { ACTIVE, DISABLED }
data class AuthenticatedUser(val id: String, val email: String, val displayName: String, val role: UserRole, val status: AccountStatus, val createdAt: Instant)

/** Contains no credentials; token handling belongs exclusively to the auth implementation. */
sealed interface AuthState {
    data object SignedOut : AuthState
    data class SignedIn(val serverId: String, val user: AuthenticatedUser, val expiresAt: Instant) : AuthState
    data class Revoked(val reason: String) : AuthState
}

enum class RemoteRoomStatus { OPEN, CLOSED, ARCHIVED }
data class AiProactiveSettings(val enabled: Boolean, val quietSeconds: Int, val minMessages: Int, val cooldownSeconds: Int)
data class AiSettings(val enabled: Boolean, val name: String, val wakeWords: List<String>, val respondOnMention: Boolean, val persona: String, val proactive: AiProactiveSettings)
data class VoiceSettings(val provider: String, val voice: String)
data class RemoteRoomSettings(val ai: AiSettings, val languages: List<String>, val voices: Map<String, VoiceSettings>)
data class RemoteMeetingRoom(val id: String, val code: String, val name: String, val status: RemoteRoomStatus, val createdAt: Instant, val closedAt: Instant?, val ownerId: String? = null, val settings: RemoteRoomSettings? = null, val joinedAt: Instant? = null)
data class RemotePage<T>(val items: List<T>, val nextCursor: String?)
data class MeetingMember(val userId: String, val displayName: String, val joinedAt: Instant? = null)
enum class MessageSenderType { USER, AI, SYSTEM }
data class LocalizedMessageText(val lang: String, val text: String)
data class MeetingMessage(val id: String, val roomId: String, val senderType: MessageSenderType, val sender: MeetingMember?, val text: String, val lang: String?, val clientTs: Instant?, val serverTs: Instant, val contentType: String = "TEXT", val contents: List<LocalizedMessageText>? = null, val trigger: String? = null)

/** Transport adapter serializes only these four fields for transcript:submit. */
data class TranscriptSubmission private constructor(val clientMsgId: String, val text: String, val lang: String, val clientTs: Instant?) {
    companion object {
        fun from(segment: TranscriptSegment): TranscriptSubmission = create(segment.id, segment.text, segment.language, segment.startedAt)

        fun create(clientMsgId: String, text: String, language: String?, clientTs: Instant?): TranscriptSubmission {
            require(clientMsgId.length in 1..64) { "Invalid client message ID" }
            val trimmed = text.trim()
            require(trimmed.length in 1..2000) { "Transcript must contain 1–2000 characters" }
            return TranscriptSubmission(clientMsgId, trimmed, if (language == "en") "en" else "zh-TW", clientTs)
        }
    }
}

/** Destination and identity remain immutable across retries and account switches. */
data class PendingTranscript(val serverId: String, val userId: String, val roomId: String, val submission: TranscriptSubmission, val status: SyncStatus = SyncStatus.PENDING)
data class SubmissionAck(val messageId: String, val serverTs: Instant, val duplicate: Boolean)

data class TranscriptDestination(val serverId: String, val userId: String, val roomId: String, val baseUrl: String)
data class RemoteDeliveryRecord(
    val target: TranscriptDestination,
    val segment: TranscriptSegment,
    val status: SyncStatus = SyncStatus.PENDING,
    val ack: SubmissionAck? = null,
    val errorCode: String? = null,
)
enum class RemoteCaptureState { STOPPED, RECORDING }
data class RemoteDiscussionState(
    val target: TranscriptDestination? = null,
    val connection: SocketState = SocketState.Disconnected,
    val capture: RemoteCaptureState = RemoteCaptureState.STOPPED,
    val startedAt: Instant? = null,
    val input: String = "尚未開始收音",
    val partialText: String = "",
    val records: List<RemoteDeliveryRecord> = emptyList(),
    val message: String? = null,
    val blocked: Boolean = false,
    val room: RemoteMeetingRoom? = null,
    val members: List<MeetingMember> = emptyList(),
    val online: List<MeetingMember> = emptyList(),
    val messages: List<MeetingMessage> = emptyList(),
    val aiState: String = "idle",
    val syncing: Boolean = false,
) {
    val pendingCount: Int get() = records.count { it.status != SyncStatus.SYNCED }
    val unmergedRecords: List<RemoteDeliveryRecord> get() {
        val ids = messages.map { it.id }.toSet()
        return records.filter { it.ack?.messageId !in ids }
    }
}
data class RoomSnapshot(val room: RemoteMeetingRoom, val members: List<MeetingMember>, val recentMessages: List<MeetingMessage>)
data class MessageSync(val messages: List<MeetingMessage>, val truncated: Boolean)
data class RemoteFailure(val code: String, val requestId: String? = null)

sealed interface SocketState {
    data object Disconnected : SocketState
    data object Connecting : SocketState
    data object Connected : SocketState
    data class Joined(val roomId: String) : SocketState
    data class Failed(val failure: RemoteFailure) : SocketState
}

sealed interface MeetingEvent {
    data class ConnectionChanged(val state: SocketState) : MeetingEvent
    data class MessageReceived(val message: MeetingMessage) : MeetingEvent
    data class AiStatusChanged(val roomId: String, val state: String) : MeetingEvent
    data class PresenceChanged(val roomId: String, val online: List<MeetingMember>) : MeetingEvent
    data class RoomUpdated(val room: RemoteMeetingRoom) : MeetingEvent
    data class RoomClosed(val roomId: String) : MeetingEvent
    data class SessionRevoked(val reason: String) : MeetingEvent
}

/** A timeline belongs to one server and room; it rejects foreign-room messages. */
class MessageTimeline(val serverId: String, val roomId: String) {
    private val messagesById = linkedMapOf<String, MeetingMessage>()
    fun merge(messages: List<MeetingMessage>): List<MeetingMessage> {
        require(messages.all { it.roomId == roomId }) { "Message belongs to another room" }
        messages.forEach { messagesById[it.id] = it }
        return messagesById.values.sortedWith(compareBy<MeetingMessage> { it.serverTs }.thenBy { it.id })
    }
}
