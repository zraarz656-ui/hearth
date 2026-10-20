# TradeQuest

An offline Android XAUUSD historical trading simulator.

TradeQuest replays real historical gold price data as a live-feeling market so a
trader can practise a prop-firm-style challenge on a professional interactive
candlestick chart. It requires no backend, no account and no internet
connection.

## Status

**Phase 0 complete.** The project builds, navigates and themes. All feature
screens are explicitly labelled placeholders; no trading, chart or simulation
logic exists yet.

See `docs/development-progress.md` for the authoritative progress record.

## Documentation

| File | Purpose |
| --- | --- |
| `AGENTS.md` | Permanent coding rules and product constraints |
| `TRADEQUEST_DEVELOPMENT_PLAN.md` | Phases, steps and acceptance criteria |
| `docs/architecture.md` | Package boundaries and dependency rules |
| `docs/development-progress.md` | Phase-by-phase progress and known gaps |

## Build

Requires JDK 17 or newer and an Android SDK with platform 36.

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug
```

## Requirements

- `minSdk` 26
- `targetSdk` 36 / `compileSdk` 36
- Kotlin, Jetpack Compose, Material 3, Room, WorkManager, Kotlin Serialization

## Data

TradeQuest ships with **no** historical data. Datasets must be imported from a
licensed source; synthetic data is used only in clearly labelled tests. No
dataset may ever be combined with another.
