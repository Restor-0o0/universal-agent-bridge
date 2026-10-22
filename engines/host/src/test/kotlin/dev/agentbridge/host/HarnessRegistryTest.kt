package dev.agentbridge.host

import dev.agentbridge.domain.Capability
import dev.agentbridge.spi.AgentInstallation
import dev.agentbridge.spi.HarnessProvider
import dev.agentbridge.spi.ManagedTransport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class HarnessRegistryTest {
    private class Provider(override val agentId: String) : HarnessProvider {
        override val displayName = agentId.uppercase()
        override val executableNames = listOf(agentId)
        override suspend fun probe(executable: String) = AgentInstallation(
            agentId = agentId,
            version = "1.0",
            executable = executable,
            displayName = displayName,
            transport = ManagedTransport.JSON_LINES_STDIO,
            capabilities = setOf(Capability.STREAMING),
        )
    }

    @Test
    fun discoversAllProvidersWithoutPerAgentConfiguration() = runTest {
        val registry = HarnessRegistry(
            providers = listOf(Provider("codex"), Provider("claude"), Provider("opencode")),
            resolver = ExecutableResolver { candidates -> "/bin/${candidates.single()}" },
        )
        val found = registry.discover().map { assertIs<HarnessDiscovery.Available>(it).installation }
        assertEquals(listOf("codex", "claude", "opencode"), found.map { it.agentId })
        assertEquals(3, found.map { it.executable }.distinct().size)
    }

    @Test
    fun oneBrokenHarnessDoesNotHideTheOthers() = runTest {
        val registry = HarnessRegistry(
            providers = listOf(Provider("codex"), Provider("claude")),
            resolver = ExecutableResolver { candidates ->
                if (candidates.single() == "codex") "/bin/codex" else null
            },
        )
        val result = registry.discover()
        assertIs<HarnessDiscovery.Available>(result[0])
        assertEquals("CLI not found", assertIs<HarnessDiscovery.Unavailable>(result[1]).reason)
    }

    @Test
    fun duplicateProvidersFailFast() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            HarnessRegistry(listOf(Provider("codex"), Provider("codex")))
        }
    }

    @Test
    fun executableResolverRejectsAbsoluteAndParentTraversalCandidates() {
        val resolver = PathExecutableResolver(path = "", windows = false, userHome = java.nio.file.Path.of("/tmp"))
        assertNull(resolver.resolve(listOf("/bin/sh", "../bin/sh")))
    }
}
