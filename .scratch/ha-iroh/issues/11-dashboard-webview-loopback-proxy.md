# 11: Dashboard WebView + loopback proxy + external_auth

**What to build:** The app's main screen shows the full HA frontend for the active server with no login prompt and live updates, served through an in-app loopback proxy over the iroh tunnel. Leaving the screen closes the port. Completes M6.

Covers Rust side: `Tunnel.startLoopbackProxy()` → `LoopbackProxy { port(), token(), stop() }` on `127.0.0.1:0`, random 128-bit lowercase-base32 token, peek request head (≤8 KiB) and require `Host: <token>.localhost:<port>` else 403, replay peeked bytes then pipe bidirectionally, `stop()` closes listener and conns. Kotlin side: WebView (JS, DOM storage), load `http://<token>.localhost:<port>/?external_auth=1` on resume, `proxy.stop()` + `tunnel.close()` on pause/stop, `externalApp` JS interface (`getExternalAuth` with refresh/force, `revokeExternalAuth` → delete tokens + route to login, `externalBus` answering `config/get` with `hasSettingsScreen: true`), interface only on the proxy origin, external links → Custom Tab, back → `goBack()`. Verify `*.localhost` resolves in Android System WebView (spec risk 9) and the external_auth shapes against current frontend source (risk 10); decide whether to pin port/token per server (risk 11).

**Blocked by:** 06 (Server model + QR scan + login_flow + token storage)

**Status:** done

- [x] Main screen shows HA frontend without login; entity state changes appear live (WS through proxy)
- [x] `curl -H 'Host: evil' http://127.0.0.1:<port>/` → 403; correct Host → proxied
- [x] Backgrounding the app closes the port (`ss`/`netstat` or `adb shell`) and the tunnel
- [x] Frontend logout → tokens cleared locally, server shows Log in
- [x] "App settings" entry reachable (Settings → Companion app for admins; sidebar for non-admins, per frontend source)
- [x] Rust test for the Host gate (match, mismatch, duplicate/missing Host, oversized head)

## Comments

**Implemented (impl-11).** Rust: `core/src/proxy.rs` (`host_ok`, `LoopbackProxy`), `Tunnel::start_loopback_proxy`, `Tunnel::disconnect` also stops proxies. Tests `core/tests/proxy.rs` (Host gate unit test + in-process bridge integration: 403 / proxied / port closed after stop); shared fixtures moved to `core/tests/common/mod.rs`. Kotlin: `Dashboard.kt` (+ `HaClient.accessToken(force)` cached with expiry, `MainActivity` start destination = last/first logged-in server's Dashboard, top bar server dropdown + gear to server list, Back from list returns to dashboard). `androidx.browser` added; network security config allows cleartext to `localhost` + subdomains (needed, else the WebView blocks `http://<token>.localhost`).

**E2E (emulator, HA 2026.x demo, bridge over n0 relay):**
- Dashboard shows the frontend with no login; toggling `light.kitchen_lights` via REST updated "Lights 5 on" to "4 on" live (WS through proxy).
- `adb forward` + curl: `Host: evil` -> 403; `Host: <token>.localhost:<port>` -> 200; right token wrong port -> 403.
- HOME key: listener gone from `netstat`, bridge logged `disconnected`. Resume: new port/token, page reloads.
- `hass.auth.revoke()` (what frontend logout calls) -> refresh token cleared, app shows server detail with "Log in".

**Risk 9 (`*.localhost`):** resolves in Android System WebView 145 with no DNS; no fallback needed.

**Risk 10 (external_auth shapes), verified against frontend `dev` `src/external_app/external_auth.ts` + `external_messaging.ts`:**
- `externalApp.getExternalAuth(json)`, payload `{callback: "externalAuthSetToken", force?: true}`; reply `window[callback](true, {access_token, expires_in})` or `(false)`.
- `externalApp.revokeExternalAuth(json)`, payload `{callback: "externalAuthRevokeToken"}`; reply `window[callback](true)`.
- `externalApp.externalBus(json)` gets `{id, type: "config/get"}`; reply by calling `window.externalBus({id, type: "result", success: true, result: {hasSettingsScreen: true, canWriteTag: false}})`. Frontend blocks on this reply at startup, so it must always be answered. `config_screen/show` (no reply) -> we navigate to the server list.
- `externalApp` (V1) is used because `externalAppV2.postMessage` also exists in the frontend; V2 + `addWebMessageListener` would give a real origin allowlist (see below).

**Deviation, "App settings" placement:** for admin users the frontend does NOT put it in the sidebar (sidebar shows Settings instead); it appears as the "Companion app" row on the Settings page (`ha-config-dashboard`). Non-admins get the sidebar entry. Verified the row is rendered (DOM via DevTools) and that clicking it opens the app's server list. The Settings page rendered blank in screenshots on the emulator although the DOM was complete; looks like an emulator compositing glitch (`tile memory limits exceeded`), not verified on a device.

**Risk 11 decision:** not pinned. New port/token/origin per resume; frontend localStorage is lost each time, auth comes from external_auth so nothing user-visible broke. Revisit only if a stale service worker shows up.

**Known limits / follow-ups:**
- `addJavascriptInterface` is exposed to all frames of the WebView; handlers check that the top-level URL host is the current `<token>.localhost`, so a cross-origin iframe inside an HA page could still call it. `externalAppV2` + `WebViewCompat.addWebMessageListener` with an exact origin rule is the strict fix.
- Frontend logout only clears the refresh token locally; it is not revoked on the HA server (`/auth/revoke`).
- Active server is not persisted; after process death the first logged-in server is shown.
- `proxy.stop()` is asynchronous (cancels the listener task); the test polls until the port refuses.
- Layout uses a fixed 80 dp top padding for the system action bar (same hack as the other screens).

**Review fixes (impl-11, round 2):**
- JS bridge now per-origin: `addWebMessageListener("externalAppNative", {http://<token>.localhost:<port>})` + document-start shim defining `window.externalApp` (a different listener name than `externalApp`, since the shim owns that global). Fallback to `addJavascriptInterface` if the WebView lacks the features (logged as `modern=false`). This supersedes the "all frames" limit noted above.
- Proxy: exactly one `Host` header and the port are now required; 403 is flushed with a FIN; token from `rand`; oversized head tested through the TCP path.
- Pause-during-connect no longer leaks a tunnel; Custom Tab only for http/https; trusted origin compares host and port; WebView paused/resumed with the lifecycle and detached before destroy.
