package com.example.meetingtranscript.data.platform

import android.content.Context
import com.example.meetingtranscript.domain.*
import java.time.Instant
import org.json.JSONObject
import org.json.JSONArray

/** Keeps original text and successful receipts; never stores credentials or raw audio. */
class RemoteTranscriptOutbox(context: Context) : RemoteDeliveryStore {
    private val preferences = context.getSharedPreferences("remote_transcript_deliveries", Context.MODE_PRIVATE)

    @Synchronized override fun all(): List<RemoteDeliveryRecord> = preferences.all.values.map { raw ->
        val json = JSONObject(raw as String)
        val ack = json.optJSONObject("ack")
        val alternatives = json.getJSONArray("alternatives")
        RemoteDeliveryRecord(
            TranscriptDestination(json.getString("serverId"), json.getString("userId"), json.getString("roomId"), json.getString("baseUrl")),
            TranscriptSegment(id = json.getString("id"), text = json.getString("text"),
                alternatives = (0 until alternatives.length()).map { alternatives.getString(it) },
                language = if (json.isNull("language")) null else json.getString("language"),
                startedAt = Instant.parse(json.getString("startedAt")), endedAt = Instant.parse(json.getString("endedAt")),
                confidence = if (json.isNull("confidence")) null else json.getDouble("confidence").toFloat()),
            SyncStatus.valueOf(json.getString("status")),
            ack?.let { SubmissionAck(it.getString("messageId"), Instant.parse(it.getString("serverTs")), it.getBoolean("duplicate")) },
            if (json.isNull("errorCode")) null else json.getString("errorCode"),
        )
    }.sortedWith(compareBy<RemoteDeliveryRecord> { it.segment.startedAt }.thenBy { it.segment.id })

    @Synchronized override fun write(record: RemoteDeliveryRecord) {
        val segment = record.segment
        val json = JSONObject().put("id", segment.id).put("serverId", record.target.serverId)
            .put("userId", record.target.userId).put("roomId", record.target.roomId).put("baseUrl", record.target.baseUrl)
            .put("text", segment.text).put("language", segment.language ?: JSONObject.NULL)
            .put("alternatives", JSONArray(segment.alternatives)).put("confidence", segment.confidence ?: JSONObject.NULL)
            .put("startedAt", segment.startedAt.toString()).put("endedAt", segment.endedAt.toString())
            .put("status", record.status.name).put("errorCode", record.errorCode ?: JSONObject.NULL)
        record.ack?.let { json.put("ack", JSONObject().put("messageId", it.messageId).put("serverTs", it.serverTs.toString()).put("duplicate", it.duplicate)) }
        // Include the full identity in the storage key, even though segment IDs are UUIDs.
        val key = JSONArray(listOf(record.target.serverId, record.target.userId, record.target.roomId, record.target.baseUrl, segment.id)).toString()
        check(preferences.edit().putString(key, json.toString()).commit()) { "Delivery storage failed" }
    }
}
