#!/usr/bin/env bash
# S6: additive deploy of svod-engine on fleetq-01. Run as the ssh user with passwordless sudo.
# Inputs in $STAGE (copied by scp): Dockerfile, svod-engine/ (installDist), config.json,
# svod.service, svod.fleetq.net.conf. Never prints a secret.
set -euo pipefail
STAGE=/tmp/svod-s6
OUT=$STAGE/out; mkdir -p "$OUT"
S="sudo -n"
log(){ echo "== $*"; }

snapshot(){ # $1 = tag
  $S docker ps --format '{{.Names}} {{.Status}}' | sort > "$OUT/ps-$1.txt"
  $S docker inspect --format '{{.Name}} {{.State.StartedAt}} {{.RestartCount}}' $($S docker ps -q) | sort > "$OUT/started-$1.txt"
  systemctl list-units --type=service --state=running --no-legend | awk '{print $1}' | sort > "$OUT/units-$1.txt"
  for h in fleetq.net office.fleetq.net hooks.fleetq.net pravoto.app kvesta.app; do
    printf '%s %s\n' "$h" "$(curl -sk -o /dev/null -w '%{http_code}' --resolve $h:443:127.0.0.1 https://$h/ || true)"
  done > "$OUT/vhosts-$1.txt"
}

log "pre-snapshot"; snapshot pre
[ -e /var/docker/nginx/conf.d/svod.fleetq.net.conf ] && { echo "ABORT: vhost already exists"; exit 2; }
$S docker ps -a --format '{{.Names}}' | grep -qx svod-engine && { echo "ABORT: container svod-engine exists"; exit 2; }
[ -e /etc/systemd/system/svod.service ] && { echo "ABORT: unit exists"; exit 2; }
true

log "user + dirs"
id svod >/dev/null 2>&1 || $S useradd --system --home-dir /srv/svod --no-create-home --shell /usr/sbin/nologin svod
UID_=$(id -u svod); GID_=$(id -g svod)
$S install -d -o svod -g svod -m 750 /srv/svod /srv/svod/config /srv/svod/vaults /srv/svod/home
$S install -d -o svod -g svod -m 700 /srv/svod/config/secrets /srv/svod/config/tls

log "secrets (generated on host, 0600, never printed)"
gen(){ # $1 file, $2 prefix
  $S test -s "$1" || $S -u svod sh -c "umask 077; printf '%s' \"$2\$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n')\" > '$1'"
}
gen /srv/svod/config/secrets/user-nikola.key svk_
gen /srv/svod/config/secrets/agent-nikola-claude.token sva_
gen /srv/svod/config/secrets/keystore.pass ""
$S sh -c "stat -c '%a %U %n' /srv/svod/config/secrets/*"

log "image"
$S docker build -q -t svod-engine:1.31.0 "$STAGE" | tail -1

log "tls keystore (self-signed, internal hop nginx -> engine)"
if ! $S test -s /srv/svod/config/tls/engine.p12; then
  $S docker run --rm --user "$UID_:$GID_" -v /srv/svod:/srv/svod --entrypoint keytool svod-engine:1.31.0 \
    -genkeypair -alias svod -keyalg RSA -keysize 3072 -validity 3650 -storetype PKCS12 \
    -keystore /srv/svod/config/tls/engine.p12 -dname "CN=svod-engine" -ext "SAN=dns:svod-engine,dns:localhost" \
    -storepass:file /srv/svod/config/secrets/keystore.pass -keypass:file /srv/svod/config/secrets/keystore.pass >/dev/null 2>&1
fi
$S chmod 600 /srv/svod/config/tls/engine.p12; $S chown svod:svod /srv/svod/config/tls/engine.p12

log "config"
$S install -o svod -g svod -m 600 "$STAGE/config.json" /srv/svod/config/config.json

log "network + unit"
$S docker network inspect svod_edge >/dev/null 2>&1 || $S docker network create svod_edge >/dev/null
sed "s/__UID__/$UID_/; s/__GID__/$GID_/" "$STAGE/svod.service" | $S tee /etc/systemd/system/svod.service >/dev/null
$S systemctl daemon-reload
$S systemctl enable --now svod.service 2>&1 | tail -1

ready_inside(){ $S docker run --rm --network svod_edge curlimages/curl:8.10.1 -sk -o /dev/null -w '%{http_code}' https://svod-engine:7517/ready 2>/dev/null || true; }
log "wait for engine"
for i in $(seq 1 60); do c=$(ready_inside); [ "$c" = 200 ] && break; sleep 3; done
echo "inside-network /ready: $c"
[ "$c" = 200 ] || { $S journalctl -u svod -n 40 --no-pager | sed -E 's/(svk_|sva_)[A-Za-z0-9_-]+/\1***/g'; echo "ABORT: engine not ready"; exit 3; }

log "attach proxy to svod_edge + vhost"
$S docker inspect nginx-proxy --format '{{range $k,$v := .NetworkSettings.Networks}}{{$k}} {{end}}' | grep -qw svod_edge \
  || $S docker network connect svod_edge nginx-proxy
$S install -m 644 "$STAGE/svod.fleetq.net.conf" /var/docker/nginx/conf.d/svod.fleetq.net.conf
$S docker exec nginx-proxy nginx -t 2>&1 | tail -2 || true
if $S docker exec nginx-proxy nginx -t >/dev/null 2>&1; then
  $S docker exec nginx-proxy nginx -s reload
else
  $S rm -f /var/docker/nginx/conf.d/svod.fleetq.net.conf; echo "ABORT: nginx -t failed, vhost removed, no reload"; exit 4
fi
sleep 2
echo "deploy phase done"
