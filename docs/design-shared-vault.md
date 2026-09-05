# Design — shared vault, sprint 1: "централен engine + лична идентификация"

- Status: Accepted for build (2026-09-05)
- Requirements: `requirements-shared-vault.md` (brainstorm, два кръга)
- Scope of THIS sprint: identity + roles + network App API (engine), multi-engine app + members UI (app)
- Deferred (sprint 2+): реплика/offline режим, SSO, избор на org в GitHub Connect, присъствие

## Forcing questions (отговорени от brainstorm-а)

**Кой има нужда и какво прави днес?** 10–15 души в компания, които трябва да четат и пишат в една обща памет (SocialScore и вероятно други vault-ове) от своите Mac-ове и от своите LLM агенти. Днес: един човек, един engine на loopback, всяка редакция е „svod-ui“, единственият начин да се сподели vault е да дадеш GitHub token-а на repo-то.

**Най-тесният MVP, за който някой би платил?** Един engine на сървър, всеки колега влиза с личен API ключ от app-а, вижда само vault-овете, за които има роля, може да пише само ако е editor, и историята показва името му. Admin създава/отзовава хора без рестарт. GitHub token-ът на компанийното repo не напуска сървъра.

**Какво кара някого да каже „whoa“?** Отваряш app-а, личните ти бележки са си на твоята машина, компанийните са до тях в същия switcher, и в History виждаш „Мария, преди 3 мин“ вместо „svod-ui“. Агентът ти пише в компанийната памет със същите права като теб.

**Как се натрупва с времето?** Компанийният vault става памет, в която и хора, и агенти пишат с проследимост. Роли per vault позволяват нови vault-ове (проекти, отдели) без нов engine. Repo-то може да се мести между org-ове без хората да усетят.

## Decisions (frozen)

1. **Хората са принципали на App API-то**, със същия модел като MCP агентите: ключ → идентичност (userId, име, email) → права → git author. Нов `UserRegistry` в engine-а, hot-reload като `AgentRegistry`.
2. **Роли per vault**: `READER` (чете, търси), `EDITOR` (пише). Отделен флаг `admin` (управлява engine-а: хора, агенти, backup, vault-ове, update). Admin вижда всички vault-ове.
3. **Loopback без token остава „локалният UI“ (admin)** — `localAdmin: true` по подразбиране, за да не се променя нищо за днешните инсталации. Централен engine се пуска с `localAdmin: false`.
4. **Мрежов bind е позволен само с TLS + поне един потребител.** Алтернатива без промяна в engine-а: engine на loopback зад reverse proxy с истински сертификат (fleetq-01 nginx) и `localAdmin: false`. И двете се поддържат.
5. **Ключът се генерира в engine-а и се показва веднъж** (при създаване и при ротация). Пази се като файл 0600 до config-а, config-ът държи `file:` ref. Никога не се връща от GET.
6. **Тайни за отдалечен engine се качват веднъж**: `POST /api/v1/secrets {name, value}` → `{ref}`; app-ът после подава само ref-а (GitHub token за org repo-то остава на сървъра).
7. **App-ът държи няколко engine-а едновременно.** Локалният е имплицитен профил; централните са „engine профили“ (име, URL, ключ). Vault-овете от отдалечен engine се адресират в app-а като `<vaultId>@<profileId>`; локалните остават с голо id, така че нищо съществуващо не се променя.
8. **Events през WebSocket се филтрират по права** — потребител не получава събития за vault, който не му е даден.
9. Contract **0.30.0**, engine **1.20.0**, app **0.2.23**. ADR-0019.

## Non-goals this sprint
- Реплика (локален clone срещу централния engine) — изисква engine-ът да сервира git трафик; отделен ADR.
- SSO — моделът е ключ-базиран, но `Principal` не знае откъде идва ключът, така че SSO е нов резолвър, не нов модел.
- Права по папка/бележка.
- Org избор в бутона Connect GitHub (локалният engine).
