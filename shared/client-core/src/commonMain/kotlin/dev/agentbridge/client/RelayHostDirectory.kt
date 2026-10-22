package dev.agentbridge.client

import dev.agentbridge.protocol.BridgeJson
import dev.agentbridge.protocol.RelayHostList
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.decodeFromString

class RelayHostDirectory(
    private val http: HttpClient,
    private val allowLoopbackHttp: Boolean,
) {
    suspend fun list(relayUrl: String, workspaceId: String, password: String): RelayHostList {
        require(validId(workspaceId)) { "Некорректное имя пространства" }
        require(password.length >= 12) { "Пароль Router должен содержать минимум 12 символов" }
        val base = relayBaseUrl(relayUrl, allowLoopbackHttp).toString().trimEnd('/')
        val response = http.get("$base/v1/hosts?workspaceId=$workspaceId") {
            header(HttpHeaders.Authorization, "Bearer $password")
        }
        check(response.status.isSuccess()) {
            if (response.status.value == 401) "Неверное пространство или пароль Router"
            else "Router вернул HTTP ${response.status.value}"
        }
        return BridgeJson.decodeFromString(response.bodyAsText())
    }

    private fun validId(value: String): Boolean =
        value.length in 1..64 && value.all { it.isLetterOrDigit() || it in "-_." }
}
