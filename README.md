# ha-iroh

Home Assistant access over iroh. See `SPEC.md`.

## One-time setup

1. Install [Nix](https://nixos.org/download), [devenv](https://devenv.sh) and direnv.
2. Install Android Studio. In SDK Manager (SDK Platforms / SDK Tools) install:
   - Android SDK Platform 37
   - Android SDK Build-Tools
   - NDK (Side by side)
   - Android SDK Command-line Tools
   - Android SDK Platform-Tools
3. `direnv allow` (or `devenv shell`). Sets `ANDROID_HOME` (if unset) to
   `~/Library/Android/sdk` and `ANDROID_NDK_HOME` to the newest `ndk/*`
   (re-enter the shell after installing the NDK).

Nix supplies Rust stable (+ Android targets), cargo-ndk and JDK 17.

## Build

```sh
cargo build
# Android: native libs -> Kotlin bindings (committed; regenerate when core's API changes) -> APK
# keep `-p ha_iroh_core`: it keeps iroh's portmapper feature off (a workspace-wide build turns it on)
cargo ndk -t arm64-v8a -t x86_64 -o android/app/src/main/jniLibs build -p ha_iroh_core --release
rm android/app/src/main/jniLibs/*/libiroh*.so   # stray iroh cdylibs cargo-ndk copies; not needed
cargo run -p ha_iroh_core --features bindgen --bin uniffi-bindgen -- generate \
  --library target/debug/libha_iroh_core.dylib --language kotlin --out-dir android/app/src/main/java
cd android && ./gradlew assembleDebug
```

Debug screen: paste a pairing URI (or `adb shell am start -n dev.ha_iroh.app/.MainActivity --es ticket '<uri>'`), Pair, then GET /api/.

## Bridge

Run next to Home Assistant. Config (`ha-iroh-bridge.toml`, every key optional):

```toml
ha_addr = "127.0.0.1:8123"
secret_key_path = "ha-iroh-bridge.key"   # generated 0600 on first run
allowlist_path = "allowlist.toml"
relay = "n0"                             # or ["https://relay.example/"]
name = "Home Assistant"
pair_ttl = 600                           # pairing code lifetime, seconds
```

```sh
cargo run -p ha_iroh_bridge -- run --config ha-iroh-bridge.toml   # prints EndpointId
```

Admin channel is JSON lines on stdin/stdout (logs go to stderr). Stdin EOF shuts the bridge down.
Start a pairing by writing one line:

```sh
(echo '{"cmd":"pair"}'; sleep 600) | cargo run -p ha_iroh_bridge -- run
# {"event":"ready","endpoint_id":"..."}
# {"ok":true,"ticket":"ha-iroh://pair?t=...","code":"..."}
# {"event":"paired","endpoint_id":"...","device_name":"..."}   (after a client pairs)
```

Also: `{"cmd":"list"}` gives `{"ok":true,"devices":[{endpoint_id,device_name,added_at}]}`. `{"cmd":"revoke","endpoint_id":"..."}` gives `{"ok":true}` or `{"ok":false,"error":"not_found"}` and closes that device's live connections. `not_found` means the id was not paired (already revoked); `internal` means the allowlist could not be written.

The ticket URI is what the HA integration will render as a QR code. Codes are single use and expire after `pair_ttl`.
Paired devices are appended to the allowlist and take effect immediately.

Desktop test client (generates `cli.key` on first run; refused until paired):

```sh
cargo run -p ha_iroh_core --example cli -- pair '<ha-iroh://pair?t=...>' --device-name cli
cargo run -p ha_iroh_core --example cli -- curl /api/ --bridge <bridge EndpointId>
```

## HA integration (`custom_components/ha_iroh`)

Runs the bridge as a subprocess inside Home Assistant and manages paired phones as devices.

1. Put a `ha-iroh-bridge` binary on the container's PATH (ticket 14 will ship it as a PyPI wheel; until then build it with `rust:alpine` and mount it at `/usr/local/bin/ha-iroh-bridge`).
2. Copy `custom_components/ha_iroh` into `<config>/custom_components/` (or add this repo in HACS), restart HA.
3. Settings → Integrations → Add "HA iroh". Then Configure → "Pair device" shows the QR to scan with the app. Deleting the device in HA revokes it.

Tests: `python -m venv .venv-ha && .venv-ha/bin/pip install pytest-homeassistant-custom-component && .venv-ha/bin/pytest tests`.

## Android app

Build: `cargo ndk ... -p ha_iroh_core --release` → regenerate bindings → `./gradlew assembleDebug` (see Build above).
Screens: dashboard (HA frontend via loopback proxy) with server switcher, server list/add/login, entity picker, Device Controls panel (lights), Assist text sheet via QS tile / launcher shortcuts.
Unit tests: `cd android && ./gradlew testDebugUnitTest`.
