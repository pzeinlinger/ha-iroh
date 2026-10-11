# 19: App design for initial release + sane Device Controls exposure

**Problem:** The app is a debug UI: bare `Text`/`Button` columns, no launcher icon (manifest has no `android:icon`), hand-rolled headings, pairing/login screens show raw output strings and dev buttons (`Connect (WS auth)`, `GET /api/`, `id <uuid>`). The entity picker lists `sensor`/`binary_sensor` (useless read-only tiles in Device Controls) and HA diagnostic/config/hidden entities.

**What to build:** Material 3 polish of the existing screens plus WebView login. No new deps (Compose BOM already ships material3 + material-icons-core). Keep the `Screen` sealed class; screens change as below.

Decisions (grilled 2026-10-11):

Device Controls exposure
- Remove `sensor` and `binary_sensor` from `toSpec()`; delete `Spec.ReadOnly`. Already-selected ids of those domains drop out at publish time (`toSpec() == null`).
- Picker hides entities whose entity-registry entry has `entity_category` (`config`/`diagnostic`), `hidden_by` or `disabled_by` set. `refreshAreas` already fetches `config/entity_registry/list`: persist the hidden set next to the area map (same file, no extra round trip).
- Picker: opt-in stays; add search field, "Select all" per area and globally, `Switch` per row.
- Picker grouping: by area (trailing "No area" group), domain as leading icon.

Identity / theme
- App label stays `ha-iroh`. Adaptive launcher icon as XML vector (`mipmap-anydpi-v26/ic_launcher.xml`, foreground + monochrome layer), `android:icon` set; same vector for the Assist tile.
- Material You dynamic color (`dynamicLight/DarkColorScheme`, minSdk 31), follows system dark mode.
- `TopAppBar` with back arrow on every screen. Dashboard: `DashboardBar` rebuilt as a `TopAppBar`: server name title, path info subtitle, server dropdown, overflow → Settings.

Screens
- **Settings** (replaces `Screen.List` + inline `AppSettings`): Servers section (status rows, Add server; empty state with icon + "No servers yet" + Add button) and App section (EndpointId copyable row, relay override via dialog).
- **Add server:** opens the camera scanner full-screen directly, viewfinder overlay, "Enter code instead" link reveals the paste field (also the fallback when camera permission is denied). After a scan: full-screen "Connecting to <server>…" with progress; success → login; failure → Snackbar with Retry (reopens scanner).
- **Login:** WebView on `<proxy origin>/auth/authorize?client_id=CLIENT_ID&redirect_uri=REDIRECT_URI` through a fresh tunnel + `startLoopbackProxy()`. Intercept `homeassistant://auth-callback?code=…` in `shouldOverrideUrlLoading`, exchange via the existing `OkHttpClient.token(..., "grant_type" to "authorization_code")`. Same hardening as the dashboard WebView (never navigate off the proxy origin, no external links), clear WebView cookies after the code arrives, loading indicator, back = cancel. Delete `LoginFlow.kt` (`Step`/`Field`/`Done`) and the native form. This reverses the deferral recorded in ticket 13; works with any HA auth provider.
- **Server detail:** sectioned `ListItem` rows: Account (status, Log in / Log out), Assist (default toggle, pipeline picker dialog), Device Controls (entity picker entry, "N selected"), Danger zone (Unpair behind `AlertDialog`). Rename via pencil icon. Remove `id <uuid>`, `Connect (WS auth)`, `GET /api/` entirely.

Out of scope: dashboard WebView content, Assist sheet (own theme), onboarding tour.

**Status:** ready-for-human

- [x] `sensor`/`binary_sensor` out of `toSpec()`, `Spec.ReadOnly` deleted, `ControlMapping` tests updated
- [x] Registry filter (entity_category / hidden_by / disabled_by) persisted by `refreshAreas`, unit test
- [x] Picker: area grouping, search, select-all per area + global, switches
- [x] Launcher icon + `android:icon`, Assist tile reuses it
- [x] Dynamic color theme, `TopAppBar` everywhere, dashboard bar rebuilt as `TopAppBar`
- [x] Settings screen (servers + app sections, empty state)
- [x] Pairing: full-screen scanner, manual entry fallback, connecting state, Snackbar retry
- [x] WebView login, `LoginFlow.kt` deleted
- [x] Detail sectioned, dev buttons removed, Unpair confirm
- [x] `./gradlew testDebugUnitTest` green; emulator E2E against throwaway Docker HA: pair, WebView login, pick entities, tile appears; light + dark screenshots
- [ ] User: pairing + WebView login on the phone before commit

## Comments

- Q&A log: sensors first kept (co2/temp), then dropped once the device_class whitelist came up; registry filter yes; picker opt-in with select-all; area grouping; name stays; dynamic color; TopAppBar incl. dashboard; scanner-first pairing; connecting screen + Snackbar retry; WebView login ("feels more secure to the user"); dashboard-grade WebView hardening; dev buttons removed; settings merged with server list; E2E on emulator.

