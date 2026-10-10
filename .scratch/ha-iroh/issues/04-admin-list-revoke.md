# 04: Admin `list` / `revoke` (stdio)

**What to build:** The parent process can inspect and remove paired devices over the daemon's stdio admin channel (spec §6). `{"cmd":"list"}` returns allowlist entries; `{"cmd":"revoke","endpoint_id":"..."}` removes the entry, persists the allowlist atomically, and terminates any live connections from that id. Completes M1.

**Blocked by:** 03 (QR pairing)

**Status:** done

- [x] `list` → `{"ok":true,"devices":[{endpoint_id, device_name, added_at}]}`
- [x] `revoke` removes the entry and persists the allowlist atomically; unknown id → `{"ok":false,"error":"not_found"}`
- [x] A `cli curl` loop running during `revoke` is cut and subsequent connects are refused
- [x] Malformed stdin line → `{"ok":false,"error":"bad_request"}`, daemon keeps running; stdin EOF → daemon exits cleanly

## Comments

Verified: `cargo test --workspace` (3 new tests in bridge/tests/admin.rs), `cargo clippy --workspace --examples --tests` clean. E2E with real binary + HA container: list shows paired device; cli curl loop ok then fails with "closed by peer: not allowed (code 1)" after revoke; list empty after; unknown id -> not_found; missing endpoint_id and garbage line -> bad_request, bridge kept running; stdin close -> exit 0.

Deviations: `add_device` now goes through a private `update` helper shared with `revoke`. Live connections are tracked before the allowlist check (closes revoke race). Persist failure on revoke returns `{"ok":false,"error":"internal"}`.
