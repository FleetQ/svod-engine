#!/usr/bin/env bash
# S6 gate checks on fleetq-01. Prints status codes and diffs only; never a secret.
set -uo pipefail
STAGE=/tmp/svod-s6; OUT=$STAGE/out; S="sudo -n"
H=svod.fleetq.net
CA=$OUT/cf-origin-ca-rsa-root.pem
[ -s "$CA" ] || curl -fsS -o "$CA" https://developers.cloudflare.com/ssl/static/origin_ca_rsa_root.pem
R="--resolve $H:443:127.0.0.1 --cacert $CA"

hdr(){ # header file with the agent token, 0600, removed by the caller
  local f; f=$(mktemp); chmod 600 "$f"
  $S cat /srv/svod/config/secrets/agent-nikola-claude.token | sed 's/^/Authorization: Bearer /' > "$f"; echo "$f"
}
INIT='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"s6-smoke","version":"1"}}}'
mcp(){ # $1 = extra args
  curl -sS -o "$OUT/mcp-body.txt" -w '%{http_code}' $R -X POST https://$H/mcp \
    -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' $1 --data "$INIT"
}

check(){ # $1 label
  echo "--- $1"
  echo "ready (valid TLS vs Cloudflare Origin CA): $(curl -sS -o /dev/null -w '%{http_code} verify=%{ssl_verify_result}' $R https://$H/ready)"
  echo "mcp without token: $(mcp '')"
  F=$(hdr); echo "mcp with nikola-claude token: $(mcp "-H @$F")  body-head: $(head -c 120 "$OUT/mcp-body.txt" | tr -d '\r\n' | sed -E 's/(svk_|sva_)[A-Za-z0-9_-]+/\1***/g')"; rm -f "$F"
  echo "mcp with wrong token: $(curl -sS -o /dev/null -w '%{http_code}' $R -X POST https://$H/mcp -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' -H 'Authorization: Bearer wrong-token' --data "$INIT")"
  echo "app api /api/v1/me without key: $(curl -sS -o /dev/null -w '%{http_code}' $R https://$H/api/v1/me)"
  echo "update route blocked: $(curl -sS -o /dev/null -w '%{http_code}' $R https://$H/api/v1/update/check)"
}

check "after deploy"
echo "--- systemctl restart svod"
$S systemctl restart svod.service
for i in $(seq 1 60); do c=$(curl -s -o /dev/null -w '%{http_code}' $R https://$H/ready); [ "$c" = 200 ] && break; sleep 3; done
echo "unit: $(systemctl is-active svod.service) enabled=$(systemctl is-enabled svod.service)"
check "after restart"

echo "--- public DNS: $(getent hosts $H || echo 'does not resolve')"
echo "--- no pre-existing service changed"
snapshot(){
  $S docker ps --format '{{.Names}} {{.Status}}' | sort > "$OUT/ps-$1.txt"
  $S docker inspect --format '{{.Name}} {{.State.StartedAt}} {{.RestartCount}}' $($S docker ps -q) | sort > "$OUT/started-$1.txt"
  systemctl list-units --type=service --state=running --no-legend | awk '{print $1}' | sort > "$OUT/units-$1.txt"
  for h in fleetq.net office.fleetq.net hooks.fleetq.net pravoto.app kvesta.app; do
    printf '%s %s\n' "$h" "$(curl -sk -o /dev/null -w '%{http_code}' --resolve $h:443:127.0.0.1 https://$h/ || true)"
  done > "$OUT/vhosts-$1.txt"
}
snapshot post
echo "containers started/restart-count diff (only svod-engine may appear):"; diff "$OUT/started-pre.txt" "$OUT/started-post.txt" | grep -E '^[<>]' || echo "  none"
echo "running units diff (only svod.service may appear):"; diff "$OUT/units-pre.txt" "$OUT/units-post.txt" | grep -E '^[<>]' || echo "  none"
echo "existing vhost status codes pre vs post:"; diff "$OUT/vhosts-pre.txt" "$OUT/vhosts-post.txt" && cat "$OUT/vhosts-post.txt"
echo "secrets never in config/logs:"; for f in /srv/svod/config/secrets/user-nikola.key /srv/svod/config/secrets/agent-nikola-claude.token; do
  $S grep -rqF -f "$f" /srv/svod/config/config.json /srv/svod/home 2>/dev/null && echo "  LEAK in config/home for $(basename $f)" || echo "  $(basename $f): not found in config.json or ~/.svod logs"
  $S journalctl -u svod --no-pager | grep -qF -f <($S cat "$f") && echo "  LEAK in journal" || echo "  $(basename $f): not in journal"
done
