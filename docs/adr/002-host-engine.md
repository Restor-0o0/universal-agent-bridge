# ADR 002-host-engine: Host Engine

Status: Accepted. Date: 2026-10-18.

## Context

PRD v0.2 requires self-hosted Android/Desktop control with independent Host/Relay lifecycles and honest session semantics.

## Decision

Dedicated user-owned JVM process. The GUI observes/controls it through a future authenticated local control channel. Host owns processes, working-directory allowlists, grants and input leases.

## Consequences

M0 provides a policy primitive and a disposable Codex handshake probe, not a background daemon. Never run Host as root/SYSTEM to simplify access.
