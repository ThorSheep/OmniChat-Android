package com.example.meetingtranscript.data.platform

import android.content.Context
import android.os.Build
import com.example.meetingtranscript.BuildConfig
import com.example.meetingtranscript.domain.AuthState

/** Shared across Activity recreation; no Activity context or password is retained. */
class AccountServices private constructor(context: Context) {
    val profiles = ServerProfileStore(context.applicationContext)
    private val http = OmniChatHttpClient(profiles)
    val auth = RemoteAuthRepository(
        OmniChatAuthApi(http, Build.MODEL, BuildConfig.VERSION_NAME),
        KeystoreRefreshTokenStore(context.applicationContext),
    )
    val meetings = RemoteMeetingRepository(http, auth)
    val savedRooms = SharedPreferencesSavedDiscussionRooms(context.applicationContext)
    val messageStore = DiscussionMessageStore(context.applicationContext)
    val discussion = RemoteTranscriptSubmissionRepository(
        RemoteTranscriptOutbox(context.applicationContext),
        { target -> SocketIoMeetingClient(target, profiles, auth) },
        { target ->
            val profile = profiles.list().firstOrNull { it.id == target.serverId } ?: throw AuthApiException("SERVER_CHANGED")
            if (normalizeServerUrl(profile.baseUrl) != target.baseUrl) throw AuthApiException("SERVER_CHANGED")
            val current = auth.current(target.serverId) as? AuthState.SignedIn ?: throw AuthApiException("UNAUTHORIZED")
            if (current.user.id != target.userId) throw AuthApiException("ACCOUNT_CHANGED")
            if (meetings.current(target.serverId)?.id != target.roomId) throw AuthApiException("NOT_ROOM_MEMBER")
        },
        { server -> auth.revoke(server) },
        messageStore = messageStore,
        loadMessages = { target, cursor ->
            val profile = profiles.list().firstOrNull { it.id == target.serverId } ?: throw AuthApiException("SERVER_CHANGED")
            if (normalizeServerUrl(profile.baseUrl) != target.baseUrl) throw AuthApiException("SERVER_CHANGED")
            auth.authorized(target.serverId, target.userId) { Unit }
            meetings.messages(target.serverId, target.roomId, cursor)
        },
    )

    companion object {
        @Volatile private var instance: AccountServices? = null
        fun get(context: Context): AccountServices = instance ?: synchronized(this) {
            instance ?: AccountServices(context).also { instance = it }
        }
    }
}
