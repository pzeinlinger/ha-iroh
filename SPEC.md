# HANDOFF: ha-iroh — Home Assistant Android prototype over iroh

Status: design agreed, nothing built. Audience: coding agent / dev picking this up.
Date: 2026-10-10.

## 1. Goal

Lightweight Android app that reaches Home Assistant **only via iroh** (no port forwarding, no Nabu Casa, no VPN) and provides:

1. **Android Device Controls** (pull-down / quick settings "Device controls" panel) for HA entities, live state + actions.
2. **HA Assist voice assistant** (voice + text), launched from a Quick Settings tile and launcher shortcuts.
3. **Normal HA dashboard** (HA frontend in a WebView) with no second login.
4. **Multiple HA servers**, each behind its own bridge.

Everything else from the official HA app is out of scope: no sensors, no notifications, no widgets, no wake word, and no default-assistant role (Gemini stays the system assistant).

## 2. Agreed decisions

| Topic | Decision |
|---|---|
| Min Android | **12 (minSdk 31)**, target latest |
| App stack | Kotlin + Jetpack Compose, Rust core via **uniffi** + **cargo-ndk** |
| Bridge | Rust binary, **dumb pipe**: iroh bi-stream ⇄ TCP to HA:8123. Shipped as a PyPI platform wheel and run as a subprocess by a **HA custom integration** (HACS). Must work on HA Container (no Supervisor/add-ons) |
| Device management | Paired phones are HA **devices** of the `ha_iroh` integration: pairing QR via config flow, delete device = revoke |
| HA auth | On phone, via HA **`/auth/login_flow`** native form (username/password + MFA steps) → refresh token |
| Bridge access | **Allowlist of app EndpointIds**, filled via **QR pairing** with one-time code |
| Relay | n0 public relays by default, **configurable** (custom relay URLs) |
| Connection lifetime | **No warm keeping.** Cold connect when the panel or Assist opens, close when done |
| Assist invocation | **QS tile + static launcher shortcuts** (voice / text). Optional: stateless "Assist" control in the Device Controls panel |
| Assist pipeline | HA preferred pipeline; settings picker override (`assist_pipeline/pipeline/list`) |
| Assist UI | Minimal bottom-sheet activity: transcript, reply, mic button, text field |
| Dashboard | WebView → **in-app loopback proxy** (`<token>.localhost:<port>`, Host-token gate, listener only while visible) → iroh stream. Auth via HA frontend **`external_auth`** (JS interface `externalApp`) |
| Multi-server | One bridge per HA instance. App keeps a server list. **One app EndpointId for all bridges**. Controls grouped per server via `setStructure` |
| Assist server | Default server in settings + server switcher chip in the sheet |
| iroh | **1.3.x** (docs.rs latest as of 2026-09-28) |
| Nix | devenv shell only. No NixOS module (HA Container is the target) |

## 3. Architecture

```
Android app (Kotlin)
  ├─ ControlsProviderService ─┐
  ├─ Assist sheet activity ───┼─ HaClient per server (OkHttp HTTP + WS)
  ├─ Setup/Login/Settings ────┘        │ custom SocketFactory + Dns
  └─ Dashboard WebView ── http://<tok>.localhost:<port> ──┐
                                       ▼                   ▼
                 Rust core (.so, uniffi): ONE iroh Endpoint, N Tunnels (one per server)
                                       + LoopbackProxy (127.0.0.1, token-gated)
                                       │  ALPN ha-iroh/tcp/0 (one bi-stream per TCP conn)
                     ── iroh (relay → holepunch, E2E encrypted) ──
                    ▼                                     ▼
   ha-iroh-bridge A ─▶ HA A :8123          ha-iroh-bridge B ─▶ HA B :8123
```

The bridge never parses HTTP. All HA semantics (auth, WS API, Assist, frontend) live in the app.

The bridge runs **inside the HA container** as a child process of the `ha_iroh` custom integration, so `ha_addr` is `127.0.0.1:8123`.

## 4. Repo layout

```
ha-iroh/
├─ devenv.nix             # devshell
├─ Cargo.toml             # workspace: bridge, core, proto
├─ proto/                 # shared: ALPNs, ticket format, pairing msgs (serde)
├─ bridge/                # bin: ha-iroh-bridge
├─ core/                  # lib (cdylib + uniffi); examples/cli.rs = desktop test client
├─ custom_components/ha_iroh/  # HA integration: runs the bridge, config flow, devices
└─ android/               # Gradle project, app module, generated uniffi Kotlin
```

