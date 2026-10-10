# 05: Rust core uniffi + IrohSocket + OkHttp debug screen

**What to build:** On the Android app's debug screen the user pastes a pairing URI, taps "Pair", then taps "GET /api/" and sees HA's 401 JSON. Proves the Rust core, uniffi bindings, `IrohSocket`, and OkHttp integration end to end on device/emulator.

Covers: `core` cdylib with uniffi API (`Core(dataDir, relay)`, `endpointId()`, `pair()`, `connect()` → `Tunnel`, `Tunnel.openStream()` → `Stream` read/write/shutdownWrite/close, `Tunnel.close()`), internal tokio runtime, secret key persisted in `dataDir`, `BridgeInfo` serialized for Kotlin storage; Kotlin `IrohSocket : java.net.Socket`, `IrohSocketFactory` + dummy `Dns`, one `OkHttpClient` per `Tunnel`, base URL `http://ha.iroh/`. Generated Kotlin bindings wired into the Gradle build.

**Blocked by:** 01 (Workspace skeleton + devenv), 03 (QR pairing)

**Status:** ready-for-agent

- [ ] Debug screen: paste URI → pair → BridgeInfo stored; GET `/api/` shows response body + status
- [ ] EndpointId is stable across app restarts (key persisted); "unpair" wipes it
- [ ] Works on arm64 device and x86_64 emulator
- [ ] `.so` size and required permissions (`INTERNET`, `ACCESS_NETWORK_STATE`) recorded; portmapper disabled if it needs more
- [ ] One Rust-side test or `cli` check covering Stream EOF (empty read) and shutdownWrite semantics
