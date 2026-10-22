package dev.agentbridge.client

import kotlin.test.*
import dev.agentbridge.protocol.*
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.test.runTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*

class ClientTest {
    @Test fun discoveryRequiresExplicitVersion() { assertFails { BridgeJson.decodeFromString<RelayInfo>("{}") } }
    @Test fun enforceHttpsAndExplicitLoopbackException() {
        assertEquals("agents.example.com", relayBaseUrl("https://agents.example.com").host)
        assertFails { relayBaseUrl("http://127.0.0.1:8787") }
        assertEquals(8787, relayBaseUrl("http://127.0.0.1:8787", true).port)
        for (url in listOf("http://example.com", "http://127.0.0.1.evil.test", "ftp://localhost", "https://user:pass@example.com", "https://example.com?token=secret")) {
            assertFails(url) { relayBaseUrl(url, true) }
        }
    }
    @Test fun draftIdempotencyAndBoundedQueue() {
        val box = DraftOutbox(1)
        val draft = TextDraft("key", "s", "hello")
        box.enqueue(draft); box.enqueue(draft)
        assertEquals(listOf(draft), box.snapshot())
        assertFails { box.enqueue(draft.copy(text = "changed")) }
        assertFails { box.enqueue(draft.copy(idempotencyKey = "second")) }
        box.acknowledge("key")
        assertTrue(box.snapshot().isEmpty())
    }
    @Test fun sequenceGapDoesNotAdvanceCursor() {
        val cursor = SequenceCursor()
        assertEquals(SequenceResult.GAP, cursor.accept(2))
        assertEquals(0, cursor.lastSequence)
        assertEquals(SequenceResult.ACCEPTED, cursor.accept(1))
        assertEquals(SequenceResult.DUPLICATE, cursor.accept(1))
        assertEquals(SequenceResult.ACCEPTED, cursor.accept(2))
    }
    @Test fun discoveryNegotiatesAndRejectsIncompatibleServer() = runTest {
        var version = 1
        val http = HttpClient(MockEngine {
            assertEquals("/.well-known/agent-bridge", it.url.encodedPath)
            respond("""{"protocolVersions":[$version],"futureField":true}""", HttpStatusCode.OK)
        })
        try {
            val discovery = RelayDiscovery(http)
            assertEquals(listOf(1), discovery.inspect("https://example.com").protocolVersions)
            version = 2
            assertFails { discovery.inspect("https://example.com") }
        } finally { http.close() }
    }
}