## 5. Protocols

### 5.1 ALPNs
- `ha-iroh/pair/0`: pairing, open to any peer but requires a valid one-time code
- `ha-iroh/tcp/0`: tunnel, **allowlisted EndpointIds only**; others are closed immediately with an error code

### 5.2 Tunnel (`ha-iroh/tcp/0`)
- One QUIC connection per app session. Each `open_bi()` maps to one new TCP connection from the bridge to `ha_addr`.
- Raw bytes in both directions, no framing. A half-close on either side propagates (`finish()` ⇄ TCP shutdown write).
- The client must write first: iroh streams appear lazily, and HTTP clients always send first anyway.
- The bridge logs the peer EndpointId, the device name from the allowlist, and stream counts. No payload logging.
- Note: HA drops the response if the client half-closes before HA has replied (verified with a raw socket). HTTP clients must not shutdownWrite before reading the response; rely on `Connection: close` / EOF from HA.

### 5.3 Pairing ticket (QR)
URI: `ha-iroh://pair?t=<base64url(JSON)>`
```json
{ "v": 1,
  "addr": <iroh EndpointAddr, serde>,
  "relay": "n0" | ["https://relay.example/"],
  "code": "<base32, 128-bit random>",
  "name": "<bridge display name>" }
```
- Code: single use, TTL 10 min (configurable).

### 5.4 Pairing exchange (`ha-iroh/pair/0`)
- Framing: u32 BE length + JSON, one request and one response.
- Request: `{ "code": "...", "device_name": "Pixel 9" }`
- Response: `{ "ok": true, "bridge_name": "..." }` or `{ "ok": false, "error": "invalid_code|expired" }`
- On success the bridge appends `{endpoint_id, device_name, added_at}` to the allowlist and burns the code.
- Rate-limit failed attempts per remote id.

## 6. Bridge (`bridge/`)

- **Config file (TOML):**
  - `ha_addr` (default `127.0.0.1:8123`)
  - `secret_key_path` (generated on first run, 0600)
  - `allowlist_path`
  - `relay` (`"n0"` or a list of URLs)
  - `pair_ttl`
  - `name`
- **Endpoint:** `Endpoint::builder(presets::N0).alpns(vec![PAIR, TCP]).bind()`. For custom relays, swap the relay map; keep DNS address lookup unless disabled.
- **Daemon:** `ha-iroh-bridge run --config <toml>`. Logs to stderr. Prints `{"event":"ready","endpoint_id":...}` on stdout at start.
- **Admin channel: stdio, JSON lines.** The parent (HA integration, or a human in a terminal) writes one JSON request per line on stdin, the bridge answers one JSON line on stdout. No unix socket; the daemon owns the key, nobody else opens an Endpoint.
  - `{"cmd":"pair"}` → `{"ok":true,"ticket":"ha-iroh://pair?t=...","code":"..."}` (daemon mints a one-time code)
  - `{"cmd":"list"}` → `{"ok":true,"devices":[{endpoint_id, device_name, added_at}]}`
  - `{"cmd":"revoke","endpoint_id":"..."}` → `{"ok":true}`; also kills live connections from that id
  - Unsolicited event lines: `{"event":"paired","endpoint_id":...,"device_name":...}` so the integration can create the device without polling.
  - Responses carry no request id: commands are processed one at a time in order; a stdout line with an `event` key is an event, anything else is the response to the oldest unanswered command.
  - `ready` is printed only after the key and allowlist loaded successfully; stdin EOF or SIGTERM stops the daemon.
- **Allowlist:** TOML, hot reload or in-memory with atomic write.
- **Size target:** small. Accept loop, `tokio::io::copy_bidirectional`-style forwarding per stream.
- **Packaging:** static musl binary, published as a PyPI platform wheel `ha-iroh-bridge` (maturin `bindings = "bin"`), tags `musllinux_1_2` + `manylinux` for `x86_64` and `aarch64`. The HA integration lists it in `manifest.json` `requirements`, so HA installs the right binary with pip. No download code in the integration.

## 6b. HA integration (`custom_components/ha_iroh/`)

