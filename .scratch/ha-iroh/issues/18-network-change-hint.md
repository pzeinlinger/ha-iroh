# 18: Fast reaction to network changes (Wi-Fi ↔ LTE)

**Problem:** When the phone leaves Wi-Fi, iroh takes several seconds to notice (apps can't use netlink on Android, so iroh's net monitor is slow/polling there). Direct paths and relay reconnects lag.

**What to build:** The app tells iroh immediately. `core`: `Core.networkChange()` → `Endpoint::network_change()`. App: `ConnectivityManager.registerDefaultNetworkCallback` in `App` (process-wide, once); on `onAvailable`, `onLost`, and `onCapabilitiesChanged` with a transport change (WIFI↔CELLULAR↔VPN) call `core.networkChange()` off the main thread. Debounce not needed (iroh coalesces). No new deps.

Check in iroh source (cargo registry) that `network_change` exists in the pinned iroh version and what it does (re-probe, re-bind, relay reconnect); note in Comments.

**Status:** ready-for-agent

- [ ] `Core.networkChange()` in core + regenerated uniffi Kotlin, `.so` rebuilt
- [ ] Callback registered once in `App`, unregistered never (process lifetime)
- [ ] Phone test: dashboard bar path info switches (ip:… ↔ relay/new ip) within ~2 s of toggling Wi-Fi off/on, vs several seconds before; Controls/Assist/dashboard keep working after the switch
