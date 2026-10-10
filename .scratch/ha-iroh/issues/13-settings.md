# 13: Settings

**What to build:** A settings screen where the user manages servers (rename, unpair, re-login, choose default Assist server), per-server entity selection and Assist pipeline picker (`assist_pipeline/pipeline/list`, overriding HA's preferred pipeline), a relay override (n0 or custom URLs, applied to the single Core Endpoint), and sees their own EndpointId.

**Blocked by:** 09 (Assist text: QS tile + shortcuts + bottom sheet), 11 (Dashboard WebView + loopback proxy + external_auth)

**Status:** ready-for-agent

- [ ] Rename / unpair (wipes that server's tokens; wiping the app key only when last server is removed is a decision to record) / re-login per server
- [ ] Default Assist server selectable; sheet opens on it
- [ ] Pipeline picker lists HA pipelines and the chosen one is sent in `assist_pipeline/run`
- [ ] Relay override persists and is used on next Core start
- [ ] Own EndpointId shown and copyable
- [ ] Reachable from the frontend's "App settings" entry (externalBus)

## Comments

- Option (from ticket 06 review): once the loopback proxy (11) exists, login could use HA's own `/auth/authorize` page in a WebView instead of the native `login_flow` form. That would support non-`homeassistant` auth providers (trusted networks, OIDC add-ons). Decide here.
