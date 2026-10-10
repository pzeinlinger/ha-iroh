# 14: NixOS module + README

**What to build:** A NixOS user adds `services.ha-iroh-bridge.enable = true;` and gets the bridge running as a hardened systemd service next to Home Assistant; `ha-iroh-bridge pair` works against the running service via the admin socket. README documents end-to-end setup (bridge install, pairing, app install, `ip_ban` caveat from spec risk 1). Completes M8.

Covers: flake `packages.bridge` (crane or rustPlatform), optional `packages.cli`, `nixosModules.default` with options `enable`, `haAddress`, `relay`, `name`, `stateDir`, `openFirewall`; systemd `DynamicUser`, `StateDirectory`, `RuntimeDirectory` for the admin socket, hardening.

**Blocked by:** 04 (Admin `list` / `revoke`)

**Status:** ready-for-agent

- [ ] `nix build .#bridge` succeeds
- [ ] Module evaluates and the service starts on a NixOS host/VM test; key and allowlist land in `stateDir`
- [ ] `ha-iroh-bridge pair` / `list` / `revoke` work via the runtime-dir admin socket as a non-root operator (group or socket perms documented)
- [ ] README covers setup, pairing, relay override, and the `ip_ban` risk
