# 04: Admin `list` / `revoke`

**What to build:** The operator can inspect and remove paired devices. `ha-iroh-bridge list` shows allowlist entries; `ha-iroh-bridge revoke <endpoint_id>` removes the entry and terminates any live connections from that id. Completes M1.

**Blocked by:** 03 (QR pairing)

**Status:** ready-for-agent

- [ ] `list` prints endpoint id, device name, added_at for each entry
- [ ] `revoke <id>` removes the entry and persists the allowlist atomically
- [ ] A `cli curl` loop running during `revoke` is cut and subsequent connects are refused
