package com.example.meetingtranscript.data.platform

import android.content.Context
import com.example.meetingtranscript.domain.*
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

internal fun decodeMessage(json: JSONObject): MeetingMessage {
    val sender = json.optJSONObject("sender")
    val contents = json.optJSONArray("contents")
    return MeetingMessage(json.getString("id"), json.getString("roomId"),
        MessageSenderType.valueOf(json.getString("senderType")),
        sender?.let { MeetingMember(it.getString("userId"), it.getString("displayName")) },
        json.getString("text"), json.optionalString("lang"), json.instantOrNull("clientTs"),
        Instant.parse(json.getString("serverTs")), json.optString("contentType", "TEXT"),
        contents?.let { array -> (0 until array.length()).map { i -> array.getJSONObject(i).let {
            LocalizedMessageText(it.getString("lang"), it.getString("text"))
        } } }, json.optionalString("trigger"))
}

private fun encodeMessage(value: MeetingMessage): JSONObject = JSONObject().put("id", value.id).put("roomId", value.roomId)
    .put("senderType", value.senderType.name).put("text", value.text).put("contentType", value.contentType)
    .put("lang", value.lang).put("clientTs", value.clientTs?.toString()).put("serverTs", value.serverTs.toString())
    .put("trigger", value.trigger).put("sender", value.sender?.let { JSONObject().put("userId", it.userId).put("displayName", it.displayName) })
    .put("contents", value.contents?.let { JSONArray(it.map { text -> JSONObject().put("lang", text.lang).put("text", text.text) }) })

data class DiscussionCache(val messages: List<MeetingMessage> = emptyList(), val completeAnchor: String? = null)

/** Durable cache identity includes the original endpoint. Only a fully completed sync may move its anchor. */
class DiscussionMessageStore(context: Context? = null) {
    private val prefs = context?.applicationContext?.getSharedPreferences("remote_discussion_messages", Context.MODE_PRIVATE)
    private val memory = mutableMapOf<TranscriptDestination, DiscussionCache>()

    @Synchronized fun read(target: TranscriptDestination): DiscussionCache = memory.getOrPut(target) {
        val raw = prefs?.getString(key(target), null)
        if (raw == null) DiscussionCache() else {
            val json = JSONObject(raw)
            val items = json.getJSONArray("messages")
            DiscussionCache((0 until items.length()).map { decodeMessage(items.getJSONObject(it)) }, json.optionalString("anchor"))
        }
    }

    @Synchronized fun merge(target: TranscriptDestination, incoming: List<MeetingMessage>): DiscussionCache {
        val previous = read(target)
        val timeline = MessageTimeline(target.serverId, target.roomId)
        timeline.merge(previous.messages)
        return save(target, previous.copy(messages = timeline.merge(incoming)))
    }

    @Synchronized fun complete(target: TranscriptDestination): DiscussionCache {
        val previous = read(target)
        return save(target, previous.copy(completeAnchor = previous.messages.lastOrNull()?.id))
    }

    private fun save(target: TranscriptDestination, value: DiscussionCache): DiscussionCache {
        if (prefs != null) {
            val json = JSONObject().put("anchor", value.completeAnchor).put("messages", JSONArray(value.messages.map(::encodeMessage)))
            check(prefs.edit().putString(key(target), json.toString()).commit()) { "無法保存討論訊息。" }
        }
        memory[target] = value
        return value
    }

    private fun key(target: TranscriptDestination) = JSONArray(listOf(target.serverId, target.userId, target.roomId, target.baseUrl)).toString()
}
