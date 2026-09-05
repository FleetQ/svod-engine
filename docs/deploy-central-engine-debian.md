# Централен Svod engine на Debian VPS — runbook

Статус: **написан преди първата реална инсталация**. Всяка стъпка е изведена от кода на engine
v1.20.0 / contract 0.30.0 (ADR-0019) и от `release.yml`, но никой още не е минал през нея на
Linux зад reverse proxy. Стъпките, отбелязани с **[непроверено]**, трябва да се потвърдят при
първото качване и документът да се поправи.

Схемата е тази от `requirements-shared-vault.md` §3: един engine на сървъра държи фирмените
vault-ове и единствен има GitHub remote към org репото; всеки човек работи с macOS app-а, който
има локален engine за личните vault-ове и профил към централния за фирмените.

## 0. Какво трябва на сървъра

- Debian 12, 2 vCPU / 4 GB RAM стигат за 10–15 души без Ollama (JVM-ът взима около 1 GB).
- `git` (engine-ът вика `git status --porcelain` при старт и при възстановяване), `curl`, `caddy`.
- DNS запис към сървъра, напр. `svod.example.com`. Caddy сам вади Let's Encrypt сертификат.
- Портове навън: **само 443**. 7517 (App API) и 7518 (MCP) остават на loopback.

Ползвай **`SvodEngine-linux-x64.tar.gz`** от release-а (jpackage образ със собствена Java, включва
`onnx-local` embedder-а). Единичният binary `svod-engine-linux-x64` е best-effort и **няма
onnx-local** — само keyword търсене или Ollama.

## 1. Потребител, директории, binary

```bash
sudo useradd --system --home /srv/svod --create-home --shell /usr/sbin/nologin svod
sudo mkdir -p /srv/svod/{engine,vaults,secrets} /etc/svod
sudo chmod 700 /srv/svod/secrets

V=1.20.0
curl --fail --silent --location -o /tmp/svod.tgz "https://github.com/FleetQ/svod-engine/releases/download/v${V}/SvodEngine-linux-x64.tar.gz"
sudo tar -xzf /tmp/svod.tgz -C /srv/svod/engine --strip-components=1   # → /srv/svod/engine/bin/SvodEngine
sudo chown -R svod:svod /srv/svod
```

Update: същите три реда с новата версия и `systemctl restart svod`. Self-update-ът
(`/update/apply`) е правен за macOS launchd; на сървъра го прави systemd + tar. **[непроверено]**
дали `/update/apply` изобщо трябва да е достъпен — по-добре го спри в прокси-то (т. 5).

## 2. Ключ на първия admin

Engine-ът чете ключовете само като референции (`file:`, `env:`, `keychain:`); самият ключ никога
не стои в конфига. Ключът на първия admin се прави на ръка, всички следващи — от app-а.

```bash
KEY="svk_$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=')"
echo -n "$KEY" | sudo -u svod tee /srv/svod/secrets/user-boss.key >/dev/null
sudo chmod 600 /srv/svod/secrets/user-boss.key
echo "$KEY"    # запиши го в 1Password (AI Agent vault); екранът е единственото място, където го виждаш
```

Директорията за ключове е **`<dir на config.json>/secrets`** — затова конфигът е в `/etc/svod`,
а `secrets/` трябва да е там: `sudo ln -s /srv/svod/secrets /etc/svod/secrets` (или дръж
конфига в `/srv/svod/config.json`). Всеки `POST /users` и `POST /secrets` пише 0600 файл в нея.

## 3. `config.json`

```json
{
  "host": "127.0.0.1",
  "appApiPort": 7517,
  "mcpPort": 7518,
  "localAdmin": false,
  "hostId": "vps-1",
  "vaults": [
    { "id": "socialscore", "name": "SocialScore", "path": "/srv/svod/vaults/socialscore" }
  ],
  "defaultVault": "socialscore",
  "users": [
    { "userId": "boss", "name": "Nikola Katsarov", "email": "katsarov@gmail.com",
      "keyRef": "file:/srv/svod/secrets/user-boss.key", "admin": true,
      "grants": [ { "vault": "socialscore", "role": "EDITOR" } ] }
  ],
  "embedder": { "provider": "onnx-local", "onnxModelId": "multilingual-e5-small" },
  "graph": { "enabled": true },
  "secretScanning": true
}
```

