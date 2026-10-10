# 01: Workspace skeleton + devenv

**What to build:** A developer can clone the repo, enter the devenv shell, and build every layer: Cargo workspace (`proto`, `bridge`, `core`), the Android `.so` via cargo-ndk, and the Android app via Gradle. Android SDK/NDK come from the system-wide Android Studio install (not Nix); devenv supplies Rust (stable + `aarch64-linux-android`, `x86_64-linux-android` targets), cargo-ndk, uniffi-bindgen, JDK 17. Nix flake is only for the bridge package / NixOS module later (see 14). User is on fish: `.envrc` with direnv, no bash-only hooks.

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] `devenv shell` (or direnv) provides rustc, cargo, cargo-ndk, uniffi-bindgen, JDK 17; `ANDROID_HOME`/`ANDROID_NDK_HOME` point at the Android Studio SDK
- [x] `cargo build` passes on an empty workspace with crates `proto`, `bridge`, `core`
- [ ] `cargo ndk -t arm64-v8a -t x86_64 build` produces a `.so` for `core` into the app's jniLibs
- [x] `./gradlew assembleDebug` passes for an empty Compose app, minSdk 31, target latest
- [x] README/dev notes list the one-time Android Studio SDK components to install

## Comments

- Verified in `devenv shell`: `cargo build`; `./gradlew assembleDebug` (AGP 9.4.1, Gradle 9.8.1, Kotlin 2.4.21, compileSdk/targetSdk 37, minSdk 31); rustc/cargo/cargo-ndk/JDK 17 on PATH; android-linux targets installed.
- Unverified (blocked: NDK not installed, `ANDROID_NDK_HOME` empty): cargo-ndk `.so` output (`Error detecting NDK version for path`), and the `ANDROID_NDK_HOME` half of box 1. Install NDK (Side by side) via SDK Manager, re-enter shell, run the cargo ndk command from README.
- Deviations: `uniffi-bindgen` not packaged in nixpkgs, skipped (do in the uniffi ticket, e.g. a workspace bin matched to the crate version). Crates named `ha_iroh_{proto,bridge,core}`. `org.gradle.jvmargs=-Xmx2g` needed (default heap OOMs).
- Env note: `~/.config/nix/nix.conf` GitHub `access-tokens` returns 401; I ran with `NIX_CONFIG="access-tokens ="` to bypass. Config untouched; the token needs refreshing.
