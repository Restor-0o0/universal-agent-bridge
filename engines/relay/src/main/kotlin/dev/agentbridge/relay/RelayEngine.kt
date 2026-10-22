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

data class RelayWorkspaceAccess(
    val password: String,
    val allowedDeviceIds: Set<String> = emptySet(),
) {
    init {
        require(password.length >= 12) { "Router password must have at least 12 characters" }
        require(allowedDeviceIds.all(String::isNotBlank))
    }
}

/** Password-protected workspaces isolate unrelated users on one Relay. */
data class RelayDevelopmentAccess(
    val workspaces: Map<String, RelayWorkspaceAccess>,
) {
    constructor(bearerToken: String, allowedDeviceIds: Set<String>) : this(
        mapOf("default" to RelayWorkspaceAccess(bearerToken, allowedDeviceIds)),
    )

    init {
        require(workspaces.isNotEmpty()) { "At least one Router workspace is required" }
        require(workspaces.keys.all(::validRouterId)) { "Invalid Router workspace id" }
    }

    fun workspace(id: String): RelayWorkspaceAccess? = workspaces[id]
}

private data class RouterDevice(
    val role: String,
    val name: String,
    val connectedAtMillis: Long,
    val channel: Channel<EventEnvelope>,
)

private class LocalEnvelopeRouter(private val access: RelayDevelopmentAccess) {
    private val recipients = ConcurrentHashMap<Pair<String, String>, RouterDevice>()

    fun connect(workspaceId: String, deviceId: String, role: String, name: String): Channel<EventEnvelope> {
        val workspace = checkNotNull(access.workspace(workspaceId)) { "Unknown workspace" }
        require(validRouterId(deviceId)) { "Invalid device id" }
        require(role in setOf("host", "client")) { "Invalid device role" }
        require(workspace.allowedDeviceIds.isEmpty() || deviceId in workspace.allowedDeviceIds) {
            "Unknown development device"
        }
        val channel = Channel<EventEnvelope>(capacity = 64)
        val device = RouterDevice(role, name.trim().take(80).ifBlank { deviceId }, System.currentTimeMillis(), channel)
        check(recipients.putIfAbsent(workspaceId to deviceId, device) == null) { "Device is already connected" }
        return channel
    }

    fun disconnect(workspaceId: String, deviceId: String, channel: Channel<EventEnvelope>) {
        val key = workspaceId to deviceId
        val device = recipients[key]
        if (device?.channel === channel && recipients.remove(key, device)) channel.close()
    }

    fun hosts(workspaceId: String): List<WireHost> = recipients.entries
        .asSequence()
        .filter { (key, device) -> key.first == workspaceId && device.role == "host" }
        .map { (key, device) -> WireHost(key.second, device.name, device.connectedAtMillis) }
        .sortedBy(WireHost::name)
        .toList()

    suspend fun route(workspaceId: String, sender: String, envelope: EventEnvelope) {
        envelope.validate()
        require(envelope.senderDeviceId == sender) { "Envelope sender mismatch" }
        val recipient = checkNotNull(recipients[workspaceId to envelope.recipientDeviceId]) {
            "Recipient is offline or belongs to another workspace"
        }
        recipient.channel.send(envelope)
    }
}

private fun validRouterId(value: String): Boolean =
    value.length in 1..64 && value.all { it.isLetterOrDigit() || it in "-_." }

fun Application.bridgeRelay(developmentAccess: RelayDevelopmentAccess? = null) {
    install(ContentNegotiation) { json(BridgeJson) }
    install(WebSockets) {
        maxFrameSize = 1_100_000
    }
    val router = developmentAccess?.let(::LocalEnvelopeRouter)
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
                get("/hosts") {
                    val workspaceId = call.request.queryParameters["workspaceId"] ?: "default"
                    val workspace = developmentAccess.workspace(workspaceId)
                    val authorization = call.request.headers[HttpHeaders.Authorization]
                    if (workspace == null || authorization != "Bearer ${workspace.password}") {
                        call.respond(HttpStatusCode.Unauthorized, mapOf("code" to "UNAUTHORIZED_WORKSPACE"))
                        return@get
                    }
                    call.respond(RelayHostList(workspaceId, checkNotNull(router).hosts(workspaceId)))
                }
                webSocket("/events") {
                    val workspaceId = call.request.queryParameters["workspaceId"] ?: "default"
                    val workspace = developmentAccess.workspace(workspaceId)
                    val authorization = call.request.headers[HttpHeaders.Authorization]
                    if (workspace == null || authorization != "Bearer ${workspace.password}") {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
                        return@webSocket
                    }
                    val deviceId = call.request.queryParameters["deviceId"]
                    val role = call.request.queryParameters["role"]
                        ?: if (deviceId == "host") "host" else "client"
                    val deviceName = call.request.queryParameters["name"] ?: deviceId.orEmpty()
                    if (deviceId == null || !validRouterId(deviceId)) {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unknown device"))
                        return@webSocket
                    }
                    val outbound = try {
                        checkNotNull(router).connect(workspaceId, deviceId, role, deviceName)
                    } catch (failure: IllegalArgumentException) {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, failure.message ?: "Invalid device"))
                        return@webSocket
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
                            checkNotNull(router).route(workspaceId, deviceId, BridgeJson.decodeFromString(text))
                        }
                    } finally {
                        sender.cancel()
                        checkNotNull(router).disconnect(workspaceId, deviceId, outbound)
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
