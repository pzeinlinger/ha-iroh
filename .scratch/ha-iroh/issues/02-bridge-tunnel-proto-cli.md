# 02: Bridge tunnel + proto + desktop CLI

**What to build:** Running `ha-iroh-bridge run` next to a Home Assistant instance exposes it over iroh as a dumb TCP pipe. A desktop test client (`core/examples/cli.rs`) with a hand-added allowlist entry runs `cli curl /api/` and receives HA's 401 JSON through the tunnel. Non-allowlisted endpoints are refused.

Covers: `proto` crate (ALPN constants, ticket + pairing message types), bridge TOML config (`ha_addr`, `secret_key_path`, `allowlist_path`, `relay`, `name`), secret key generated 0600 on first run, `ha-iroh/tcp/0` ALPN where each `open_bi()` becomes one TCP connection with half-close propagation, allowlist TOML (hot reload or in-memory atomic write), structured logging of peer id / device name / stream counts and no payload logging. Relay configurable (`"n0"` or URL list).

**Blocked by:** 01 (Workspace skeleton + devenv)

**Status:** done

- [x] `ha-iroh-bridge run` starts, prints its EndpointId, forwards streams to `ha_addr`
- [x] `cli curl /api/` from an allowlisted key returns HA's 401 (HA :stable image (fresh, not onboarded) answers `/api/` with `401: Unauthorized` text/plain, not JSON; the integration test's fake HA uses the JSON body)
- [x] Connection from a non-allowlisted EndpointId is closed with an error code and logged
- [x] Half-close on either side propagates (verified with a test that writes, `finish()`es, and reads the full response)
- [x] Cold-connect latency (relay-first) measured and recorded in a comment on this ticket

## Comments

Verified (devenv shell):
- `cargo build --workspace --examples`, `cargo test --workspace` (2 tunnel tests pass), `cargo clippy --workspace --examples --tests` clean.
- Tests written first, seen failing against a stub, then green. `bridge/tests/tunnel.rs`: fake HA reads request to half-close EOF, replies 401 JSON; allowlisted client writes + `finish()` + reads to EOF and gets the exact body (boxes 2/4 in-process). Non-allowlisted client gets closed with code 1 "not allowed".
- End to end: real `ghcr.io/home-assistant/home-assistant:stable` in docker on 8123, real bridge (n0 relay), `cli curl /api/` returns HA's 401. Non-allowlisted first run was refused (`closed by peer: not allowed (code 1)`) and logged with peer id. Allowlist edited while bridge ran, picked up on next connection (hot reload). Key file mode is 0600. Container and bridge removed afterwards.

Cold-connect latency (`Endpoint::connect` on fresh process, N0 preset, relay-first, discovery by EndpointId only), 3 runs: 227 ms, 264 ms, 143 ms. Earlier 3 runs with same setup: 143 ms, 150 ms, 213 ms. Caveat: client and bridge were on the SAME host/LAN, so the holepunch/direct path can win quickly; this is not a WAN or mobile number. Re-measure on the phone (M4).

Findings / deviations:
- **HA drops the response if the client half-closes before it answers** (reproduced with plain python socket straight to HA :8123, `shutdown(SHUT_WR)` -> empty reply). The bridge forwards half-close faithfully, so the tunnel is not at fault. Consequence: the CLI does NOT `finish()` after the request (relies on `Connection: close`), and the Android `Stream.shutdownWrite()` must not be used before the response on HTTP/1.1 requests to HA. Half-close propagation is covered by the integration test instead.
- `tokio::io::copy_bidirectional` is enough: iroh `SendStream` shutdown == `finish()`, so no manual copy tasks.
- pair ALPN is registered; connections on it are closed with code 2 until ticket 03.
- proto has no serde_json dep (not needed yet). `PairResponse` is a flat struct with optional `bridge_name`/`error`.
- Secret key files are raw 32 bytes (bridge and cli). `Config` defaults: `allowlist_path = allowlist.toml`, `secret_key_path = ha-iroh-bridge.key` (cwd-relative).
- iroh 1.3 API as expected: `Endpoint::builder(presets::N0)`, `EndpointAddr`, `EndpointId` (impl FromStr, so clap parses `--bridge` directly), `conn.alpn()`/`remote_id()`, `RelayMode::custom(urls)`. `RecvStream::read_to_end(limit) -> Vec<u8>` (not tokio-style). Tests use `presets::Minimal` + `RelayMode::Disabled` + explicit loopback `EndpointAddr`; connect is instant.

Review follow-up: latency numbers above are same-host only; WAN/phone measurement is deferred to ticket 05.