- Installable via HACS (custom repository) or by copying the folder. Works on HA Container, Core, OS.
- One config entry. Setup: write `bridge.toml` + key + allowlist under `<config>/ha_iroh/`, spawn `ha-iroh-bridge run` with `asyncio.create_subprocess_exec`, read stdout lines, restart with backoff on exit. Stop on unload.
- Options flow / "Add device": send `pair`, show the ticket with HA's `QrCodeSelector` plus the URI as text. On the `paired` event, create a device in the device registry (identifiers `(ha_iroh, endpoint_id)`, name = device_name, model "ha-iroh app").
- `async_remove_config_entry_device`: send `revoke`, return `True`.
- Options: relay (`n0` / custom URLs), bridge name. Changing options rewrites the TOML and restarts the subprocess.
- Diagnostics: endpoint id, device list, subprocess state. No secrets.
- Python: HA's bundled version, no extra Python deps beyond the wheel.


## 7. Rust core (`core/`, uniffi)

The Kotlin-facing API is blocking (called from Dispatchers.IO), with an internal tokio runtime:

```
object Core(dataDir: String, relay: RelayConfig)
  fun endpointId(): String
  fun pair(ticketUri: String, deviceName: String): BridgeInfo
  fun connect(bridge: BridgeInfo): Tunnel        // throws on timeout/denied
class Tunnel { fun openStream(): Stream; fun startLoopbackProxy(): LoopbackProxy; fun close() }
class Stream { fun read(max: UInt): ByteArray /* empty = EOF */; fun write(b: ByteArray); fun shutdownWrite(); fun close() }
class LoopbackProxy { fun port(): UShort; fun token(): String; fun stop() }
```

- **Multiple servers:** one `Core` / Endpoint for the whole app. `connect()` is called per server, and several Tunnels can be live at once.
- **LoopbackProxy** (for the dashboard WebView only):
  - Binds `127.0.0.1:0`; the token is random 128-bit, lowercase base32 (valid DNS label).
  - Per accepted TCP conn: peek the HTTP request head (up to 8 KiB) and require `Host: <token>.localhost:<port>`. On mismatch, reply 403 and close.
  - Then open a stream, replay the peeked bytes, and pipe bidirectionally.
  - One check per TCP connection is enough: keep-alive requests stay on the same verified conn, and WS upgrades are checked on their first request.
  - Host is forwarded unchanged; HA doesn't validate it.
  - `stop()` closes the listener and all proxied conns.

- **Key:** secret key persisted in `dataDir`, so the app's EndpointId is stable. Wipe it on "unpair".
- **BridgeInfo:** EndpointAddr + relay + name, serialized; Kotlin stores it.
- **Builds:** `cargo ndk -t arm64-v8a -t x86_64` → jniLibs; uniffi bindgen generates Kotlin.
- **Desktop test client:** `examples/cli.rs` does `pair <uri>` and `curl <path>` (HTTP/1.1 GET over the tunnel). It's used to test the bridge without Android.

## 8. Android app (`android/`)

### 8.0 Server model (multi-server)
`Server { id (uuid), name, bridgeInfo, refreshToken (encrypted), selectedEntities, pipelineId?, isDefaultAssist }`, stored in DataStore and Tink-encrypted.
- "Add server" runs the pairing + login flow; the list is managed in settings.
- `ServerManager` lazily creates one `HaClient` per server and tears it down when unused.

### 8.1 Networking
- `IrohSocket : java.net.Socket` wraps `Stream`:
  - `connect()` calls `openStream()`
  - `getInputStream` / `getOutputStream` adapt `read` / `write`
  - `shutdownOutput` maps to `shutdownWrite`
- `IrohSocketFactory` + `Dns` returning a dummy loopback address. Base URL: `http://ha.iroh/`, plain HTTP because iroh already encrypts.
- One `OkHttpClient` per `Tunnel`. WS via `client.newWebSocket(Request("ws://ha.iroh/api/websocket"))`.
- No loopback listener for controls/Assist. The only loopback listener is the dashboard proxy (§7), running only while the dashboard is visible.

### 8.2 Setup flow
1. Scan the QR (CameraX + ML Kit or ZXing). Manual URI paste is the fallback.
2. `Core.pair`, then store BridgeInfo.
3. Login screen using HA login_flow:
   - `POST /auth/login_flow` with `{client_id, handler:["homeassistant",null], redirect_uri}`
   - Loop `POST /auth/login_flow/{flow_id}`, rendering the `data_schema` steps (username/password, MFA code)
   - On `create_entry`, take the `result` code and `POST /auth/token` with `grant_type=authorization_code` → access + refresh token
