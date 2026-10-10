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
`uniffi-bindgen` is not in nixpkgs; it arrives with the uniffi ticket.

## Build

```sh
cargo build
cargo ndk -t arm64-v8a -t x86_64 -o android/app/src/main/jniLibs build -p ha_iroh_core
cd android && ./gradlew assembleDebug
```

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

The ticket URI is what the HA integration will render as a QR code. Codes are single use and expire after `pair_ttl`.
Paired devices are appended to the allowlist and take effect immediately.

Desktop test client (generates `cli.key` on first run; refused until paired):

```sh
cargo run -p ha_iroh_core --example cli -- pair '<ha-iroh://pair?t=...>' --device-name cli
cargo run -p ha_iroh_core --example cli -- curl /api/ --bridge <bridge EndpointId>
```
