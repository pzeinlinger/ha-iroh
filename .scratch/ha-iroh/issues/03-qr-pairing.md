# 03: QR pairing (bridge `pair` over stdio + `cli pair`)

**What to build:** The parent process (HA integration, or a human in a terminal) writes `{"cmd":"pair"}` to the daemon's stdin; the daemon mints a one-time code and answers one JSON line with the `ha-iroh://pair?t=...` ticket URI. A client runs `cli pair <uri>` and is added to the allowlist with its device name. Afterwards `cli curl /api/` works without manual allowlist edits.

Covers: stdio admin channel (JSON lines on stdin/stdout, logs on stderr, `{"event":"ready","endpoint_id"}` on start, `{"event":"paired",...}` after success; spec §6), `ha-iroh/pair/0` ALPN with u32 BE length + JSON framing, ticket JSON (`v`, `addr`, `relay`, `code`, `name`), code TTL (`pair_ttl`, default 10 min) and single use, rate-limit on failed attempts per remote id, error responses `invalid_code` / `expired`.

**Blocked by:** 02 (Bridge tunnel + proto + desktop CLI)

**Status:** ready-for-agent

- [ ] `{"cmd":"pair"}` on stdin → `{"ok":true,"ticket":"ha-iroh://pair?t=...","code":...}` on stdout; daemon owns the key, nothing else opens an Endpoint
- [ ] `{"event":"paired","endpoint_id","device_name"}` emitted on stdout after a successful pairing
- [ ] `cli pair <uri>` returns `{ok:true, bridge_name}` and appends `{endpoint_id, device_name, added_at}` to the allowlist
- [ ] Second use of the same code → `invalid_code`; expired code → `expired`
- [ ] Repeated bad codes from one remote id are rate-limited
- [ ] `cli curl /api/` works immediately after pairing (allowlist hot-applied)