4. Store the refresh token encrypted (Keystore-backed, e.g. Tink + DataStore).
5. Entity picker: list the supported domains and let the user choose which appear in Device Controls. The Android panel also has its own favorites UI.

### 8.3 HaClient
- On every connect:
  - Refresh the access token (`grant_type=refresh_token`); it lives about 30 min.
  - Open the WS and complete the `auth` handshake.
- **WS commands:**
  - `get_states`
  - `subscribe_entities` (compressed diff format: `a`/`c`/`r`), filtered to the chosen entity ids
  - `call_service`
  - `assist_pipeline/run`
  - `assist_pipeline/pipeline/list`
- **On 401 / invalid refresh:** surface a "re-login required" state.

### 8.4 Device Controls (`ControlsProviderService`)
- `createPublisherForAllAvailable()`: stateless controls from the cached entity list. The cache is refreshed during setup; on cold start it may connect.
- `createPublisherFor(ids)`:
  - Emit cached states first (stale marker via status text), then connect.
  - Subscribe and emit stateful controls on every diff.
  - Close the tunnel when the subscriber cancels (panel closed).
- `performControlAction`:
  - Send `call_service` over the live session (connect if none).
  - Reply via `consumer.accept(ControlAction.RESPONSE_OK)`; the state update arrives through the subscription.
- Reactive glue: `kotlinx-coroutines-jdk9` `Flow.asPublisher()`, since the service must return `java.util.concurrent.Flow.Publisher`.
- **Multi-server:**
  - Control id = `<serverId>:<entity_id>`; `setStructure(server.name)` so the system groups per server; `setZone(area name)` if available (area registry via WS `config/area_registry/list` + entity registry).
  - On panel open, group the requested ids by server and connect to those servers **in parallel**. One server failing marks only its controls as `STATUS_ERROR`.
- Tapping a control opens the dashboard for that server (`more-info` deep link if easy, otherwise the server root). A `PendingIntent` is required by the API.

**Entity mapping:**

| Domain | DeviceType | Template | Action |
|---|---|---|---|
| light | TYPE_LIGHT | ToggleRangeTemplate (brightness 0–100 %), Toggle if no brightness | `light.turn_on/off`, `brightness_pct` |
| switch, input_boolean | TYPE_SWITCH / TYPE_GENERIC_ON_OFF | ToggleTemplate | `homeassistant.turn_on/off` |
| fan | TYPE_FAN | ToggleRangeTemplate if `percentage` supported | `fan.turn_on/off`, `fan.set_percentage` |
| cover | TYPE_BLINDS/GARAGE/… by device_class | RangeTemplate (position) or ToggleTemplate | `cover.set_cover_position` / open / close |
| lock | TYPE_LOCK | ToggleTemplate, auth required (`setAuthRequired`, API 33+; on 31–32 the system requires unlock anyway) | `lock.lock/unlock` |
| climate | TYPE_AC_HEATER/THERMOSTAT | TemperatureControlTemplate wrapping RangeTemplate (target temp, step from attrs) | `climate.set_temperature`, `set_hvac_mode` |
| scene, script, button | TYPE_ROUTINE | StatelessTemplate | `scene.turn_on`, `script.turn_on`, `button.press` |
| sensor, binary_sensor | TYPE_GENERIC_VIEWSTREAM / NO_TEMPLATE | read-only, state in status text | — |

`unavailable` / `unknown` states map to `Control.STATUS_ERROR` with status text.

### 8.5 Assist
- **Entry points:**
  - `TileService`: on Android 14+ use `startActivityAndCollapse(PendingIntent)`; on 12–13 the Intent variant.
  - Static shortcuts "Assist (voice)" and "Assist (text)".
  - Optional stateless control `assist` in the Device Controls panel.
