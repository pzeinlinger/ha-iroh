# 06: Server model + QR scan + login_flow + token storage

**What to build:** Fresh install → "Add server" → scan the bridge QR (manual paste fallback) → native login form driven by HA `login_flow` (username/password, then TOTP MFA step if configured) → app holds a refresh token and opens an authenticated WebSocket (`auth_ok`). Kill and relaunch the app: still authenticated without re-login. Completes M3.

Covers: `Server { id, name, bridgeInfo, refreshToken, selectedEntities, pipelineId?, isDefaultAssist }` list in DataStore, Tink/Keystore-encrypted; `ServerManager` creating one `HaClient` per server lazily; `HaClient` connect = refresh access token + WS `auth` handshake; `login_flow` step rendering from `data_schema`; `POST /auth/token` with `authorization_code`; 401 / invalid refresh → "re-login required" state. Decide and record `client_id` / `redirect_uri` (candidate: official app's values) — spec risk 2.

**Blocked by:** 05 (Rust core uniffi + IrohSocket + OkHttp debug screen)

**Status:** ready-for-agent

- [ ] CameraX/ML Kit (or ZXing) QR scan of the pairing ticket; paste fallback
- [ ] Login works for a user with and without TOTP MFA
- [ ] After login the debug screen shows WS `auth_ok`
- [ ] App restart → connect → `auth_ok` without re-entering credentials
- [ ] Revoking the refresh token in HA → app shows "re-login required"
- [ ] Data model is a server list from day one, even with a single entry
