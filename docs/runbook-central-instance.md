# Runbook: a central Svod instance for a team

Status: written for engine 1.31.0 / contract 0.39.0 (ADR-0020); the `syncBranch` part and section 12 for 1.32.0 / 0.40.0. Sections 1(b), 2-5, 7, 9 and 11 were
executed end to end on a fresh checkout (see "What was verified" at the end). Everything marked
**[not run]** was derived from code or docs and has not been executed. It replaces
`deploy-central-engine-debian.md`, which was written before any installation; the corrections are
listed at the end.

## 0. Topology

```
 colleague A: Claude Code ──┐                             ┌─ vault "team" (git repo, own index)
 colleague B: Claude Code ──┼─ HTTPS ─ reverse proxy ─ central engine ─┤
 colleague C: Svod app ─────┘   /mcp → MCP port                   └─ backup remote (optional)
                                /…   → App API port
                          optional replica engines ── shared bare git repo ── central engine
```

- One **central engine** holds the team vaults. It is the team's MCP endpoint (`/mcp`, bearer token
  per agent) and the App API (`/api/v1/…`, personal key per person).
- A **person** is a `users[]` entry (ADR-0019): a name, an email, a personal App API key, `admin`, and
  `EDITOR`/`READER` grants per vault.
- An **agent** is an `agents[]` entry (ADR-0005/0014/0020): one MCP token. It is bound to a person with
  `user`, so its commits are authored by that person, and it holds `{vault, role}` grants
  (`reader` < `writer` < `maintainer`). The sharing unit is the vault; there is no per-folder access.
