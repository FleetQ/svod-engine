# Architecture — shared vault sprint 1 (contract 0.30.0)

Design: `design-shared-vault.md`. Test plan: `test-plan-shared-vault.md`.

## A. Engine (`~/htdocs/svod`, Kotlin)

### A1. Config (`lifecycle/SvodConfig.kt`)
```kotlin
val appApiTls: TlsSettings? = null          // same shape as mcpTls
val localAdmin: Boolean = true               // loopback + no token ⇒ local UI principal (admin)
val users: List<UserSettings> = emptyList()

data class UserSettings(
    val userId: String, val name: String, val email: String? = null,
    val keyRef: String,                      // Secrets ref (env:/file:/keychain:), never raw
    val admin: Boolean = false,
    val grants: List<VaultGrant> = emptyList(),
)
data class VaultGrant(val vault: String, val role: String)   // READER | EDITOR
```
Validation additions:
- `host !in LOOPBACK` is allowed **iff** `appApiTls != null && users.isNotEmpty()`; otherwise the existing error stays.
- `localAdmin == false` ⇒ `users.isNotEmpty()` (else nobody can reach the engine).
- userId pattern `^[a-z0-9][a-z0-9_-]*$`, unique; keyRefs non-blank + unique; grant vault ids exist; roles ∈ `USER_ROLES = [READER, EDITOR]`.
- `toUserSpecs()` resolves `keyRef` through `Secrets.resolve` (mirrors `toAgentSpecs`).

### A2. Principal model (`api/Principal.kt`, new)
```kotlin
enum class VaultRole { READER, EDITOR }
data class Principal(
    val userId: String, val author: Author, val admin: Boolean,
    val grants: Map<String, VaultRole>, val local: Boolean,
) {
    fun canRead(vault: String) = admin || local || vault in grants
    fun canWrite(vault: String) = admin || local || grants[vault] == VaultRole.EDITOR
    fun roleLabel(vault: String): String   // "admin" | "editor" | "reader"
}
class UserRegistry(specs: List<UserSpec>) { reload(); authenticate(key): Principal?; byUserId(id) }
```
`authenticate` is constant-time over keys (copy of `AgentRegistry.constantTimeEquals`). Local principal = `Principal("local", config.uiAuthor, admin = true, grants = {}, local = true)`.

### A3. Auth plugin (`api/AppApiAuth.kt`, new; installed in `AppApiServer.module()`)
`createApplicationPlugin("SvodAuth")` with `onCall`:
1. Path `/health`, `/ready`, `/metrics`, and the web viewer (`config.webViewerPath` set and path not under `/api/`) ⇒ skip.
2. `Authorization: Bearer <key>` present ⇒ `users.authenticate(key)`; null ⇒ **401** `ErrorDto("unauthorized", …)`.
3. Absent ⇒ if `config.localAdmin && remoteIsLoopback(call)` ⇒ local principal; else **401**.
4. Store in `call.attributes[PrincipalKey]`.
5. **Admin gate table** (method + path prefix ⇒ admin required), evaluated in the plugin so no route can forget it:
   `POST|DELETE /api/v1/vaults`, `* /api/v1/agents`, `* /api/v1/users`, `POST /api/v1/secrets`, `PUT /api/v1/settings/backup`, `POST /api/v1/backup/now`, `POST /api/v1/sync/now`, `POST /api/v1/maintenance/reindex`, `POST /api/v1/index/{pause,resume,reembed}`, `POST /api/v1/graph/rebuild`, `PUT /api/v1/embedder`, `POST /api/v1/embedder/test`, `* /api/v1/update/*`, `POST|DELETE|PUT /api/v1/sources*`, `POST /api/v1/import`. Non-admin ⇒ **403** `ErrorDto("forbidden", …)`.
   WebSocket `/api/v1/events` authenticates like any route (the app sends the header on the upgrade request).

`remoteIsLoopback`: `call.request.origin.remoteAddress` (Ktor CIO) ∈ {127.0.0.1, ::1, 0:0:0:0:0:0:0:1, localhost} — `remoteHost` may already be resolved; check both.

### A4. Per-vault authorization (`AppApiServer.kt`)
- `RoutingContext.vault()` (line ~139): after resolving, if `!principal.canRead(v.id)` ⇒ throw `Forbidden`. `install(StatusPages)` maps `Forbidden` ⇒ 403 JSON, `Unauthorized` ⇒ 401.
- New `RoutingContext.author(vc: VaultView): Author` = `principal.canWrite(vc.id) ? principal.author : throw Forbidden`. Replace every `config.uiAuthor` in routes (write 266, delete 272, move 278, restore 298, memory 522/757/786, sources resolve 1037, event publish 988) with `author(vc)` / `principal.author`. `config.uiAuthor` stays as the local principal's author.
- `GET /api/v1/vaults`: non-admin ⇒ filter to `grants.keys`; every row gains `role` (contract: optional string).
- `GET /api/v1/search?across=true`: post-filter hits to readable vaults.
- `/api/v1/events`: per-connection filter — an event whose `data.vault` is set and not readable by the principal is dropped.

