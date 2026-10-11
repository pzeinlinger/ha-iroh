# 19: App design for initial release + sane Device Controls exposure

**Problem:** The app is a debug UI: bare `Text`/`Button` columns, no launcher icon (manifest has no `android:icon` → stock Android icon), headings hand-rolled, pairing/login screens show raw output strings and dev buttons (`Connect (WS auth)`, `GET /api/`, `id <uuid>`). The entity picker lists every supported domain including `sensor`/`binary_sensor`, which render as useless read-only tiles in Device Controls, and it shows HA diagnostic/config/hidden entities.

**What to build:** Material 3 polish of the existing screens, no new screens, no new deps (Compose BOM already has material3 + material-icons-core). Keep the `Screen` sealed class and the navigation as is.

Design:
- `Scaffold` + `TopAppBar` (title + back arrow) on every non-dashboard screen; dynamic color (`dynamicLightColorScheme`/`dynamicDarkColorScheme` on API 31+, which is `minSdk`), follows system dark mode as now.
- Adaptive launcher icon (`mipmap-anydpi-v26/ic_launcher.xml`, vector foreground, monochrome layer for themed icons) + `android:icon`; reuse the same vector for the Assist tile. App label: pick a user-facing name (currently `ha-iroh`).
- **Server list:** empty state (icon + "No servers yet" + primary "Add server" button), cards/`ListItem` with status (logged in / login required), FAB stays.
- **Add server (pairing):** one primary action "Scan QR" opening the scanner full-screen with a viewfinder overlay; manual ticket paste behind "Enter code instead"; progress indicator while pairing; errors as a `Snackbar`/inline error text, not raw `out` strings.
- **Login:** form in a card, password field with visibility toggle, `CircularProgressIndicator` on submit, HA error messages inline.
- **Server detail:** sections (Account, Assist, Device Controls, Danger zone) using `ListItem` rows; remove `id <uuid>`, `Connect (WS auth)`, `GET /api/` (dev-only: keep behind `BuildConfig.DEBUG`). Unpair behind an `AlertDialog` confirm.
- **App settings:** same `ListItem` style; EndpointId as a copyable row.
- **Entity picker:** search field at top, group by area (fallback domain), friendly name + domain icon, `Switch`; "select all in area".

Device Controls exposure (`ControlMapping.kt`, `EntityPicker`):
- Drop `sensor`/`binary_sensor` from `toSpec()` (remove `Spec.ReadOnly`), so they are neither pickable nor published. Already-selected ids of those domains are ignored at publish time (`toSpec() == null`).
- Picker hides entities whose entity-registry entry has `entity_category` (`config`/`diagnostic`) or `hidden_by`/`disabled_by` set. `refreshAreas` already fetches `config/entity_registry/list`; extend it to persist that set alongside areas (one JSON file, no new round trip).

Out of scope: dashboard WebView, Assist sheet (own themes), onboarding tour, new screens.

**Status:** needs-info

- [ ] Launcher icon (adaptive + monochrome), app label decided
- [ ] Dynamic color theme, `TopAppBar` on all non-dashboard screens
- [ ] Server list empty state + styled rows
- [ ] Pairing: full-screen scanner with overlay, manual entry secondary, progress + inline errors
- [ ] Login: styled form, password toggle, progress, inline errors
- [ ] Detail: sectioned, dev buttons debug-only, Unpair confirm dialog
- [ ] Entity picker: search, area grouping, switches; hides config/diagnostic/hidden/disabled entities
- [ ] `sensor`/`binary_sensor` removed from `toSpec()`; `ControlMapping` tests updated (`toSpec()` returns null for them)
- [ ] `./gradlew testDebugUnitTest` green; screens checked on emulator in light + dark

## Comments

- Open question (blocks `ready-for-agent`): drop `sensor`/`binary_sensor` entirely vs. keep them pickable but unchecked by default. Ticket assumes drop.
