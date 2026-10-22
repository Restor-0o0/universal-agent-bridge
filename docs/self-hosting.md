# Self-hosting

The current M1 preview can run behind a trusted HTTPS reverse proxy. Relay stays
bound to `127.0.0.1`; Caddy exposes only ports 80/443 and proxies HTTP/WebSocket
traffic. Clients authenticate with a shared bearer token and encrypt payloads with
a shared AES-256-GCM key.

Use the packaged `AgentBridge-Relay-JVM-0.1.0.zip` and follow the
[VPS quickstart](vps-quickstart.md).
All local, USB, VPS and remote-Host topologies are listed in
[connection methods](connections.md).

This remains a preview deployment model. Device enrollment, independent per-device
keys, revocation, reconnect queues and key rotation are not implemented yet. Keep
`/etc/agent-bridge/relay.env` private and rotate both secrets if a client device or
configuration file is lost.