### A5. Users admin + me + secrets
- `api/UserRouting.kt`: `interface UserAdmin` (list/create/update/delete/rotateKey) + typed errors (InvalidRequest 400, Conflict 409, UnknownUser 404) + `UsersView`, `UserSpecView`, `CreatedUserView(user, key)`.
- `lifecycle/UserController.kt` (mirrors `AgentController`): persists via `ConfigStore.update {}`, then `registry.reload(config.toUserSpecs())`. **Key generation**: 32 random bytes (`SecureRandom`) → base64url, prefixed `svk_`; written to `<configDir>/secrets/user-<id>.key` (create 0600 via `PosixFilePermissions` where supported), config keeps `file:<path>`. Rotate = overwrite file + reload ⇒ old key fails on the next call. Delete = remove config entry + delete key file + reload.
- `lifecycle/SecretStore.kt`: `POST /api/v1/secrets {name, value}` ⇒ writes `<configDir>/secrets/<name>.secret` (name pattern `^[a-z0-9][a-z0-9_.-]*$`, 0600, overwrite allowed) ⇒ `{ref: "file:…"}`.
- Routes in `AppApiServer`: `GET/POST /api/v1/users`, `PUT/DELETE /api/v1/users/{id}`, `POST /api/v1/users/{id}/key`, `GET /api/v1/me`, `POST /api/v1/secrets`. `userAdmin`/`secretStore` null ⇒ 501 (same pattern as `agentAdmin`).
- `GET /api/v1/me` ⇒ `MeDto{userId, name, admin, local, grants:[{vault, role}]}` — also the app's connection test.
- `SvodNode.kt`: build `UserRegistry(config.toUserSpecs())`, `UserController`, `SecretStore(configDir)`, pass into `AppApiServer(…, userAdmin, secretStore, users)`; `AppApiServer.Config` gains `localAdmin`, `tls: Tls?`. `start()` uses `sslConnector` when `tls != null` (copy of `SvodMcpServer.start`).

### A6. Contract + version
- `contract/openapi.yaml` 0.30.0: paths `/api/v1/users`, `/api/v1/users/{id}`, `/api/v1/users/{id}/key`, `/api/v1/me`, `/api/v1/secrets`; schemas `UserDto`, `CreateUserRequest`, `UpdateUserRequest`, `CreatedUserDto`, `RotatedKeyDto`, `MeDto`, `VaultGrantDto`, `CreateSecretRequest`, `SecretRefDto`; `VaultInfoDto.role?`; components `securitySchemes.bearerKey`; documented 401/403 on `/api/v1/*`.
- `ApiCompatibility.CURRENT_CONTRACT_VERSION = "0.30.0"`, gradle `version = "1.20.0"`, `SvodNode.currentAppVersion` literal, `AppApiContractTest` implemented-routes set, CHANGELOG, `docs/adr/0019-app-api-principals.md`.

## B. App (`~/htdocs/svod-ui-macos`, Swift)

### B1. Engine profiles (`App/EngineProfiles.swift`, new)
```swift
struct EngineProfile: Codable, Identifiable { let id: String; var name: String; var baseURL: URL }
@MainActor final class EngineProfileStore: ObservableObject {
    init(defaults: UserDefaults = .standard, secretsDir: URL? = nil)   // INJECT in tests
    @Published private(set) var profiles: [EngineProfile]             // key "svod.settings.engineProfiles" (JSON)
    func add(_ p: EngineProfile, apiKey: String) throws               // key → <AppSupport>/Svod/engine-<id>.key 0600
    func remove(_ id: String)                                          // deletes key file (guarded to engine-*.key)
    func apiKey(for id: String) -> String?
}
```

### B2. Vault keys (`Networking/VaultKey.swift`, new)
`"<vaultId>@<profileId>"` for remote engines; bare id for the local engine (`@` cannot appear in an engine vault id, and `:` is already taken by `GlobalNoteRef`). `VaultKey.parse(_:) -> (vaultId, profileId?)`, `VaultKey.make(vaultId:profileId:)`.

