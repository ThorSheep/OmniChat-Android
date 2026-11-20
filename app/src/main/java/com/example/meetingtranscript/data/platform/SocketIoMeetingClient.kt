package com.example.meetingtranscript.data.platform

import com.example.meetingtranscript.domain.*
import io.socket.client.Ack
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** One client belongs permanently to one destination. Reconnect uses a new client and fresh token. */
class SocketIoMeetingClient(
    private val target: TranscriptDestination,
    private val profiles: ServerProfileRepository,
    private val auth: RemoteAuthRepository,
) : RealtimeMeetingClient {
    init {
        // Socket.IO's FINE diagnostics include event payloads; never enable them in this app.
        java.util.logging.Logger.getLogger("io.socket").level = java.util.logging.Level.WARNING
    }
    @Volatile private var socket: Socket? = null
    private var observer: (MeetingEvent) -> Unit = {}

    override suspend fun connect(serverId: String) {
        require(serverId == target.serverId)
        val profile = profiles.list().firstOrNull { it.id == serverId } ?: throw AuthApiException("SERVER_NOT_FOUND")
        if (normalizeServerUrl(profile.baseUrl) != target.baseUrl) throw AuthApiException("SERVER_CHANGED")
        auth.authorized(serverId, target.userId) { token ->
            disconnect()
            val options = IO.Options().apply {
                forceNew = true
                reconnection = false
                transports = arrayOf("websocket")
                this.auth = mapOf("token" to token)
                timeout = 15_000
            }
            val client = IO.socket("${target.baseUrl}/rt", options)
            socket = client
            client.on(Socket.EVENT_DISCONNECT) { observer(MeetingEvent.ConnectionChanged(SocketState.Disconnected)) }
            fun receive(event: String, decode: (JSONObject) -> MeetingEvent) {
                client.on(event) { args ->
                    try {
                        val json = args.firstOrNull() as? JSONObject ?: throw AuthApiException("INVALID_EVENT")
                        observer(decode(json))
                    } catch (_: Exception) {
                        // Reconnect and replay from the complete anchor rather than silently losing an event.
                        observer(MeetingEvent.ConnectionChanged(SocketState.Disconnected))
                        client.disconnect()
                    }
                }
            }
            receive("message:new") { MeetingEvent.MessageReceived(decodeMessage(it)) }
            receive("ai:status") { MeetingEvent.AiStatusChanged(it.getString("roomId"), it.getString("state")) }
            receive("ai:message") { json ->
                val contents = json.getJSONArray("contents")
                val first = contents.optJSONObject(0)
                val message = JSONObject(json.toString()).put("senderType", "AI").put("contentType", "TEXT")
                    .put("text", first?.optString("text").orEmpty()).put("lang", first?.optString("lang"))
                MeetingEvent.MessageReceived(decodeMessage(message))
            }
            receive("presence:update") { json ->
                val online = json.getJSONArray("online")
                MeetingEvent.PresenceChanged(json.getString("roomId"), (0 until online.length()).map { i ->
                    online.getJSONObject(i).let { MeetingMember(it.getString("userId"), it.getString("displayName")) }
                })
            }
            receive("room:updated") { MeetingEvent.RoomUpdated(decodeRoom(it)) }
            client.on("room:closed") { args ->
                val json = args.firstOrNull() as? JSONObject
                if (json?.optString("roomId") == target.roomId) observer(MeetingEvent.RoomClosed(target.roomId))
            }
            client.on("session:revoked") { args ->
                val reason = (args.firstOrNull() as? JSONObject)?.optString("reason") ?: "SESSION_REVOKED"
                observer(MeetingEvent.SessionRevoked(reason))
            }
            try {
                withTimeout(16_000) {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        val connected = Emitter.Listener { if (continuation.isActive) continuation.resume(Unit) }
                        val failed = Emitter.Listener { args ->
                            val json = args.firstOrNull() as? JSONObject
                            val code = json?.optJSONObject("data")?.optString("code")?.takeIf { it.isNotBlank() }
                                ?: json?.optString("code")?.takeIf { it.isNotBlank() } ?: "CONNECTION_FAILED"
                            if (continuation.isActive) continuation.resumeWithException(AuthApiException(code))
                        }
                        client.once(Socket.EVENT_CONNECT, connected)
                        client.once(Socket.EVENT_CONNECT_ERROR, failed)
                        continuation.invokeOnCancellation { client.off(); client.disconnect() }
                        client.connect()
                    }
                }
            } catch (error: Exception) { disconnect(); throw error }
        }
    }

    override suspend fun join(roomId: String): RoomSnapshot {
        require(roomId == target.roomId)
        val data = emitAck("room:join", JSONObject().put("roomId", roomId))
        val room = data.getJSONObject("room")
        val members = data.getJSONArray("members")
        val messages = data.getJSONArray("recentMessages")
        return RoomSnapshot(decodeRoom(room),
            (0 until members.length()).map { index -> members.getJSONObject(index).let {
                MeetingMember(it.getString("userId"), it.getString("displayName"), Instant.parse(it.getString("joinedAt")))
            } },
            (0 until messages.length()).map { decodeMessage(messages.getJSONObject(it)) })
    }

    override suspend fun leave() { emitAck("room:leave", JSONObject()) }

    override suspend fun submit(submission: TranscriptSubmission): SubmissionAck {
        val payload = JSONObject().put("clientMsgId", submission.clientMsgId).put("text", submission.text).put("lang", submission.lang)
        submission.clientTs?.let { payload.put("clientTs", it.toString()) }
        val data = emitAck("transcript:submit", payload)
        return SubmissionAck(data.getString("messageId"), Instant.parse(data.getString("serverTs")), data.getBoolean("duplicate"))
    }

    private suspend fun emitAck(event: String, payload: JSONObject): JSONObject {
        val client = socket?.takeIf { it.connected() } ?: throw AuthApiException("DISCONNECTED")
        return withTimeout(5_000) {
            suspendCancellableCoroutine { continuation ->
                client.emit(event, payload, Ack { args ->
                    if (!continuation.isActive) return@Ack
                    try {
                        val response = args.firstOrNull() as? JSONObject ?: throw AuthApiException("INVALID_ACK")
                        if (!response.getBoolean("ok")) throw AuthApiException(response.getJSONObject("error").getString("code"))
                        continuation.resume(response.optJSONObject("data") ?: JSONObject())
                    } catch (error: Exception) { continuation.resumeWithException(error) }
                })
            }
        }
    }

    override suspend fun sync(roomId: String, lastMessageId: String?): MessageSync {
        require(roomId == target.roomId)
        val payload = JSONObject().put("roomId", roomId)
        lastMessageId?.let { payload.put("lastMessageId", it) }
        val data = emitAck("sync:since", payload)
        val messages = data.getJSONArray("messages")
        return MessageSync((0 until messages.length()).map { decodeMessage(messages.getJSONObject(it)) }, data.getBoolean("truncated"))
    }
    override fun observe(listener: (MeetingEvent) -> Unit) { observer = listener }
    override fun disconnect() { socket?.off(); socket?.disconnect(); socket = null }
}
