# Changelog

## 0.3.3 — Sane notifications

- 区块用量告警默认关闭：自动检测的"token 限额"在多 IDE/CLI 同时使用场景下不可靠（取的是历史最高用量），会出现 123% 这种虚假百分比。仅当用户在设置里手动配置 `customTokenLimit` 或显式开启 `enableBlockNotifications` 时才告警。
- 通知 toast 默认 60 秒后自动关闭（可在设置中调整 `notificationAutoCloseSeconds`，0 = 不自动关）。

## 0.3.2 — Live tab shows all sources

- 实时标签页默认显示所有来源（多个 IDE / CLI / Antigravity 等）的活动，不再只显示当前 IDE 项目。
- 实时表格新增「项目」列，方便区分不同来源的会话。
- 工具栏新增「仅当前项目」复选框，需要旧行为时可勾选。
- 概览行展示当前作用域：`全部来源 · N 个项目` 或 `项目名`。

## 0.3.1 — Marketplace fixes

- Rename plugin from `ccidea` to `Ccode Stats` (Marketplace forbids "IDEA" in name).
- Drop `untilBuild` upper bound (no magic 999 sentinel).
- Sessions tab: sort by most recent activity by default; multi-select project filter via toolbar button + right-click menu.
- Patterns tab: always show a usage overview row; broaden recommendation rules.
- Blocks tab: snap burn-rate chart's "now" to minute granularity to stop flicker on idle refresh ticks.

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
