# Architecture — shared vault hardening (sprint 2a)

Вход: `design-shared-vault-hardening.md`. Всичко е в `AppApiAuth`/`AppApiServer` и три малки
нови класа; app промените са в `MultiEngineClient` и `AddEngineSheet`.

## Engine

### `api/AppApiAuth.kt`

```
intercept(Plugins):
  raw → canonicalPath → (400 ако не е канонично)          [sprint 1]
  principal = authenticate(call)                             [sprint 1]
     ├ bearer → registry.authenticate(key) → touch lastUsed  [нов: activity.touch(userId)]
     └ без bearer: localAdmin && isLoopback && hostAllowed   [нов: hostAllowed]
  null → WARN "auth failed ip=… method=… path=… reason=no-key|bad-key|host" → 401
  admin routes → 403 (WARN)
  vault: !canRead → 404 not_found  (беше 403)                [нов]
         write && !canWrite → 403 (WARN)
  call.attributes[PrincipalKey] = principal
after (ApplicationCallPipeline.Call, on response sent):
  if (!principal.local) audit.record(ts, userId, method, path, vault, status, ip)   [нов]
```

- `hostAllowed(call)`: `call.request.headers[Host]` → маха порт (`host:port`, `[v6]:port`) →
  `in setOf("localhost","127.0.0.1","::1","0:0:0:0:0:0:0:1")`. Липсващ `Host` → false (HTTP/1.1 го изисква).
- `originAllowed(call)`: няма `Origin` → true (native клиенти); `Origin` равен на `host:port` от
  `Host` header-а → true (собственият web viewer); всичко друго → false. Покрива cross-origin
  WebSocket, който `Host` allowlist-ът не може (реализирано след review-а на спринта).
- Логът е `org.slf4j.LoggerFactory.getLogger(AppApiAuth::class.java)`, никога не съдържа
  стойността на bearer-а.
- Audit hook: `app.sendPipeline.intercept(ApplicationSendPipeline.After)` не дава статус
  надеждно за WebSocket; по-просто е `app.intercept(ApplicationCallPipeline.Monitoring)` с
  `proceed()` и след него `call.response.status()`. За `/api/v1/events` (WS) се записва един ред
  при затваряне на сокета (status какъвто Ktor е записал; 0 ако няма). Заявки, които хвърлят, се
  одитират със статуса, който клиентът е видял (400 за BadRequestException, иначе 500); отказани
  заявки (400/401/403 преди principal) се одитират като `anonymous`. `/metrics` също се одитира.

### `api/ApiAuditLog.kt` (нов)

`class ApiAuditLog(file: Path, clock)` — същата форма като `mcp/AuditLog`: synchronized append на
един JSON ред `{ts, userId, method, path, vault?, status, ip}`; `entries()` за тестове. Файл:
`<configDir>/audit-api.log`, създаден 0600 през `SecretFiles`? Не — не е секрет, но е лично данни:
0600 е правилното. Ротация: не в този спринт (jsonl, append; logrotate на сървъра).

### `api/UserActivity.kt` (нов)

`class UserActivity(file: Path, clock, minIntervalMs = 60_000)`:
- `touch(userId)`: in-memory `ConcurrentHashMap<String, Long>`; ако последният persist за този
  user е преди > minInterval, записва целия map в `file` (atomic move на temp файл).
- `lastUsed(userId): Long?`; `load()` при старт.
- Никога не хвърля към caller-а (IO грешка → WARN); автентикацията не зависи от него.

`UserRegistry.authenticate` остава чист; `AppApiAuth` вика `activity.touch` след успешен match.
`UserController.list()` и `/me` четат `activity.lastUsed` и го форматират ISO-8601 UTC.

### Redaction за не-admin (`AppApiServer`)

Реализирано като `val admin = principal().admin` + `if (admin) … else …` във всеки от трите route-а
(без общ helper — три места, три различни DTO):
- `/settings`: `vaultPath = ""`, `host = ""`, `embedder.endpoint = null`.
- `/sources` (list + get): `path = path.substringAfterLast('/')`.
- `/sync/config`: credential-ът в remote-а вече се махаше; за не-admin `backupRemote = null`,
  `syncPeers = []`, `hostId = null` — остава само schedule-ът.

### `/metrics`

`get("/metrics")`: ако `!config.localAdmin` и няма `Authorization` header → 401. Понеже
`/metrics` не е под `/api/`, проверката е в самия route през `AppApiAuth.authenticate(call)`.

### Vault 404

Единствената промяна е кодът и текстът на отговора в интерцептора. `PrincipalAuthTest`
„ungranted vault is 403“ се обръща на 404 и добавя: същият body като за несъществуващ vault.

### Wiring (`SvodNode`)

`ApiAuditLog(configDir/audit-api.log)`, `UserActivity(configDir/user-activity.json)`; при
config-less старт → `~/.svod/`. Подават се на `AppApiServer` (ctor) и `UserController`
(за `list()`).

### Contract 0.31.0

- `User.lastUsedAt: string(date-time) | null`, `Me.lastUsedAt`.
- Описание на 404 за vault без grant в `Forbidden`/`NotFound` responses.
- `/metrics`: `security: [{}, {personalKey: []}]` + текст „bearer required when localAdmin=false“.
- `ApiCompatibility.CURRENT_CONTRACT_VERSION`, `GraphRagUnitTest`, `AppApiContractTest`,
  `build.gradle.kts` 1.21.0, `SvodNode.currentAppVersion`, CHANGELOG.

## App

### `MultiEngineClient.configure(remotes:)`

Ако `activeKey` сочи профил, който вече го няма: `current = deadEngine` където
`deadEngine = LiveSvodClient(baseURL: URL("http://127.0.0.1:1")!)` (connection refused →
`.offline` за всяка заявка). Следващият `setActiveVault` от `VaultModel.load()` го замества.
`testUnknownProfileFallsBackToLocal` става `testUnknownProfileFailsClosedInsteadOfWritingLocally`:
`tree()` хвърля `.offline`, `local.served` е празен.

`VaultModel.load()` вече ре-селектира при липсващ ключ (sprint 1), така че UI-ят се оправя сам.

### `AddEngineSheet`

`url` е non-nil само за `https://…` или loopback host; при `http://` към друг host — полето
показва предупреждението (вече го има) и Test/Add са неактивни. Текстът: „Only https:// (or a
loopback address) is accepted.“

### Members: „last seen“

`UserInfo.lastUsedAt: String?` (optional, decodes nil на 0.30), показан като relative date в
реда на члена. `Me.lastUsedAt` — не се показва.

### Тестове/мокове

`MockSvodClient.mockUsers`/`mockKeyCounter` → instance `var`. Merged-events тестът получава
`withTimeout`-подобна граница: `Task` който след 2 s `XCTFail` + прекъсва.

### Версия

`MARKETING_VERSION 0.2.24`, `CURRENT_PROJECT_VERSION 26`.
