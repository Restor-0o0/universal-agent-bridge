package dev.agentbridge.opencode

import dev.agentbridge.acp.AcpProbe
import dev.agentbridge.acp.ProcessAcpProbe
import dev.agentbridge.domain.Capability
import dev.agentbridge.host.CommandRunner
import dev.agentbridge.host.ProcessCommandRunner
import dev.agentbridge.host.firstVersionLine
import dev.agentbridge.host.requireSuccess
import dev.agentbridge.spi.AgentInstallation
import dev.agentbridge.spi.AgentAdapter
import dev.agentbridge.spi.HarnessProvider
import dev.agentbridge.spi.ManagedTransport

class OpenCodeHarnessProvider(
    private val runner: CommandRunner = ProcessCommandRunner(),
    private val acpProbe: AcpProbe = ProcessAcpProbe(),
) : HarnessProvider {
    override val agentId = "opencode"
    override val displayName = "OpenCode"
    override val executableNames = listOf(
        "opencode",
        "opencode.exe",
        "node_modules/opencode-ai/bin/opencode.exe",
    )

    override suspend fun probe(executable: String): AgentInstallation {
        val version = firstVersionLine(runner.requireSuccess(executable, listOf("--version"), "OpenCode version probe"))
        val acp = acpProbe.probe(executable, listOf("acp"))
        check(acp.supportsSessions) { "OpenCode ACP did not advertise session support" }
        return AgentInstallation(
            agentId = agentId,
            version = version,
            executable = executable,
            displayName = displayName,
            transport = ManagedTransport.AGENT_CLIENT_PROTOCOL,
            capabilities = buildSet {
                addAll(
                    setOf(
                        Capability.APPROVAL,
                        Capability.STREAMING,
                        Capability.STOP,
                    ),
                )
                if (acp.supportsResume) add(Capability.RESUME)
                if (acp.supportsImages) add(Capability.IMAGE_INPUT)
            },
        )
    }

    override fun createAdapter(installation: AgentInstallation): AgentAdapter =
        OpenCodeAgentAdapter(installation)
}
