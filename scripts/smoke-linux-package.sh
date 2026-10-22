#!/usr/bin/env bash
set -euo pipefail

deb_path="${1:?Usage: smoke-linux-package.sh /path/to/agentbridge.deb}"
smoke_dir="$(mktemp -d /tmp/agentbridge-deb-smoke.XXXXXX)"
app_pid=""

cleanup() {
  if [[ -n "$app_pid" ]]; then
    kill "$app_pid" >/dev/null 2>&1 || true
    wait "$app_pid" >/dev/null 2>&1 || true
  fi
  rm -rf -- "$smoke_dir"
}
trap cleanup EXIT

dpkg-deb -x "$deb_path" "$smoke_dir"
"$smoke_dir/opt/agentbridge/bin/AgentBridge" >"$smoke_dir/app.log" 2>&1 &
app_pid="$!"

for _ in $(seq 1 40); do
  if curl -fsS http://127.0.0.1:8787/health >/dev/null 2>&1; then
    echo "LINUX_DEB_HEALTH=ok"
    exit 0
  fi
  sleep 0.25
done

cat "$smoke_dir/app.log"
echo "Linux package did not start its embedded Relay" >&2
exit 1
