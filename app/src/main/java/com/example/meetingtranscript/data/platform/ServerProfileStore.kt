package com.example.meetingtranscript.data.platform

import android.content.Context
import com.example.meetingtranscript.domain.ServerProfile
import com.example.meetingtranscript.domain.ServerProfileRepository
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Local server identities never depend on a temporary tunnel hostname. */
interface ServerProfileSettings : ServerProfileRepository {
    fun selectedId(): String?
    fun select(id: String?)
}

class ServerProfileStore(context: Context) : ServerProfileSettings {
    private val preferences = context.getSharedPreferences("server_profiles", Context.MODE_PRIVATE)

    override suspend fun list(): List<ServerProfile> = withContext(Dispatchers.IO) {
        val array = JSONArray(preferences.getString("profiles", "[]"))
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            ServerProfile(item.getString("id"), item.getString("name"), item.getString("url"))
        }
    }

    override suspend fun save(profile: ServerProfile) {
        require(profile.displayName.isNotBlank()) { "請輸入伺服器名稱。" }
        val normalized = normalizeServerUrl(profile.baseUrl)
        write(list().filterNot { it.id == profile.id } + profile.copy(displayName = profile.displayName.trim(), baseUrl = normalized))
    }

    override suspend fun delete(profileId: String) {
        write(list().filterNot { it.id == profileId })
        if (selectedId() == profileId) select(null)
    }

    override fun selectedId(): String? = preferences.getString("selected", null)
    override fun select(id: String?) { check(preferences.edit().putString("selected", id).commit()) }

    private suspend fun write(profiles: List<ServerProfile>) = withContext(Dispatchers.IO) {
        val array = JSONArray(profiles.map { profile ->
            JSONObject().put("id", profile.id).put("name", profile.displayName).put("url", profile.baseUrl)
        })
        check(preferences.edit().putString("profiles", array.toString()).commit())
    }
}

fun normalizeServerUrl(value: String): String {
    val uri = runCatching { URI(value.trim().trimEnd('/')) }.getOrNull()
    require(uri != null && uri.scheme == "https" && !uri.host.isNullOrBlank() &&
        uri.userInfo == null && uri.query == null && uri.fragment == null &&
        (uri.port == -1 || uri.port in 1..65535)) { "請輸入有效的 HTTPS API 網址，不可包含帳密、查詢或片段。" }
    return uri.toASCIIString()
}
