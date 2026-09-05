# Design — shared vault hardening (sprint 2a)

Дата: 2026-09-05. Вход: `security-shared-vault.md` §3 и §5 плюс отложените дефекти от code
review-а на sprint 1 (`retro/retro-2026-09-05-shared-vault.md` в app репото). Think фазата не
задава нови въпроси: обхватът е списък от намерени проблеми, а решенията по-долу са тези, които
review-ът вече препоръча.

## Кой има нужда и какво прави днес

Същите хора като в sprint 1: собственикът и 10–15 колеги на един централен engine, плюс всеки
Mac с локален engine. Днес локалният engine приема заявки без ключ от всичко, което изглежда като
loopback, включително браузър, чийто DNS е пренасочен; централният engine не логва кой е опитал
да влезе и не пази кой какво е чел.

## Най-тесният обхват, който затваря review-а

| # | Проблем (security review) | Решение | Repo |
|---|---|---|---|
| 1 | DNS rebinding при `localAdmin=true` | Keyless loopback заявка се приема само с `Host` от allowlist (`localhost`, `127.0.0.1`, `[::1]`, `::1`, с произволен порт). Иначе 401. Заявка с валиден bearer не се пипа. | engine |
| 2 | Няма audit за хората | `ApiAuditLog` (jsonl) записва всяка `/api` заявка на **не-локален** principal: ts, userId, method, каноничен path, vault, status, remote IP. Без тела, без query стойности освен `vault`. Файл: `<configDir>/audit-api.log`. | engine |
| 3 | 401/403 не се логват | `AppApiAuth` логва WARN с IP, метод, път, причина. Ключът никога не се логва. | engine |
| 4 | Ключовете не показват кога са ползвани | `lastUsedAt` per user: обновява се при успешна автентикация, най-много веднъж на 60 s, persist-ва се в `<configDir>/user-activity.json`, връща се в `GET /users` и `/me`. `expiresAt` — не в този спринт. | engine + app (Members показва „last seen“) |
| 5 | `/settings`, `/sync/config`, `/sources` показват сървърни пътища на READER | За principal без `admin`: `vaultPath` → `""`, `ExternalSourceDto.path` → само последният сегмент, `SyncConfigDto.backupRemote` → host/owner/repo без credential (вече е така? проверява се), `embedder.endpoint` → `""`. Полетата остават (contract е additive). | engine |
| 6 | `/metrics` открит | При `localAdmin=false` `/metrics` иска bearer (всеки principal). `/health` и `/ready` остават открити — не съдържат vault данни. | engine |
| 7 | Vault enumeration 403 vs 404 | Vault без read grant → 404 `not_found`, същото като несъществуващ. Write без EDITOR остава 403. | engine |
| 9 | App приема `http://` към външен адрес | `AddEngineSheet`: адресът е валиден само ако е `https://` или loopback. Test/Add са неактивни иначе. | app |
| — | Профил, махнат по време на autosave, пише в локалния default vault | `MultiEngineClient.configure`: изчезнал активен профил → `current` става клиент към мъртъв порт (`http://127.0.0.1:1`), всяка заявка пада с `.offline` до следващото `setActiveVault`. Никога не се пише в друг vault. | app |
| — | `MockSvodClient.mockUsers` е static | Инстанционно състояние. | app |
| — | Тестът за слети events може да виси | Ограничен wait (2 s) → fail вместо hang. | app |

Извън обхвата и защо: `expiresAt` (review: „по желание“), SSO, reconnect който сваля всички
remote сокети (ефективност, не дефект), дублираните helper-и (cleanup), offline режим (sprint 2b).

## Какво ще накара някого да каже „whoa“

Нищо видимо. Това е спринт, който прави миналия честен: admin отваря Members и вижда кога всеки
ключ е ползван за последно; audit файлът отговаря „кой чете какво“; браузър вече не може да
стигне до личния vault на никого.

## Как се натрупва

Audit + lastUsedAt са основата за SSO/expiry по-късно (кой е активен, кой не). Host allowlist е
единственият ред, който пази всеки Mac днес, независимо дали има централен engine.

## Версии

Engine **1.21.0**, contract **0.31.0** (additive: `UserDto.lastUsedAt`, `MeDto.lastUsedAt`,
404 за ungranted vault, `/metrics` под bearer при `localAdmin=false`). App **0.2.24 (build 26)**.
