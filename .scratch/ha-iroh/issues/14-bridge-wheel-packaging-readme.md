# 14: Bridge PyPI wheel + README

**What to build:** `pip install ha-iroh-bridge` on HA Container (Alpine/musl, x86_64 and aarch64) drops a working `ha-iroh-bridge` binary on PATH. The HA integration (15) lists it in `manifest.json` `requirements`, so HA installs it itself. README documents end-to-end setup (HACS install, pairing, app install, `ip_ban` caveat from spec risk 1). Completes M8.

Covers: `bridge/pyproject.toml` with maturin `bindings = "bin"`, static musl builds for `x86_64-unknown-linux-musl` and `aarch64-unknown-linux-musl` (`musllinux_1_2` tags; manylinux optional), GitHub Actions workflow building wheels on tag and publishing to PyPI (trusted publishing), `maturin` in devenv.

**Blocked by:** 04 (Admin `list` / `revoke`)

**Status:** ready-for-agent

- [ ] `maturin build --release` locally produces a wheel containing the binary
- [ ] CI builds x86_64 + aarch64 musl wheels; `pip install` of the wheel inside `ghcr.io/home-assistant/home-assistant:stable` (both arches, via docker `--platform`) runs `ha-iroh-bridge run` successfully
- [ ] Tag push publishes to PyPI
- [ ] README covers HACS install, pairing from the HA UI, relay override, and the `ip_ban` risk
