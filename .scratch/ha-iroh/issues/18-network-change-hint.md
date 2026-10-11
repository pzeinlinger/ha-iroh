# 18: Fast reaction to network changes (Wi-Fi ↔ LTE)

**Problem:** When the phone leaves Wi-Fi, iroh takes several seconds to notice (apps can't use netlink on Android, so iroh's net monitor is slow/polling there). Direct paths and relay reconnects lag.

**What to build:** The app tells iroh immediately. `core`: `Core.networkChange()` → `Endpoint::network_change()`. App: `ConnectivityManager.registerDefaultNetworkCallback` in `App` (process-wide, once); on `onAvailable`, `onLost`, and `onCapabilitiesChanged` with a transport change (WIFI↔CELLULAR↔VPN) call `core.networkChange()` off the main thread. Debounce not needed (iroh coalesces). No new deps.

Check in iroh source (cargo registry) that `network_change` exists in the pinned iroh version and what it does (re-probe, re-bind, relay reconnect); note in Comments.

**Status:** done

- [x] `Core.networkChange()` in core + regenerated uniffi Kotlin, `.so` rebuilt
- [x] Callback registered once in `App`, unregistered never (process lifetime)
- [x] Phone test: dashboard bar path info switches (ip:… ↔ relay/new ip) within ~2 s of toggling Wi-Fi off/on, vs several seconds before; Controls/Assist/dashboard keep working after the switch

## Comments

iroh 1.3.0: `Endpoint::network_change()` exists (endpoint.rs:1665), async, doc says it is for Android where native code cannot see network changes; harmless if nothing changed; ignored (debug log) if the endpoint is closed. It sends `ActorMessage::NetworkChange` to the socket actor, which calls `network_monitor.network_change()`; the monitor then drives iroh's normal change handling (net-report re-probe, rebind of UDP sockets, direct-address refresh, relay reconnect, path re-selection). Core exposes it as `Core.networkChange()`. App registers a default-network callback in `App.onCreate`; `onCapabilitiesChanged` only fires the hint when the WIFI/CELLULAR/VPN transport set differs from the last seen one.

Observe on phone: `adb logcat -s ha_iroh_core` (with `iroh=debug` log_filter) shows the network-change handling when toggling Wi-Fi; the dashboard bar path info should flip (ip:... <-> relay/new ip).