### B3. Router client (`Networking/MultiEngineClient.swift`, new) — `final class MultiEngineClient: SvodClient`
- Holds `local: LiveSvodClient` + `remotes: [profileId: LiveSvodClient]` built from `EngineProfileStore` (each remote `LiveSvodClient` carries `bearerKey`; `LiveSvodClient` gains `init(baseURL:session:bearerKey:)` and sets `Authorization` on every request and on the WebSocket upgrade).
- `setActiveVault(key)` parses the key, sets `active = (client, bareId)`, calls `client.setActiveVault(bareId)`. `activeVault` returns the key. `baseURL` returns the active client's.
- Every per-vault and per-engine method forwards to `active.client` (default: local). Explicit-vault overloads (`readFile(path:inVault:)`, `federatedSearch`) parse the key.
- `health()`/`ready()` **always** hit the local engine (EngineModel owns the local lifecycle). New `remoteReady(profileId)` + `me(profileId)` for Settings.
- `vaults()`: local vaults as-is; each reachable remote's vaults re-keyed (`id = key`, `name` unchanged, `engineId = profileId`, `engineName`); an unreachable remote contributes nothing and is recorded in `unreachable: Set<String>` (published for the UI). Never throws because one remote is down.
- `events()`: merges the local stream with one stream per remote; remote events are re-tagged so `data.vault` becomes the key. Reconnect logic stays in `EngineModel` (one merged stream ⇒ one failure ⇒ one reconnect; acceptable for sprint 1).
- `reloadProfiles()` rebuilds `remotes` after Settings changes.

### B4. AppModel / VaultModel / Editor
- `SvodApp.swift`: `AppModel(client: MultiEngineClient(local: LiveSvodClient(), profiles: EngineProfileStore()))`. `AppModel.updateBaseURL` targets `local`.
- `VaultModel`: unchanged API; `Vault` DTO gains non-coded `engineId: String?`, `engineName: String?`, coded `role: String?`. `activeVaultRole` computed.
- `VaultSwitcherView`: sections per engine (Local / profile name), role pill (`read-only` for reader).
- `EditorModel/EditorView`: `isReadOnly = app.vault.activeVaultRole == "reader"` ⇒ editor `.disabled`, banner "Read-only in this vault". Engine 403 still surfaces as an error if bypassed.

### B5. Settings
- `ConnectionSettingsView`: new "Central engines" group — list (name, host, status: connected as X / unreachable), **Add** sheet (name, URL, API key; "Test" calls `GET /api/v1/me`; Save writes profile + key; then `app.reloadEngines()`), Remove (confirm).
- New `MembersSettingsView` (section `.members`, "Members", icon `person.2`): for the active vault's engine; `GET /me` ⇒ if `admin` ⇒ list users (name, id, admin badge, grant chips), Add/Edit sheet (name → slug id, email, admin toggle, per-vault role picker none/reader/editor over that engine's vaults), Revoke (confirm), Rotate key; **KeyRevealSheet** shows the key once with Copy. Non-admin ⇒ "You are <name> (<role>)". Engine `< 0.30` ⇒ "needs a newer Svod engine".
- `SyncBackupSettingsView`: when the active vault is remote, hide "Connect GitHub" (device flow is local-only) and show "Connect with token": repo URL + token ⇒ `POST /secrets {name: "backup-<vault>", value: "https://x-access-token:<token>@github.com/<owner>/<repo>.git"}` ⇒ `PUT /settings/backup` with the ref. Gated `apiVersionAtLeast(0,30)`.
- DTOs: `Me`, `UserInfo`, `UsersInfo`, `CreateUserRequest`, `UpdateUserRequest`, `CreatedUser`, `RotatedKey`, `SecretRef` (lenient decode). `SvodClient` protocol: `me()`, `users()`, `createUser`, `updateUser`, `deleteUser`, `rotateUserKey`, `createSecret`. Mock implements with in-memory data.

### B6. Version
`MARKETING_VERSION 0.2.23`, `CURRENT_PROJECT_VERSION 25` (release only after the engine ships; the app must tolerate 0.29 engines: every new surface is gated on `apiVersionAtLeast(0,30)` or a 501/404).

## C. Data flow (remote vault write)
App (editor save) → `MultiEngineClient.writeFile` → active remote `LiveSvodClient` adds `Authorization: Bearer svk_…` + `?vault=socialscore` → engine SvodAuth plugin: key → Principal(maria, EDITOR on socialscore) → `vault()` canRead ✓ → `author(vc)` canWrite ✓ → `engine.write(…, Author("Мария", email))` → git commit author Мария, committer hostId → `COMMIT_CREATED{vault, author: "Мария"}` → every WS client with read on socialscore receives it → other apps `reconcileExternalChange`.

## D. Deployment notes (not built this sprint, documented for the ADR)
- Standalone: `host: "0.0.0.0"`, `appApiTls: {keystore…}`, `localAdmin: false`, `users: [admin]` — the app trusts the cert via the system keychain (self-signed ⇒ install it; Let's Encrypt ⇒ nothing to do).
- Behind fleetq-01 nginx: engine stays on loopback, `localAdmin: false`, nginx terminates TLS and proxies `/api/v1/events` with WebSocket upgrade headers.
- First admin is created by editing the config once (or `users` in the config with a `file:` key ref); after that everything is through the app.
