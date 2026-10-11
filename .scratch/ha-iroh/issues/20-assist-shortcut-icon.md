# 20: Assist launcher shortcuts show no icon

**Bug:** long-press app icon → "Assist (text)" / "Assist (voice)" render without a glyph. `shortcuts.xml` pointed at `ic_assist`, a bare white 24 dp vector; launchers draw shortcut icons on their own surface so a white glyph on transparent is invisible.

**Fix:** two adaptive icons `ic_shortcut_assist_text` (chat bubble) / `ic_shortcut_assist_voice` (mic) on the launcher background colour, so the two actions are distinguishable at a glance; `ic_assist` (unused) deleted.

**Status:** ready-for-human

- [x] `assembleDebug` green
- [ ] User: shortcuts show chat / mic glyphs on the teal disc on the Pixel launcher (needs a tagged release for Obtainium)

## Comments

2026-10-11: self-implemented by supervisor (tiny glue, no review agents).
