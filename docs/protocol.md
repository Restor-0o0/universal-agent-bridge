# Protocol v1 — draft

## Implemented diagnostic HTTP

- GET /health → text/plain ok.
- GET /.well-known/agent-bridge → RelayInfo JSON, protocolVersions [1],
  stage M0, enrollmentEnabled false, encryptedTransportReady false.
- POST /v1/pairing → 403 ENROLLMENT_DISABLED.
- GET /v1/events → 503 PAIRING_E2EE_NOT_IMPLEMENTED in normal mode.

Release clients require HTTPS with OS/default TLS certificate validation.
Only Desktop --dev can inspect HTTP on literal loopback/localhost.
Clients do not follow redirects. The Relay binds only 127.0.0.1 or ::1.
Discovery version negotiation chooses the highest common supported version.

An explicit `--dev` Relay mode upgrades `/v1/events?deviceId=...` to WebSocket
only on loopback. It requires a 256-bit-class bearer token, accepts only the
configured `host`, `desktop` and `android` fixture identities, bounds queues/frames, and
routes validated envelopes. This is an integration fixture, not enrollment.

## Encrypted envelope

EventEnvelope follows PRD fields: v, eventId, kind, senderDeviceId,
recipientDeviceId, sessionId, seq, createdAt, ciphertext, nonce, keyEpoch.
validate() checks basic shape/bounds. The JVM dev clients use AES-256-GCM and
authenticate all routing metadata as AAD, require 12-byte nonces and reject a
bounded window of duplicate event IDs. Relay does not receive the key or decode
`BridgePayload`.

Production M1 must replace the shared dev key with paired per-device keys,
durable replay state, rotation and negotiated payload limits.
Use generic outer kind=session.event; semantic event names stay encrypted.
A sequence belongs to (sender, recipient, session, keyEpoch); SequenceCursor is
one such stream. Receiving a gap leaves its cursor unchanged and requires replay.
Ciphertext replay and routing metadata substitution need independent checks.

## Commands, acknowledgements and approvals

`BridgePayload` currently defines agent inventory, start session, send text,
stream delta, approval request/response, interrupt, turn completion, activity,
help-needed notification, Host directory listing and failure. Directory listing
returns folders only, excludes symbolic links, sorts names and limits each response
to 200 entries; it is used by Android to choose a computer-side working directory.
Host maps these commands to AgentAdapter without exposing harness CLI details.

Text drafts use an idempotency key. Same key + changed content fails;
acknowledgement removes a pending draft. This is not durable delivery.
Host M1 must persist command result/ack atomically before accepting duplicates.
No approval type exists in DraftOutbox.

Approval authorization checks online state, grant, lease, session and expiration.
Consumption is once per process; request IDs cannot be registered again in
the same gate. Production IDs are scoped to a Host connection/turn. M1 needs
durable consumption or guaranteed fresh IDs across restart and transactional
coupling to the adapter response. Do not interpret a Relay ack as tool approval.

## Pending protocol work

Authenticated WSS handshake; device identities and revocation; heartbeat and
backoff; bounded outboxes; persistent replay window; quotas; pairing expiry;
chunked encrypted uploads; authenticated error codes; event schema migration.
