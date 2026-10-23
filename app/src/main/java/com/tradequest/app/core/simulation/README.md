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

**Phase 4 — deterministic market path engine.**

- `path/PathTypes.kt` — `PathIdentity`, `PathWaypoint`, `FormingCandle`,
  `MarketGap`, `PathModelVersion`, and the `PathFingerprint` (FNV-1a) that makes
  a path's identity stable without relying on `hashCode()`.
- `path/WaypointTiming.kt` — the single place that decides *when* the high and
  low occur. Shape is open → low → high → close for bullish and doji,
  open → high → low → close for bearish.
- `path/IntraminutePath.kt` — one candle's path. `priceAt` interpolates exactly
  in integers; `firstTouchElapsedMs` / `firstTouchFrom` **solve** a threshold
  crossing rather than sampling; `formingCandleAt` returns only traversed
  extrema.
- `path/MarketPathBuilder.kt` — turns a candle series into paths and emits an
  explicit `MarketGap` for every break between candles.
- `path/MarketPathWindow.kt` — time-indexed view over a run of candles.
  `priceAt` returns **null** inside a gap, so a caller cannot interpolate across
  a weekend by accident.

## Not implemented

The replay processor and time warp (Phase 12).

## Key rules

- Historical time never moves backwards. A device clock correction is absorbed
  by a process-scoped high-water mark, not by rewinding the simulation.
- Challenge Mode is locked to 1x and keeps running while the app is closed;
  Practice Mode supports 1x to 60x with pause.
- Sessions are defined in their own timezones, because the US and UK change
  clocks on different dates and the London/New York overlap genuinely differs
  by an hour for two weeks each spring and autumn.
- The simulation stops at the dataset endpoint and never enters another dataset.
- The chart and the trading engine must consume `MarketPathWindow`, never
  re-derive a path from OHLC. Two derivations would drift (product rule 2.4).
- Waypoint timing is an **assumption**, not a measurement: it is uniform
  quarter-minute spacing, so which of a stop and a target is hit first is a
  property of this model. See `docs/adr-0002-intraminute-path.md`.

See `docs/architecture.md` for the dependency rules that govern this package.
