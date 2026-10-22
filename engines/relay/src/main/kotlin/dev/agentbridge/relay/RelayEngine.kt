package dev.agentbridge.relay

import dev.agentbridge.protocol.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.util.concurrent.ConcurrentHashMap

/** M0 has no public bind switch. Authenticated E2EE is a prerequisite for one. */
data class RelayConfig(val bindHost: String = "127.0.0.1", val port: Int = 8787) {
    init {
        require(bindHost in setOf("127.0.0.1", "::1")) { "M0 relay is loopback-only" }
        require(port in 1..65535) { "Invalid port" }
    }
}

/** Explicit local-only fixture. Production enrollment must never use a shared bearer token. */
data class RelayDevelopmentAccess(
    val bearerToken: String,
    val allowedDeviceIds: Set<String>,
) {
    init {
        require(bearerToken.length >= 32) { "Development token must have at least 32 characters" }
        require(allowedDeviceIds.size >= 2 && allowedDeviceIds.all(String::isNotBlank))
    }
}

private class LocalEnvelopeRouter(private val allowedDeviceIds: Set<String>) {
    private val recipients = ConcurrentHashMap<String, Channel<EventEnvelope>>()

    fun connect(deviceId: String): Channel<EventEnvelope> {
        require(deviceId in allowedDeviceIds) { "Unknown development device" }
        val channel = Channel<EventEnvelope>(capacity = 64)
        check(recipients.putIfAbsent(deviceId, channel) == null) { "Device is already connected" }
        return channel
    }

    fun disconnect(deviceId: String, channel: Channel<EventEnvelope>) {
        if (recipients.remove(deviceId, channel)) channel.close()
    }

    suspend fun route(sender: String, envelope: EventEnvelope) {
        envelope.validate()
        require(envelope.senderDeviceId == sender) { "Envelope sender mismatch" }
        require(envelope.recipientDeviceId in allowedDeviceIds) { "Unknown envelope recipient" }
        checkNotNull(recipients[envelope.recipientDeviceId]) { "Recipient is offline" }.send(envelope)
    }
}

fun Application.bridgeRelay(developmentAccess: RelayDevelopmentAccess? = null) {
    install(ContentNegotiation) { json(BridgeJson) }
    install(WebSockets) {
        maxFrameSize = 1_100_000
    }
    val router = developmentAccess?.let { LocalEnvelopeRouter(it.allowedDeviceIds) }
    routing {
        get("/health") { call.respondText("ok", ContentType.Text.Plain) }
        get("/.well-known/agent-bridge") {
            call.respond(
                RelayInfo(
                    protocolVersions = listOf(PROTOCOL_VERSION),
                    stage = if (developmentAccess == null) "M0" else "M1-dev",
                ),
            )
        }
        route("/v1") {
            if (developmentAccess == null) {
                get("/events") { call.respond(HttpStatusCode.ServiceUnavailable, mapOf("code" to "PAIRING_E2EE_NOT_IMPLEMENTED")) }
            } else {
                webSocket("/events") {
                    val authorization = call.request.headers[HttpHeaders.Authorization]
                    if (authorization != "Bearer ${developmentAccess.bearerToken}") {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
                        return@webSocket
                    }
                    val deviceId = call.request.queryParameters["deviceId"]
                    if (deviceId == null || deviceId !in developmentAccess.allowedDeviceIds) {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unknown device"))
                        return@webSocket
                    }
                    val outbound = try {
                        checkNotNull(router).connect(deviceId)
                    } catch (_: IllegalStateException) {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Device already connected"))
                        return@webSocket
                    }
                    val sender = launch {
                        for (envelope in outbound) send(BridgeJson.encodeToString(envelope))
                    }
                    try {
                        for (frame in incoming) {
                            val text = (frame as? Frame.Text)?.readText() ?: continue
                            require(text.length <= 1_100_000) { "Frame too large" }
                            checkNotNull(router).route(deviceId, BridgeJson.decodeFromString(text))
                        }
                    } finally {
                        sender.cancel()
                        checkNotNull(router).disconnect(deviceId, outbound)
                    }
                }
            }
            post("/pairing") { call.respond(HttpStatusCode.Forbidden, mapOf("code" to "ENROLLMENT_DISABLED")) }
        }
    }
}
class RelayEngine(
    private val config: RelayConfig = RelayConfig(),
    private val developmentAccess: RelayDevelopmentAccess? = null,
) : AutoCloseable {
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    @Synchronized
    fun start(wait: Boolean = false) {
        check(server == null) { "Relay already running" }
        server = embeddedServer(CIO, host = config.bindHost, port = config.port) { bridgeRelay(developmentAccess) }
        server!!.start(wait)
    }
    @Synchronized
    override fun close() {
        server?.stop(500, 2_000)
        server = null
    }
}
