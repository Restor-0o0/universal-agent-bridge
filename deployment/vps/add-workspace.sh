#!/usr/bin/env bash
set -euo pipefail

if [[ ${EUID} -ne 0 ]]; then
  echo "Run as root: sudo $0 WORKSPACE" >&2
  exit 1
fi

WORKSPACE="${1:-}"
if [[ ! "${WORKSPACE}" =~ ^[A-Za-z0-9._-]{1,64}$ ]]; then
  echo "Workspace must contain 1-64 letters, digits, dots, underscores or dashes." >&2
  exit 2
fi

ENV_FILE=/etc/agent-bridge/relay.env
WORKSPACE_DIR=/etc/agent-bridge/workspaces
[[ -f "${ENV_FILE}" ]] || { echo "Relay is not installed." >&2; exit 3; }
install -d -m 0700 "${WORKSPACE_DIR}"

CURRENT="$(grep '^AGENT_BRIDGE_WORKSPACES=' "${ENV_FILE}" | cut -d= -f2- || true)"
if [[ -z "${CURRENT}" ]]; then
  LEGACY_TOKEN="$(grep '^AGENT_BRIDGE_DEV_TOKEN=' "${ENV_FILE}" | cut -d= -f2- || true)"
  [[ -n "${LEGACY_TOKEN}" ]] && CURRENT="default=${LEGACY_TOKEN}"
fi
if [[ ";${CURRENT};" == *";${WORKSPACE}="* ]]; then
  echo "Workspace '${WORKSPACE}' already exists." >&2
  exit 4
fi

PASSWORD="$(openssl rand -hex 32)"
KEY="$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n')"
UPDATED="${CURRENT:+${CURRENT};}${WORKSPACE}=${PASSWORD}"

if grep -q '^AGENT_BRIDGE_WORKSPACES=' "${ENV_FILE}"; then
  sed -i "s|^AGENT_BRIDGE_WORKSPACES=.*$|AGENT_BRIDGE_WORKSPACES=${UPDATED}|" "${ENV_FILE}"
else
  printf '\nAGENT_BRIDGE_WORKSPACES=%s\n' "${UPDATED}" >> "${ENV_FILE}"
fi
chmod 600 "${ENV_FILE}"
printf 'WORKSPACE=%s\nPASSWORD=%s\nKEY=%s\n' "${WORKSPACE}" "${PASSWORD}" "${KEY}" > "${WORKSPACE_DIR}/${WORKSPACE}.conf"
chmod 600 "${WORKSPACE_DIR}/${WORKSPACE}.conf"
systemctl restart agent-bridge-relay

echo "Workspace created. Give these values only to its owner:"
cat "${WORKSPACE_DIR}/${WORKSPACE}.conf"
echo "The owner sets the same WORKSPACE, PASSWORD and KEY on Host and phone."
