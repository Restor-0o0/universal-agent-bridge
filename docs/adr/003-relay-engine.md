# ADR 003-relay-engine: Relay Engine

Status: Accepted. Date: 2026-10-18.

## Context

PRD v0.2 requires self-hosted Android/Desktop control with independent Host/Relay lifecycles and honest session semantics.

## Decision

One reusable Ktor/JVM engine, with independent headless entry point and Desktop --relay-headless mode. No Compose dependency in Relay.

## Consequences

M0 is diagnostic-only with loopback enforced in RelayConfig. Configuration schema, migrations, service supervision and routing are next work.