Защо така:

- **`localAdmin: false` е задължително.** На споделен сървър всеки друг shell акаунт също е
  loopback клиент; с `true` той е admin без ключ. Валидацията отказва `host` извън loopback без
  това, а зад прокси engine-ът е на loopback и правилото трябва да го спазиш ти.
- `localAdmin: false` изисква поне един **admin** в `users[]`, иначе никой не може да добавя хора.
- `hostId` влиза като committer във всеки commit (author-ът е човекът). Сложи нещо, което
  разпознаваш в `git log`.
- Embedder: `onnx-local` с `multilingual-e5-small` работи на CPU и е достатъчен за малък vault,
  но търси слабо между езици. `ollama` с `bge-m3` е по-добър, но без GPU е бавен — ако го искаш,
  Ollama върви на същия сървър и `ollamaEndpoint` остава loopback. `none` = само keyword.
- `secretScanning: true` — engine-ът е достъпен от много хора, нека не пуска токени в бележки.

Вариант без Caddy: `host: "0.0.0.0"` + `appApiTls` и `mcpTls` (`{keystorePath, keystorePassword,
keyAlias, keyPassword}` към `.p12`). Работи (AppApiTlsTest), но сертификатът е твоя грижа.

## 4. Vault-ът: SocialScore от сегашния backup

Backup-ът на локалния engine push-ва пълната git история на vault-а (`main` ==
`refs/svod/backup/socialscore`). Затова на сървъра стига обикновен clone:

```bash
sudo -u svod git clone https://x-access-token:<PAT>@github.com/escapeboy/svod-backup-socialscore.git /srv/svod/vaults/socialscore
sudo -u svod git -C /srv/svod/vaults/socialscore remote remove origin   # engine-ът държи remote-а в собствен файл, не в .git/config
```

**[непроверено]**: engine-ът отваря готов git clone като vault (`SvodEngine.open` + `recover()`
реконсилира дърво ↔ git). Първия път провери `GET /api/v1/tree?vault=socialscore` и `history`.
Индексът (`.svod/`) се строи наново при първия старт — при e5-small на CPU за няколко хиляди
бележки са минути, не секунди. Не копирай `.svod/` от Mac-а: векторите са от друг embedder.

`svod-engine clone <remote> <dest> <vaultId>` е **за sync remote-а** (`refs/svod/sync/<id>`), не
за backup репото. Ползвай го само ако vault-ът е бил в multi-machine sync.

Преди да спреш локалния SocialScore: спри backup-а му от app-а (иначе два engine-а push-ват към
едно репо), после махни vault-а от локалния конфиг или го остави read-only архив.

## 5. Caddy

`/etc/caddy/Caddyfile`:

```caddy
svod.example.com {
    encode zstd gzip

    # Ops surface-ът на engine-а е без автентикация по дизайн. Навън не му е мястото.
    @ops path /health /ready /metrics /api/v1/update/*
    respond @ops 404

    # App API + WebSocket /api/v1/events (Caddy proxy-ва upgrade без допълнителна настройка)
    handle /api/* {
        reverse_proxy 127.0.0.1:7517
    }

    # MCP за агентите на хората (bearer token; engine-ът го проверява сам)
    handle /mcp* {
        reverse_proxy 127.0.0.1:7518
    }

    handle {
        respond 404
    }
}
```

**[непроверено]**: точният path, на който engine-ът сервира MCP (виж `McpServer` / `mcpPort`), и
дали агентите очакват отделен host. Ако е по-просто, дай на MCP отделен subdomain
(`mcp.example.com → 127.0.0.1:7518`). Ако `/health` ти трябва за външен monitoring, пусни го
само от IP-то на монитора (`@ops` + `remote_ip`).

