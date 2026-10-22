# ADR 006-os-services: OS service lifecycle

Status: Accepted for architecture; implementation pending. Date: 2026-10-18.

## Context

PRD v0.2 requires self-hosted Android/Desktop control with independent Host/Relay lifecycles and honest session semantics.

## Decision

Relay: dedicated Linux system service or Windows Service. Host: interactive user process, Linux systemd --user / Windows user autostart or supervisor. GUI lifecycle is independent.

## Consequences

Windows Session 0 must not capture the user's screen. Linux screen capture depends on desktop/portal consent. The included systemd template is a draft; installers/wrappers are not implemented.
