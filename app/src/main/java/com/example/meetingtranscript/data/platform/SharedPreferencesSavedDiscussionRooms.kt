package com.example.meetingtranscript.data.platform

import android.content.Context
import com.example.meetingtranscript.domain.SavedDiscussionRoom
import com.example.meetingtranscript.domain.SavedDiscussionRoomRepository
import com.example.meetingtranscript.domain.SavedDiscussionRooms
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class SharedPreferencesSavedDiscussionRooms(context: Context) : SavedDiscussionRoomRepository {
    private val preferences = context.getSharedPreferences("saved_remote_discussions", Context.MODE_PRIVATE)
    // A JSON tuple avoids collisions between arbitrary server and account identifiers.
    private fun key(serverId: String, userId: String) = JSONArray(listOf(serverId, userId)).toString()

    override suspend fun load(serverId: String, userId: String): SavedDiscussionRooms = withContext(Dispatchers.IO) {
        val raw = preferences.getString(key(serverId, userId), null) ?: return@withContext SavedDiscussionRooms()
        val json = JSONObject(raw)
        val entries = json.getJSONArray("entries")
        SavedDiscussionRooms(
            (0 until entries.length()).map { index ->
                val entry = entries.getJSONObject(index)
                SavedDiscussionRoom(entry.getString("id"), entry.getString("code"),
                    if (entry.isNull("roomId")) null else entry.getString("roomId"),
                    if (entry.isNull("name")) null else entry.getString("name"))
            },
            if (json.isNull("recentId")) null else json.getString("recentId"),
        )
    }

    override suspend fun save(serverId: String, userId: String, rooms: SavedDiscussionRooms) = withContext(Dispatchers.IO) {
        val json = JSONObject().put("recentId", rooms.recentId ?: JSONObject.NULL)
            .put("entries", JSONArray(rooms.entries.map { entry ->
                JSONObject().put("id", entry.id).put("code", entry.code)
                    .put("roomId", entry.roomId ?: JSONObject.NULL).put("name", entry.name ?: JSONObject.NULL)
            }))
        check(preferences.edit().putString(key(serverId, userId), json.toString()).commit()) { "Saved discussion storage failed" }
    }
}
