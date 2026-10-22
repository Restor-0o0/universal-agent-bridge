package dev.agentbridge.relay

import dev.agentbridge.protocol.*
import io.ktor.client.request.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.ktor.websocket.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.*

class RelayTest {
    @Test fun diagnosticRoutesAndDisabledEnrollment() = testApplication {
        application { bridgeRelay() }
        assertEquals("ok", client.get("/health").bodyAsText())
        val info = BridgeJson.decodeFromString<RelayInfo>(client.get("/.well-known/agent-bridge").bodyAsText())
        assertEquals(listOf(1), info.protocolVersions)
        assertFalse(info.enrollmentEnabled)
        assertFalse(info.encryptedTransportReady)
        assertEquals(HttpStatusCode.Forbidden, client.post("/v1/pairing").status)
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/v1/events").status)
    }
    @Test fun cannotBindPublicInterface() {
        for (host in listOf("0.0.0.0", "::", "192.168.1.4", "example.com")) {
            assertFailsWith<IllegalArgumentException> { RelayConfig(bindHost = host) }
        }
        assertFails { RelayConfig(port = 0) }
        assertEquals("127.0.0.1", RelayConfig().bindHost)
    }

    @Test fun developmentSocketAuthenticatesAndRoutesOpaqueEnvelope() = testApplication {
        val token = "0123456789abcdef0123456789abcdef"
        application {
            bridgeRelay(RelayDevelopmentAccess(token, setOf("host", "desktop", "android")))
        }
        val socketClient = createClient { install(WebSockets) }
        val host = socketClient.webSocketSession {
            url("/v1/events?deviceId=host")
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val desktop = socketClient.webSocketSession {
            url("/v1/events?deviceId=desktop")
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val android = socketClient.webSocketSession {
            url("/v1/events?deviceId=android")
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val envelope = EventEnvelope(
            eventId = "event-1",
            kind = "session.event",
            senderDeviceId = "desktop",
            recipientDeviceId = "host",
            sessionId = "control",
            seq = 1,
            createdAt = "2026-10-18T00:00:00Z",
            ciphertext = "opaque-ciphertext",
            nonce = "opaque-nonce",
            keyEpoch = 1,
        )
        desktop.send(Frame.Text(BridgeJson.encodeToString(envelope)))
        val received = BridgeJson.decodeFromString<EventEnvelope>((host.incoming.receive() as Frame.Text).readText())
        assertEquals(envelope, received)
        val notification = envelope.copy(
            eventId = "event-2",
            senderDeviceId = "host",
            recipientDeviceId = "android",
            sessionId = "session-1",
            seq = 2,
        )
        host.send(Frame.Text(BridgeJson.encodeToString(notification)))
        val androidReceived = BridgeJson.decodeFromString<EventEnvelope>(
            (android.incoming.receive() as Frame.Text).readText(),
        )
        assertEquals(notification, androidReceived)
        android.close()
        desktop.close()
        host.close()
    }

    @Test fun developmentSocketRejectsMissingToken() = testApplication {
        application {
            bridgeRelay(
                RelayDevelopmentAccess(
                    "0123456789abcdef0123456789abcdef",
                    setOf("host", "desktop"),
                ),
            )
        }
        val socketClient = createClient { install(WebSockets) }
        val session = socketClient.webSocketSession { url("/v1/events?deviceId=host") }
        assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, session.closeReason.await()?.code)
    }

    @Test fun workspacesHaveSeparatePasswordsHostListsAndRouting() = testApplication {
        val alicePassword = "alice-password-0123456789"
        val bobPassword = "bob-password-012345678901"
        application {
            bridgeRelay(
                RelayDevelopmentAccess(
                    mapOf(
                        "alice" to RelayWorkspaceAccess(alicePassword),
                        "bob" to RelayWorkspaceAccess(bobPassword),
                    ),
                ),
            )
        }
        val socketClient = createClient { install(WebSockets) }
        val aliceHost = socketClient.webSocketSession {
            url("/v1/events?workspaceId=alice&deviceId=laptop&role=host")
            header(HttpHeaders.Authorization, "Bearer $alicePassword")
        }
        val bobHost = socketClient.webSocketSession {
            url("/v1/events?workspaceId=bob&deviceId=laptop&role=host")
            header(HttpHeaders.Authorization, "Bearer $bobPassword")
        }
        val aliceClient = socketClient.webSocketSession {
            url("/v1/events?workspaceId=alice&deviceId=phone&role=client")
            header(HttpHeaders.Authorization, "Bearer $alicePassword")
        }
        val bobClient = socketClient.webSocketSession {
            url("/v1/events?workspaceId=bob&deviceId=phone&role=client")
            header(HttpHeaders.Authorization, "Bearer $bobPassword")
        }

        val aliceHostsResponse = client.get("/v1/hosts?workspaceId=alice") {
            header(HttpHeaders.Authorization, "Bearer $alicePassword")
        }
        assertEquals(HttpStatusCode.OK, aliceHostsResponse.status)
        val aliceHosts = BridgeJson.decodeFromString<RelayHostList>(aliceHostsResponse.bodyAsText())
        assertEquals(listOf("laptop"), aliceHosts.hosts.map(WireHost::id))
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/v1/hosts?workspaceId=alice") {
                header(HttpHeaders.Authorization, "Bearer $bobPassword")
            }.status,
        )

        fun envelope(sender: String, text: String) = EventEnvelope(
            eventId = "event-$text",
            kind = "session.event",
            senderDeviceId = sender,
            recipientDeviceId = "laptop",
            sessionId = "control",
            seq = 1,
            createdAt = "2026-10-22T00:00:00Z",
            ciphertext = text,
            nonce = "nonce-$text",
            keyEpoch = 1,
        )
        aliceClient.send(Frame.Text(BridgeJson.encodeToString(envelope("phone", "alice"))))
        bobClient.send(Frame.Text(BridgeJson.encodeToString(envelope("phone", "bob"))))
        val aliceReceived = BridgeJson.decodeFromString<EventEnvelope>((aliceHost.incoming.receive() as Frame.Text).readText())
        val bobReceived = BridgeJson.decodeFromString<EventEnvelope>((bobHost.incoming.receive() as Frame.Text).readText())
        assertEquals("alice", aliceReceived.ciphertext)
        assertEquals("bob", bobReceived.ciphertext)

        aliceClient.close(); bobClient.close(); aliceHost.close(); bobHost.close()
    }
}
