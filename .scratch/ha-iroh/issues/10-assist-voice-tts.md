# 10: Assist voice + TTS

**What to build:** Tap the "Assist (voice)" shortcut or the mic in the sheet, say "turn on kitchen light": the light turns on, transcript and reply appear, and HA's TTS answer plays. Completes M5.

Covers: `RECORD_AUDIO` runtime permission, `AudioRecord` 16 kHz mono PCM16 `VOICE_RECOGNITION` started in parallel with connect and buffered until `run-start`, `stt_binary_handler_id` framing (`[id] + pcm`, flush buffer then live, `[id]` alone to end), `stt-vad-end` or manual stop, `stt-end` → transcript, `intent-end` → reply, `tts-end` → fetch relative `tts_output.url` over the tunnel and play (MediaPlayer/ExoPlayer from temp file or custom DataSource). Optional TTS on/off setting for text mode. No foreground service.

**Blocked by:** 09 (Assist text: QS tile + shortcuts + bottom sheet)

**Status:** ready-for-agent

- [ ] Voice shortcut opens the sheet already recording; audio spoken before connect completes is not lost
- [ ] VAD end stops streaming automatically; stop button also works
- [ ] Transcript, reply, and TTS playback all appear for a spoken command
- [ ] Mic released and tunnel closed when the sheet is dismissed
- [ ] Unit test for binary frame assembly (handler byte prefix, end-of-stream frame)
