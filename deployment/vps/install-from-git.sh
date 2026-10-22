#!/usr/bin/env bash
set -euo pipefail

ASSET_NAME="AgentBridge-Relay-JVM-0.1.0.zip"
REPOSITORY="${AGENT_BRIDGE_GITHUB_REPOSITORY:-${1:-}}"
VERSION="${AGENT_BRIDGE_VERSION:-latest}"

if [[ -z "${REPOSITORY}" ]] && command -v git >/dev/null 2>&1; then
  ORIGIN="$(git -C "$(dirname -- "${BASH_SOURCE[0]}")" remote get-url origin 2>/dev/null || true)"
  case "${ORIGIN}" in
    git@github.com:*) REPOSITORY="${ORIGIN#git@github.com:}" ;;
    https://github.com/*) REPOSITORY="${ORIGIN#https://github.com/}" ;;
  esac
  REPOSITORY="${REPOSITORY%.git}"
fi

if [[ ! "${REPOSITORY}" =~ ^[^/]+/[^/]+$ ]]; then
  echo "Usage: $0 GITHUB_OWNER/REPOSITORY" >&2
  echo "Or set AGENT_BRIDGE_GITHUB_REPOSITORY." >&2
  exit 2
fi

missing=()
for command_name in curl unzip openssl java; do
  command -v "${command_name}" >/dev/null 2>&1 || missing+=("${command_name}")
done
if (( ${#missing[@]} > 0 )); then
  echo "Installing required Ubuntu packages: ${missing[*]}"
  sudo apt-get update
  sudo apt-get install -y curl unzip openssl openjdk-17-jre-headless
fi

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "${WORK_DIR}"' EXIT

if command -v gh >/dev/null 2>&1; then
  release_args=(release download --repo "${REPOSITORY}" --pattern "${ASSET_NAME}" --dir "${WORK_DIR}" --clobber)
  if [[ "${VERSION}" != "latest" ]]; then
    release_args+=("${VERSION}")
  fi
  gh "${release_args[@]}"
else
  if [[ "${VERSION}" == "latest" ]]; then
    DOWNLOAD_URL="https://github.com/${REPOSITORY}/releases/latest/download/${ASSET_NAME}"
  else
    DOWNLOAD_URL="https://github.com/${REPOSITORY}/releases/download/${VERSION}/${ASSET_NAME}"
  fi
  curl --fail --location --retry 3 --output "${WORK_DIR}/${ASSET_NAME}" "${DOWNLOAD_URL}"
fi

unzip -q "${WORK_DIR}/${ASSET_NAME}" -d "${WORK_DIR}/release"
chmod +x "${WORK_DIR}/release/relay-vps/install-relay.sh"
sudo "${WORK_DIR}/release/relay-vps/install-relay.sh"

echo
echo "Public checks after configuring the reverse proxy:"
echo "  curl https://cdn.restorkava.ru/health"
echo "  curl https://cdn.restorkava.ru/.well-known/agent-bridge"
