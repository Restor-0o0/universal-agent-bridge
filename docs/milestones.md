# Next work

## M0 — in progress

Implemented: module skeleton, protocol/domain/SPIs, diagnostics, Compose entry
points, policy tests, Codex codec + handshake probe, automatic structured
transport discovery for Codex/Claude Code/OpenCode, architecture and ADRs.
Codex, Claude Code and OpenCode have normalized managed-session, streaming,
approval, interruption and cleanup adapters verified with fixtures. Codex and
OpenCode use structured JSON-RPC transports; Claude uses bidirectional JSONL.
See validation.md for commands actually run.

Still required to close M0:
- Managed Codex turn + structured approval on Windows and Linux.
- Verify all three adapters against real harmless turns and real approval prompts.
- Verify installed/version-specific interfaces and OS requirements on Linux.
- Select and evaluate a vetted crypto library and pairing protocol.
- Record Windows/Linux runtime evidence; CI is not evidence until run.

## M1 — local vertical slice started

Implemented locally: authenticated loopback WebSocket routing, AES-GCM opaque
envelopes with test-only pre-shared keys, unified Host command routing,
Desktop/Android managed chat and approval UI, Host activity events, foreground
Android monitoring, opt-in audible help notifications, and an end-to-end
integration test.

1. Shared Relay config and persistent devices/grants store with migrations.
2. Pairing, OS key storage, authenticated E2EE and revocation before public access.
3. WSS routing, bounded frames/queues/quotas, heartbeat, resume, ack and idempotency.
4. Complete Codex adapter and Host project allowlist/input lease enforcement.
5. Persisted cross-device session inventory and reconnect for Desktop/Android.
6. Independent user Host / system Relay service lifecycle with smoke tests.
7. Local TLS integration test Host ↔ Relay ↔ Android client, including
   failed TLS, revoked token, duplicate approval and dropped connection.

Then physical Android device validation, encrypted artifacts/consented screenshots,
packaging and public beta. Do not ship a plaintext intermediate
remote-control service merely to demonstrate the transport.
