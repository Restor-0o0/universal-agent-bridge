package dev.agentbridge.host

import dev.agentbridge.domain.ApprovalDecision
import dev.agentbridge.domain.ApprovalRequest
import dev.agentbridge.domain.Capability
import dev.agentbridge.domain.SessionDescriptor
import dev.agentbridge.domain.SessionMode
import dev.agentbridge.protocol.BridgePayload
import dev.agentbridge.protocol.SavedAgentTab
import dev.agentbridge.spi.AgentAdapter
import dev.agentbridge.spi.AgentEvent
import dev.agentbridge.spi.AgentInstallation
import dev.agentbridge.spi.Attachment
import dev.agentbridge.spi.HarnessProvider
import dev.agentbridge.spi.ManagedStart
import dev.agentbridge.spi.ManagedTransport
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.io.path.createTempDirectory
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class ManagedAgentHostTest {
    @Test
    fun resumesExistingManagedSessionWithoutDuplicatingIt() = runBlocking {
        val adapter = FakeAdapter()
        val link = FakeLink()
        val host = ManagedAgentHost(
            HarnessRegistry(listOf(FakeProvider(adapter)), ExecutableResolver { "/test/codex" }),
            link,
        )
        val job = launch { host.run() }
        try {
            link.clientCommands.send(BridgePayload.StartSession("start-resume", "project"))
            val original = withTimeout(2_000) { assertIs<BridgePayload.SessionStarted>(link.hostEvents.receive().first) }
            link.hostEvents.receive() // ready

            link.clientCommands.send(BridgePayload.ResumeSession("resume", original.sessionId, "codex", "project"))
            val resumed = withTimeout(2_000) { assertIs<BridgePayload.SessionStarted>(link.hostEvents.receive().first) }
            assertEquals(original.sessionId, resumed.sessionId)
            assertTrue(resumed.resumed)
            assertEquals(1, adapter.listSessions().size)
        } finally {
            host.dispose()
            job.cancel()
        }
    }

    @Test
    fun persistsSavedTabsOnTheHost() = runBlocking {
        val root = createTempDirectory("agent-bridge-tabs")
        val tabsFile = root.resolve("tabs.json")
        val registry = HarnessRegistry(listOf(FakeProvider(FakeAdapter())), ExecutableResolver { "/test/codex" })
        val firstLink = FakeLink()
        val firstHost = ManagedAgentHost(registry, firstLink, savedTabsPath = tabsFile)
        val firstJob = launch { firstHost.run() }
        val tab = SavedAgentTab("tab-1", "Backend", root.toString(), "codex", "session-old", true)
        try {
            firstLink.clientCommands.send(BridgePayload.SaveTab("save-tab", tab))
            val saved = withTimeout(2_000) { assertIs<BridgePayload.SavedTabList>(firstLink.hostEvents.receive().first) }
            assertEquals(listOf(tab), saved.tabs)
        } finally {
            firstHost.dispose()
            firstJob.cancel()
        }

        val secondLink = FakeLink()
        val secondHost = ManagedAgentHost(registry, secondLink, savedTabsPath = tabsFile)
        val secondJob = launch { secondHost.run() }
        try {
            secondLink.clientCommands.send(BridgePayload.ListSavedTabs("list-tabs"))
            val restored = withTimeout(2_000) { assertIs<BridgePayload.SavedTabList>(secondLink.hostEvents.receive().first) }
            assertEquals(listOf(tab), restored.tabs)
        } finally {
            secondHost.dispose()
            secondJob.cancel()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun autoSelectsAdapterAndNormalizesSessionEvents() = runBlocking {
        val adapter = FakeAdapter()
        val provider = FakeProvider(adapter)
        val registry = HarnessRegistry(listOf(provider), ExecutableResolver { "/test/codex" })
        val link = FakeLink()
        val host = ManagedAgentHost(registry, link)
        val job = launch { host.run() }

        link.clientCommands.send(BridgePayload.StartSession("start-1", "."))
        val started = withTimeout(2_000) { assertIs<BridgePayload.SessionStarted>(link.hostEvents.receive().first) }
        assertEquals("codex", started.agentId)
        val ready = withTimeout(2_000) { assertIs<BridgePayload.AgentActivity>(link.hostEvents.receive().first) }
        assertEquals("ready", ready.state)

        link.clientCommands.send(BridgePayload.SendText("send-1", started.sessionId, "hello", "key-1"))
        val working = withTimeout(2_000) { assertIs<BridgePayload.AgentActivity>(link.hostEvents.receive().first) }
        assertEquals("working", working.state)
        val delta = withTimeout(2_000) { assertIs<BridgePayload.TextDelta>(link.hostEvents.receive().first) }
        assertEquals("echo:hello", delta.text)
        val finished = withTimeout(2_000) { assertIs<BridgePayload.TurnFinished>(link.hostEvents.receive().first) }
        assertEquals(true, finished.successful)
        val idle = withTimeout(2_000) { assertIs<BridgePayload.AgentActivity>(link.hostEvents.receive().first) }
        assertEquals("idle", idle.state)

        host.dispose()
        job.cancel()
    }

    @Test
    fun emitsHelpSignalAndWaitingActivityForApproval() = runBlocking {
        val adapter = FakeAdapter()
        val link = FakeLink()
        val host = ManagedAgentHost(
            HarnessRegistry(listOf(FakeProvider(adapter)), ExecutableResolver { "/test/codex" }),
            link,
            clockMillis = { 123L },
        )
        val job = launch { host.run() }

        link.clientCommands.send(BridgePayload.StartSession("start-help", "."))
        val started = withTimeout(2_000) { assertIs<BridgePayload.SessionStarted>(link.hostEvents.receive().first) }
        link.hostEvents.receive() // ready
        adapter.requestApproval()

        val approval = withTimeout(2_000) { assertIs<BridgePayload.ApprovalRequested>(link.hostEvents.receive().first) }
        val help = withTimeout(2_000) { assertIs<BridgePayload.HelpNeeded>(link.hostEvents.receive().first) }
        val waiting = withTimeout(2_000) { assertIs<BridgePayload.AgentActivity>(link.hostEvents.receive().first) }
        assertEquals(started.sessionId, approval.sessionId)
        assertEquals(approval.approvalId, help.helpId)
        assertEquals("waiting_for_approval", waiting.state)
        assertEquals(123L, waiting.createdAtMillis)

        host.dispose()
        job.cancel()
    }

    @Test
    fun returnsAgentInventoryWithoutHarnessCommandsInClientRequest() = runBlocking {
        val registry = HarnessRegistry(
            listOf(FakeProvider(FakeAdapter())),
            ExecutableResolver { "/test/codex" },
        )
        val link = FakeLink()
        val host = ManagedAgentHost(registry, link)
        val job = launch { host.run() }

        link.clientCommands.send(BridgePayload.ListAgents("list-1"))
        val result = withTimeout(2_000) { assertIs<BridgePayload.AgentList>(link.hostEvents.receive().first) }
        assertEquals(listOf("codex"), result.agents.map { it.id })
        assertEquals(true, result.agents.single().available)

        host.dispose()
        job.cancel()
    }

    @Test
    fun listsHostDirectoriesForRemotePicker() = runBlocking {
        val root = createTempDirectory("agent-bridge-picker")
        Files.createDirectories(root.resolve("Zulu"))
        Files.createDirectories(root.resolve("alpha"))
        Files.writeString(root.resolve("not-a-directory.txt"), "ignored")
        val link = FakeLink()
        val host = ManagedAgentHost(
            HarnessRegistry(listOf(FakeProvider(FakeAdapter())), ExecutableResolver { "/test/codex" }),
            link,
            defaultWorkingDirectory = root,
        )
        val job = launch { host.run() }
        try {
            link.clientCommands.send(BridgePayload.ListDirectories("directories-1"))
            val result = withTimeout(2_000) {
                assertIs<BridgePayload.DirectoryList>(link.hostEvents.receive().first)
            }
            assertEquals(root.toString(), result.current)
            assertEquals(listOf("alpha", "Zulu"), result.directories.map { it.name })
        } finally {
            host.dispose()
            job.cancel()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun listsAndWatchesAnExternalCodexSessionReadOnly() = runBlocking {
        val home = createTempDirectory("agent-bridge-external-host")
        val history = home.resolve(".codex/sessions/2026/10/21/rollout-test.jsonl")
        Files.createDirectories(history.parent)
        Files.writeString(
            history,
            """{"type":"session_meta","payload":{"id":"external-1","cwd":"C:\\work\\alpha"}}""" + "\n" +
                """{"type":"response_item","payload":{"type":"message","role":"assistant","content":[{"type":"output_text","text":"still working"}]}}""" + "\n",
        )
        Files.setLastModifiedTime(history, java.nio.file.attribute.FileTime.fromMillis(99_000))
        val link = FakeLink()
        val host = ManagedAgentHost(
            HarnessRegistry(listOf(FakeProvider(FakeAdapter())), ExecutableResolver { "/test/codex" }),
            link,
            externalScanner = ExternalSessionScanner(home) { 100_000 },
        )
        val job = launch { host.run() }
        try {
            link.clientCommands.send(BridgePayload.ListExternalSessions("list-external"))
            val listed = withTimeout(2_000) {
                assertIs<BridgePayload.ExternalSessionList>(link.hostEvents.receive().first)
            }
            val session = listed.sessions.single()
            assertEquals("external-1", session.id)
            assertTrue(session.active)

            link.clientCommands.send(BridgePayload.WatchExternalSession("watch-external", "codex", session.id))
            val (snapshotPayload, routedSession) = withTimeout(2_000) { link.hostEvents.receive() }
            val snapshot = assertIs<BridgePayload.ExternalSessionSnapshot>(snapshotPayload)
            assertEquals(session.id, routedSession)
            assertTrue("AGENT\nstill working" in snapshot.transcript)
        } finally {
            host.dispose()
            job.cancel()
            home.toFile().deleteRecursively()
        }
    }

    @Test
    fun keepsMultipleSessionsIndependentAndClosesOnlyRequestedTab() = runBlocking {
        val adapter = FakeAdapter()
        val link = FakeLink()
        val host = ManagedAgentHost(
            HarnessRegistry(listOf(FakeProvider(adapter)), ExecutableResolver { "/test/codex" }),
            link,
        )
        val job = launch { host.run() }
        try {
            link.clientCommands.send(BridgePayload.StartSession("start-a", "project-a"))
            val first = withTimeout(2_000) { assertIs<BridgePayload.SessionStarted>(link.hostEvents.receive().first) }
            link.hostEvents.receive() // ready
            link.clientCommands.send(BridgePayload.StartSession("start-b", "project-b"))
            val second = withTimeout(2_000) { assertIs<BridgePayload.SessionStarted>(link.hostEvents.receive().first) }
            link.hostEvents.receive() // ready
            assertTrue(first.sessionId != second.sessionId)

            link.clientCommands.send(BridgePayload.SendText("send-b", second.sessionId, "second", "key-b"))
            link.hostEvents.receive() // working
            val delta = withTimeout(2_000) { assertIs<BridgePayload.TextDelta>(link.hostEvents.receive().first) }
            assertEquals(second.sessionId, delta.sessionId)
            link.hostEvents.receive() // finished
            link.hostEvents.receive() // idle

            link.clientCommands.send(BridgePayload.CloseSession("close-a", first.sessionId))
            val closed = withTimeout(2_000) { assertIs<BridgePayload.SessionClosed>(link.hostEvents.receive().first) }
            assertEquals(first.sessionId, closed.sessionId)
            assertEquals(listOf(first.sessionId), adapter.closedSessions)
        } finally {
            host.dispose()
            job.cancel()
        }
    }

    private class FakeLink : HostRelayLink {
        val clientCommands = Channel<BridgePayload>(Channel.UNLIMITED)
        val hostEvents = Channel<Pair<BridgePayload, String>>(Channel.UNLIMITED)
        override val incoming: Flow<BridgePayload> = clientCommands.receiveAsFlow()
        override suspend fun send(payload: BridgePayload, sessionId: String) {
            hostEvents.send(payload to sessionId)
        }
    }

    private class FakeProvider(private val adapter: FakeAdapter) : HarnessProvider {
        override val agentId = "codex"
        override val displayName = "Codex"
        override val executableNames = listOf("codex")
        override suspend fun probe(executable: String) = adapter.installation
        override fun createAdapter(installation: AgentInstallation): AgentAdapter = adapter
    }

    private class FakeAdapter : AgentAdapter {
        val installation = AgentInstallation(
            "codex",
            "test",
            "/test/codex",
            "Codex",
            ManagedTransport.JSON_RPC_STDIO,
            setOf(Capability.STREAMING, Capability.APPROVAL),
        )
        private val sessionCounter = AtomicInteger()
        private val events = ConcurrentHashMap<String, Channel<AgentEvent>>()
        private val sessions = ConcurrentHashMap<String, SessionDescriptor>()
        val closedSessions = mutableListOf<String>()

        override val agentId = "codex"
        override suspend fun detect() = installation
        override suspend fun listSessions() = sessions.values.toList()
        override fun capabilities(session: SessionDescriptor) = session.capabilities
        override suspend fun startManaged(request: ManagedStart): SessionDescriptor {
            val id = "session-${sessionCounter.incrementAndGet()}"
            val session = SessionDescriptor(
                id, "local", "codex", "Test", SessionMode.MANAGED_LIVE,
                setOf(Capability.STREAMING, Capability.APPROVAL),
            )
            sessions[id] = session
            events[id] = Channel(Channel.UNLIMITED)
            return session
        }
        override suspend fun attach(sessionId: String) = error("unsupported")
        override suspend fun resume(sessionId: String, workingDirectory: String?) = checkNotNull(sessions[sessionId])
        override suspend fun sendText(sessionId: String, text: String, idempotencyKey: String) {
            checkNotNull(events[sessionId]).send(AgentEvent.MessageDelta(sessionId, "echo:$text"))
            checkNotNull(events[sessionId]).send(AgentEvent.TurnFinished(sessionId, true))
        }
        suspend fun requestApproval() {
            val session = sessions.values.maxBy { it.id }
            checkNotNull(events[session.id]).send(
                AgentEvent.Approval(
                    ApprovalRequest("approval-1", session.id, "process", "echo test", ".", 456L),
                ),
            )
        }
        override suspend fun sendAttachments(sessionId: String, attachments: List<Attachment>) = Unit
        override fun streamEvents(sessionId: String): Flow<AgentEvent> = checkNotNull(events[sessionId]).receiveAsFlow()
        override suspend fun respondApproval(sessionId: String, approvalId: String, decision: ApprovalDecision) = Unit
        override suspend fun interrupt(sessionId: String) = Unit
        override suspend fun close(sessionId: String) {
            closedSessions += sessionId
            events.remove(sessionId)?.close()
            sessions.remove(sessionId)
        }
        override suspend fun dispose() { events.values.forEach { it.close() } }
    }
}
