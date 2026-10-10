# 08: Device Controls: full domain mapping + areas

**What to build:** Entity picker and panel support every domain in the spec table: switch/input_boolean, fan (percentage), cover (position or open/close, type by device_class), lock (auth required), climate (temperature control with step), scene/script/button (stateless routine), sensor/binary_sensor (read-only). Controls show the HA area name as zone. Completes M4.

**Blocked by:** 07 (Device Controls: light toggle end-to-end)

**Status:** done

- [x] Each domain renders with the template/type from the spec table and sends the listed service calls (mode change unit-tested only)
- [x] Lock uses `setAuthRequired` on API 33+ (code path gated SDK ≥ 33; prompt not verified on emulator)
- [x] Climate target temperature uses the step from entity attributes
- [x] `setZone` populated from `config/area_registry/list` + entity registry when available
- [x] Sensors show state in status text and reject actions
- [x] Unit test for the entity → Control mapping covering at least one entity per domain

## Comments

- Built: `ControlMapping.kt` (pure `Spec`, `toSpec`, `errorText`, `actionFor`; no android.service.controls objects, JVM-testable), `Spec -> Control` glue in `ControlsService.kt`, `areaMap`/`refreshAreas`/`loadAreas` in `Entities.kt` (entity area, else device area; cached in `filesDir/areas-<serverId>.json`, refreshed on panel connect and when the picker opens; failure falls back to cache), picker lists every supported domain under domain headers.
- Tests: `ControlMappingTest` (12: every domain, cover device classes, unavailable, actions incl. read-only/unsupported -> null), `AreaMapTest`. `testDebugUnitTest assembleDebug` pass. `HaClientTest.subscribeYieldsEventsWhileCommandGetsResult` failed once (NoSuchElement, timing), passed on 3 reruns: pre-existing flake, untouched.
- Deviations: `actionFor(entityId, on/value/mode)` has no `spec` arg (domain decides everything). Switch/input_boolean use `homeassistant.turn_on/off`. Scene/script/button state `unknown` is not an error (it is `unknown` until first use); only `unavailable` is. Their status text is empty (state is a last-used timestamp). Climate mode button: `ModeAction` -> `climate.set_hvac_mode` implemented (off/heat/cool/heat_cool only) but NOT exercised on device.
- E2E (emulator-5554, HA stable + demo, scene/script/input_boolean added in yaml; areas Kitchen (existing) + Garage; fan area on entity, switch/cover/climate/sensor area on device, lock/scene unregistered): add-controls screen groups by zone Kitchen (Hvac, Living Room Fan, Outside Temperature, Decorative Lights), Garage (Garage Door), Other (rest): entity-area and device-area fallback both work. Panel: all 14 tiles render with the right icons/status. HA state verified by REST after each action: switch tap -> off, input_boolean -> on, button.push pressed, scene.movie activated, lock.front_door -> unlocked (no unlock prompt on the emulator), garage cover (no position) toggle -> open, kitchen window -> open, fan tap -> on 67 %, fan drag -> percentage 100, hvac drag -> temperature 35 (max_temp), living room window (position) drag -> current_position 100. Sensor/binary_sensor show "15.6 °C" / "on", chevron only (no action).
- Not verified on device: lock `setAuthRequired` prompt (code path guarded by SDK_INT>=33, emulator has no secure lock), climate mode change, cover types other than garage/blind, script tap (HA accepted, script state is transient), `unavailable` after the refactor (unit-tested only; earlier ticket-07 check covers the platform rendering), zone is not shown on the panel tiles themselves, only in the add-controls grouping.
- Review round: climate `max >= min` guard, null-attribute tests, `Spec.Range`/nested `ToggleRange` without format, shared `domainOf`, areas refreshed after the first live state with atomic cache write (`writeAtomic` shared with `EntityCache`), picker is a `LazyColumn` over a plain map, `AreaMapTest` own file, `HaClientTest` flake fixed (poll for `unsub:`). SPEC §8.4 amended (THERMOSTAT, device-area fallback, scene/button `unknown`).
