package com.example.meetingtranscript.data.platform

import android.content.Context
import com.example.meetingtranscript.domain.MeetingRoom
import com.example.meetingtranscript.domain.MeetingRoomRepository
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Stores local testing targets only. No server request is made from this repository. */
class SharedPreferencesMeetingRoomRepository(context: Context) : MeetingRoomRepository {
    private val preferences = context.getSharedPreferences("meeting_rooms", Context.MODE_PRIVATE)

    override fun list(): List<MeetingRoom> = preferences.getString(ROOMS_KEY, "[]")
        ?.let(::JSONArray)
        ?.let { items -> (0 until items.length()).mapNotNull { index -> fromJson(items.optJSONObject(index)) } }
        ?.sortedByDescending { it.createdAt }
        .orEmpty()

    override fun save(room: MeetingRoom) {
        val rooms = list().filterNot { it.id == room.id } + room
        val json = JSONArray(rooms.map { item -> JSONObject().apply {
            put("id", item.id)
            put("displayName", item.displayName)
            put("userDisplayName", item.userDisplayName)
            put("serverUrl", item.serverUrl)
            put("createdAt", item.createdAt.toString())
        }})
        preferences.edit().putString(ROOMS_KEY, json.toString()).apply()
    }
    override fun delete(roomId: String) { write(list().filterNot { it.id == roomId }) }

    private fun write(rooms: List<MeetingRoom>) {
        val json = JSONArray(rooms.map { item -> JSONObject().apply {
            put("id", item.id); put("displayName", item.displayName); put("serverUrl", item.serverUrl); put("createdAt", item.createdAt.toString())
            put("userDisplayName", item.userDisplayName)
        }})
        preferences.edit().putString(ROOMS_KEY, json.toString()).apply()
    }

    private fun fromJson(json: JSONObject?): MeetingRoom? = json?.let {
        runCatching {
            MeetingRoom(
                id = it.getString("id"),
                displayName = it.getString("displayName"),
                userDisplayName = it.optString("userDisplayName", "使用者"),
                serverUrl = it.getString("serverUrl"),
                createdAt = Instant.parse(it.getString("createdAt")),
            )
        }.getOrNull()
    }

    private companion object { const val ROOMS_KEY = "rooms" }
}
