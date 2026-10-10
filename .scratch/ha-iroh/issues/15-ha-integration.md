# 15: HA custom integration `ha_iroh`

**What to build:** A HACS-installable custom integration (`custom_components/ha_iroh/`) that runs the bridge as a subprocess inside the HA container and manages paired phones as HA devices (spec §6b). Must work on HA Container (no Supervisor). Completes M1b.

Covers: `manifest.json` (`requirements: ["ha-iroh-bridge==<ver>"]`, `iot_class: local_push`, `version`), `hacs.json`, config flow (single entry), subprocess lifecycle (`asyncio.create_subprocess_exec`, stdout JSON-line reader, restart with backoff, stop on unload), `<config>/ha_iroh/` for `bridge.toml`, key, allowlist; "Add device" via options flow sending `pair` and rendering the ticket with `QrCodeSelector` + URI text; `paired` event → device registry entry (identifiers `(ha_iroh, endpoint_id)`); `async_remove_config_entry_device` → `revoke`; options for relay and bridge name (rewrite TOML, restart subprocess); diagnostics without secrets.

**Blocked by:** 04 (Admin `list` / `revoke`). For local dev before 14 lands, point `requirements` at a local wheel or put the binary on PATH in the dev container.

**Status:** ready-for-agent

- [ ] Fresh HA Container + integration folder → "Add integration" → entry created, bridge subprocess running, endpoint id shown in entry title or diagnostics
- [ ] Options flow "Add device" shows a QR; `cli pair <uri>` succeeds and a device appears in HA within 2 s
- [ ] Deleting the device in HA revokes it: a running `cli curl` loop is cut
- [ ] HA restart / integration reload restarts the bridge with the same endpoint id (key persisted)
- [ ] Bridge crash → restarted with backoff, logged as a HA warning
- [ ] `pytest` with `pytest-homeassistant-custom-component`: config flow happy path + device removal calls `revoke`
