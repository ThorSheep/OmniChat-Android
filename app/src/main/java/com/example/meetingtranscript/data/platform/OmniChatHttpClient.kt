package com.example.meetingtranscript.data.platform

import com.example.meetingtranscript.domain.ServerProfileRepository
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Shared REST transport. Credentials never appear in redirects or diagnostic messages. */
class OmniChatHttpClient(private val profiles: ServerProfileRepository) {
    suspend fun request(
        serverId: String, path: String, method: String, body: JSONObject? = null, token: String? = null,
    ): JSONObject = withContext(Dispatchers.IO) {
        val profile = profiles.list().firstOrNull { it.id == serverId } ?: throw AuthApiException("SERVER_NOT_FOUND")
        val connection = URL("${normalizeServerUrl(profile.baseUrl)}$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val error = runCatching { JSONObject(raw) }.getOrNull()
                throw AuthApiException(error?.optString("code")?.takeIf { it.isNotBlank() } ?: "HTTP_$status",
                    connection.getHeaderField("x-request-id"))
            }
            if (status == 204) JSONObject() else JSONObject(raw)
        } finally { connection.disconnect() }
    }
}
