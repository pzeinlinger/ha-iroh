# 03: QR pairing (bridge `pair` + admin socket + `cli pair`)

**What to build:** An operator runs `ha-iroh-bridge pair`; the running daemon mints a one-time code and the CLI prints the ticket as a terminal QR and as a `ha-iroh://pair?t=...` URI. A client runs `cli pair <uri>` and is added to the allowlist with its device name. Afterwards `cli curl /api/` works without manual allowlist edits.

Covers: admin unix socket (`admin_socket` config, default under `/run/ha-iroh-bridge/`), `ha-iroh/pair/0` ALPN with u32 BE length + JSON framing, ticket JSON (`v`, `addr`, `relay`, `code`, `name`), code TTL (`pair_ttl`, default 10 min) and single use, rate-limit on failed attempts per remote id, error responses `invalid_code` / `expired`.

**Blocked by:** 02 (Bridge tunnel + proto + desktop CLI)

**Status:** ready-for-agent

- [ ] `ha-iroh-bridge pair` prints QR + URI; daemon owns the key, CLI never opens its own Endpoint
- [ ] `cli pair <uri>` returns `{ok:true, bridge_name}` and appends `{endpoint_id, device_name, added_at}` to the allowlist
- [ ] Second use of the same code → `invalid_code`; expired code → `expired`
- [ ] Repeated bad codes from one remote id are rate-limited
- [ ] `cli curl /api/` works immediately after pairing (allowlist hot-applied)
