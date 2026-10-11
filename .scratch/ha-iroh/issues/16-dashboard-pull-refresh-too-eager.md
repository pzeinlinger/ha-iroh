# 16: Dashboard pull-to-refresh triggers on normal scroll

**Bug:** In the dashboard WebView, pulling down to refresh also fires when the user scrolls up normally inside a page (SwipeRefreshLayout doesn't know the WebView's inner scroll position).

**Likely fix:** `SwipeRefreshLayout.setOnChildScrollUpCallback { _, _ -> webView.scrollY > 0 }` or check `window.scrollY` of the page via `evaluateJavascript` (HA's layout scrolls an inner element, so `webView.scrollY` may always be 0 → may need JS scroll position via the existing message bridge).

**Reported:** 2026-10-11, user's phone.

**Status:** needs-triage
