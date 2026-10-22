# Security model — M0 + local M1 fixture

## Trust and assets

The Host user's project files, CLI credentials, prompts, approvals, screen and
private keys are sensitive. Relay/VPS and CDN are untrusted for content.
Routing metadata, traffic timing and sizes remain visible even with E2EE.
The AI provider still receives inputs from its CLI under the user's settings.

| Threat | Required control | Current state |
|---|---|---|
| Compromised Relay / CDN | Authenticated pairing + end-to-end AEAD | Dev transport encrypts payloads; production pairing pending |
| QR substitution | Fingerprint + challenge-bound key exchange + local confirmation | Not implemented |
| MITM / downgrade | Verified TLS; reject redirects / HTTP | URL policy implemented; real TLS negative test pending |
| Device impersonation / lost phone | Scoped grants, revocation, key epochs | Domain types only |
| Approval replay / wrong controller | One active lease, expiry, one-time consumption | Policy primitive + unit tests; not wired to agents |
| External CLI duplication | Explicit modes; safe resume/handoff | Mode gates implemented; discovery absent |
| File traversal / symlinks | Canonical allowlist, quotas, safe temporary storage | No file endpoint exists |
| Unwanted screen capture | Local consent + OS grant + visible indicator | No capture implementation |
| Public insecure enrollment | Disabled by default; local bootstrap | Relay loopback-only, pairing returns 403 |
| Secrets in logs | Redaction, no payload logging | Relay logs no payload; adapter stderr is drained/discarded |
| Resource exhaustion | Size/rate/TTL/backpressure, bounded replay persistence | Dev frames/queues/replay IDs bounded; persistence/rate limits pending |

## Crypto decision

Use a vetted implementation of authenticated device-to-device key establishment
and AEAD. X25519 + HKDF + AEAD is a candidate, not an implemented protocol or
security guarantee. Pairing must bind challenge, server identity, endpoint
identities, role and key epoch. All routing metadata affecting authorization
must be authenticated. Nonce allocation and restart behavior need review.

No XOR/base64-as-encryption, no trust-all TLS, no plaintext E2EE fallback.
The crypto module contains `DevelopmentPresharedCipher`, a JVM-only AES-GCM
fixture using a caller-supplied 32-byte key. It authenticates routing metadata
and has tamper/replay tests. It deliberately does not implement pairing, device
identity, secure key storage or rotation. Do not enable public routing until
those controls and negative tests pass.
This gate takes precedence over the PRD's later placement of E2EE in M3.

Android Keystore, Windows DPAPI and Linux Secret Service integrations remain
platform-specific work. No secret config file format has been chosen.

## Limits of implemented primitives

ApprovalGate trusts caller-supplied identity/grants/time; it is not an auth layer.
Its seen-ID set is process-local and unbounded; production must replace this
with bounded durable replay state and defined expiry/restart semantics.
SequenceCursor is not cryptographic replay protection.
Envelope validation is structural only. Tests do not constitute security review.

Before beta: threat-model review, authenticated WSS/TLS rejection tests,
pairing substitution/expiry, device revocation, approval crash/replay,
path traversal/symlinks, limits/CDN reconnect, dependency audit and SBOM.
