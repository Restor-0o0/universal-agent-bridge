package dev.agentbridge.opencode

import dev.agentbridge.acp.AcpAgentInfo
import dev.agentbridge.acp.AcpProbe
import dev.agentbridge.domain.Capability
import dev.agentbridge.host.CommandResult
import dev.agentbridge.host.CommandRunner
import dev.agentbridge.spi.ManagedTransport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import kotlin.test.assertIs

class OpenCodeHarnessProviderTest {
    @Test
    fun detectsAcpBridge() = runTest {
        val runner = CommandRunner { _, args ->
            if (args == listOf("--version")) CommandResult(0, "1.18.26\n", "")
            else error("Unexpected command")
        }
        val installation = OpenCodeHarnessProvider(runner, successfulAcpProbe()).probe("/bin/opencode")
        assertEquals(ManagedTransport.AGENT_CLIENT_PROTOCOL, installation.transport)
        assertTrue(Capability.APPROVAL in installation.capabilities)
        assertTrue(Capability.IMAGE_INPUT in installation.capabilities)
    }

    @Test
    fun refusesAcpWithoutSessions() = runTest {
        val runner = CommandRunner { _, args ->
            if (args == listOf("--version")) CommandResult(0, "1.0", "")
            else error("Unexpected command")
        }
        val noSessions = AcpProbe { _, _ -> acpInfo(supportsSessions = false) }
        assertFails { OpenCodeHarnessProvider(runner, noSessions).probe("/bin/opencode") }
    }

    @Test
    fun createsNormalizedAdapterFromDiscoveredInstallation() = runTest {
        val runner = CommandRunner { _, _ -> CommandResult(0, "1.18.26", "") }
        val provider = OpenCodeHarnessProvider(runner, successfulAcpProbe())
        val installation = provider.probe("/bin/opencode")
        val adapter = assertIs<OpenCodeAgentAdapter>(provider.createAdapter(installation))
        adapter.dispose()
    }

    private fun successfulAcpProbe() = AcpProbe { _, args ->
        assertEquals(listOf("acp"), args)
        acpInfo(supportsSessions = true)
    }

    private fun acpInfo(supportsSessions: Boolean) = AcpAgentInfo(
        protocolVersion = 2,
        name = "opencode",
        title = "OpenCode",
        version = "1.18.26",
        supportsSessions = supportsSessions,
        supportsResume = true,
        supportsImages = true,
        rawCapabilities = kotlinx.serialization.json.JsonObject(emptyMap()),
    )
}
