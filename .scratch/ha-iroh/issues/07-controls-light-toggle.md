# 07: Device Controls: light toggle end-to-end

**What to build:** User picks lights in an entity picker; the Android Device Controls panel lists them. Opening the panel shows cached states immediately (marked stale), then connects, subscribes, and goes live. Toggling the light in HA updates the tile; tapping the tile toggles the light in HA. Closing the panel closes the tunnel. Tracer bullet for M4.

Covers: `ControlsProviderService` with `createPublisherForAllAvailable` (stateless from cached list) and `createPublisherFor` (cached → live), `subscribe_entities` compressed diff merge (`a`/`c`/`r`) filtered to selected ids, `call_service`, `performControlAction` replying `RESPONSE_OK`, `Flow.asPublisher()` via `kotlinx-coroutines-jdk9`, control id `<serverId>:<entity_id>`, `setStructure(server.name)`, `PendingIntent` to the main activity (placeholder), tunnel close on cancel/destroy. Light: `ToggleRangeTemplate` with brightness, `ToggleTemplate` otherwise.

**Blocked by:** 06 (Server model + QR scan + login_flow + token storage)

**Status:** done

- [x] Entity picker lists `light.*` entities via `get_states`; selection persisted per server
- [x] Panel shows selected lights with cached state before connect completes
- [x] Toggle in HA → tile updates live; tile tap / brightness slide → HA changes
- [x] Panel close → tunnel closed (verified via bridge logs)
- [x] Unit test for the `subscribe_entities` diff merge (`a`, `c`, `r`, including attribute removal)
- [x] `unavailable`/`unknown` → `STATUS_ERROR` with status text

## Comments

- Built: `Session` reader loop (results by id, events to `subscribe` flows, `HaError`), `Entities.kt` (`EntityState`, `EntityDiff`, `lightTemplateKind`, `brightnessPct`, `EntityCache` plain JSON in `filesDir/entities-<serverId>.json`), `ControlsService.kt`, Entities picker screen in `MainActivity.kt`, manifest service, `kotlinx-coroutines-jdk9`. `ServerManager.client()` no longer releases first; callers own the client.
- Tests: `./gradlew testDebugUnitTest assembleDebug` pass. New: `EntityDiffTest` (a/c/-/r, mixed event, get_states, light kind, brightness pct), `HaClientTest.subscribeYieldsEventsWhileCommandGetsResult`. Mock WS server now answers close frames (otherwise teardown hangs 5s).
- E2E (emulator-5554 API 37, HA stable docker + `demo:`, bridge via n0 relay): picker listed all 6 demo lights; selected Bed Light + Ceiling Lights. Opened the panel via `adb shell cmd statusbar expand-settings`, swipe to QS page 2, house tile ("Device controls") -> "Choose app" -> ha-iroh -> both lights listed under structure "ha07" (stateless publisher) -> checked both, Save -> panel shows "Off" and "On 71%" (brightness 180/255).
- Live: REST `light.toggle` bed_light + `turn_on brightness_pct 30` ceiling updated tiles to "On 71%" / "On 30%" within 2 s. Tap Bed Light -> HA `off`. Horizontal swipe on Ceiling tile -> HA brightness 255, tile "On 100%".
- Close panel (X): bridge logged `disconnected` at the same second (tunnel released). Reopen with HA changed while closed: first frame showed the cached "on" icon for Ceiling Lights (HA was off), then live. Stale text "(cached)" was too brief to screenshot; icon proof only.
- Gotcha for E2E: a pairing ticket minted before the bridge's relay home is up contains only LAN IPs, which the emulator cannot reach: pair then fails with `CoreException$Timeout`. Mint the ticket after "home is now relay" appears in the bridge log. Also keep the bridge's stdin open (EOF stops it).
- Not exercised: `unavailable`/`unknown` -> `STATUS_ERROR` on device (mapping is in code, `toControl`; the decision is trivial but has no unit test since Control needs a Context); the failing-server error path; `performControlAction` without a live session (connect-call-close).
- Review round: applied fixes (remote WS close now fails subscriptions/pending with IOException, fail-fast on closed session/send failure, conditional `live.remove`, action reply always sent, removed entities emit an error control once, error text by cause, unique-temp-file cache writes under a global lock, `ControlId`, `store.update`, `EntityPicker`/`onNavigate` renames, dropped `lastChanged`). Extra: favourites of a removed/unknown server now get `STATUS_NOT_FOUND` instead of "Loading..." forever. Unit test `remoteCloseFailsSubscription` added.
- Found by E2E and fixed: app crash (uncaught `CoreException$Io: connection lost`) on OkHttp's writer thread when the WS close frame was written after the tunnel was disconnected. `IrohSocket` now maps `CoreException` to `IOException` in write/read/shutdownOutput.
- `unavailable`/`unknown` verified on emulator: `POST /api/states/light.bed_light` with each state -> tile shows the system's error rendering ("Can't load status"); the platform replaces our status text for `STATUS_ERROR`, so the text itself is not visible.
- Note for ticket 12: no mid-stream reconnect; a dropped tunnel shows the tile as error until the panel is reopened.
