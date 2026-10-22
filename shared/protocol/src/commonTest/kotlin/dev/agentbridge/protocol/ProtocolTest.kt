package dev.agentbridge.protocol

import kotlin.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

class ProtocolTest {
    private val envelope = EventEnvelope(eventId = "event-1", kind = "session.event",
        senderDeviceId = "host", recipientDeviceId = "phone", sessionId = "session-1",
        seq = 1, createdAt = "2026-10-17T00:00:00Z", ciphertext = "dGVzdA", nonce = "bm9uY2U", keyEpoch = 1)

    @Test fun envelopeRoundTrip() {
        envelope.validate()
        assertEquals(envelope, BridgeJson.decodeFromString<EventEnvelope>(BridgeJson.encodeToString(envelope)))
    }
    @Test fun rejectInvalidEnvelopeVersionSequenceAndPlainPayload() {
        assertFailsWith<IllegalArgumentException> { envelope.copy(v = 2).validate() }
        assertFailsWith<IllegalArgumentException> { envelope.copy(seq = 0).validate() }
        assertFailsWith<IllegalArgumentException> { envelope.copy(ciphertext = "").validate() }
        assertFailsWith<IllegalArgumentException> { envelope.copy(keyEpoch = 0).validate() }
    }
    @Test fun versionNegotiationRequiresIntersection() {
        assertEquals(2, negotiateVersion(setOf(1, 2), setOf(2, 3)))
        assertFailsWith<IllegalStateException> { negotiateVersion(setOf(1), setOf(2)) }
    }
    @Test fun helpAndActivityPayloadsRoundTrip() {
        val values: List<BridgePayload> = listOf(
            BridgePayload.AgentActivity("session-1", "working", "Running tests", 42),
            BridgePayload.HelpNeeded("approval-1", "session-1", "Allow command", 99),
            BridgePayload.CloseSession("close-1", "session-1"),
            BridgePayload.SessionClosed("close-1", "session-1"),
            BridgePayload.ResumeSession("resume-1", "session-1", "opencode", "C:\\projects"),
            BridgePayload.SaveTab(
                "save-1",
                SavedAgentTab(
                    "tab-1", "API", "C:\\projects", "opencode", "session-1", true,
                    listOf("Run tests", "Prepare summary"),
                ),
            ),
            BridgePayload.SavedTabList(
                "tabs-1",
                listOf(SavedAgentTab("tab-1", "API", "C:\\projects", "opencode", "session-1", true)),
            ),
            BridgePayload.DirectoryList(
                "directories-1",
                "C:\\projects",
                "C:\\",
                listOf(WireDirectory("bridge", "C:\\projects\\bridge")),
            ),
            BridgePayload.ExternalSessionList(
                "external-1",
                listOf(WireExternalSession("thread-1", "codex", "Codex · bridge", "C:\\projects\\bridge", 42, true)),
            ),
            BridgePayload.WatchExternalSession("watch-1", "codex", "thread-1"),
            BridgePayload.ExternalSessionSnapshot(
                "watch-1",
                WireExternalSession("thread-1", "codex", "Codex · bridge", "C:\\projects\\bridge", 43, true),
                "AGENT\nWorking",
            ),
            BridgePayload.StopWatchingExternalSession("stop-watch-1", "codex", "thread-1"),
        )
        values.forEach { value ->
            assertEquals(value, BridgeJson.decodeFromString<BridgePayload>(BridgeJson.encodeToString(value)))
        }
    }
    @Test fun oldSavedTabsLoadWithAnEmptyQueue() {
        val oldJson = """{"tabId":"tab-1","title":"API","workingDirectory":"C:/projects"}"""
        val tab = BridgeJson.decodeFromString<SavedAgentTab>(oldJson)
        assertEquals(emptyList(), tab.queuedMessages)
        assertFalse(tab.pinned)
    }
}
