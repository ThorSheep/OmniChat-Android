package com.example.meetingtranscript.domain

/** Platform audio route discovery. Speech recognition owns microphone capture in the MVP. */
interface AudioCaptureService {
    fun currentInputName(): String
}

interface SpeechToTextService {
    fun start()
    fun stop()
}

interface TranscriptRepository {
    fun create(session: TranscriptSession)
    fun appendSegment(sessionId: String, segment: TranscriptSegment)
    fun finish(sessionId: String, endedAt: java.time.Instant)
}

interface MeetingRoomRepository {
    fun list(): List<MeetingRoom>
    fun save(room: MeetingRoom)
    fun delete(roomId: String)
}

interface ServerProfileRepository {
    suspend fun list(): List<ServerProfile>
    suspend fun save(profile: ServerProfile)
    suspend fun delete(profileId: String)
}

/** Implementations own secure token storage and serialize refresh operations. */
interface AuthRepository {
    suspend fun register(serverId: String, email: String, password: String, displayName: String): AuthenticatedUser
    suspend fun rename(serverId: String, displayName: String): AuthState
    suspend fun changePassword(serverId: String, currentPassword: String, newPassword: String)
    suspend fun login(serverId: String, email: String, password: String): AuthState
    suspend fun refresh(serverId: String): AuthState
    suspend fun logout(serverId: String)
    suspend fun current(serverId: String): AuthState
}

interface MeetingRepository {
    suspend fun join(serverId: String, code: String): RemoteMeetingRoom
    suspend fun current(serverId: String): RemoteMeetingRoom?
    suspend fun leave(serverId: String, roomId: String)
    suspend fun history(serverId: String, cursor: String? = null): RemotePage<RemoteMeetingRoom>
    suspend fun messages(serverId: String, roomId: String, cursor: String? = null): RemotePage<MeetingMessage>
}

/** A platform adapter translates Socket.IO events to domain events. */
interface RealtimeMeetingClient {
    suspend fun connect(serverId: String)
    suspend fun join(roomId: String): RoomSnapshot
    suspend fun leave()
    suspend fun submit(submission: TranscriptSubmission): SubmissionAck
    suspend fun sync(roomId: String, lastMessageId: String?): MessageSync
    fun observe(listener: (MeetingEvent) -> Unit)
    fun disconnect()
}

interface RemoteDeliveryStore {
    fun all(): List<RemoteDeliveryRecord>
    fun write(record: RemoteDeliveryRecord)
}

interface TranscriptSubmissionRepository {
    suspend fun enqueue(submission: PendingTranscript)
    suspend fun retry(serverId: String, userId: String, roomId: String)
    suspend fun pending(serverId: String, userId: String): List<PendingTranscript>
}
