package dev.agentbridge.client

import dev.agentbridge.crypto.EnvelopeCipher
import dev.agentbridge.crypto.EnvelopeContext
import dev.agentbridge.protocol.BridgeJson
import dev.agentbridge.protocol.BridgePayload
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.takeFrom
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class EncryptedRelayConnection(
    private val http: HttpClient,
    baseUrl: String,
    private val bearerToken: String,
    val deviceId: String,
    private val cipher: EnvelopeCipher,
    private val keyEpoch: Int = 1,
) : AutoCloseable {
    private val endpoint = relayBaseUrl(baseUrl, allowLoopbackHttp = true)
        .toString()
        .trimEnd('/')
        .let { if (it.startsWith("https://")) "wss://${it.removePrefix("https://")}" else "ws://${it.removePrefix("http://")}" } +
        "/v1/events?deviceId=$deviceId"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val received = Channel<RoutedBridgePayload>(capacity = 64)
    private val writes = Mutex()
    private val sequence = AtomicLong(1)

    @Volatile private var socket: DefaultClientWebSocketSession? = null

    val routedIncoming: Flow<RoutedBridgePayload> = received.receiveAsFlow()
    val incoming: Flow<BridgePayload> = routedIncoming.map { it.payload }

    suspend fun connect() {
        check(socket == null) { "Relay connection already open" }
        val connected = http.webSocketSession {
            url.takeFrom(endpoint)
            header(HttpHeaders.Authorization, "Bearer $bearerToken")
        }
        socket = connected
        scope.launch {
            val failure = runCatching {
                for (frame in connected.incoming) {
                    val text = (frame as? Frame.Text)?.readText() ?: continue
                    require(text.length <= MAX_FRAME_CHARS) { "Relay frame too large" }
                    val envelope = BridgeJson.decodeFromString<dev.agentbridge.protocol.EventEnvelope>(text)
                    require(envelope.recipientDeviceId == deviceId) { "Envelope recipient mismatch" }
                    val plaintext = cipher.decrypt(envelope)
                    require(plaintext.size <= MAX_PAYLOAD_BYTES) { "Relay payload too large" }
                    received.send(
                        RoutedBridgePayload(
                            envelope.senderDeviceId,
                            BridgeJson.decodeFromString(
                            BridgePayload.serializer(),
                            plaintext.decodeToString(),
                            ),
                        ),
                    )
                }
            }.exceptionOrNull()
            received.close(failure)
        }
    }

    suspend fun send(recipientDeviceId: String, sessionId: String, payload: BridgePayload) {
        require(recipientDeviceId.isNotBlank() && sessionId.isNotBlank())
        val active = checkNotNull(socket) { "Relay connection is not open" }
        val plaintext = BridgeJson.encodeToString(BridgePayload.serializer(), payload).encodeToByteArray()
        require(plaintext.size <= MAX_PAYLOAD_BYTES) { "Relay payload too large" }
        val envelope = cipher.encrypt(
            EnvelopeContext(
                eventId = UUID.randomUUID().toString(),
                senderDeviceId = deviceId,
                recipientDeviceId = recipientDeviceId,
                sessionId = sessionId,
                sequence = sequence.getAndIncrement(),
                keyEpoch = keyEpoch,
            ),
            plaintext,
        )
        writes.withLock {
            active.send(Frame.Text(BridgeJson.encodeToString(envelope)))
        }
    }

    override fun close() {
        val active = socket
        socket = null
        active?.cancel()
        received.close()
        scope.cancel()
    }

    private companion object {
        const val MAX_PAYLOAD_BYTES = 1_000_000
        const val MAX_FRAME_CHARS = 1_100_000
    }
}