- 2026-10-11 impl: all items built; `./gradlew testDebugUnitTest assembleDebug` green (39 tests, 0 failures).
  **Files:** new `Ui.kt` (AppTheme, BackArrow, Section), `Login.kt` (WebView login); rewritten `MainActivity.kt` (Screen.List -> Settings, Add, Detail, EntityPicker); `Dashboard.kt` (DashboardBar = TopAppBar); `ControlMapping.kt` (sensor/binary_sensor + `Spec.ReadOnly` gone, `isControllable`); `ControlsService.kt`; `Entities.kt` (`Registry`, `hiddenEntities`, `pickerGroups`, `refreshAreas`/`loadAreas` now return/persist `Registry` in the same areas file); res (launcher vector, adaptive icon, colors); manifest; `build.gradle.kts`. Deleted `LoginFlow.kt` + `LoginFlowTest.kt` (token exchange is still covered by `HaClientTest`).
  **Decisions / deviations:**
  - `androidx.compose.material:material-icons-core` is NOT transitive of material3 any more, so it was added to `build.gradle.kts` (BOM-managed, one line). Only dep change.
  - Domain icons in the picker are emoji (extended icon set is not a dependency).
  - Hidden-by-registry entities stay visible in the picker if already selected (otherwise they could never be deselected).
  - Pre-ticket areas file format (plain map) is ignored and rewritten on the next refresh (best effort anyway).
  - `createPublisherForAllAvailable` filters `selectedEntities` through `isControllable`, and Detail's "N selected" counts only those. Already-favourited sensor tiles in the system panel show "unsupported" (no way to drop favourites from the provider).
  - "Connecting to <server>..." is just "Connecting..." on the pair screen (name unknown until pair returns); Login's title shows the name. Failure shows a Failed placeholder + indefinite Snackbar; Retry or dismiss returns to scanner/paste form.
  - Launcher/tile icon: one white vector (house outline + wifi arcs) scaled to fit the splash/adaptive safe circle; shortcuts still use `ic_assist`.
  - Pipeline row shows "Custom" until the list was fetched (ids are not user-readable); list loads on tap.
  **Verified on emulator-5554 vs Docker HA (`ha19`, demo entities, torn down):** pairing via pasted ticket (scanner UI shown with the emulator's virtual camera, QR itself not scannable), "Connecting" state, WebView login over tunnel+proxy (username/password typed, code intercepted, tokens stored, landed on Detail), re-login after Log out (login page shown again), Settings (empty and with server), Detail, Picker (select two entities, select-all UI, switches), dashboard with new bar + path info + overflow, camera-permission-denied -> paste form, bad ticket -> Snackbar Retry, Device Controls: "Add controls" lists exactly the 2 selected entities for ha19, saved, panel shows both tiles live ("Off"), no sensors. Dark + light.
  **Not verified:** real QR scan, picker area grouping and registry hiding against live HA (demo entities have no area/hidden entries; covered by unit tests only), rename/pipeline dialog/unpair dialog on device, back-to-cancel in Login, off-origin link blocking in Login, dynamic color is the default palette on the emulator (no wallpaper colors).
  **Screenshots (superseded by the fix round below):** `/tmp/ticket19/` (settings-empty-light, settings-light/dark, add-paste-light/dark, add-scan-light/dark, add-permission/add-denied-light, add-failed-light, login-light/dark, detail-light/dark, picker-light/dark, dashboard-light/dark, controls-ha19-light, add-controls2). Also a stale `ha08/ha07` Device Controls structure on the emulator is left over from earlier tickets.


- 2026-10-11 fix round (after Standards + Spec reviews), all verified on emulator-5554 vs a fresh throwaway HA:
  - Login: `ensureActive()` after connect so Back mid-connect closes tunnel + proxy; `exchanging` reset on exchange failure; `onRenderProcessGone` handled; Back goes to Detail (pairing is now stored before login, so a cancelled first login leaves a "Login required" row, verified).
  - Settings root has no back arrow when it is home. QS tile uses new `ic_tile.xml` (same glyph scaled to 24dp; seen in the QS tile editor and panel). Launcher icon files are the other agent's (solid house + portal + dot); the earlier "house + arcs, white strokes" description in the first Comments entry is obsolete.
  - Picker domain icons: four 24dp XML vectors `ic_light.xml` (Material lightbulb), `ic_switch.xml` (power_settings_new), `ic_climate.xml` (thermostat) and `ic_fan.xml` (hand-drawn three-blade fan, no Material fan path to hand), tinted via `painterResource`; cover (arrow up), lock, scene/script/button (play) and input_boolean (check) reuse material-icons-core. No emoji, no extended icons. Verified in `picker-light/dark.png`.
  - Dashboard subtitle is "Direct · 2 ms" / "Relay · 40 ms" (+ " · N paths"); long-press on the title toasts the raw iroh path. Unit test `PathLabelTest`.
  - Labels "Endpoint ID", "Default (n0)". Error texts via `Throwable.userMessage()` (uniffi exceptions often have an empty message: the bad-offline-bridge case showed "Couldn't connect: " before, now "Timeout").
  - Camera permission dialog now sits over the viewfinder frame (black background) instead of a blank screen. Paste field capped (`heightIn(max = 120.dp)` + `maxLines = 3`). `title()` moved to `Entities.kt`; unused import gone. Debug-only `--es ticket` is ignored after a config recreate (night-mode toggle used to re-open the paste screen).
  - SPEC.md: HA auth row, section 8.2 step 3 and the M3 acceptance row now describe WebView `/auth/authorize`; the section 11 decision record about `login_flow` was left as history.
  - Final: `testDebugUnitTest` 40 tests, 0 failures; `assembleDebug` green.
  **Screenshots (current, `/tmp/ticket19/`):** settings-empty-light, settings-light (before the server row existed) and settings-dark, add-paste-light/dark (paste-dark predates the field cap), add-scan-light/dark, add-permission (dialog over viewfinder), add-denied-light, add-connecting-light (real Connecting frame: paired ticket for a stopped bridge), add-failed-light (Snackbar + Retry, "Timeout"), login-light/dark, detail-loginrequired, detail-light/dark, picker-light/dark, dashboard-light/dark, controls-ha19-light, qs (QS panel with the new tile) and qs-tile-edit (tile editor). Not all light/dark pairs were retaken after the fix round: only dashboard, picker, detail, settings dark and the ones listed above are from the new build.
