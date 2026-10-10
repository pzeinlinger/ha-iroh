# 15: HA custom integration `ha_iroh`

**What to build:** A HACS-installable custom integration (`custom_components/ha_iroh/`) that runs the bridge as a subprocess inside the HA container and manages paired phones as HA devices (spec §6b). Must work on HA Container (no Supervisor). Completes M1b.

Covers: `manifest.json` (`requirements: ["ha-iroh-bridge==<ver>"]`, `iot_class: local_push`, `version`), `hacs.json`, config flow (single entry), subprocess lifecycle (`asyncio.create_subprocess_exec`, stdout JSON-line reader, restart with backoff, stop on unload), `<config>/ha_iroh/` for `bridge.toml`, key, allowlist; "Add device" via options flow sending `pair` and rendering the ticket with `QrCodeSelector` + URI text; `paired` event → device registry entry (identifiers `(ha_iroh, endpoint_id)`); `async_remove_config_entry_device` → `revoke`; options for relay and bridge name (rewrite TOML, restart subprocess); diagnostics without secrets.

**Blocked by:** 04 (Admin `list` / `revoke`). For local dev before 14 lands, point `requirements` at a local wheel or put the binary on PATH in the dev container.

**Status:** done

- [x] Fresh HA Container + integration folder → "Add integration" → entry created, bridge subprocess running, endpoint id shown in entry title or diagnostics
- [x] Options flow "Add device" shows a QR; `cli pair <uri>` succeeds and a device appears in HA within 2 s
- [x] Deleting the device in HA revokes it: a running `cli curl` loop is cut
- [x] HA restart / integration reload restarts the bridge with the same endpoint id (key persisted)
- [x] Bridge crash → restarted with backoff, logged as a HA warning
- [x] `pytest` with `pytest-homeassistant-custom-component`: config flow happy path + device removal calls `revoke`

## Comments

- Verified on HA 2026.10.1 container (aarch64, `ghcr.io/home-assistant/home-assistant:stable`) with an alpine-musl aarch64 bridge binary mounted at `/usr/local/bin/ha-iroh-bridge`. Unit tests ran on 2026.10.0 (`pytest-homeassistant-custom-component`, venv `.venv-ha`, python 3.14): 3 passed.
- Deviation: `requirements: []` and binary looked up via `shutil.which("ha-iroh-bridge")` (error "ha-iroh-bridge binary not found" -> `ConfigEntryNotReady`). Switch to `requirements=["ha-iroh-bridge==0.1.0"]` when ticket 14 publishes the wheel (ponytail comment in `bridge.py`).
- This HA version uses `probatio` instead of `voluptuous` for schemas; flows import `probatio`. Device registry: `async_get_device` is now an error; use `async_get_device_by_identifier((DOMAIN, id), config_entry_id)`.
- QR selector API: `selector.QrCodeSelector(QrCodeSelectorConfig(data=ticket, scale=6, error_correction_level=QrErrorCorrectionLevel.LOW))` as an optional field; REST flow response has `data_schema[0].selector.qr_code.data == ticket`. URI also in `description_placeholders.uri`. Submitting the form closes the flow.
- Options change (name/relay) -> `async_reload` (rewrites TOML, restarts bridge) only when settings differ; title update does not restart.
- Device removal: `revoke` ok or `not_found` -> True; bridge down/timeout/`internal` -> False (device stays).
- E2E results: entry created, title "HA iroh (a237684a)", pair via options flow, `cli pair` -> device in registry (cli call ~2.4 s total incl. relay), delete device -> `cli curl` loop cut within ~2 s, `docker restart` -> same endpoint id, `kill -9` bridge -> restarted after 1 s with HA warning, settings flow validates relay and rewrote toml.
- No bridge protocol change needed.
