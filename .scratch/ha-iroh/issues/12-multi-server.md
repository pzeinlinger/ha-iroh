# 12: Multi-server

**What to build:** User pairs a second HA instance. Device Controls panel shows both servers' entities grouped per server and goes live for both in parallel; if one server is offline only its controls show an error. The Assist sheet has a server chip that switches server (new tunnel, new `conversation_id`). The dashboard has a server switcher. Tapping a control opens that server's dashboard. Completes M7.

Covers: parallel connects grouped by `serverId` in `createPublisherFor`, per-server `STATUS_ERROR` on failure, Assist server chip + `serverId` shortcut extra, dashboard top-bar/drawer switcher, control `PendingIntent` → dashboard for that server (`more-info` deep link if easy, else root).

**Blocked by:** 08 (Device Controls: full domain mapping + areas), 10 (Assist voice + TTS), 11 (Dashboard WebView + loopback proxy + external_auth)

**Status:** done

- [x] Two bridges paired; panel shows both groups via `setStructure`, both live
- [x] Stop one bridge → only its controls go `STATUS_ERROR`, the other stays live
- [x] Assist chip switches server; conversation restarts
- [x] Dashboard switcher swaps proxy + tunnel to the other server
- [x] Control tap opens the correct server's dashboard

Also: honor `BridgeInfo.relay` in `Core.connect` (ticket 05 ignores it).

Also: no mid-stream reconnect in Device Controls yet (ticket 07); a dropped tunnel leaves tiles in error until the panel is reopened. Decide whether to reconnect here.

## Comments

**Already existed (verified in code):** `createPublisherFor` groups by server and merges per-server flows; `serverControls` isolates failures (`STATUS_ERROR`); `setStructure(server.name)` + zones; dashboard server switcher (`DashboardBar`); Assist server chip + `EXTRA_SERVER_ID`. Boxes ticked from code reading; the two-bridge phone run below is still to do.

**Changed:**
- Control tap: `ControlsService.pi(serverId)` = per-server PendingIntent (`EXTRA_SERVER_ID`, `FLAG_UPDATE_CURRENT`). `MainActivity` reads the extra for the initial dashboard and handles `onNewIntent` (system may reuse the running instance; `am start` showed exactly that), jumping to that server if logged in. `more-info` deep link skipped: dashboard root only.
- Device Controls reconnect: `serverControls` retries after IOException/CoreException/timeout (also a subscription that ends silently) with 2 s, 5 s, 10 s; between tries tiles are STATUS_ERROR "reconnecting…" (not operable). After the 3rd failure: "offline". Each connect attempt can hang up to CONNECT_TIMEOUT (15 s), so worst case "offline" after ~1.5 min, and recovery takes similar. Static shortcuts carry no serverId (optional per spec, deferred). Live event resets the counter. Non-network errors (re-login, HaError) stop immediately. No unit test: loop is Flow + Android + tunnel; policy is a 3-element list.
- Relay: no code change. The bridge only mints a ticket after `online()` and `Info.addr` (EndpointAddr) carries the bridge's relay URL (ticket decoded: has `Relay` addr); iroh dials the peer via the URLs in the addr. The Endpoint relay mode only selects the phone's own home relay. `BridgeInfo.relay` stays informational; comment in `core/src/lib.rs` updated. Rust `.so` not rebuilt (comment only).

**Phone test (needs ha12 bridge running on the Mac, see below):**
1. Pair second server: Add server, scan/paste ticket from `echo '{"cmd":"pair"}' > /tmp/ha12/in; sleep 2; tail -1 /tmp/ha12/out`. Login `ha12` / `ha12-test-pw`. HA at http://localhost:8125 on the Mac has the `demo:` integration (lights, switches, ...). Pick entities in the picker.
2. Add favourites from both servers in the Device Controls panel: two structure groups, both live.
3. Stop the ha12 bridge (`pkill -f "bridge run --config /tmp/ha12"`): only ha12 tiles go "reconnecting…" then "offline" (up to ~1.5 min); the other server stays live.
4. Restart it (command below), reopen nothing: ha12 tiles recover within about the same time (if already "offline", reopen the panel).
5. Tap a tile of each server: opens that server's dashboard, also while the app is already open on the other server.
6. Assist: server chip switches server, conversation restarts. Dashboard top bar switcher swaps servers.

Restart ha12 bridge: `cd <repo>; nohup sh -c 'tail -f /dev/null > /tmp/ha12/in & exec ./target/release/ha-iroh-bridge run --config /tmp/ha12/bridge.toml < /tmp/ha12/in > /tmp/ha12/out 2>>/tmp/ha12/err' >/dev/null 2>&1 &` (mkfifo /tmp/ha12/in first if missing).

- 2026-10-11: two-server phone test passed (ha12 on the Mac + user HA): both live, ha12 offline isolation + recovery, tap → correct dashboard, Assist chip, switcher. Done.
