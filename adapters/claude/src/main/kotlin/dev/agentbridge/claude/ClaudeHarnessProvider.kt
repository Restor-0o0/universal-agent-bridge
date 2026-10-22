package dev.agentbridge.claude

import dev.agentbridge.domain.Capability
import dev.agentbridge.host.CommandRunner
import dev.agentbridge.host.ProcessCommandRunner
import dev.agentbridge.host.firstVersionLine
import dev.agentbridge.host.requireSuccess
import dev.agentbridge.spi.AgentInstallation
import dev.agentbridge.spi.AgentAdapter
import dev.agentbridge.spi.HarnessProvider
import dev.agentbridge.spi.ManagedTransport

class ClaudeHarnessProvider(
    private val runner: CommandRunner = ProcessCommandRunner(),
) : HarnessProvider {
    override val agentId = "claude"
    override val displayName = "Claude Code"
    override val executableNames = listOf(
        "claude",
        "claude.exe",
        "node_modules/@anthropic-ai/claude-code/bin/claude.exe",
    )

    override suspend fun probe(executable: String): AgentInstallation {
        val version = firstVersionLine(runner.requireSuccess(executable, listOf("--version"), "Claude Code version probe"))
        val help = runner.requireSuccess(executable, listOf("--help"), "Claude Code bridge probe")
        val required = listOf("--input-format", "--output-format", "stream-json", "--permission-prompts", "--resume")
        val missing = required.filterNot(help::contains)
        check(missing.isEmpty()) { "Claude Code lacks bridge flags: ${missing.joinToString()}" }
        return AgentInstallation(
            agentId = agentId,
            version = version,
            executable = executable,
            displayName = displayName,
            transport = ManagedTransport.JSON_LINES_STDIO,
            capabilities = setOf(
                Capability.RESUME,
                Capability.APPROVAL,
                Capability.STREAMING,
                Capability.STOP,
            ),
        )
    }

    override fun createAdapter(installation: AgentInstallation): AgentAdapter =
        ClaudeAgentAdapter(installation)
}
