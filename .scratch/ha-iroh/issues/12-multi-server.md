# 12: Multi-server

**What to build:** User pairs a second HA instance. Device Controls panel shows both servers' entities grouped per server and goes live for both in parallel; if one server is offline only its controls show an error. The Assist sheet has a server chip that switches server (new tunnel, new `conversation_id`). The dashboard has a server switcher. Tapping a control opens that server's dashboard. Completes M7.

Covers: parallel connects grouped by `serverId` in `createPublisherFor`, per-server `STATUS_ERROR` on failure, Assist server chip + `serverId` shortcut extra, dashboard top-bar/drawer switcher, control `PendingIntent` → dashboard for that server (`more-info` deep link if easy, else root).

**Blocked by:** 08 (Device Controls: full domain mapping + areas), 10 (Assist voice + TTS), 11 (Dashboard WebView + loopback proxy + external_auth)

**Status:** ready-for-agent

- [ ] Two bridges paired; panel shows both groups via `setStructure`, both live
- [ ] Stop one bridge → only its controls go `STATUS_ERROR`, the other stays live
- [ ] Assist chip switches server; conversation restarts
- [ ] Dashboard switcher swaps proxy + tunnel to the other server
- [ ] Control tap opens the correct server's dashboard
