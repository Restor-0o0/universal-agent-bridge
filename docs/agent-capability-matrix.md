# Agent capability matrix — 2026-10-18

Legend: documented = official interface exists; spike = implemented narrow local
proof; pending = not integrated/verified. None of these rows promises arbitrary
live attach to an independently started CLI.

| Agent | Official integration path | Managed streaming / approvals | External session | Windows / Linux evidence |
|---|---|---|---|---|
| Codex 0.163.0 | app-server stdio JSON-RPC | Normalized thread/turn, streaming, command/file approvals, interrupt, resume and cleanup tested with fixtures; handshake passed; real turn pending | thread/resume is new execution context, not interception of any PID | Local Windows executable/schema inspected; Linux runtime pending |
| Claude Code 2.1.302 | stream-json CLI / Agent SDK | Normalized managed process, partial text, result, host permission response, interrupt, resume and cleanup tested; control handshake passed; real turn pending | Resume starts a managed process around persisted context, not arbitrary PID attach | Windows control channel passed; Linux pending |
| OpenCode 1.18.26 | native ACP v1 over NDJSON stdio | Normalized managed adapter implements text streaming, prompt completion, one-shot approval, interrupt and cleanup; real inference pending | Host controls only the ACP subprocess it starts; arbitrary PID attach rejected | Windows handshake and contract tests passed; Linux pending |
| Gemini CLI | Headless structured JSON/stream-json | Output documented; interactive approval control not claimed | Resume/version behavior needs spike | Runtime verification pending |
| Generic PTY | Windows ConPTY / Linux PTY | Raw terminal only until structured support is proven | No arbitrary PID attach | Neither backend implemented |

Only capabilities verified for the concrete adapter version, launch mode and OS
may be exposed by a production adapter. SCREEN_CAPTURE and FILE_OUTBOX are Host
features and require their own consent/policy, not guesses from CLI support.

Official sources:
- [Codex App Server](https://developers.openai.com/codex/app-server/): initialize,
  initialized, thread/start, thread/resume, turn/start, item/agentMessage/delta,
  command approval requests with JSON-RPC response IDs.
- [Claude Agent SDK permissions](https://platform.claude.com/docs/en/agent-sdk/permissions):
  use structured callbacks; do not regex-match terminal permission prompts.
- [OpenCode ACP](https://docs.opencode.ai/docs/acp/): native `opencode acp`, JSON-RPC
  over stdio and the standard session/permission surface.
- [Gemini headless mode](https://geminicli.com/docs/cli/headless/): structured output
  does not itself establish a bidirectional approval channel.

`hostApp --list-agents` is the executable feature probe. It requires no
per-agent path, port or bridge configuration under a standard installation.
The encrypted Host router accepts the same `BridgePayload` commands for every row.

Local schema generation:
`codex app-server generate-json-schema --out .dev/codex-schema`.
Schemas remain ignored build evidence; tests contain minimal synthetic fixtures.
