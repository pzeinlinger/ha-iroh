# 14: HACS zip release (bridge binary bundled) + README

**What to build:** Installing the integration as a HACS custom repository (`pzeinlinger/ha-iroh`) gives a working bridge with no PyPI, no pip, no binaries in git. CI builds the bridge on tag and attaches a `ha_iroh.zip` to the GitHub release; HACS downloads that zip (`zip_release`). Completes M8.

Decisions (2026-10-11): **no PyPI wheel / maturin / trusted publishing** (dropped). Repo name is `pzeinlinger/ha-iroh` (fix `manifest.json` URLs). `/config` is a normal volume mount (exec allowed).

Covers:
- `hacs.json`: `{"name":"HA iroh","zip_release":true,"filename":"ha_iroh.zip","hide_default_branch":true}`.
- `.github/workflows/release.yml` on `v*` tag: build `ha-iroh-bridge` static musl for `x86_64-unknown-linux-musl` and `aarch64-unknown-linux-musl` (`rust:alpine` container per arch via `docker --platform`, or `cross`; same recipe as the handoff's musl build), zip `custom_components/ha_iroh/**` + `custom_components/ha_iroh/bin/ha-iroh-bridge-{x86_64,aarch64}` as `ha_iroh.zip` (zip root = contents of `custom_components/ha_iroh/`, as HACS expects), attach to the GitHub release (`softprops/action-gh-release` or `gh release upload`).
- `bridge.py` `start()`: resolve binary: `shutil.which("ha-iroh-bridge")` (dev override) else `<integration dir>/bin/ha-iroh-bridge-<arch>` with `platform.machine()` → `x86_64` / `aarch64` (`arm64` → `aarch64`); copy to `<config>/ha_iroh/bin/ha-iroh-bridge` and `chmod 0o755` (HACS unzips with `zipfile`, exec bit is lost); recopy when source mtime/size differs. Clear error if no binary for the arch.
- `.gitignore` `custom_components/ha_iroh/bin/`.
- README: HACS custom-repo install, restart, add integration, pairing from the HA UI (QR), relay override, standalone bridge alternative (`bind_port`, router dst-nat note), `ip_ban` risk (spec risk 1), local-network permission note for Android 16+.
- **All bridge configuration via the integration UI** (user decision 2026-10-11): options flow exposes everything a user would otherwise edit in `bridge.toml`: relay (`n0`/custom URLs, exists), bridge name (exists), `bind_port` (fixed UDP port for router dst-nat; 0 = random; new), `ha_addr` if not derivable from HA. Options change → rewrite TOML + restart subprocess (existing mechanism). No manual TOML editing in the README's HACS path.
- Local check script or Makefile target that builds the zip the same way CI does (reuse in CI).

**Blocked by:** 04 (Admin `list` / `revoke`) — done

**Status:** ready-for-agent

- [ ] `hacs.json` zip_release + filename; manifest URLs point at `pzeinlinger/ha-iroh`
- [ ] Release workflow builds x86_64 + aarch64 musl binaries and uploads `ha_iroh.zip`; zip layout verified with `unzip -l`
- [ ] `ha_iroh.zip` extracted into `custom_components/ha_iroh/` inside `ghcr.io/home-assistant/home-assistant:stable` (both arches via `docker --platform`) → `BridgeProcess.start()` finds, copies, chmods and runs the bundled binary
- [ ] `shutil.which` override still works (tests in `tests/` updated)
- [ ] Options flow has `bind_port` (validated 0 or 1024–65535) and it lands in `bridge.toml`; tests cover it
- [ ] README covers HACS install, pairing, relay override, standalone bridge, `ip_ban` risk
