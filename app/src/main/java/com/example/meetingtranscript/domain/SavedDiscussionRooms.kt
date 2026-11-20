package com.example.meetingtranscript.domain

import java.util.UUID

/** A local bookmark, not proof of remote membership or a valid room code. */
data class SavedDiscussionRoom(
    val id: String = UUID.randomUUID().toString(),
    val code: String,
    val roomId: String? = null,
    val name: String? = null,
)

data class SavedDiscussionRooms(
    val entries: List<SavedDiscussionRoom> = emptyList(),
    val recentId: String? = null,
) {
    val recent: SavedDiscussionRoom? get() = entries.firstOrNull { it.id == recentId }

    fun remember(room: RemoteMeetingRoom): SavedDiscussionRooms {
        val existing = entries.firstOrNull { it.roomId == room.id } ?: entries.firstOrNull { it.code == room.code }
        val entry = existing?.copy(code = room.code, roomId = room.id, name = room.name)
            ?: SavedDiscussionRoom(code = room.code, roomId = room.id, name = room.name)
        return copy(entries = entries.filterNot { it.id == entry.id || it.code == room.code } + entry, recentId = entry.id)
    }
}

interface SavedDiscussionRoomRepository {
    suspend fun load(serverId: String, userId: String): SavedDiscussionRooms
    suspend fun save(serverId: String, userId: String, rooms: SavedDiscussionRooms)
}
