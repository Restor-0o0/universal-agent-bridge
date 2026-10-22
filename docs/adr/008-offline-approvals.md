# ADR 008-offline-approvals: Offline queue and approval replay

Status: Accepted. Date: 2026-10-18.

## Context

PRD v0.2 requires self-hosted Android/Desktop control with independent Host/Relay lifecycles and honest session semantics.

## Decision

Queue text drafts only, keep stable idempotency keys, never queue approvals. Host validates current grant and lease at execution time and consumes each approval once.

## Consequences

In-memory prototypes require serialized access/persistent transactional replacements. Reconnect does not automatically execute dangerous stale responses. Expired or revoked grants fail closed.
