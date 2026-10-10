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
