# 06: Server model + QR scan + login_flow + token storage

**What to build:** Fresh install → "Add server" → scan the bridge QR (manual paste fallback) → native login form driven by HA `login_flow` (username/password, then TOTP MFA step if configured) → app holds a refresh token and opens an authenticated WebSocket (`auth_ok`). Kill and relaunch the app: still authenticated without re-login. Completes M3.

Covers: `Server { id, name, bridgeInfo, refreshToken, selectedEntities, pipelineId?, isDefaultAssist }` list as one Tink/Keystore-encrypted blob under filesDir; `ServerManager` creating one `HaClient` per server lazily; `HaClient` connect = refresh access token + WS `auth` handshake; `login_flow` step rendering from `data_schema`; `POST /auth/token` with `authorization_code`; 401 / invalid refresh → "re-login required" state. Decide and record `client_id` / `redirect_uri` (candidate: official app's values) — spec risk 2.

**Blocked by:** 05 (Rust core uniffi + IrohSocket + OkHttp debug screen)

**Status:** done

- [x] CameraX/ML Kit (or ZXing) QR scan of the pairing ticket; paste fallback (paste verified on emulator; camera scan compiled but not exercised, emulator has no real camera)
- [x] Login works for a user with and without TOTP MFA
- [x] After login the debug screen shows WS `auth_ok`
- [x] App restart → connect → `auth_ok` without re-entering credentials
- [x] Revoking the refresh token in HA → app shows "re-login required"
- [x] Data model is a server list from day one, even with a single entry

## Comments

- **client_id decision:** official app values `https://home-assistant.io/android` / `homeassistant://auth-callback` accepted by HA (stable image) for `login_flow`, `/auth/token` (code + refresh). Fallback `http://ha.iroh/` not needed. Recorded in SPEC §11 risk 2.
- Verified on emulator-5554 vs HA stable docker via bridge: paste URI -> login (user/pass) -> `auth_ok`; force-stop + relaunch -> Connect -> `auth_ok`; refresh token deleted via WS `auth/delete_refresh_token` -> "re-login required"; TOTP enabled via WS `auth/setup_mfa` (module `totp`), login shows `code` step -> `auth_ok`.
- JVM tests: `LoginFlowTest` (incl. MFA step), `HaClientTest` (handshake, invalid_grant, auth_invalid) with MockWebServer; `testDebugUnitTest assembleDebug` pass.
- Deviations: functions are suspend but tests use `runBlocking` (no coroutines-test dep; `runTest` virtual time + real IO is flaky). Session drops WS events (07 adds subscriptions). Old "Unpair/wipeKey" debug button removed. Entity picker (spec 8.2 step 5) not in scope here.
- Deviation: storage is one Tink AES-GCM encrypted JSON blob in `filesDir/servers.bin` (Keystore key), not DataStore (deprecated security-crypto avoided; less code). `allowBackup=false`; an unreadable file resets to an empty list.
- Review fixes: store IO off main + single lock; token redacted from `Server.toString`; 15s timeouts on WS handshake/commands; cancellable HTTP calls; token-endpoint errors never include the body; `ReloginRequired` clears the stored refresh token and Detail offers "Log in" (re-login with stored bridgeInfo, no re-pair).
