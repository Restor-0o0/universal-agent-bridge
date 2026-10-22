# ADR 007-adapter-architecture: Adapter architecture

Status: Accepted. Date: 2026-10-18.

## Context

PRD v0.2 requires self-hosted Android/Desktop control with independent Host/Relay lifecycles and honest session semantics.

## Decision

Versioned AgentAdapter contract with explicit capability sets and session modes. Prefer structured official interfaces; Generic PTY exposes terminal behavior only.

The Host also owns a built-in HarnessRegistry. Providers declare executable
names and validate required versioned features. Discovery searches PATH and
standard user installation roots, probes providers independently and returns a
normalized installation descriptor. App clients never receive executable paths,
CLI arguments, local HTTP credentials or agent-specific event payloads.

## Consequences

No live takeover of arbitrary PID. Codex app-server, Claude stream-json and
OpenCode's native ACP subprocess are the initial structured transports. ACP is
the preferred common bridge for future compatible agents; no runtime package
download is allowed. Resume and
read-only history remain distinct. Every session capability needs per-version
and per-OS contract evidence. Transport discovery alone cannot enable an action
in UI.
