# 21: Reorder servers

**What to build:** Settings → Servers list: move a server up/down. Order is the stored list order and drives the dashboard server menu and Controls grouping.

**Fix:** drag the handle (≡, shown with ≥2 servers) to reorder. Hand-rolled on the plain `Column`: local `order` copy during the drag, rows swap when the dragged row passes half the neighbour's height, `ServerStore.reorder(ids)` persists on release. No reorderable-list dependency.

**Status:** ready-for-human

- [x] `assembleDebug` + unit tests green
- [ ] User: drag to reorder on the phone, order persists after app restart and shows in the dashboard server menu

## Comments

2026-10-11: self-implemented by supervisor (tiny glue, no review agents).
