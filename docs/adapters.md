# Adapter spike

AgentAdapter is the normalized app-facing contract in shared/agent-spi.

HarnessProvider is the automatic discovery layer. The Host ships providers for
Codex, Claude Code and OpenCode, locates native executables without shelling out,
and probes all three concurrently. Failure of one provider does not hide the
others. Clients consume normalized session/events and never construct a CLI
command or local port.

Verified local transports:

- Codex: `app-server --listen stdio://`, newline JSON-RPC.
- Claude Code: `--input-format stream-json --output-format stream-json` with
  host permission prompts and resume support.
- OpenCode: native `opencode acp`, ACP v1 JSON-RPC over NDJSON stdio.

`acp-client` is the reusable transport for any CLI that implements Agent Client
Protocol. It negotiates ACP v1/v2, correlates concurrent JSON-RPC requests,
surfaces agent requests/notifications, and implements baseline new/prompt/cancel
session operations. It never invokes `npx` or downloads an adapter at runtime.

CodexAgentAdapter, ClaudeAgentAdapter and OpenCodeAgentAdapter implement managed
sessions. HarnessRegistry creates them from discovered installations, so application code
does not receive an executable path or construct an agent-specific bridge. It
maps text chunks, v2 completion state and v1 prompt completion to AgentEvent;
permission replies select only the exact `allow_once`, `reject_once` or
`reject_always` option offered by the agent. Interrupt cancels every pending
permission before cancelling the turn. Event queues are bounded.

Codex uses thread/start, thread/resume, turn/start and structured app-server
notifications/approval requests. Claude uses one bidirectional stream-json process
per managed session and preserves the original tool input in one-shot approval
responses. OpenCode maps ACP session/prompt/cancel and offered permission options.
All adapters reject arbitrary live PID attachment and bound their event queues.

The discovered capability set describes what the probed CLI transport exposes.
It must not be shown as an active session capability before the complete
AgentAdapter is created and its lifecycle contract passes.

CodexProtocol encodes initialize/initialized and allow-once/deny responses.
Its event decoder handles message delta, command approval and turn completion.
It preserves numeric/string RPC request IDs; itemId is not the response ID.
Unknown events remain unknown. Missing required fields fail parsing.

CodexSpike starts a fresh process using ProcessBuilder argument arrays, not a
shell string. It runs a 20-second bounded handshake, discards stderr and cleans
up that child process. It does not launch turns, touch external histories,
modify credentials or weaken sandbox policy.

Remaining M0 acceptance:
1. Run harmless real turns and approval prompts for all three adapters.
2. Connect authenticated grants/input leases to the Host command router.
3. Run the same lifecycle checks on Linux.
4. Verify resume against disposable sessions and record version-specific outcomes.

Use an isolated fixture project. Never point this spike at the current agent's
development conversation or automatically approve the model's tool requests.
