# 09: Assist text: QS tile + shortcuts + bottom sheet

**What to build:** User adds the "Assist" Quick Settings tile or uses the "Assist (text)" launcher shortcut; a translucent bottom sheet opens on the default Assist server, the user types a command, and HA's reply appears. Pipeline errors are shown inline.

Covers: `TileService` (`startActivityAndCollapse(PendingIntent)` on 14+, Intent variant on 12–13), static shortcuts (voice/text, optional `serverId` extra), bottom-sheet activity with transcript/reply/text field (mic button present but may be inert until 10), `assist_pipeline/run` with `start_stage: intent`, `conversation_id` kept for the sheet session, `error` event handling. Verify event names against current HA `assist_pipeline` source (spec risk 7).

**Blocked by:** 06 (Server model + QR scan + login_flow + token storage)

**Status:** done

- [x] Tile and text shortcut open the sheet (API 37 tile collapse verified; the API 31-33 Intent variant is code-only, no such emulator)
- [x] Typed "turn on kitchen light" → light on, reply text shown from `intent_output.response.speech.plain.speech`
- [x] Follow-up message reuses `conversation_id`
- [x] Pipeline `error` events displayed with their message
- [x] Tunnel closed when the sheet is dismissed

## Comments

- Verified against HA core source (assist_pipeline `websocket_api.py`, `models.py`, `default_pipeline.py`): command `assist_pipeline/run` {start_stage, end_stage, input:{text}, pipeline?, conversation_id?}. Events (`event.type`, payload in `event.data`): `run-start`, `intent-start`, `intent-progress`, `intent-end` (`data.intent_output.response.speech.plain.speech`, `data.intent_output.conversation_id`), `run-end`, `error` (`data.code`, `data.message`). Validation failures (e.g. unknown pipeline `pipeline-not-found`, missing engine) come back as a failed `result` (HaError), not an `error` event; both are shown inline.
- Code: `Assist.kt` (`AssistSession.ask`, `endStage` param for ticket 10), `AssistActivity.kt`, `AssistTile.kt`, `res/xml/shortcuts.xml`, theme/strings/icon, manifest insertions. `AssistTest` (MockWebServer): happy path + error event. `testDebugUnitTest assembleDebug` pass.
- E2E (emulator-5554 API 37, HA stable + `demo:`, bridge via relay): `cmd statusbar add-tile` + QS tile opens the sheet; "turn on bed light" -> HA light on + "Turned on the light"; "turn it off" reused the same conversation id (shown small under transcript) and resolved "it"; pipeline engine set to a missing agent via WS -> red inline "Intent recognition engine conversation.nope is not found"; scrim tap -> bridge logged `disconnected` within 1 s. `get-shortcuts` lists "Assist (text)" and "Assist (voice)".
- Deviations: mic is a disabled "mic: later" button (no tooltip); `voice=true` extra ignored. Activity uses platform `Theme.Material.NoActionBar` translucent + Compose Column with scrim, not Material3 theme/ModalBottomSheet (no material-components dep). Sheet finishes in `onStop` (no state restore). Server choice: `serverId` extra, else `isDefaultAssist`, else first. A non-HA error (network) drops the session and reconnects on next send; pipeline/HA errors keep it.
- Review fixes: activity `exported=false`; connect+ask in a cancellable job, stop/switch cancels and releases the target server id (off main thread); chip disabled while busy; timeout shown inline ("timed out"); `convId` kept when a reply omits it; empty speech -> "(no reply)"; `endStage` param removed; extras as constants; `voice=true` shows a "voice: later" line. New tests: failed result -> HaError, second ask sends first reply's conversation id.
- Shortcut verified on emulator: launcher long-press lists both shortcuts; "Assist (voice)" opened the sheet with the voice hint (explicit intent works with `exported=false`; `am start` from adb no longer can).
- Ticket 10 needs a binary-frame send on `Session` (currently text-only `ws.send`).
- Known nit: shortcut/tile icon is white, so it is invisible on the launcher's light shortcut circle.
