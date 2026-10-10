# 02: Bridge tunnel + proto + desktop CLI

**What to build:** Running `ha-iroh-bridge run` next to a Home Assistant instance exposes it over iroh as a dumb TCP pipe. A desktop test client (`core/examples/cli.rs`) with a hand-added allowlist entry runs `cli curl /api/` and receives HA's 401 JSON through the tunnel. Non-allowlisted endpoints are refused.

Covers: `proto` crate (ALPN constants, ticket + pairing message types), bridge TOML config (`ha_addr`, `secret_key_path`, `allowlist_path`, `relay`, `name`), secret key generated 0600 on first run, `ha-iroh/tcp/0` ALPN where each `open_bi()` becomes one TCP connection with half-close propagation, allowlist TOML (hot reload or in-memory atomic write), structured logging of peer id / device name / stream counts and no payload logging. Relay configurable (`"n0"` or URL list).

**Blocked by:** 01 (Workspace skeleton + devenv)

**Status:** ready-for-agent

- [ ] `ha-iroh-bridge run` starts, prints its EndpointId, forwards streams to `ha_addr`
- [ ] `cli curl /api/` from an allowlisted key returns HA's `401 {"message":"Unauthorized"}`
- [ ] Connection from a non-allowlisted EndpointId is closed with an error code and logged
- [ ] Half-close on either side propagates (verified with a test that writes, `finish()`es, and reads the full response)
- [ ] Cold-connect latency (relay-first) measured and recorded in a comment on this ticket
