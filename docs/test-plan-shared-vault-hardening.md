# Test plan — shared vault hardening (sprint 2a)

Всеки ред е тест, който трябва да падне, ако съответната поправка се махне (негативна проверка
преди PR, както в sprint 1).

## Engine — `PrincipalAuthTest` (fixture от sprint 1: vault a/b, boss/maria/ivan)

| # | Тест | Очаквано |
|---|---|---|
| H1 | keyless loopback заявка с `Host: evil.example:7517` при `localAdmin=true` | 401; `Host: 127.0.0.1:<port>`, `localhost:<port>`, `[::1]:<port>` → 200 |
| H2 | същата заявка с валиден bearer и `Host: evil.example` | 200 (Host важи само за keyless път) |
| H3 | липсващ `Host` (HTTP/1.0 raw socket) без ключ | 401 |
| A1 | maria (EDITOR на a) чете `/tree?vault=a` и пише файл; boss чете `/users` | audit файлът има по един ред за всяка заявка: userId, method, каноничен path, vault, status; редовете за `boss` не съдържат `svk_` |
| A2 | локалният UI (keyless loopback) прави 5 заявки | audit файлът няма редове (local principal не се одитира) |
| A3 | заявка с грешен ключ | 401 и WARN ред в лога с IP + path, без стойността на ключа (проверка през logback appender в теста или през `ListAppender`) |
| L1 | maria се автентикира → `GET /users` (boss) показва `lastUsedAt` за maria ≈ now; ivan без заявки → `null` | ISO-8601 UTC |
| L2 | две автентикации в рамките на 60 s | файлът `user-activity.json` е записан веднъж (mtime/съдържание), in-memory стойността е втората |
| L3 | рестарт (нов `UserActivity` от същия файл) | `lastUsed(maria)` е запазено |
| R1 | ivan (READER) `GET /settings?vault=a` | `vaultPath == ""`, `host == ""`; boss вижда реалния път |
| R2 | ivan `GET /sources?vault=a` (един регистриран source) | `path` е само basename; boss — пълен път |
| R3 | ivan `GET /sync/config?vault=a` с remote `https://x:tok@github.com/o/r.git` | `backupRemote == "https://github.com/o/r.git"` |
| M1 | `localAdmin=false`: `GET /metrics` без ключ → 401; с ключа на ivan → 200; `localAdmin=true` без ключ → 200 | |
| V1 | ivan `GET /tree?vault=b` (без grant) | 404 с body идентичен на `GET /tree?vault=zzz` |
| V2 | ivan `PUT /file?vault=a` | остава 403 (read-only) |

## Engine — `SharedEngineConfigTest` / `UserActivityTest` (нов)

- `UserActivity.touch` при недостъпен файл (dir без права) → не хвърля, WARN.
- Формат на файла: `{ "maria": 1757070000000 }` → `load()` → `lastUsed("maria")`.

## Engine — contract

- `AppApiContractTest`: implemented-routes set непроменен; версия 0.31.0.
- `GraphRagUnitTest`, `VersionConsistencyTest` → 0.31.0 / 1.21.0.

## App — XCTest

| # | Тест | Очаквано |
|---|---|---|
| C1 | `AddEngineSheet` валидация (извлечена в `EngineAddress.parse`) | `http://svod.example.com` → nil; `https://svod.example.com` → URL; `http://127.0.0.1:7517` → URL; `http://localhost:1` → URL |
| C2 | `MultiEngineClient`: активен `x@central`, после `configure(remotes: [])` | `tree()` хвърля `.offline`; `local.served` празен; след `setActiveVault("local-main")` работи |
| C3 | `writeFile` в същото състояние | `.offline`, нищо не е писано никъде |
| C4 | `MembersModel` с mock без `lastUsedAt` (0.30 engine) | зарежда се, полето е nil, няма crash |
| C5 | два `MockSvodClient` инстанции | създаден user в едната не се вижда в другата |
| C6 | merged-events тестът с remote, който никога не yield-ва | пада за < 3 s със съобщение, не виси |

Пълен suite: engine ≥ 439 + нови, 0 провала; app ≥ 53 + нови, 0 провала; router класът 8× подред.

## Негативна проверка преди PR

Махни: Host allowlist (H1 пада), audit hook (A1), redaction (R1–R3), 404 (V1), metrics guard
(M1), dead-engine (C2/C3). Всяко трябва да счупи точно своя тест.

## Live проверка

Throwaway engine (`localAdmin=false`, boss key): `curl -H 'Host: evil.example'` без ключ → 401;
`/metrics` без ключ → 401; `audit-api.log` расте; `user-activity.json` се появява до 60 s след
първата заявка; `GET /users` показва `lastUsedAt`. После :7619 с main (localAdmin=true):
app 0.2.23 и MCP bridge-овете продължават да работят (те пращат `Host: 127.0.0.1`).
