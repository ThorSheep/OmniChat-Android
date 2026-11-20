package com.example.meetingtranscript.data.platform

import android.content.Context
import com.example.meetingtranscript.domain.TranscriptRepository
import com.example.meetingtranscript.domain.TranscriptSegment
import com.example.meetingtranscript.domain.TranscriptSession
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Deliberately small local store for the MVP. It stores final text only, never raw audio.
 * Replace with a database implementation when history/search requirements grow.
 */
class SharedPreferencesTranscriptRepository(context: Context) : TranscriptRepository {
    private val preferences = context.getSharedPreferences("transcript_sessions", Context.MODE_PRIVATE)

    override fun create(session: TranscriptSession) = write(session)

    override fun appendSegment(sessionId: String, segment: TranscriptSegment) {
        val session = read(sessionId) ?: return
        write(session.copy(segments = session.segments + segment))
    }

    override fun finish(sessionId: String, endedAt: Instant) {
        val session = read(sessionId) ?: return
        write(session.copy(endedAt = endedAt))
    }

    fun latestSession(): TranscriptSession? = preferences.all.values
        .filterIsInstance<String>()
        .mapNotNull(::fromJson)
        .maxByOrNull { it.startedAt }

    fun history(): List<TranscriptSession> = preferences.all.values.filterIsInstance<String>()
        .mapNotNull(::fromJson).sortedByDescending { it.startedAt }

    private fun write(session: TranscriptSession) {
        preferences.edit().putString(session.id, session.toJson().toString()).apply()
    }

    private fun read(id: String): TranscriptSession? = preferences.getString(id, null)?.let(::fromJson)

    private fun TranscriptSession.toJson() = JSONObject().apply {
        put("clientTranscriptId", id)
        put("deviceId", deviceId)
        put("meetingRoomId", meetingRoomId)
        put("roomDisplayName", roomDisplayName)
        put("userDisplayName", userDisplayName)
        put("serverUrl", serverUrl)
        put("locale", locale)
        put("startedAt", startedAt.toString())
        put("endedAt", endedAt?.toString())
        put("syncStatus", syncStatus.name.lowercase())
        put("segments", JSONArray(segments.map { segment -> JSONObject().apply {
            put("segmentId", segment.id); put("text", segment.text)
            put("alternatives", JSONArray(segment.alternatives)); put("language", segment.language)
            put("startedAt", segment.startedAt.toString()); put("endedAt", segment.endedAt.toString())
            put("isFinal", true); put("confidence", segment.confidence)
        }}))
    }

    private fun fromJson(raw: String): TranscriptSession? = runCatching {
        val json = JSONObject(raw)
        val segments = json.getJSONArray("segments").let { items ->
            (0 until items.length()).map { index -> items.getJSONObject(index).let { item ->
                TranscriptSegment(
                    id = item.getString("segmentId"), text = item.getString("text"),
                    alternatives = item.getJSONArray("alternatives").let { alternatives ->
                        (0 until alternatives.length()).map(alternatives::getString)
                    }, language = item.optString("language").ifBlank { null },
                    startedAt = Instant.parse(item.getString("startedAt")), endedAt = Instant.parse(item.getString("endedAt")),
                    confidence = item.takeIf { it.has("confidence") && !it.isNull("confidence") }?.getDouble("confidence")?.toFloat(),
                )
            }}
        }
        TranscriptSession(
            id = json.getString("clientTranscriptId"), deviceId = json.getString("deviceId"),
            meetingRoomId = json.optString("meetingRoomId").ifBlank { null },
            roomDisplayName = json.optString("roomDisplayName").ifBlank { null },
            userDisplayName = json.optString("userDisplayName").ifBlank { null },
            serverUrl = json.optString("serverUrl").ifBlank { null },
            locale = json.getString("locale"), startedAt = Instant.parse(json.getString("startedAt")),
            endedAt = json.optString("endedAt").takeIf { it.isNotBlank() && it != "null" }?.let(Instant::parse),
            segments = segments,
        )
    }.getOrNull()
}
