#!/bin/bash
# Start the OpenAI tunnel that exposes the local Svod MCP endpoint to ChatGPT.
#
#   ~/htdocs/svod/dist/bridge/svod-chatgpt-tunnel.sh
#
# Keep it running: ChatGPT reaches Svod only while this process is alive, both
# for the tool scan when creating the app and for every later call.
set -euo pipefail

if [ -z "${CONTROL_PLANE_API_KEY:-}" ]; then
  export OP_SERVICE_ACCOUNT_TOKEN="${OP_SERVICE_ACCOUNT_TOKEN:-$(cat "$HOME/.config/op/sa-token")}"
  CONTROL_PLANE_API_KEY="$(op read 'op://AI Agent/OpenAI Tunnel Runtime/credential')"
  export CONTROL_PLANE_API_KEY
fi

# The engine must be up — the tunnel forwards to 127.0.0.1:7620.
if ! curl -sf -m 3 http://127.0.0.1:7619/ready >/dev/null; then
  echo "svod-chatgpt-tunnel: Svod engine is not ready on :7619 — start Svod first." >&2
  exit 1
fi

exec "$HOME/.local/bin/tunnel-client" run --profile svod-chatgpt
