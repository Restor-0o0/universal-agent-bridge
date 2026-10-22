package dev.agentbridge.claude

import dev.agentbridge.domain.Capability
import dev.agentbridge.host.CommandResult
import dev.agentbridge.host.CommandRunner
import dev.agentbridge.spi.ManagedTransport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class ClaudeHarnessProviderTest {
    @Test
    fun detectsStructuredBridge() = runTest {
        val runner = CommandRunner { _, args ->
            if (args == listOf("--version")) CommandResult(0, "2.1.302 (Claude Code)\n", "")
            else CommandResult(0, "--input-format stream-json --output-format stream-json --permission-prompts --resume", "")
        }
        val installation = ClaudeHarnessProvider(runner).probe("/bin/claude")
        assertEquals(ManagedTransport.JSON_LINES_STDIO, installation.transport)
        assertTrue(Capability.APPROVAL in installation.capabilities)
    }

    @Test
    fun refusesOlderCliWithoutHostPermissions() = runTest {
        val runner = CommandRunner { _, args ->
            if (args == listOf("--version")) CommandResult(0, "1.0", "")
            else CommandResult(0, "--input-format stream-json --output-format stream-json --resume", "")
        }
        assertFails { ClaudeHarnessProvider(runner).probe("/bin/claude") }
    }
}
