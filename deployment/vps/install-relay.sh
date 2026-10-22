#!/usr/bin/env bash
set -euo pipefail

if [[ ${EUID} -ne 0 ]]; then
  echo "Run as root: sudo ./install-relay.sh" >&2
  exit 1
fi

if ! command -v java >/dev/null 2>&1; then
  echo "Java 17 or newer is required. On Ubuntu: apt install openjdk-17-jre-headless" >&2
  exit 1
fi

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
id agent-bridge >/dev/null 2>&1 || useradd --system --home /var/lib/agent-bridge --create-home --shell /usr/sbin/nologin agent-bridge
install -d -m 0755 /opt/agent-bridge /etc/agent-bridge
install -d -m 0700 /etc/agent-bridge/workspaces
rm -rf /opt/agent-bridge/relay
cp -a "${SCRIPT_DIR}/relay" /opt/agent-bridge/relay
chmod +x /opt/agent-bridge/relay/bin/relayHeadless
chown -R agent-bridge:agent-bridge /opt/agent-bridge/relay /var/lib/agent-bridge

if [[ ! -f /etc/agent-bridge/relay.env ]]; then
  PASSWORD="$(openssl rand -hex 32)"
  KEY="$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n')"
  printf 'AGENT_BRIDGE_WORKSPACES=default=%s\nAGENT_BRIDGE_DEV_KEY=%s\n' "${PASSWORD}" "${KEY}" > /etc/agent-bridge/relay.env
  printf 'WORKSPACE=default\nPASSWORD=%s\nKEY=%s\n' "${PASSWORD}" "${KEY}" > /etc/agent-bridge/workspaces/default.conf
  chmod 600 /etc/agent-bridge/relay.env
  chmod 600 /etc/agent-bridge/workspaces/default.conf
fi

if [[ -f "${SCRIPT_DIR}/add-workspace.sh" ]]; then
  install -m 0755 "${SCRIPT_DIR}/add-workspace.sh" /opt/agent-bridge/add-workspace.sh
fi

install -m 0644 "${SCRIPT_DIR}/agent-bridge-relay.service" /etc/systemd/system/agent-bridge-relay.service
systemctl daemon-reload
systemctl enable --now agent-bridge-relay

echo "Relay installed. Local check: curl http://127.0.0.1:8787/health"
echo "Connection secrets (keep them private):"
cat /etc/agent-bridge/workspaces/default.conf 2>/dev/null || cat /etc/agent-bridge/relay.env
echo "Add another isolated user: sudo /opt/agent-bridge/add-workspace.sh NAME"
