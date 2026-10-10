# 09: Assist text: QS tile + shortcuts + bottom sheet

**What to build:** User adds the "Assist" Quick Settings tile or uses the "Assist (text)" launcher shortcut; a translucent bottom sheet opens on the default Assist server, the user types a command, and HA's reply appears. Pipeline errors are shown inline.

Covers: `TileService` (`startActivityAndCollapse(PendingIntent)` on 14+, Intent variant on 12–13), static shortcuts (voice/text, optional `serverId` extra), bottom-sheet activity with transcript/reply/text field (mic button present but may be inert until 10), `assist_pipeline/run` with `start_stage: intent`, `conversation_id` kept for the sheet session, `error` event handling. Verify event names against current HA `assist_pipeline` source (spec risk 7).

**Blocked by:** 06 (Server model + QR scan + login_flow + token storage)

**Status:** ready-for-agent

- [ ] Tile and text shortcut open the sheet; tile collapses the shade on 12, 13, and 14+
- [ ] Typed "turn on kitchen light" → light on, reply text shown from `intent_output.response.speech.plain.speech`
- [ ] Follow-up message reuses `conversation_id`
- [ ] Pipeline `error` events displayed with their message
- [ ] Tunnel closed when the sheet is dismissed
