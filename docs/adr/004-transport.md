# ADR 004-transport: Transport

Status: Accepted for architecture; implementation pending. Date: 2026-10-18.

## Context

PRD v0.2 requires self-hosted Android/Desktop control with independent Host/Relay lifecycles and honest session semantics.

## Decision

Outbound HTTPS/WSS from Android and Host to user-owned Relay. No mandatory developer cloud, VPN, VLESS wrapping or inbound Host port. TLS verification is mandatory.

## Consequences

M0 discovery uses ordinary HTTP only in explicit loopback development. WSS needs limits, heartbeat, bounded backoff, durable ack/replay and trusted-proxy configuration.
