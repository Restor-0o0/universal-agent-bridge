package dev.agentbridge.codex

import dev.agentbridge.domain.Capability
import dev.agentbridge.host.CommandResult
import dev.agentbridge.host.CommandRunner
import dev.agentbridge.spi.ManagedTransport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class CodexHarnessProviderTest {
    @Test
    fun detectsAppServer() = runTest {
        val runner = CommandRunner { _, args ->
            if (args == listOf("--version")) CommandResult(0, "codex-cli 0.162.1\n", "")
            else CommandResult(0, "--listen stdio://", "")
        }
        val installation = CodexHarnessProvider(runner).probe("/bin/codex")
        assertEquals(ManagedTransport.JSON_RPC_STDIO, installation.transport)
        assertTrue(Capability.APPROVAL in installation.capabilities)
    }

    @Test
    fun refusesCodexWithoutStdioAppServer() = runTest {
        val runner = CommandRunner { _, args ->
            if (args == listOf("--version")) CommandResult(0, "codex-cli old", "")
            else CommandResult(0, "app-server", "")
        }
        assertFails { CodexHarnessProvider(runner).probe("/bin/codex") }
    }
}
