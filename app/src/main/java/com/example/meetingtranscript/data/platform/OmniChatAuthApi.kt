package com.example.meetingtranscript.data.platform

import com.example.meetingtranscript.domain.AccountStatus
import com.example.meetingtranscript.domain.AuthenticatedUser
import com.example.meetingtranscript.domain.UserRole
import java.time.Instant
import org.json.JSONObject

class OmniChatAuthApi(
    private val http: OmniChatHttpClient,
    private val model: String,
    private val appVersion: String,
) : AuthApi {
    override suspend fun register(serverId: String, email: String, password: String, displayName: String): AuthenticatedUser =
        user(request(serverId, "/auth/register", "POST", JSONObject().put("email", email).put("password", password).put("displayName", displayName)))

    override suspend fun rename(serverId: String, accessToken: String, displayName: String): AuthenticatedUser =
        user(request(serverId, "/me", "PATCH", JSONObject().put("displayName", displayName), accessToken))

    override suspend fun changePassword(serverId: String, accessToken: String, currentPassword: String, newPassword: String) {
        request(serverId, "/me/password", "POST", JSONObject().put("currentPassword", currentPassword).put("newPassword", newPassword), accessToken)
    }
    override suspend fun login(serverId: String, email: String, password: String): LoginTokens = tokens(request(
        serverId, "/auth/login", "POST", JSONObject().put("email", email).put("password", password)
            .put("clientType", "APP").put("deviceInfo", JSONObject().put("platform", "android")
                .put("model", model.take(100)).put("appVersion", appVersion.take(50))),
    ))

    override suspend fun refresh(serverId: String, refreshToken: String): LoginTokens = tokens(request(
        serverId, "/auth/refresh", "POST", JSONObject().put("refreshToken", refreshToken),
    ))

    override suspend fun me(serverId: String, accessToken: String): AuthenticatedUser = user(
        request(serverId, "/me", "GET", token = accessToken),
    )

    override suspend fun logout(serverId: String, accessToken: String) {
        request(serverId, "/auth/logout", "POST", token = accessToken)
    }

    private suspend fun request(
        serverId: String, path: String, method: String, body: JSONObject? = null, token: String? = null,
    ): JSONObject = http.request(serverId, path, method, body, token)

    private fun tokens(json: JSONObject): LoginTokens = LoginTokens(
        json.getString("accessToken"), json.getString("refreshToken"), json.getLong("expiresIn"), user(json.getJSONObject("user")),
    )

    private fun user(json: JSONObject) = AuthenticatedUser(
        json.getString("id"), json.getString("email"), json.getString("displayName"),
        UserRole.valueOf(json.getString("role")), AccountStatus.valueOf(json.getString("status")),
        Instant.parse(json.getString("createdAt")),
    )
}
