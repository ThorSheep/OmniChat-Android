package com.example.meetingtranscript.data.platform

import com.example.meetingtranscript.domain.*
import java.time.Instant
import java.util.Locale
import java.util.UUID
import org.json.JSONObject

fun normalizeRoomCode(input: String): String {
    val code = input.trim().uppercase(Locale.ROOT)
    require(Regex("^[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{6}$").matches(code)) {
        "請輸入有效的六碼討論室代碼（不含 0、O、1、I、L）。"
    }
    return code
}

class RemoteMeetingRepository(
    private val http: OmniChatHttpClient,
    private val auth: RemoteAuthRepository,
) : MeetingRepository {
    override suspend fun join(serverId: String, code: String): RemoteMeetingRoom {
        val payload = JSONObject().put("code", normalizeRoomCode(code))
        val response = auth.authorized(serverId) { token -> http.request(serverId, "/rooms/join", "POST", payload, token) }
        return requireNotNull(membership(response)) { "伺服器未回傳討論室。" }
    }

    override suspend fun current(serverId: String): RemoteMeetingRoom? = auth.authorized(serverId) { token ->
        membership(http.request(serverId, "/me/room", "GET", token = token))
    }

    override suspend fun leave(serverId: String, roomId: String) {
        val id = UUID.fromString(roomId).toString()
        auth.authorized(serverId) { token -> http.request(serverId, "/rooms/$id/leave", "POST", token = token) }
    }

    override suspend fun history(serverId: String, cursor: String?): RemotePage<RemoteMeetingRoom> = auth.authorized(serverId) { token ->
        val json = http.request(serverId, "/history/rooms?limit=50${cursorQuery(cursor)}", "GET", token = token)
        val items = json.getJSONArray("items")
        RemotePage((0 until items.length()).map { decodeRoom(items.getJSONObject(it)) }, json.optionalString("nextCursor"))
    }

    override suspend fun messages(serverId: String, roomId: String, cursor: String?): RemotePage<MeetingMessage> = auth.authorized(serverId) { token ->
        val id = UUID.fromString(roomId).toString()
        val json = http.request(serverId, "/rooms/$id/messages?limit=200${cursorQuery(cursor)}", "GET", token = token)
        val items = json.getJSONArray("items")
        RemotePage((0 until items.length()).map { decodeMessage(items.getJSONObject(it)) }, json.optionalString("nextCursor"))
    }

    private fun cursorQuery(cursor: String?) = cursor?.let { "&cursor=" + java.net.URLEncoder.encode(it, "UTF-8") }.orEmpty()

    private fun membership(json: JSONObject): RemoteMeetingRoom? {
        if (json.isNull("room")) return null
        return decodeRoom(json.getJSONObject("room")).copy(joinedAt = json.instantOrNull("joinedAt"))
    }

}

internal fun decodeRoom(room: JSONObject): RemoteMeetingRoom = RemoteMeetingRoom(
    id = room.getString("id"), code = room.getString("code"), name = room.getString("name"),
    status = RemoteRoomStatus.valueOf(room.getString("status")),
    createdAt = Instant.parse(room.getString("createdAt")), closedAt = room.instantOrNull("closedAt"),
    ownerId = room.optionalString("ownerId"), settings = room.optJSONObject("settings")?.let(::decodeRoomSettings),
)

    private fun decodeRoomSettings(json: JSONObject): RemoteRoomSettings {
        val ai = json.getJSONObject("ai")
        val proactive = ai.getJSONObject("proactive")
        val wakeWords = ai.getJSONArray("wakeWords")
        val languages = json.getJSONArray("languages")
        val voices = json.getJSONObject("voices")
        return RemoteRoomSettings(
            ai = AiSettings(ai.getBoolean("enabled"), ai.getString("name"),
                (0 until wakeWords.length()).map { wakeWords.getString(it) }, ai.getBoolean("respondOnMention"),
                ai.getString("persona"), AiProactiveSettings(proactive.getBoolean("enabled"),
                    proactive.getInt("quietSeconds"), proactive.getInt("minMessages"), proactive.getInt("cooldownSeconds"))),
            languages = (0 until languages.length()).map { languages.getString(it) },
            voices = voices.keys().asSequence().associateWith { lang ->
                val voice = voices.getJSONObject(lang)
                VoiceSettings(voice.getString("provider"), voice.getString("voice"))
            },
        )
    }

internal fun JSONObject.optionalString(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)
internal fun JSONObject.instantOrNull(key: String): Instant? = optionalString(key)?.let(Instant::parse)
