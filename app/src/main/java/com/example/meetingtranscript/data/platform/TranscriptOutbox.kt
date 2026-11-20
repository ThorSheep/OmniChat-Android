package com.example.meetingtranscript.data.platform

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Entries retain their original destination, even after a room is edited or deleted. */
class TranscriptOutbox(context: Context) {
    private val preferences = context.getSharedPreferences("transcript_outbox", Context.MODE_PRIVATE)
    fun enqueue(id: String, server: String, payload: String) {
        check(preferences.edit().putString(id, JSONObject().put("server", server).put("payload", payload).toString()).commit())
    }
    fun flush(): Int {
        preferences.all.forEach { (id, raw) ->
            runCatching {
                val entry = JSONObject(raw as String)
                val server = entry.getString("server")
                require(URL(server).protocol == "https")
                val connection = URL("${server.trimEnd('/')}/v1/transcripts").openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 10_000; connection.readTimeout = 10_000
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.outputStream.use { it.write(entry.getString("payload").toByteArray(Charsets.UTF_8)) }
                    if (connection.responseCode in 200..299) preferences.edit().remove(id).commit()
                } finally { connection.disconnect() }
            }
        }
        return preferences.all.size
    }
}
