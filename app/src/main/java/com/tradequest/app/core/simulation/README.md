# core.simulation

Simulation clock, deterministic intraminute market path, replay processor and time warp. Owns the single authoritative price path shared by chart and trading engine.

## Implemented

**Phase 3 — simulation clock and trading calendar.**

- `clock/TimeSource.kt` — `WallClock`, `MonotonicClock`, `TestTimeSource`. The
  three time concepts are separate interfaces, not one type with a mode flag.
- `clock/ClockAnchor.kt` — the persisted anchoring state, `ClockMode`,
  `PlaybackSpeed`, `DatasetBounds`, and `ClockMath`, the pure time derivation.
- `clock/SimulationClock.kt` — the authoritative clock. No ticker: historical
  time is a pure function of the anchor and the real clock.
- `clock/DatasetBoundsResolver.kt` — derives clock bounds from stored candles.
- `calendar/NewYorkTradingCalendar.kt` — 17:00 ET rollover, DST, the weekly
  window, and `SessionClassifier` for Asia / London / New York / overlap.
- `calendar/DatasetTradingCalendar.kt` — combines the rules above with the
  closures actually observed in a dataset.

## Not implemented

The deterministic market path, replay processor and time warp (Phases 4 and 12).

## Key rules

- Historical time never moves backwards. A device clock correction is absorbed
  by a process-scoped high-water mark, not by rewinding the simulation.
- Challenge Mode is locked to 1x and keeps running while the app is closed;
  Practice Mode supports 1x to 60x with pause.
- Sessions are defined in their own timezones, because the US and UK change
  clocks on different dates and the London/New York overlap genuinely differs
  by an hour for two weeks each spring and autumn.
- The simulation stops at the dataset endpoint and never enters another dataset.

See `docs/architecture.md` for the dependency rules that govern this package.
