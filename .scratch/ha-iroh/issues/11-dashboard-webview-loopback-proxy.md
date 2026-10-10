# 11: Dashboard WebView + loopback proxy + external_auth

**What to build:** The app's main screen shows the full HA frontend for the active server with no login prompt and live updates, served through an in-app loopback proxy over the iroh tunnel. Leaving the screen closes the port. Completes M6.

Covers Rust side: `Tunnel.startLoopbackProxy()` → `LoopbackProxy { port(), token(), stop() }` on `127.0.0.1:0`, random 128-bit lowercase-base32 token, peek request head (≤8 KiB) and require `Host: <token>.localhost:<port>` else 403, replay peeked bytes then pipe bidirectionally, `stop()` closes listener and conns. Kotlin side: WebView (JS, DOM storage), load `http://<token>.localhost:<port>/?external_auth=1` on resume, `proxy.stop()` + `tunnel.close()` on pause/stop, `externalApp` JS interface (`getExternalAuth` with refresh/force, `revokeExternalAuth` → delete tokens + route to login, `externalBus` answering `config/get` with `hasSettingsScreen: true`), interface only on the proxy origin, external links → Custom Tab, back → `goBack()`. Verify `*.localhost` resolves in Android System WebView (spec risk 9) and the external_auth shapes against current frontend source (risk 10); decide whether to pin port/token per server (risk 11).

**Blocked by:** 06 (Server model + QR scan + login_flow + token storage)

**Status:** ready-for-agent

- [ ] Main screen shows HA frontend without login; entity state changes appear live (WS through proxy)
- [ ] `curl -H 'Host: evil' http://127.0.0.1:<port>/` → 403; correct Host → proxied
- [ ] Backgrounding the app closes the port (`ss`/`netstat` or `adb shell`) and the tunnel
- [ ] Frontend logout → tokens deleted, login screen shown
- [ ] "App settings" entry appears in the frontend sidebar
- [ ] Rust test for the Host gate (match, mismatch, oversized head)
