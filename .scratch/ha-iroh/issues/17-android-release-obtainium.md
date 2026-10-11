# 17: Android release APK on GitHub Releases (Obtainium)

**What to build:** Every `v*` tag also attaches a release-signed arm64 APK `ha-iroh-<version>.apk` to the GitHub release, so users install/update the app with Obtainium pointed at `https://github.com/pzeinlinger/ha-iroh`.

Decisions (2026-10-11): distribute only via GitHub Releases + Obtainium (no Play Store, no F-Droid). arm64-v8a only (x86_64 is emulator-only). Same workflow file as ticket 14 (`.github/workflows/release.yml`), new job `apk`.

Covers:
- `android/app/build.gradle.kts`: `release` signingConfig from env (`KEYSTORE_PATH`, `KEYSTORE_PASS`, `KEY_ALIAS`, `KEY_PASS`); when unset, release build is unsigned (local builds keep working). `versionName` from env `VERSION_NAME` (default `0.0.0-dev`), `versionCode` from env `VERSION_CODE` (default 1). Release build minified off (keep it simple; uniffi/JNA reflection).
- `*.jks` in `.gitignore`.
- Workflow job `apk` (ubuntu-latest; SDK/NDK/JDK17 are preinstalled on the runner): `dtolnay/rust-toolchain@stable` with target `aarch64-linux-android`, cache `~/.cargo` + `target` (actions/cache or Swatinem/rust-cache), `cargo install cargo-ndk` (cached), set `ANDROID_NDK_HOME` from `$ANDROID_HOME/ndk/<newest>`, `cargo ndk -t arm64-v8a -o android/app/src/main/jniLibs build -p ha_iroh_core --release`, `rm android/app/src/main/jniLibs/*/libiroh*.so` (per README), decode `KEYSTORE_B64` secret to a file, `VERSION_NAME=${GITHUB_REF_NAME#v} VERSION_CODE=$GITHUB_RUN_NUMBER ./gradlew -q assembleRelease`, rename to `ha-iroh-<version>.apk`, upload with `softprops/action-gh-release@v2` (`files:`). The existing `release` job (zip) and the `apk` job both call `action-gh-release` on the same tag; that is fine (it appends assets), but verify nothing overwrites the other's asset.
- Kotlin uniffi bindings are committed; regenerate only if `core` API changed (not expected here).
- README: "Android app" section: install Obtainium, add app with the repo URL, first install of the release-signed APK requires uninstalling the debug build (data loss = re-pair); Android 16+ local-network permission prompt note already exists.
- Wizard-style one-time setup for the user (human-only steps, write as `scripts/setup-signing.sh` or a README snippet): `keytool -genkeypair -v -keystore ha-iroh.jks -alias ha-iroh -keyalg RSA -keysize 4096 -validity 36500`, `gh secret set KEYSTORE_B64 < <(base64 < ha-iroh.jks)`, `gh secret set KEYSTORE_PASS`, `KEY_ALIAS`, `KEY_PASS`; keep the jks in a password manager; losing it = no more updates for existing installs.

**Blocked by:** 14 (release workflow exists; committed f1d410b)

**Status:** done (Obtainium install verified on the phone 2026-10-11)

- [x] `./gradlew assembleRelease` locally without signing env produces an unsigned APK; with a throwaway keystore + env it produces a signed one (`apksigner verify`)
- [x] `versionName`/`versionCode` come from env; defaults work
- [x] Workflow job `apk` builds arm64 `.so` + release APK and attaches `ha-iroh-<version>.apk` to the release (CI run needs the user's tag; verify YAML + commands locally)
- [x] README Obtainium section + one-time signing setup steps
- [x] User: keystore generated, secrets set, tag pushed, Obtainium installs the APK on the phone

## Comments

2026-10-11: Built. `android/app/build.gradle.kts` (env-driven versionName/versionCode, release signingConfig only if KEYSTORE_PATH set, minify off), `*.jks` gitignored, `apk` job appended to `.github/workflows/release.yml`, `scripts/setup-signing.sh`, README Android section (Obtainium + debug-uninstall note + maintainer pointer).
Verified locally: no env -> `app-release-unsigned.apk`; throwaway keystore + VERSION_NAME=1.2.3 VERSION_CODE=42 -> `app-release.apk`, `apksigner verify --print-certs` rc=0 (CN=test), `aapt2 dump badging` -> versionCode='42' versionName='1.2.3'; assembleDebug + testDebugUnitTest green; workflow YAML parses (ruby YAML).
Release assets: `softprops/action-gh-release@v2` with `files:` only uploads/replaces same-named assets; zip vs apk names differ, nothing deleted. Race: both jobs may create the release concurrently (known action edge; rerun fixes).
Not verified: actual CI run (needs tag + secrets), setup-signing.sh executed (only `bash -n`), Obtainium install.

2026-10-11 review fixes: single release upload (apk job -> artifact, release job needs [build, apk] and uploads zip+apk), secrets via env, rm -f, NDK from runner's ANDROID_NDK_LATEST_HOME, ponytail note on VERSION_CODE, gradle signing folded into release buildType, setup-signing.sh var rename + existing-jks password check. Re-verified: unsigned/signed assembleRelease (apksigner rc=0, versionCode 42), assembleDebug+tests, YAML parse, bash -n.
