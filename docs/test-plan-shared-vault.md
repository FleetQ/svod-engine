# Test plan — shared vault sprint 1

Every test below must be shown to FAIL without its fix at least once (revert or comment the guard) before the PR opens.

## Engine (JUnit, `engine/src/test/kotlin`)

### `api/PrincipalAuthTest.kt` (new; fixture = `AppTestSupport` + `users` list + `localAdmin` switch)
1. No header, loopback, `localAdmin=true` ⇒ 200 and a write is authored `svod-ui` (back-compat, the whole existing suite is this case).
2. No header, `localAdmin=false` ⇒ **401** on `GET /api/v1/tree`; `/health` and `/ready` still 200.
3. Unknown key ⇒ 401. Known key ⇒ 200.
4. Reader: `GET /file` 200, `PUT /file` **403** `forbidden`, and the vault HEAD is unchanged.
5. Editor: `PUT /file` 201/200 and `git log -1` author == the user's name/email (not `svod-ui`); `COMMIT_CREATED` event carries `author == user name`.
6. Grant scoping: editor on `a`, nothing on `b` ⇒ `GET /file?vault=b` **403** (not 404), `GET /vaults` lists only `a` with `role: "editor"`; admin sees both with `role: "admin"`.
7. Admin gate: editor ⇒ `PUT /settings/backup` 403, `POST /vaults` 403, `GET /users` 403; admin ⇒ 200/201.
8. `GET /me` for a key ⇒ `{userId, name, admin:false, local:false, grants:[{vault:"a", role:"editor"}]}`; local ⇒ `local:true, admin:true`.
9. Federated search `across=true` returns no hit from a vault the principal cannot read.
10. Events: a reader on `a` connected to `/api/v1/events` does not receive `commit.created` for `b` but does for `a`.

### `lifecycle/UserAdminTest.kt` (new; mirrors `AgentAdminTest`)
11. `POST /users` ⇒ 201 with a `key` starting `svk_`; `GET /users` never contains `key`; the key file exists with mode 0600 (skip the mode assert on non-POSIX).
12. The new key authenticates on the next request **without restart**.
13. `POST /users/{id}/key` ⇒ new key works, old key ⇒ 401 immediately.
14. `DELETE /users/{id}` ⇒ key ⇒ 401 immediately; key file removed; re-DELETE 404.
15. 400 bad id / bad role / unknown vault in grants; 409 duplicate id; PUT partial update keeps omitted fields.
16. `POST /secrets {name, value}` ⇒ `{ref: "file:…"}`, file mode 0600, `Secrets.resolve(ref) == value`; bad name ⇒ 400.

### `lifecycle/SvodConfigTest` additions
17. `host="0.0.0.0"` without `appApiTls` ⇒ error; without users ⇒ error; with both ⇒ valid.
18. `localAdmin=false` with no users ⇒ error.
19. Duplicate userId / duplicate keyRef / unknown grant vault / bad role ⇒ error each.

### `api/AppApiTlsTest.kt` (new, reuse the keystore helper from `McpTlsTest`)
20. `AppApiServer` started with `tls` answers `GET /health` over HTTPS and refuses plain HTTP on the same port.

### Existing
21. `AppApiContractTest."every path declared in the contract is implemented"` updated (5 new paths).
22. `VersionConsistencyTest` passes after the 1.20.0 bump.
23. Full suite `./gradlew test --rerun-tasks` green; count read from `build/test-results/**/*.xml`, not from BUILD SUCCESSFUL.

## App (XCTest, `SvodTests`) — inject a `UserDefaults(suiteName:)` in EVERY test (the XCTest host is the real app)
24. `VaultKeyTests`: `make("socialscore","central")=="socialscore@central"`; `parse("personal")==("personal",nil)`; `parse("a@b@c")` ⇒ vault `a@b`, profile `c` (last `@` wins) — and a `GlobalNoteRef("socialscore@central:notes/x.md")` still splits on the first `:`.
25. `EngineProfileStoreTests`: add/remove round-trips through the injected suite; key file written 0600 and deleted on remove; remove refuses a path outside `engine-*.key`.
26. `MultiEngineClientTests` (two `MockSvodClient`s): `setActiveVault("r@central")` routes `tree()` to the remote mock and `writeFile` too; `vaults()` merges and re-keys; a throwing remote leaves the local list intact and lands in `unreachable`; `events()` re-tags `data.vault` to the key; `health()` goes to local even when the active vault is remote.
27. `MembersModelTests`: create ⇒ `revealedKey` set once, cleared on dismiss, never in `users` list; rotate ⇒ new `revealedKey`.
28. Build `xcodebuild -scheme Svod build test` green, zero new warnings.

## Live verification (after merge, local engine :7619 — the user's dev engine)
29. Deploy 1.20.0 (`installDist` + kickstart) with an unchanged config ⇒ app 0.2.22 keeps working (no header, loopback ⇒ local admin). `GET /api/v1/settings` shows `apiVersion 0.30.0`.
30. Add `localAdmin: false` + one admin user to a **second** engine config on :7719 with a test vault; in the app add a Central engine profile with that key; the vault appears as `<id>@<profile>`; write a note; `git log` in that vault shows the user's name; a second user with READER gets the read-only banner and a 403 on a forced save.
31. Members panel: create user ⇒ key sheet once; rotate; revoke ⇒ profile with old key shows unreachable/401.
32. Remove the :7719 test engine afterwards (it is the only state the verification creates).

## Negative-verification checklist (before PR)
- Comment out the admin-gate table ⇒ test 7 fails.
- Return `true` from `canRead` ⇒ tests 6, 9, 10 fail.
- Skip `registry.reload` after rotate ⇒ test 13 fails.
- Drop the `Authorization` header in `LiveSvodClient` ⇒ test 26 (remote routing with a `me()` check) fails.
