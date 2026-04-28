# ccidea

A JetBrains IDE plugin that brings ccusage-style Claude Code token & cost
visibility into the IDE itself: tool window with charts, status-bar widget,
30-second auto-refresh, and notifications when you're approaching the 5-hour
billing block limit.

Reads `~/.claude/projects/**/*.jsonl` directly — no remote API calls except
fetching the LiteLLM pricing JSON (cached locally, with offline fallback).

## Features

- **Daily / Monthly / Sessions / Blocks / Patterns** tabs in a right-side tool window.
- **Stacked-bar charts** (Lets-Plot) for token-type breakdown, plus a burn-rate
  time series with dashed ETA-to-limit reference line.
- **Status-bar widget** showing current 5h block usage: `█████░░░ 62% · 1h47m · $4.21`.
- **5-hour rolling block** tracker matching ccusage's algorithm, with gap-block
  synthesis and burn rate over current / 1h / 24h windows.
- **Cost calculation** from LiteLLM pricing JSON. 1-hour ephemeral cache priced
  at 2× the 5-min rate. Built-in offline fallback for Claude Opus / Sonnet / Haiku 4.x.
- **Pattern recognition**: top-N expensive sessions, model cache-hit ratio,
  hour-of-day heatmap, p95 outlier detection, rule-based recommendations.
- **Notifications** at 80% / 95% / 10 minutes before block reset.
- **Light/dark themed** charts and icons; re-renders on theme switch.
- **Settings** under Tools → ccidea: refresh interval, thresholds, custom token
  limit, claude config dir override, offline-only mode.
- **Read-only** — never writes to `~/.claude`.

## Build & try

```bash
./gradlew test         # unit tests (parser, dedup, blocks, burn rate, patterns, ...)
./gradlew buildPlugin  # build/distributions/ccidea-<version>.zip
./gradlew runIde       # sandbox IDE with the plugin loaded
```

## Release

Push a `v<version>` tag (matching `version` in `build.gradle.kts`) — the
[release workflow](.github/workflows/release.yml) signs and publishes to
JetBrains Marketplace. Required repo secrets:

- `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD` — plugin signing
- `JETBRAINS_MARKETPLACE_TOKEN` — Marketplace upload token

To run the JetBrains Plugin Verifier locally, uncomment the
`pluginVerification` block in `build.gradle.kts` and run
`./gradlew verifyPlugin`. (It's commented out by default because it requires
network access to plugins.jetbrains.com.)

## Layout

```
src/main/kotlin/com/ccidea/plugin/
  data/          # JSONL parser, models, incremental tail-reader, dedup
  pricing/       # LiteLLM fetch + 24h cache + offline fallback
  blocks/        # 5h rolling window algorithm + burn rate
  service/       # daily/monthly/session aggregation + event bus
  poller/        # 30s scheduler + WatchService + startup activity
  notifications/ # 80/95/10-min state machine
  patterns/      # top-N, hit-ratio, heatmap, outliers, recommendations
  settings/      # PersistentStateComponent
  ui/            # tool window tabs, charts (Lets-Plot), status bar, Configurable
src/main/resources/
  META-INF/plugin.xml
  pricing/litellm-fallback.json
  messages/CcideaBundle*.properties
  icons/ccidea_13.svg, ccidea_13_dark.svg
```

## License

Apache 2.0 (planned). Reads only your local Claude data; reports stay in your IDE.
