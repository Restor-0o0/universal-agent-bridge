# ADR 001-kmp-shared: KMP Shared

Status: Accepted. Date: 2026-10-18.

## Context

PRD v0.2 requires self-hosted Android/Desktop control with independent Host/Relay lifecycles and honest session semantics.

## Decision

Android + JVM targets for protocol, domain, crypto contracts, client-core, agent-spi and Compose UI. Android application and Desktop executable are separate. Server processes stay JVM-only.

## Consequences

Platform keystores, services, PTY and screen capture stay behind platform interfaces; no iOS/browser targets are required by this PRD.