- Optional **replicas** (a colleague's own engine) sync through a shared bare git repo (section 10).

Per person you issue two secrets: the App API key (for the Svod app and `/api/v1`) and one MCP agent
token per agent they run (for Claude Code and similar). They are separate files and are revoked
separately.

## 1. Install the engine

Needs: Debian 12 or similar, `git`, `curl`, `openssl`, 2 vCPU / 4 GB RAM (the JVM takes about 1 GB).
Only port 443 (the proxy) is exposed; the engine ports stay on loopback or a private docker network.

### 1(a) From a release tarball [not run]

The release carries `SvodEngine-linux-x64.tar.gz` (a jpackage image with its own Java, includes
`onnx-local`) and `SHA256SUMS`. The tarball's top directory is `SvodEngine/` (from `release.yml`).

```bash
V=1.31.0
cd /tmp
curl --fail --location -O "https://github.com/FleetQ/svod-engine/releases/download/v${V}/SvodEngine-linux-x64.tar.gz"
curl --fail --location -O "https://github.com/FleetQ/svod-engine/releases/download/v${V}/SHA256SUMS"
grep ' SvodEngine-linux-x64.tar.gz$' SHA256SUMS | sha256sum -c -
sudo mkdir -p /srv/svod/engine
sudo tar -xzf SvodEngine-linux-x64.tar.gz -C /srv/svod/engine --strip-components=1
# launcher: /srv/svod/engine/bin/SvodEngine
```

If the repository is private, `curl` needs a token; keep it out of shell history (`op run`, a netrc
file). The single-file `svod-engine-linux-x64` is best-effort and has no `onnx-local`; do not use it here.

### 1(b) From a source checkout (JDK 20) [run]

```bash
git clone https://github.com/FleetQ/svod-engine.git /srv/svod/src     # or any checkout of the branch to deploy
export JAVA_HOME=/path/to/jdk-20                                       # JDK 20 or newer
/srv/svod/src/engine/gradlew -p /srv/svod/src/engine installDist --no-daemon
sudo mkdir -p /srv/svod/engine
sudo cp -a /srv/svod/src/engine/build/install/svod-engine/. /srv/svod/engine/
# launcher: /srv/svod/engine/bin/svod-engine   (needs JAVA_HOME or `java` on PATH)
```

The build prints `BUILD SUCCESSFUL` and the launcher is `bin/svod-engine`. In the examples below
`ENGINE=/srv/svod/engine/bin/svod-engine`; for the tarball use `/srv/svod/engine/bin/SvodEngine`.

## 2. Service user and directories

```bash
sudo useradd --system --home /srv/svod/home --create-home --shell /usr/sbin/nologin svod
sudo install -d -o svod -g svod -m 755 /srv/svod/vaults
sudo install -d -o svod -g svod -m 750 /srv/svod/config
sudo install -d -o svod -g svod -m 700 /srv/svod/config/secrets /srv/svod/config/tls
sudo chown -R svod:svod /srv/svod/engine
```

Layout, and why it is fixed:

| Path | What | Note |
|---|---|---|
| `/srv/svod/config/config.json` | engine config | **the engine rewrites it** on every user/agent/vault change, so `svod` must be able to write it |
| `/srv/svod/config/secrets/` | key and token files | must be `<dir of config.json>/secrets`: `POST /users`, `POST /secrets` write here |
| `/srv/svod/config/tls/` | keystore (engine-native TLS only) | |
| `/srv/svod/vaults/team/` | the vault: a git repo plus `.svod/` (index, audit, `backup.json`) | the engine runs `git init` and writes the first commit if the directory is empty |
| `/srv/svod/home/` | `user.home` of the service user | logs `~/.svod/logs/svod.log`, host id `~/.config/svod/host-id`, discovery file `~/.config/svod/engine.json`, model cache `~/.cache/svod-models` |

## 3. Secrets and first keys

The config never contains a key or token: every secret field is a reference (`file:`, `env:`,
`keychain:`; `keychain:` is macOS only). Files are 0600 under `/srv/svod/config/secrets/`. Keys are
read when the engine starts, so a user or agent declared in `config.json` must have its file
**before** the first start.

```bash
SECRETS=/srv/svod/config/secrets
newkey() { printf '%s%s' "$1" "$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=')"; }

# App API key of every person declared in config.json (the same shape the engine generates itself)
for p in alice bob; do
  (umask 077; newkey svk_ | sudo -u svod tee "$SECRETS/user-$p.key" >/dev/null)
done
# One MCP agent token per person's agent
for p in alice bob; do
  (umask 077; newkey svt_ | sudo -u svod tee "$SECRETS/agent-$p-claude.token" >/dev/null)
done
sudo chmod 600 "$SECRETS"/*
```

Do not `echo` these values and do not paste them into a ticket or chat. Hand each colleague their own
two values through the password manager (1Password share), once. Operator copies belong in the AI Agent
vault of the operator, never in `Personal`.

Only the first admin has to be created by hand. Every later person can be created with
`POST /api/v1/users` (section 7), which generates the key and shows it once.

## 4. `config.json`

Two people, one team vault, agents bound to people, no keyless admin, TLS inside the engine (the
variant for a proxy on another host or in a container; for a proxy on the same host see 6A).

```json
{
  "host": "0.0.0.0",
  "appApiPort": 7517,
  "mcpPort": 7518,
  "localAdmin": false,
  "hostId": "central",
  "secretScanning": true,

  "vaults": [
    { "id": "team", "name": "Team", "path": "/srv/svod/vaults/team", "hostId": "central" }
  ],
  "defaultVault": "team",

  "embedder": { "provider": "onnx-local", "onnxModelId": "multilingual-e5-small" },

  "appApiTls": {
    "keystorePath": "/srv/svod/config/tls/svod.p12",
    "keystorePassword": "file:/srv/svod/config/tls/keystore.pass",
    "keyAlias": "svod",
    "keyPassword": "file:/srv/svod/config/tls/keystore.pass"
  },
  "mcpTls": {
    "keystorePath": "/srv/svod/config/tls/svod.p12",
    "keystorePassword": "file:/srv/svod/config/tls/keystore.pass",
    "keyAlias": "svod",
    "keyPassword": "file:/srv/svod/config/tls/keystore.pass"
  },

  "users": [
    { "userId": "alice", "name": "Alice Example", "email": "alice@example.com",
      "keyRef": "file:/srv/svod/config/secrets/user-alice.key",
      "admin": true,
      "grants": [ { "vault": "team", "role": "EDITOR" } ] },
    { "userId": "bob", "name": "Bob Example", "email": "bob@example.com",
      "keyRef": "file:/srv/svod/config/secrets/user-bob.key",
      "admin": false,
      "grants": [ { "vault": "team", "role": "EDITOR" } ] }
  ],

  "agents": [
    { "agentId": "alice-claude", "name": "Alice's Claude", "role": "WRITE", "user": "alice",
      "token": "file:/srv/svod/config/secrets/agent-alice-claude.token",
      "vaults": [ { "vault": "team", "role": "maintainer" } ] },
    { "agentId": "bob-claude", "name": "Bob's Claude", "role": "WRITE", "user": "bob",
      "token": "file:/srv/svod/config/secrets/agent-bob-claude.token",
      "vaults": [ { "vault": "team", "role": "writer" } ] }
  ]
}
```

Rules the engine enforces at startup (`SvodConfig.validate()`; a violation prints every problem and
exits). The ones that matter here:

- `host` other than `127.0.0.1`/`::1`/`localhost` needs **all four**: `appApiTls`, `mcpTls`, a non-empty
  `users[]`, and `localAdmin: false`. Observed message for a bare `0.0.0.0`:
  `host '0.0.0.0' is not loopback: appApiTls is required to expose the App API` (and the same for `mcpTls`).
- `localAdmin: false` needs at least one `admin` user. Keep it `false` on any shared engine, also on
  loopback behind a proxy: with `true`, a request with no key is the local admin, and a proxy on the same
  host makes every outside request look local.
- `users[].keyRef` and `agents[].token` must be non-blank; keys must be unique; `keyRef` must be a
  reference (`env:`, `file:`, `keychain:`), never the key itself.
- `userId`/`agentId` match `^[a-z0-9][a-z0-9_-]*$`. `users[].grants[].role` is `READER` or `EDITOR`.
- `agents[].user` must name an existing `users[].userId` (fatal otherwise). A role must belong to a vault
  the agent is granted; a grant to an unknown vault is an error.
- `agents[].role` (`WRITE` | `READ_ONLY`) is the ceiling: `READ_ONLY` caps every grant at `reader`.
- `appApiPort` and `mcpPort` differ.

How ADR-0020 reads in this file:

- `vaults` entries are a bare id (a `writer`, the old form) or `{vault, role}`.
- Role enforcement applies to an agent that opts in: it has a `user`, or at least one `{vault, role}`
  entry. Every other agent behaves as before.
- `reader`: read/search/graph tools. `writer`: also write, edit, move, delete, remember. `maintainer`:
  also promote. Conflict resolution, backup and sync settings are App API operations (people with
  `EDITOR` or `admin`), not MCP tools.
- Choose the embedder with the machine in mind: `onnx-local` runs on the CPU and downloads its model on
  first start (outbound internet needed once); `none` is keyword search only and needs no download.

The TLS keystore, when the engine terminates TLS (6B):

```bash
sudo -u svod sh -c 'umask 077; openssl rand -base64 24 | tr -d "=+/\n" > /srv/svod/config/tls/keystore.pass'
sudo -u svod keytool -genkeypair -alias svod -keyalg RSA -keysize 2048 -validity 825 -storetype PKCS12 \
  -dname "CN=svod.example.com" -ext "SAN=dns:svod.example.com" \
  -keystore /srv/svod/config/tls/svod.p12 \
  -storepass:file /srv/svod/config/tls/keystore.pass -keypass:file /srv/svod/config/tls/keystore.pass
```

The keystore may be PKCS12 or JKS. Both ports can share one keystore. Put the real DNS name (and any
docker container name the proxy uses) in the SAN; a self-signed certificate is fine when the proxy
trusts it explicitly or skips verification on the private hop.

## 5. systemd

`/etc/systemd/system/svod.service` **[unit not run under systemd; `systemd-analyze verify` only]**:

```ini
[Unit]
Description=Svod engine (central)
After=network-online.target
Wants=network-online.target

[Service]
User=svod
Group=svod
WorkingDirectory=/srv/svod
ExecStart=/srv/svod/engine/bin/svod-engine /srv/svod/config/config.json
Environment=JAVA_HOME=/path/to/jdk-20
Environment=JAVA_TOOL_OPTIONS=-Xmx1g
Restart=on-failure
RestartSec=5
NoNewPrivileges=true
ProtectSystem=strict
ReadWritePaths=/srv/svod
PrivateTmp=true
ProtectHome=true

[Install]
WantedBy=multi-user.target
```

For the tarball install drop `JAVA_HOME` and use `ExecStart=/srv/svod/engine/bin/SvodEngine …`.
`ReadWritePaths=/srv/svod` covers config, secrets, vaults and the service user's home. The engine takes
an exclusive lock on each vault, so a second instance on the same vault refuses to start.

```bash
sudo systemctl daemon-reload && sudo systemctl enable --now svod
sudo journalctl -u svod -n 50 --no-pager
```

What to look for: `Responding at https://…:7517` and `…:7518` (or `http://` without engine TLS) and a
line `svod-engine ready on java …`. The banner under it still prints `http://… (loopback)` even with TLS
on a non-loopback host; ignore it and test with `/ready`:

```bash
curl -s --cacert /srv/svod/config/tls/svod.crt https://localhost:7517/ready
# {"ready":true,"engine":true,"index":true}
```

(Export the certificate for `--cacert` with
`keytool -exportcert -rfc -alias svod -keystore svod.p12 -storepass:file keystore.pass -file svod.crt`.)
On a loopback engine without TLS use `http://127.0.0.1:7517/ready`. Restart is `systemctl restart svod`;
a restart was exercised in the gate (users, agents and tokens come back from `config.json`).

## 5a. Encryption at rest

**[not run]** The engine does not encrypt anything itself; the supported setup is a LUKS2 volume
whose passphrase or key you hold ([ADR-0021](adr/0021-encryption-at-rest.md)). Everything the
engine writes in this runbook's layout is under `/srv/svod` (section 2: the vaults, the config
directory with `secrets/` and `audit-api.log`, and the service user's home with `~/.svod/logs`,
`~/.config/svod` and `~/.cache/svod-models`), so one volume mounted at `/srv/svod` covers it. That
holds only while you keep it so: an external source folder (ADR-0016, imported from and, with
`writeBack`, written back to), a local bare repository used as a sync or backup remote, or
`SVOD_LOG_DIR` / `SVOD_DISCOVERY_FILE` pointing outside `/srv/svod` must sit on encrypted storage too.
The full path list is in [security-overview.md, Encryption at rest](security-overview.md#encryption-at-rest).
Set this up **before section 1**, on an empty disk or partition (`/dev/sdb` below is an example):
section 1 installs the engine into `/srv/svod/engine`, and mounting a volume at `/srv/svod` later
would hide that install. On a host that already has `/srv/svod`, stop the engine, mount the new
volume elsewhere, copy `/srv/svod` onto it with `cp -a`, check the copy, and only then mount it at
`/srv/svod`.

```bash
sudo apt-get install -y cryptsetup
sudo cryptsetup luksFormat --type luks2 /dev/sdb      # asks for the passphrase; keep it in your password manager
sudo cryptsetup open /dev/sdb svod-data
sudo mkfs.ext4 /dev/mapper/svod-data
sudo mkdir -p /srv/svod
sudo mount /dev/mapper/svod-data /srv/svod
```

To unlock and mount it at boot, add it to `/etc/crypttab` and `/etc/fstab` (with `nofail` the host
still boots when nobody types the passphrase; the engine then stays down, see below):

```
# /etc/crypttab
svod-data  UUID=<uuid of /dev/sdb from blkid>  none  luks
# /etc/fstab
/dev/mapper/svod-data  /srv/svod  ext4  defaults,nofail  0  2
```

`none` means the passphrase is asked for at boot. A key file instead of `none` must itself live on
storage you control (not on the same disk unencrypted), or the encryption protects nothing.

If logs must live elsewhere, point `SVOD_LOG_DIR` (an `Environment=` line in the unit) at a folder
on the encrypted volume, never at an unencrypted path.

The engine must start only after the volume is unlocked and mounted, or it would create an empty
vault on the root disk underneath the mount point. Add to the `[Unit]` section of
`/etc/systemd/system/svod.service` (section 5):

```ini
RequiresMountsFor=/srv/svod
```

Then `sudo systemctl daemon-reload`. With the volume locked, `systemctl start svod` waits for or
fails on the mount instead of starting.

Backup and sync remotes (sections 10 and 11) receive the full git history and are outside this
volume: keep them on encrypted storage too. The same goes for the Sentry project if `SENTRY_DSN` is set.

## 6. TLS and reverse proxy

Which one do you need?

| Situation | Engine config | Where TLS ends |
|---|---|---|
| **A.** proxy on the same host (nginx, Caddy) | `host: 127.0.0.1`, no engine TLS needed, `localAdmin: false` | proxy, with a public certificate |
| **B.** engine reachable off-host: proxy in a container or on another machine, or no proxy at all | non-loopback `host` ⇒ `appApiTls` + `mcpTls` + `users[]` + `localAdmin:false` are **mandatory** | engine (self-signed is fine behind a proxy); a proxy re-encrypts to it |

### What each port serves (read from the code)

| Port | Paths |
|---|---|
| MCP (7518) | `/mcp` only (POST for JSON-RPC, GET for the SSE stream, DELETE to end a session). Nothing else: no `/health`, no `/ready`. |
| App API (7517) | `/health`, `/ready` (no auth), `/metrics` (admin key on a shared engine), `/api/v1/**` (bearer key; includes the WebSocket `/api/v1/events`), and `/` only when `webViewerPath` is set |

So the proxy needs exactly two routes: `/mcp` to the MCP port, everything else to the App API port. The
App API rejects non-canonical paths (`//api/v1/x`, `%xx` segments) with 400, so the proxy must pass the
URI unchanged: no URI part in `proxy_pass`, no rewrites.

### 6A / 6B. nginx in front **[not run: no nginx on the test host]**

```nginx
upstream svod_api { server 127.0.0.1:7517; }          # 6B: server svod-engine:7517; and use https below
upstream svod_mcp { server 127.0.0.1:7518; }

map $http_upgrade $connection_upgrade { default upgrade; '' close; }

server {
    listen 443 ssl;
    server_name svod.example.com;
    ssl_certificate     /etc/ssl/svod.example.com.crt;     # public certificate (Let's Encrypt, Cloudflare origin cert, …)
    ssl_certificate_key /etc/ssl/svod.example.com.key;

    # Engine self-update is admin-only in the engine; do not expose it at all.
    location /api/v1/update { return 404; }

    location /mcp {
        proxy_pass http://svod_mcp;                        # 6B: https://svod_mcp  + proxy_ssl_verify off (or trust the engine cert)
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header Authorization $http_authorization;
        proxy_buffering off;                               # streamable HTTP / SSE
        proxy_read_timeout 3600s;
    }

    location / {
        proxy_pass http://svod_api;                        # 6B: https://svod_api
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header Upgrade $http_upgrade;            # /api/v1/events WebSocket
        proxy_set_header Connection $connection_upgrade;
        proxy_read_timeout 3600s;
        client_max_body_size 50m;                          # vault import uploads
    }
}
```

Notes:

- The engine ignores `X-Forwarded-For`; behind a proxy every request comes from the proxy's address,
  which is exactly why `localAdmin` must be `false`.
- `/ready` and `/health` are open on purpose (no vault data). Restrict them at the proxy if you do not
  want them public.
- A request without `Authorization` on `/mcp` is answered 401 by the engine; the proxy must not add or
  strip the header (the config above forwards it as received).

### 6B without a proxy

Set `host` to the server's address (or `0.0.0.0`), put both TLS blocks in, open the two ports in the
firewall, and give clients the engine's certificate (or use a certificate they already trust). This
passed the same checks as the proxied case in the gate (the gate bound to loopback with both TLS blocks).

## 7. Per-colleague issuance, revocation, rotation

Admin credentials below are an `admin` person's App API key, from a file, not typed: use
`AK=$(cat …/user-alice.key)` inside a script, or `op run`. Base URL `https://svod.example.com`.

### New person (App API key, shown once)

```bash
curl -s -H "Authorization: Bearer $AK" -H 'Content-Type: application/json' \
  -X POST https://svod.example.com/api/v1/users \
  -d '{"userId":"carol","name":"Carol Example","email":"carol@example.com",
       "grants":[{"vault":"team","role":"reader"}]}'
# 201 {"user":{…,"keyRef":"file:/srv/svod/config/secrets/user-carol.key"},"key":"svk_…"}
```

The `key` is in this response only; `GET /users` returns the `keyRef`, never the key. Deliver it, then
clear your terminal. `role` is `reader` or `editor`; `"admin":true` makes an engine admin.

### New MCP agent token for that person

Create the secret file on the server, then register the agent with a reference to it. Never send the
token through the API or the config.

```bash
# on the server
(umask 077; printf 'svt_%s' "$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=')" \
  | sudo -u svod tee /srv/svod/config/secrets/agent-carol-claude.token >/dev/null)

# register: bound to carol, reader on team
curl -s -H "Authorization: Bearer $AK" -H 'Content-Type: application/json' \
  -X POST https://svod.example.com/api/v1/agents \
  -d '{"agentId":"carol-claude","name":"Carol Claude","role":"WRITE","user":"carol",
       "vaults":["team"],"vaultRoles":{"team":"reader"},
       "tokenRef":"file:/srv/svod/config/secrets/agent-carol-claude.token"}'
```

It takes effect on the next call, with no restart. `role` is the ceiling (`WRITE` or `READ_ONLY`),
`vaultRoles` gives the per-vault role. `tokenRef` that is not `env:`/`file:`/`keychain:` is refused (422).
The token file can also be made through `POST /api/v1/secrets {"name","value"}` (stored as
`<name>.secret`, returns a `file:` ref), but then the value travels once through the API; the
server-side file avoids that.

### Revoke

| What | How | Effect (observed) |
|---|---|---|
| a person's App API key and access | `DELETE /api/v1/users/{id}` | key stops working at once (401); the engine deletes `user-<id>.key` |
| one agent's token | `DELETE /api/v1/agents/{id}` | the token gets 401 at once; **the token file stays on disk, delete it by hand** |

Offboarding a person: delete their agents first, then the person, then remove the leftover token files.
At the S5 base the engine does not refuse deleting a person who still has bound agents (ADR-0020 says it
will; **[not verified]**), so do not rely on it. Other people's keys keep working.

### Rotate

- Person key: `POST /api/v1/users/{id}/key` returns the new key once; the old one is 401 at once.
- Agent token: write the new value into the same file (or a new file), then
  `PUT /api/v1/agents/{id} {"tokenRef":"file:…"}` with that ref. Overwriting the file alone changes
  nothing until the registry reloads: observed old token still 200 and new one 401 until the PUT,
  then old 401 and new 200. The `PUT` is the reload.

### What the engine shows about use

`GET /api/v1/users` lists `lastUsedAt`; every keyed App API call and every refusal is a line in
`/srv/svod/config/audit-api.log` (0600); refusals are also in the log with the peer address, never the
key. A token or key value appeared in none of: `config.json`, the engine log, the audit log (checked by
searching those files for every key and token of the gate).

## 8. What the colleague pastes (onboarding)

Claude Code (the engine expects `Authorization: Bearer <agent token>` on `/mcp`, streamable HTTP):

```bash
claude mcp add --transport http svod https://svod.example.com/mcp \
  --header "Authorization: Bearer $SVOD_TOKEN"
```

or the equivalent project/user MCP JSON:

```json
{
  "mcpServers": {
    "svod": {
      "type": "http",
      "url": "https://svod.example.com/mcp",
      "headers": { "Authorization": "Bearer <your agent token>" }
    }
  }
}
```

(`--transport http` and `--header` are checked against `claude mcp add --help`; the JSON field names are
the Claude Code ones **[not run against a client]**.) The token goes into the colleague's own secret
store or an environment variable, not into a repository. Tell them: the token is theirs, an agent writes
as them, and a lost token is replaced by the operator, not recovered.

The MCP protocol itself was exercised with `curl`: `initialize` returns `serverInfo.name = "svod"` and a
`Mcp-Session-Id` header, then `tools/list` and `tools/call` with that header. Clients that send no session
and no `initialize` (a bare `tools/list`) are served statelessly and also get 200. No token, or a wrong
one, is 401.

Per-vault calls take an optional `vault` argument; with several grants a call names the vault, otherwise
it goes to the agent's first granted vault.

## 9. Verify (the gate)

From a machine that can reach the engine. `CA` is the certificate to trust (omit `--cacert` when the
proxy has a public certificate). Tokens come from files; nothing prints them.

```bash
A=https://svod.example.com; M=$A/mcp
curl -s -o /dev/null -w '%{http_code}\n' $A/ready                     # 200
curl -s -o /dev/null -w '%{http_code}\n' $A/api/v1/me                 # 401, no key
curl -s -H "Authorization: Bearer $(cat user-alice.key)" $A/api/v1/me # {"userId":"alice",…,"admin":true,"local":false,"grants":[…]}
curl -s -H "Authorization: Bearer $(cat user-bob.key)"   $A/api/v1/me # {"userId":"bob",…,"admin":false,…}
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $(cat user-bob.key)" $A/api/v1/users  # 403, not an admin

init='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"check","version":"1"}}}'
H=(-H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream')
curl -s -o /dev/null -w '%{http_code}\n' "${H[@]}" -d "$init" $M      # 401, no token
curl -s -o /dev/null -w '%{http_code}\n' "${H[@]}" -H "Authorization: Bearer $(cat agent-bob-claude.token)" -d "$init" $M   # 200
```

Then `tools/list` and a `write` with the returned `Mcp-Session-Id`; the commit must carry the person as
author:

```bash
git -C /srv/svod/vaults/team log --format='%h %an <%ae> | committer %cn | %s' | head
# aff9670 Bob Example <bob@example.com> | committer central | write: gate/hello-from-bob.md
```

## 10. Optional replica (ADR-0009) [bootstrap run, replica engine not started]

Read this before relying on "merge authority": in the shipped engine, sync is the backup service's
two-way mode and it is **symmetric**. Every engine fetches `refs/svod/sync/<vaultId>` from a shared git
remote, reconciles, and pushes it back (class comment of `SyncEngine`). The config fields `syncRemotes`,
`mergeAuthority` and `syncIntervalSeconds` only drive the role label shown in the app; no code starts
syncing from them. What starts sync is a per-vault setting made through the App API. The central
instance is "authority" by convention (it is the host that always runs and holds the MCP endpoint), not
by a runtime mode. ADR-0009's authority/proposal-branch text does not describe this code path.

1. A bare repo both sides can reach, for example on the central host over ssh:
   `git init --bare /srv/svod/sync/team.git` (owned by `svod`), or a private GitHub repo.
2. Turn sync on at the central engine; for a remote that needs credentials, store the full URL as a
   secret and pass its ref:

   ```bash
   curl -s -H "Authorization: Bearer $AK" -H 'Content-Type: application/json' \
     -X PUT "https://svod.example.com/api/v1/settings/backup?vault=team" \
     -d '{"remote":"ssh://svod@svod.example.com/srv/svod/sync/team.git","enabled":true,
          "syncEnabled":true,"syncIntervalMinutes":3}'
   curl -s -H "Authorization: Bearer $AK" -X POST "https://svod.example.com/api/v1/sync/now?vault=team"
   # {"ok":true,"head":"…","conflicts":0}; the bare repo now has refs/svod/sync/team
   ```

   Observed: after `PUT`, `/sync/config` says `"role":"synced"`; the first push happened on `sync/now`
   (the interval loop waits for its first period). Credentials inline in the URL are refused (422).
3. On a colleague's machine, with the engine stopped:
   `svod-engine clone <remote> <dest-dir> team` (observed: creates the repo with the full history, on
   branch `master`), then add `<dest-dir>` to that engine's config as a vault with id `team`, start it, and
   set the same remote with `syncEnabled` through its own App API. **[the replica engine was not started]**
4. Conflicts are never auto-resolved: they appear at `GET /api/v1/conflicts` and resolve with
   `POST /conflicts/resolve`. Both versions stay in history.

### Sync through a plain branch (`syncBranch`)

By default the sync ref is `refs/svod/sync/<vaultId>`, which GitHub and desktop git clients do not list;
the engine force-pushes a read-only mirror to `main` so the vault looks non-empty. That mirror would
overwrite commits a person makes on `main`. To let people work in the repository with plain git, set a
branch:

```bash
curl -s -H "Authorization: Bearer $AK" -H 'Content-Type: application/json' \
  -X PUT "https://svod.example.com/api/v1/settings/backup?vault=team" \
  -d '{"remote":"<repo>","enabled":true,"syncEnabled":true,"syncBranch":"main"}'
```

- The engine fetches `refs/heads/main`, merges with the usual 3-way merge (same files changed on both
  sides become a conflict, never an overwrite) and pushes **without force**. No `refs/svod/sync/*` ref
  and no mirror are written. A branch that does not exist yet is created by the first push.
- An existing repository with its own history can be connected to a vault with other files: the histories
  are unrelated, so the result is the union of the files, and a path that differs on both sides is a conflict.
- Names with `..`, a leading `-` or `+`, whitespace, non-ASCII, `HEAD`, `@`, `@{`, a `refs/` prefix, or a
  trailing `/` or `.lock` are refused (422).
- **Rollback to an engine older than 1.32.0:** first turn sync off on every vault that has `syncBranch` set, or
  clear `syncBranch` (PUT `/settings/backup` without it). An older engine ignores the field, syncs through
  `refs/svod/sync/<vault>` again and force-pushes its mirror to `main`, which overwrites the commits people
  made there. Check with `GET /api/v1/sync/config?vault=<id>` that `syncBranch` is `null` before downgrading.
- A new machine: `svod-engine clone <remote> <dest-dir> <vaultId> --branch main`.
- `GET /api/v1/sync/status` and `/sync/config` carry `lastError` (for example `fetch failed: ...`) while
  the last cycle failed on the network or the credentials; it is empty after a good cycle. Credentials are
  never in it.
- Changing `syncBranch` on a vault that already synced starts from the new ref. The first cycle merges it
  with local history like any other peer.

Replicas hold the whole vault; give a person a replica only if they may read all of it. Access control
on the central engine does not apply to a git remote.

## 11. Per-vault backup (ADR-0014)

Backup pushes the vault's canonical branch to `refs/svod/backup/<vaultId>` on a remote of your choosing,
per vault. Not the same thing as sync (section 10); do not point both at one remote for the same vault.

```bash
# 1. upload the credentialed URL once; the response is a file: ref, never the value
curl -s -H "Authorization: Bearer $AK" -H 'Content-Type: application/json' \
  -X POST https://svod.example.com/api/v1/secrets \
  -d '{"name":"backup-team","value":"https://x-access-token:<PAT>@github.com/ORG/team-backup.git"}'
# 201 {"ref":"file:/srv/svod/config/secrets/backup-team.secret"}

# 2. set the vault's remote with that ref
curl -s -H "Authorization: Bearer $AK" -H 'Content-Type: application/json' \
  -X PUT "https://svod.example.com/api/v1/settings/backup?vault=team" \
  -d '{"remote":"file:/srv/svod/config/secrets/backup-team.secret","enabled":true,"backupIntervalMinutes":60}'

# 3. push now and look at the result
curl -s -H "Authorization: Bearer $AK" -X POST "https://svod.example.com/api/v1/backup/now?vault=team"
```

Observed: `/secrets` writes a 0600 file named `<name>.secret` (name `^[a-z0-9][a-z0-9_.-]*$`); a URL with inline
credentials in `PUT` is 422; `PUT` returns the redacted config and the setting is stored in
`<vault>/.svod/backup.json` (ADR-0014: survives a restart; restart persistence of this file was not
checked). These calls are admin-only. The push itself needs a
reachable remote and was not run in the gate.

Not covered by vault backup, back these up separately: `/srv/svod/config/` (config, user keys, agent
tokens, TLS). Without it a lost server means new keys for everybody.

## 12. fleetq-01 appendix — first real deployment (2026-11-15, engine 1.31.0)

Executed by the DevOps stage (S6). The files used are in `dist/central-docker/` (no secrets in them):
`Dockerfile`, `svod.service`, `svod.fleetq.net.conf`, `config.example.json`, `deploy.sh`, `verify.sh`.

Host facts: the proxy is the container `nginx-proxy` (`nginx:stable-alpine`, owns host :80/:443, vhosts
in `/var/docker/nginx/conf.d/*.conf`, certificates in `/var/docker/nginx/certs`). Backends are
containers on docker networks. ufw drops everything not opened, so a host-native engine would not be
reachable from the proxy container without a firewall change. `*.fleetq.net` uses one Cloudflare Origin
CA certificate (`fleetq.net.crt/.key`).

What was done (all additive):

1. Build `gradlew installDist` from the release commit on a machine with JDK 20, copy
   `build/install/svod-engine/` plus the files above to the host (`/tmp/svod-s6/`).
2. `deploy.sh` (idempotent; aborts if the vhost, container or unit already exist):
   - system user `svod` (nologin, home `/srv/svod`), dirs `/srv/svod/{config,vaults,home}` (750) and
     `/srv/svod/config/{secrets,tls}` (700), all owned by `svod`;
   - secrets generated on the host with `openssl rand`, written by the `svod` user with umask 077
     (`user-nikola.key`, `agent-nikola-claude.token`, `keystore.pass`); never printed;
   - image `svod-engine:1.31.0` (`eclipse-temurin:21-jre-jammy` + `git` + the installDist tree; the
     engine needs a `git` binary for history);
   - self-signed PKCS12 keystore made with the image's `keytool` (`-storepass:file`), SAN `svod-engine`;
   - `config.json` (600, owned by `svod`): `host 0.0.0.0` inside the container, both TLS blocks,
     `localAdmin:false`, one vault `team`, person `nikola` (admin, EDITOR on `team`), agent
     `nikola-claude` bound to `nikola` with `{vault: team, role: maintainer}`, `embedder: none`;
   - new docker network `svod_edge`; new unit `svod.service` running
     `docker run --rm --name svod-engine --network svod_edge --user <svod uid> -e HOME=/srv/svod/home
     -v /srv/svod:/srv/svod svod-engine:1.31.0 /srv/svod/config/config.json` (`/srv/svod` is mounted
     read-write: the engine rewrites `config.json` and writes `secrets/`; `HOME` on the mount keeps
     `~/.svod/logs` across `--rm`);
   - `docker network connect svod_edge nginx-proxy` (adds an interface; no restart);
   - new `conf.d/svod.fleetq.net.conf`; `docker exec nginx-proxy nginx -t`, and only if that passes
     `nginx -s reload` (graceful). If `nginx -t` fails the file is removed and nothing is reloaded.
     `/api/v1/update` is answered 404 by the vhost (updates are done by replacing the image).
3. `verify.sh` takes a before/after snapshot of container start times, running units and five existing
   vhosts, and checks the gate through the real proxy with
   `curl --resolve svod.fleetq.net:443:127.0.0.1 --cacert <Cloudflare origin_ca_rsa_root.pem>` —
   verified TLS against the Cloudflare Origin CA, not `-k`.

Observed (2026-11-15 ~21:00 UTC): `/ready` 200 (`ssl_verify_result=0`); MCP `initialize` with the agent
token 200, without a token 401, with a wrong token 401; App API without a key 401; the same after
`systemctl restart svod`; no existing container restarted, only `svod.service` added to the running
units, existing vhosts returned the same codes before and after; neither token appears in `config.json`,
`~/.svod/logs` or the journal. A write through the proxy was committed with author
`Nikola Katsarov <katsarov@gmail.com>`, committer `fleetq-01` and trailer `Svod-Agent: nikola-claude`.

Corrections against the plan above: the image is not built from the release tarball (installDist +
a JRE base image was simpler and the host's own `java` is not used at all); keytool runs from the image.

**Still open: DNS.** `svod.fleetq.net` does not resolve. It needs a **proxied** (orange cloud) Cloudflare
record pointing at fleetq-01 (`195.201.195.167`); the origin certificate is trusted only through
Cloudflare, so a grey-cloud record would show a certificate error. Until then the instance is reachable
only with `--resolve` on the host.

Handing a token to its owner: the values live only in `/srv/svod/config/secrets/` on the host. Read one
over ssh straight into a password manager, e.g.
`ssh fleetq-01 sudo cat /srv/svod/config/secrets/agent-nikola-claude.token | op item create …` — never
into a chat or a log.

Rollback (removes only what was added): `systemctl disable --now svod`, `rm /etc/systemd/system/svod.service`,
`systemctl daemon-reload`, `rm /var/docker/nginx/conf.d/svod.fleetq.net.conf`,
`docker exec nginx-proxy nginx -t && docker exec nginx-proxy nginx -s reload`,
`docker network disconnect svod_edge nginx-proxy`, `docker network rm svod_edge`,
`docker image rm svod-engine:1.31.0`; `/srv/svod` holds the vault (a git repo) — keep or archive it.

## 13. What was verified, and the corrections to the old runbook

### Executed for this runbook

On a fresh clone of branch `s5-runbook` (base S0): `gradlew installDist` (1(b)), config as in section 4
adapted to loopback with both TLS blocks and a self-signed PKCS12 keystore from `keytool`, engine run as
an unprivileged user on ports 27517/27518. Observed: `/ready` 200; two people's `/api/v1/me` return their
own identity; 401 without a key; 403 for a non-admin on `/users`; two agent tokens each completed
`initialize`, `tools/list` (20 tools) and a `write`; no token is 401; engine restart keeps all of it;
issuance, key rotation, agent-token rotation, revocation and backup configuration behaved as written in
sections 7 and 11 (agent-token rotation and revocation by restart-free API calls); the validation message of section 4; `svod-engine clone` and a sync push to a bare repo.

### Not verified

nginx snippets, the systemd unit under systemd, the release tarball, an MCP client, `onnx-local` (the
gate used `embedder: none`), a replica engine, a real backup push, and the ADR-0020 behaviours that are
built in parallel (the `Svod-Agent` commit trailer, per-role enforcement such as a reader being denied a
write, refusing deletion of a bound person). At the S5 base the git author of an agent bound to a person
is already the person (observed above).

### Corrections to `deploy-central-engine-debian.md`

1. Version: written for 1.20.0/0.30.0; this one is 1.31.0/0.39.0 and covers people-bound agents and roles,
   which the old one lacked.
2. The MCP path was "[unverified]": it is `/mcp` on the MCP port and nothing else (`SvodMcpServer`), and
   `/health`/`/ready` are on the App API port only. The old Caddy `/mcp*` rule works but the server serves
   only `/mcp`.
3. `.remote` file "[unverified]": there is none. A vault's backup/sync setting is `<vault>/.svod/backup.json`.
   Home is irrelevant for it, so `ProtectHome` was never a problem for backup. What does need a writable
   home is logs, host id, discovery file and the model cache; `ReadWritePaths=/srv/svod` with the service
   home inside it covers them.
4. Clarification, not an error: the old text was right that validation refuses a non-loopback `host` without
   `localAdmin:false`. It did not say that behind a same-host proxy the engine is on loopback, validation
   does not force `localAdmin:false`, and you must set it yourself (section 4).
5. Engine-native TLS was one sentence ("works"); the fields, that passwords are `file:` refs, that both
   ports need it, and the keystore recipe are now written out.
6. Starting a vault from a git clone of a backup repo was "[unverified]" and is not repeated here: a new
   vault directory is initialised by the engine (git init plus `init: svod vault`).
7. The config directory had to be `/etc/svod` plus a symlink to `secrets`; simpler and verified: keep
   `config.json` and `secrets/` together in one writable directory, because the engine rewrites the file.
8. `dist/README.md` still says a non-loopback host is refused outright. That is outdated for a shared
   engine (ADR-0019); this runbook is the current description, and `dist/README.md` is a candidate for the
   owner's next docs pass (not edited here).
9. "Merge authority" (also ADR-0020 §5 and the plan): see section 10, the shipped sync is symmetric.
10. Tokens in the app ("Settings → LLM Access") are replaced by the explicit API and file steps, so the
    runbook works without the macOS app.

## 12. Hosted engines: `denyPrivateRemotes`

On an engine where people name their own git remote, set `"denyPrivateRemotes": true` in the config. Sync and
backup will then only talk to `https` remotes whose DNS name resolves to public addresses:

- No plain `http`, `file:`, `ssh` or `git@host:path`. No IP literal in any spelling (dotted, decimal such as
  `2130706433`, octal such as `0177.0.0.1`, hex, bracketed IPv6), no trailing-dot host, no URL fragment, no
  backslash, and no userinfo containing `@ ? # /`. The URL is parsed by JGit's `URIish` and must give the
  same host to `java.net.URI` and `java.net.URL`.
- Nothing that resolves to loopback, RFC1918, link-local (169.254/16, fe80::/10), unique-local (fc00::/7),
  CGNAT (100.64/10), benchmarking (198.18/15), reserved (240/4, 255.255.255.255), multicast or unspecified
  addresses, or IPv6 forms that can wrap one (IPv4-mapped, IPv4-compatible, NAT64, 6to4, Teredo). That covers the cloud metadata endpoints (169.254.169.254, fd00:ec2::254,
  100.100.100.200).
- The name is checked before every cycle and every backup. That check and JGit resolve the name separately,
  so the answer can change in between (DNS rebinding). Every HTTPS connection JGit opens therefore also checks
  the peer address it actually connected to, inside the TLS handshake and before any request or credential is
  sent. Certificate and hostname checks stay on the name in the URL.
- The embedder endpoints (`PUT /embedder`, `POST /embedder/test`, `POST /embedder/models`, admin only) apply the
  same rules to an endpoint the request names (422 when refused). Their HTTP client has no hook for the
  connected address, so for them only the request-time check applies.
- HTTP redirects are not followed.
- Secrets have a purpose by name. An embedder API key must be stored with `POST /api/v1/secrets` under a name
  starting with `embedder-`, and a git remote secret under `git-remote-` (svod-web uses `git-remote-main`).
  Any other ref (`env:`, `keychain:`, another file) is refused with 422 for both. An embedder request that
  names a different endpoint never gets the stored key.

A refused remote shows as an `offline` sync with `lastError` = `remote check failed: remote host not allowed`
(or `fetch failed: ... remote peer address not allowed` when the connect-time check caught it). Only an admin
sees `lastError`; a member gets no `lastError` field.

Residual risk: the connect-time check sees the proxy, not the git host, when the JVM is set to use a forward
proxy. A proxy on a private address is refused; a public proxy is trusted to reach only public hosts. The
connect-time check covers JGit's HTTPS only (sync and backup); the embedder probes are checked at request
time only. A firewall that blocks the team engines' egress to private ranges and metadata addresses is the
outer layer for both.

Leave it off (the default) on a self-hosted engine that syncs to a LAN Gitea.