- **Activity:** translucent bottom-sheet activity with transcript, response, mic toggle and text field.
- **Voice flow:**
  1. Start iroh connect **and** `AudioRecord` (16 kHz, mono, PCM16, `VOICE_RECOGNITION` source) in parallel. Buffer audio until the pipeline is ready.
  2. Send `{"type":"assist_pipeline/run","start_stage":"stt","end_stage":"tts","input":{"sample_rate":16000},"pipeline":<optional id>,"conversation_id":<keep within sheet session>}`.
  3. On `run-start`, read `runner_data.stt_binary_handler_id`.
  4. Send binary frames: `[handler_id byte] + pcm chunk`. Flush the buffer first, then stream live.
  5. End of speech: HA VAD fires `stt-vad-end`, or the user taps stop. Then send a frame containing only `[handler_id]`.
  6. Show `stt-end` → transcript and `intent-end` → `intent_output.response.speech.plain.speech`.
  7. On `tts-end`, `tts_output.url` is relative: fetch it over the tunnel with OkHttp and play via MediaPlayer/ExoPlayer from a temp file or custom DataSource.
  8. Handle `error` events and display them.
- **Text flow:** same command with `start_stage: "intent"`, `input: {"text": ...}`. Optional TTS on/off in settings.
- **Server choice:** the sheet opens on the default Assist server. A chip shows the server name, and tapping it switches servers (new tunnel, new `conversation_id`). Shortcuts may carry an optional `serverId` extra.
- **Permissions:** `RECORD_AUDIO` (runtime), plus `POST_NOTIFICATIONS` only if needed. No foreground service, since recording only happens while the activity is visible.

### 8.6 Dashboard (WebView)
- **Activity** = the app's main screen, with a server switcher (top bar dropdown or drawer).
- **On resume:**
  1. `connect` the active server.
  2. `startLoopbackProxy()`.
  3. Load `http://<token>.localhost:<port>/?external_auth=1`.
- **On pause/stop:** `proxy.stop()` + `tunnel.close()` (no warm keeping). Accept the reload on return. WebView keeps its cache, but the port and token change, so the origin changes too.
  - Consequence: frontend localStorage is per-origin and lost. Fine, because auth comes from external_auth.
  - If that's annoying, pin the port/token per server (persisted) so the origin is stable. Decide during implementation.
- **WebView settings:** JS on, DOM storage on, mixed content irrelevant (plain http on localhost is a secure context in Chromium). Media autoplay for camera streams if needed.
- **External auth** (`addJavascriptInterface(obj, "externalApp")`, same contract as the official app):
  - `getExternalAuth(payloadJson)`, payload `{callback, force}`:
    1. Refresh the access token if expired or `force`.
    2. Run `window[callback](true, {access_token, expires_in})` via `evaluateJavascript`.
    3. On failure, call `callback(false)` and route to re-login.
  - `revokeExternalAuth(payloadJson)`: the frontend logged out. Delete that server's tokens, run `callback(true)`, route to login.
  - `externalBus(msgJson)`: answer `config/get` with minimal capabilities (`hasSettingsScreen: true`, everything else false) so the frontend shows an "App settings" entry. Reply via `window.externalBus(response)`. Ignore other messages for now.
  - Only expose the interface for pages on the current `<token>.localhost` origin. Check the URL in `onPageStarted`; never load external links in this WebView, send them to a Custom Tab instead.
- Back button: `webView.canGoBack()` → `goBack()`.

### 8.7 Settings
- Server list: add / rename / unpair / re-login per server, default Assist server
- Per server: entity selection, pipeline picker
- Relay override (n0 / custom URLs)
- Show own EndpointId

## 9. Dev environment

- **devenv shell** (`devenv.nix`, `.envrc` with `use devenv`, fish-friendly): Rust stable with targets `aarch64-linux-android`, `x86_64-linux-android`, `cargo-ndk`, uniffi-bindgen, JDK 17, `maturin`. Android SDK/NDK from the system Android Studio install (`ANDROID_HOME` / `ANDROID_NDK_HOME`).
- **Bridge wheels:** built in CI (GitHub Actions, `maturin` + `cross`/zig for musl aarch64) and published to PyPI on tag. Local dev: `maturin build --release` or plain `cargo build`.
- APK build stays Gradle (not nixified) for the prototype.

## 10. Milestones + acceptance

