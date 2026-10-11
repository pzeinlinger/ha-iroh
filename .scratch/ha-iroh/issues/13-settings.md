# 13: Settings

**What to build:** A settings screen where the user manages servers (rename, unpair, re-login, choose default Assist server), per-server entity selection and Assist pipeline picker (`assist_pipeline/pipeline/list`, overriding HA's preferred pipeline), a relay override (n0 or custom URLs, applied to the single Core Endpoint), and sees their own EndpointId.

**Blocked by:** 09 (Assist text: QS tile + shortcuts + bottom sheet), 11 (Dashboard WebView + loopback proxy + external_auth)

**Status:** done (user verified 2026-10-11)

- [x] Rename / unpair (wipes that server's tokens; wiping the app key only when last server is removed is a decision to record) / re-login per server
- [x] Default Assist server selectable; sheet opens on it
- [x] Pipeline picker lists HA pipelines and the chosen one is sent in `assist_pipeline/run`
- [x] Relay override persists and is used on next Core start
- [x] Own EndpointId shown and copyable
- [x] Reachable from the frontend's "App settings" entry (externalBus)

## Comments

- Option (from ticket 06 review): once the loopback proxy (11) exists, login could use HA's own `/auth/authorize` page in a WebView instead of the native `login_flow` form. That would support non-`homeassistant` auth providers (trusted networks, OIDC add-ons). Decide here.
- Decision: "Unpair" wipes that server's tokens + entity files; the app key (`wipeKey` + `resetCore`) is wiped only when it was the last server, so remaining servers keep the same EndpointId.
- Decision: WebView `/auth/authorize` login deferred; native `login_flow` stays until a non-homeassistant auth provider is actually needed.
- Relay override is stored in plain SharedPreferences and read at `Core` construction; applies after app restart (hint shown, no `resetCore`, avoids leaking the old endpoint).
- Verified on emulator only: settings render; pipeline picker, rename, default switch, unpair not exercised against a live HA.
