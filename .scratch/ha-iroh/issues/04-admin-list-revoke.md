# 04: Admin `list` / `revoke` (stdio)

**What to build:** The parent process can inspect and remove paired devices over the daemon's stdio admin channel (spec §6). `{"cmd":"list"}` returns allowlist entries; `{"cmd":"revoke","endpoint_id":"..."}` removes the entry, persists the allowlist atomically, and terminates any live connections from that id. Completes M1.

**Blocked by:** 03 (QR pairing)

**Status:** ready-for-agent

- [ ] `list` → `{"ok":true,"devices":[{endpoint_id, device_name, added_at}]}`
- [ ] `revoke` removes the entry and persists the allowlist atomically; unknown id → `{"ok":false,"error":"not_found"}`
- [ ] A `cli curl` loop running during `revoke` is cut and subsequent connects are refused
- [ ] Malformed stdin line → `{"ok":false,"error":"bad_request"}`, daemon keeps running; stdin EOF → daemon exits cleanly
