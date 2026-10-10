# 07: Device Controls: light toggle end-to-end

**What to build:** User picks lights in an entity picker; the Android Device Controls panel lists them. Opening the panel shows cached states immediately (marked stale), then connects, subscribes, and goes live. Toggling the light in HA updates the tile; tapping the tile toggles the light in HA. Closing the panel closes the tunnel. Tracer bullet for M4.

Covers: `ControlsProviderService` with `createPublisherForAllAvailable` (stateless from cached list) and `createPublisherFor` (cached → live), `subscribe_entities` compressed diff merge (`a`/`c`/`r`) filtered to selected ids, `call_service`, `performControlAction` replying `RESPONSE_OK`, `Flow.asPublisher()` via `kotlinx-coroutines-jdk9`, control id `<serverId>:<entity_id>`, `setStructure(server.name)`, `PendingIntent` to the main activity (placeholder), tunnel close on cancel/destroy. Light: `ToggleRangeTemplate` with brightness, `ToggleTemplate` otherwise.

**Blocked by:** 06 (Server model + QR scan + login_flow + token storage)

**Status:** ready-for-agent

- [ ] Entity picker lists `light.*` entities via `get_states`; selection persisted per server
- [ ] Panel shows selected lights with cached state before connect completes
- [ ] Toggle in HA → tile updates live; tile tap / brightness slide → HA changes
- [ ] Panel close → tunnel closed (verified via bridge logs)
- [ ] Unit test for the `subscribe_entities` diff merge (`a`, `c`, `r`, including attribute removal)
- [ ] `unavailable`/`unknown` → `STATUS_ERROR` with status text
