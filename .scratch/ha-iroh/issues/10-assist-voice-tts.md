# 10: Assist voice + TTS

**What to build:** Tap the "Assist (voice)" shortcut or the mic in the sheet, say "turn on kitchen light": the light turns on, transcript and reply appear, and HA's TTS answer plays. Completes M5.

Covers: `RECORD_AUDIO` runtime permission, `AudioRecord` 16 kHz mono PCM16 `VOICE_RECOGNITION` started in parallel with connect and buffered until `run-start`, `stt_binary_handler_id` framing (`[id] + pcm`, flush buffer then live, `[id]` alone to end), `stt-vad-end` or manual stop, `stt-end` → transcript, `intent-end` → reply, `tts-end` → fetch relative `tts_output.url` over the tunnel and play (MediaPlayer/ExoPlayer from temp file or custom DataSource). Optional TTS on/off setting for text mode. No foreground service.

**Blocked by:** 09 (Assist text: QS tile + shortcuts + bottom sheet)

**Status:** done

- [x] Voice shortcut opens the sheet already recording; audio spoken before connect completes is not lost
- [x] VAD end stops streaming automatically; stop button also works
- [x] Transcript, reply, and TTS playback all appear for a spoken command
- [x] Mic released and tunnel closed when the sheet is dismissed (device-verify)
- [x] Unit test for binary frame assembly (handler byte prefix, end-of-stream frame)

## Comments

Implemented; unit-tested (frame assembly + mocked `listen` run). Not verified on device (needs phone + real HA). Verify:
- Voice shortcut: permission prompt first time, then recording starts; speech before connect is not lost.
- VAD end stops recording on its own; Stop button also works; mic indicator disappears.
- Transcript, reply, TTS audio plays (assumed `.mp3` temp file; MediaPlayer sniffs content anyway).
- Dismiss mid-listen / mid-TTS: mic off, player released, tunnel closed.
- Conversation id carried across a voice then text turn.

Skipped (YAGNI): TTS on/off setting for text mode, ExoPlayer, custom DataSource, state restore. HaClient.bytes uses blocking execute() (not cancellable mid-download).

- 2026-10-11 device test on user phone + HA (local STT): voice, VAD stop, transcript, reply, TTS all work. Fixed `data: null` on run-end (JsonNull). Done.
