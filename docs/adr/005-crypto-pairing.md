# ADR 005-crypto-pairing: Crypto and pairing

Status: Proposed; provider selection pending. Date: 2026-10-18.

## Context

PRD v0.2 requires self-hosted Android/Desktop control with independent Host/Relay lifecycles and honest session semantics.

## Decision

Authenticated E2EE before public event transport. Evaluate vetted X25519/HKDF/AEAD implementations and complete transcript binding; do not design ad hoc crypto. Store endpoint keys in platform secure storage.

## Consequences

Fingerprint + one-time challenge + trusted-device approval must bind identities and key epochs. Envelope metadata is AEAD associated data. No production cipher exists yet; encryptedTransportReady remains false.