Engine-ът не чете `X-Forwarded-For` (нарочно: `isLoopback` гледа само реалния peer адрес). Зад
прокси всяка заявка е „loopback“ за него, и точно затова `localAdmin` трябва да е `false`.

## 6. systemd

`/etc/systemd/system/svod.service`:

```ini
[Unit]
Description=Svod engine (central)
After=network-online.target
Wants=network-online.target

[Service]
User=svod
Group=svod
WorkingDirectory=/srv/svod
ExecStart=/srv/svod/engine/bin/SvodEngine /etc/svod/config.json
Restart=on-failure
RestartSec=5
Environment=JAVA_TOOL_OPTIONS=-Xmx1g
# hardening
NoNewPrivileges=true
ProtectSystem=strict
ReadWritePaths=/srv/svod /etc/svod
PrivateTmp=true
ProtectHome=true

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload && sudo systemctl enable --now svod
sudo journalctl -u svod -f        # чакай "svod-engine ready"
curl -s 127.0.0.1:7517/ready       # {"ready":true,...} — от сървъра, локално
```

`ReadWritePaths` трябва да включва `/etc/svod`, защото `POST /users` и `POST /secrets` пишат в
`secrets/` до конфига, а `PUT /settings/backup` пише `.remote` файл. **[непроверено]** къде точно
пада `.remote` файлът на Linux (на macOS е `~/Library/Application Support/Svod/`); ако е под
`$HOME` на `svod`, `ProtectHome=true` ще го спре — тогава `ProtectHome=read-only` +
`ReadWritePaths=/srv/svod`.

## 7. Проверка отвън (от твоя Mac)

```bash
H=https://svod.example.com; K=<ключът от т. 2>
curl -s $H/api/v1/vaults                         # 401 — без ключ нищо
curl -s -H "Authorization: Bearer $K" $H/api/v1/me      # {"userId":"boss",...,"admin":true,"local":false}
curl -s -H "Authorization: Bearer $K" $H/api/v1/vaults  # socialscore с "role":"editor"
curl -s -o /dev/null -w '%{http_code}\n' "$H//api/v1/vaults"   # 400 — каноничен път, не 200
curl -s -o /dev/null -w '%{http_code}\n' $H/health              # 404 от Caddy
```

## 8. В app-а

1. Settings → Connection → Central engines → **Add**: име „Company“, адрес `https://svod.example.com`,
   ключът. **Test** пита `/me`; без зелен отговор бутонът Add е неактивен.
2. Превключвателят показва „Company (central)“ със SocialScore. Отваряш, пишеш, commit-ът е с
   твоето име и committer `vps-1`.
3. Settings → Members (само admin): създай човек, ролята му по vault, ключът се показва **веднъж**.
   Ключът стига до човека през 1Password споделяне, не по чат.
4. Settings → Sync & Backup при активен SocialScore: org репото + fine-grained PAT (Contents
   read/write, само това репо). App-ът качва токена с `POST /secrets` в `secrets/` на сървъра и
   подава `file:` референцията — токенът не остава на Mac-а. Провери с `git ls-remote` на org
   репото, че `refs/svod/backup/socialscore` се движи.
5. Агенти: Settings → LLM Access създава MCP token; в Claude/Cursor endpoint-ът е
   `https://svod.example.com/mcp…` с bearer. Това работеше отдалечено и преди.

## 9. Какво още липсва (sprint 2)

- **Offline**: няма. Падне ли VPS-ът, фирмените vault-ове са `unreachable` в app-а, личните
  продължават. Репликата с credential на човека е следващата тема.
- **SSO**: няма; само лични ключове, admin ги ротира от Members.
- **Backup на самия сървър**: vault-овете са в GitHub (org репо), но `config.json` и `secrets/`
  не са никъде. Сложи ги в offsite backup (GPG към FleetQ backup-а) — иначе загубата на VPS-а
  значи нови ключове за всички.
- `GET /settings`, `/sync/config`, `/sources` показват сървърни пътища на всеки READER
  (code review находка, отложена).
