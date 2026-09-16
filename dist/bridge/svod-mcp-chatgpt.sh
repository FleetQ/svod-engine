#!/bin/bash
# stdio MCP entrypoint for OpenAI's tunnel-client (ChatGPT developer-mode app).
#
# tunnel-client spawns this as `mcp.commands[].command` and speaks JSON-RPC over
# stdio; the bridge forwards to the local Svod MCP endpoint and adds the bearer
# token. The token stays in its 0600 file — it is never written into the
# tunnel-client profile or passed on a command line.
set -euo pipefail

AGENT_ID="${SVOD_AGENT_ID:-chatgpt-local}"
SECRET="$HOME/Library/Application Support/Svod/agent-${AGENT_ID}-token.secret"

if [ ! -r "$SECRET" ]; then
  echo "svod-mcp-chatgpt: missing token file $SECRET" >&2
  exit 1
fi

export SVOD_MCP_URL="${SVOD_MCP_URL:-http://127.0.0.1:7620/mcp}"
export SVOD_AUTH="Bearer $(cat "$SECRET")"

exec /opt/homebrew/bin/node "$(dirname "$0")/svod-mcp-bridge.mjs"
