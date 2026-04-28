# Changelog

## 0.3.0 — Patterns + polish (M3/M4)

- Patterns tab: top-N expensive sessions, model cache-hit ratio bar, 7×24 hour-of-day heatmap, p95 outlier highlighting, rule-based recommendations.
- Light/dark plugin icons + tool window icon.
- i18n bundle (English + Simplified Chinese), notification group key wired to bundle.
- README and CHANGELOG.

## 0.2.0 — Charts (M2)

- Lets-Plot charts on Daily / Monthly tabs (stacked bar by token type with stable color palette).
- Burn-rate time series on the Blocks tab with a dashed ETA-to-limit reference line.
- Theme-aware: re-renders on Darcula/Light switch via `LafManagerListener`.
- Tool window content disposable chain so chart panels and message-bus connections are released on window/project close.

## 0.1.0 — Tables + status bar + 30s polling (M1)

- Recursive walk of `~/.claude/projects/**/*.jsonl`, byte-offset incremental tail-reader with truncation/rotation detection, LRU dedup of `messageId:requestId`.
- Daily / Monthly / Sessions / Blocks `JBTable` views, sortable.
- Status bar widget: `█████░░░ 62% · 1h47m · $4.21`.
- 5-hour rolling block algorithm with gap-block synthesis and burn-rate over current/1h/24h windows.
- Cost calculation from LiteLLM pricing JSON with offline fallback bundled in resources; 1-hour ephemeral cache priced at 2× the 5-min rate.
- Notifications at 80% / 95% / 10 minutes before block reset.
- Settings: refresh interval, thresholds, custom token limit, claude config dir override, offline-only toggle.
