# 16: Dashboard pull-to-refresh triggers on normal scroll

**Bug:** In the dashboard WebView, pulling down to refresh also fires when the user scrolls up normally inside a page (SwipeRefreshLayout doesn't know the WebView's inner scroll position).

**Likely fix:** `SwipeRefreshLayout.setOnChildScrollUpCallback { _, _ -> webView.scrollY > 0 }` or check `window.scrollY` of the page via `evaluateJavascript` (HA's layout scrolls an inner element, so `webView.scrollY` may always be 0 → may need JS scroll position via the existing message bridge).

**Reported:** 2026-10-11, user's phone.

**Status:** done

## Comments

**Done:** `web.scrollY` is document scroll, always 0 for HA (inner scroller in shadow DOM). Added a capture/passive `touchstart` listener to the document-start shim in `Dashboard.kt`: it checks `composedPath()` for any element with `scrollTop > 0` and posts `canScrollUp` through the existing origin-scoped WebMessageListener into a `@Volatile` flag. `setOnChildScrollUpCallback` returns that flag; reset to false in `onPageFinished`.

**Limit:** the legacy `addJavascriptInterface` fallback (no WEB_MESSAGE_LISTENER/DOCUMENT_START_SCRIPT) never injects the shim, so refresh stays eager there (old behaviour).

**Verified:** emulator-5554 paired with throwaway ha12, `wm density 640` to make Overview scrollable, counting `proxy port` log lines (one per refresh/start): scroll down, two pull-downs while scrolled = 0 refreshes; big pull-down that reaches the top (touch began scrolled) = 0; pull-down starting at top = 1. Density reset afterwards. Not checked on the real phone.

- 2026-10-11 phone: touchstart-only reporting was too late (SwipeRefreshLayout decides on touch-down) → fast gestures used the previous value. Final: hook `scroll` on the touched scroller and report live. Verified on the phone. Done.
