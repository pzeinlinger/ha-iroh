# 03: QR pairing (bridge `pair` over stdio + `cli pair`)

**What to build:** The parent process (HA integration, or a human in a terminal) writes `{"cmd":"pair"}` to the daemon's stdin; the daemon mints a one-time code and answers one JSON line with the `ha-iroh://pair?t=...` ticket URI. A client runs `cli pair <uri>` and is added to the allowlist with its device name. Afterwards `cli curl /api/` works without manual allowlist edits.

Covers: stdio admin channel (JSON lines on stdin/stdout, logs on stderr, `{"event":"ready","endpoint_id"}` on start, `{"event":"paired",...}` after success; spec §6), `ha-iroh/pair/0` ALPN with u32 BE length + JSON framing, ticket JSON (`v`, `addr`, `relay`, `code`, `name`), code TTL (`pair_ttl`, default 10 min) and single use, rate-limit on failed attempts per remote id, error responses `invalid_code` / `expired`.

**Blocked by:** 02 (Bridge tunnel + proto + desktop CLI)

**Status:** done

- [x] `{"cmd":"pair"}` on stdin → `{"ok":true,"ticket":"ha-iroh://pair?t=...","code":...}` on stdout; daemon owns the key, nothing else opens an Endpoint
- [x] `{"event":"paired","endpoint_id","device_name"}` emitted on stdout after a successful pairing
- [x] `cli pair <uri>` returns `{ok:true, bridge_name}` and appends `{endpoint_id, device_name, added_at}` to the allowlist
- [x] Second use of the same code → `invalid_code`; expired code → `expired`
- [x] Repeated bad codes from one remote id are rate-limited
- [x] `cli curl /api/` works immediately after pairing (allowlist hot-applied)

## Comments

Verified (unit, `cargo test --workspace`, clippy clean): happy path + allowlist on disk + tunnel works right after pairing, single-use code, expired code (`pair_ttl = 0`), rate limit (5 bad codes, then close code 3 `rate_limited`).
Verified E2E (real binary, HA container, FIFO stdin): `{"cmd":"pair"}` -> ticket; `cli pair` -> `{ok:true,bridge_name}`; `paired` event line on stdout; `cli curl /api/` -> HA 401 with no restart; second `cli pair` with same URI -> `invalid_code`, exit 1; malformed stdin line -> `bad_request`; stdin EOF -> bridge exits.

Deviations / notes:
- iroh 1.3: `ep.addr()` is sync but has no relay URL until online. `main.rs` waits up to 3 s for `ep.online()` before minting; the lib does not (tests run relay-less).
- Code = first 16 bytes of `SecretKey::generate()` (base32 lowercase), avoids a rand dependency.
- Rate limit is in memory and keyed by remote EndpointId; resets on restart. Window 60 s measured from the last failure.
- Pair handler waits up to 5 s for the client to close before `close(0,"ok")` so the response is not cut off.
- Allowlist now loaded once at start (invalid TOML makes `Bridge::new` fail instead of silently denying all); manual edits need a restart. Written via `.tmp` + rename.
- `added_at` is RFC3339 UTC via `time` (already in the lockfile).
- stdout lines are emitted from the single select loop in `main.rs`; JSON key order is alphabetical (serde_json default map), fine for parsers.
