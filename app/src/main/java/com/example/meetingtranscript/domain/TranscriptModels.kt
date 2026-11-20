package com.example.meetingtranscript.domain

import java.time.Instant
import java.util.UUID

enum class SyncStatus { PENDING, UPLOADING, SYNCED, FAILED }

data class TranscriptSegment(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val alternatives: List<String> = listOf(text),
    val language: String? = null,
    val startedAt: Instant,
    val endedAt: Instant,
    val confidence: Float? = null,
)

data class TranscriptSession(
    val id: String = UUID.randomUUID().toString(),
    val deviceId: String,
    val meetingRoomId: String? = null,
    val roomDisplayName: String? = null,
    val userDisplayName: String? = null,
    val serverUrl: String? = null,
    val locale: String = "zh-TW",
    val startedAt: Instant,
    val endedAt: Instant? = null,
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val segments: List<TranscriptSegment> = emptyList(),
)

/** A local test target. Authentication and server connectivity are added later. */
data class MeetingRoom(
    val id: String = UUID.randomUUID().toString(),
    val displayName: String,
    val userDisplayName: String = "使用者",
    val serverUrl: String,
    val createdAt: Instant = Instant.now(),
)