| # | Deliverable | Done when |
|---|---|---|
| M0 | devenv shell, workspace skeleton | `devenv shell` → `cargo build` + `cargo ndk build` + `./gradlew assembleDebug` all pass |
| M1 | bridge + proto + desktop cli | `cli pair <uri>` then `cli curl /api/` returns HA's 401 response (proves the tunnel). A non-allowlisted id is rejected. `revoke` over stdin cuts a live conn |
| M1b | HA integration | HACS install on HA Container → config flow shows QR → `cli pair` creates a device in HA → deleting the device revokes it |
| M2 | core uniffi + IrohSocket + OkHttp | App debug screen: GET `/api/` through the tunnel shows a response |
| M3 | QR pairing + login_flow + token storage | Fresh install → scan → login (incl. TOTP MFA) → authenticated WS `auth_ok`. Survives app restart |
| M4 | Device Controls | Panel shows chosen entities. Toggling a light in HA updates the tile live, and the tile toggles HA. Cached states appear before connect completes |
| M5 | Assist voice + text | Tile → speak "turn on kitchen light" → light turns on, transcript + reply shown, TTS plays. Text input works |
| M6 | Dashboard | Main screen shows the HA frontend without a login prompt; live updates work (WS through proxy). A request from another origin/Host to the proxy port gets 403. Leaving the screen closes the port |
| M7 | Multi-server | Two HA instances paired. Controls panel shows both, grouped per server, live. One server offline → only its controls show an error. Assist + dashboard switcher work |
| M8 | Packaging + polish | Bridge wheel on PyPI for x86_64/aarch64 musl; HACS repo installs cleanly; README with setup steps |

Build the data model multi-server from M3 onward (server list with one entry), so M7 is mostly UI and parallel connects.

Dev env: two HA instances in Docker (`ghcr.io/home-assistant/home-assistant:stable`) with demo entities, plus an Android emulator API 34+ and one real Android 12 device.

## 11. Risks / verify early

1. **HA `ip_ban` / login attempts:** every phone appears to HA as the bridge's IP (usually 127.0.0.1). A few failed logins could ban the bridge IP and lock out **all** devices.
   - Mitigation: document it and keep `ip_ban_enabled` thresholds sane. The bridge runs inside the HA container, so a dedicated IP is not an option here.
   - A dumb pipe can't add `X-Forwarded-For`. That would need a tiny HTTP-aware mode later; out of scope.
2. **login_flow `client_id` / `redirect_uri`:** HA validates IndieAuth-style. Verify which pair is accepted for a native app without an http redirect page.
   - Candidate: reuse the official app's values (`https://home-assistant.io/android` / `homeassistant://auth-callback`).
   - Alternative: host a small client_id page. Decide in M3.
3. **Cold-connect latency:** relay first, then holepunch; expect roughly 0.3–1.5 s. Measure in M1/M4.
   - Mitigation: state cache plus audio buffering.
   - If the result is unacceptable, revisit "no warm keeping" (user decision).
4. **uniffi + iroh on Android:** check `.so` size, and confirm netwatch/portmapper work without extra permissions. iroh needs `INTERNET` + `ACCESS_NETWORK_STATE`; disable the portmapper feature if problematic.
5. **Device Controls service lifecycle:** the service may be unbound abruptly; always close the tunnel on cancel/destroy. Some OEMs differ in panel behavior. Test on Pixel + one OEM.
6. **`subscribe_entities` compressed format:** implement the diff merge carefully. Fallback is `get_states` + `subscribe_trigger` / `state_changed` events.
7. **Assist binary framing / VAD details:** confirm against the current HA `assist_pipeline` WS docs/source (2026.x), since event names and fields may have shifted.
8. **n0 public relays:** traffic is E2E encrypted, but n0 sees metadata (endpoint ids, timing). A custom relay is already supported via config.
9. **Loopback proxy exposure:** other apps on the phone can reach the port while the dashboard is open. The Host-token gate blocks them unless the token leaks; it never leaves the app process + WebView.
   - Verify that `*.localhost` resolves in Android System WebView without DNS. If not, fall back to `127.0.0.1:<port>` with a token path prefix or cookie gate (weaker).
10. **external_auth contract drift:** verify the `externalApp` / `externalBus` message shapes against current HA frontend source (`src/external_app/`) and the official Android app's `WebViewActivity`/`ExternalBus` code before M6.
11. **Changing origin per session:** a new port means a new origin, which drops frontend localStorage and service worker state each time. Watch for frontend issues (e.g. a stale service worker). Pinning port + token per server is the fix.
12. **One EndpointId across all bridges:** bridges can correlate that the same phone talks to each of them. Accepted for now; per-server keys are a later option.

## 12. Non-goals (explicit)

Sensors / location, push notifications, widgets, wake word, default assistant role, Wear OS, Android Auto, iOS.
