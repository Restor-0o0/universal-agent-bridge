package dev.agentbridge.client

import dev.agentbridge.protocol.*
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.serialization.decodeFromString

/** Plain HTTP exists only for explicitly opted-in loopback development. */
fun relayBaseUrl(raw: String, allowLoopbackHttp: Boolean = false): Url {
    val url = Url(raw.trim())
    require(url.host.isNotBlank()) { "Укажите адрес сервера" }
    require(url.user == null && url.password == null) { "Не указывайте пароль в URL" }
    require(url.parameters.isEmpty() && url.fragment.isEmpty()) { "URL не должен содержать query или fragment" }
    require(url.encodedPath == "/" || url.encodedPath.isEmpty()) { "В M0 поддержан только корневой адрес Relay" }
    val local = url.host in setOf("localhost", "127.0.0.1", "[::1]", "::1")
    require(url.protocol == URLProtocol.HTTPS || (allowLoopbackHttp && local && url.protocol == URLProtocol.HTTP)) {
        "Нужен HTTPS URL с доверенным сертификатом"
    }
    return url
}

class RelayDiscovery(private val http: HttpClient, private val allowLoopbackHttp: Boolean = false) {
    suspend fun inspect(raw: String): RelayInfo {
        val base = relayBaseUrl(raw, allowLoopbackHttp)
        val response = http.get(base.toString().trimEnd('/') + "/.well-known/agent-bridge")
        require(response.status == HttpStatusCode.OK) { "Relay ответил HTTP ${response.status.value}" }
        val body = response.bodyAsText()
        require(body.length <= 16_384) { "Слишком большой ответ Relay" }
        val info = BridgeJson.decodeFromString<RelayInfo>(body)
        negotiateVersion(setOf(PROTOCOL_VERSION), info.protocolVersions.toSet())
        return info
    }
}
