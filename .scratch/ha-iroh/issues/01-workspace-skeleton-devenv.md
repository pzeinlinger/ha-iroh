# 01: Workspace skeleton + devenv

**What to build:** A developer can clone the repo, enter the devenv shell, and build every layer: Cargo workspace (`proto`, `bridge`, `core`), the Android `.so` via cargo-ndk, and the Android app via Gradle. Android SDK/NDK come from the system-wide Android Studio install (not Nix); devenv supplies Rust (stable + `aarch64-linux-android`, `x86_64-linux-android` targets), cargo-ndk, uniffi-bindgen, JDK 17. Nix flake is only for the bridge package / NixOS module later (see 14). User is on fish: `.envrc` with direnv, no bash-only hooks.

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] `devenv shell` (or direnv) provides rustc, cargo, cargo-ndk, uniffi-bindgen, JDK 17; `ANDROID_HOME`/`ANDROID_NDK_HOME` point at the Android Studio SDK
- [ ] `cargo build` passes on an empty workspace with crates `proto`, `bridge`, `core`
- [ ] `cargo ndk -t arm64-v8a -t x86_64 build` produces a `.so` for `core` into the app's jniLibs
- [ ] `./gradlew assembleDebug` passes for an empty Compose app, minSdk 31, target latest
- [ ] README/dev notes list the one-time Android Studio SDK components to install
