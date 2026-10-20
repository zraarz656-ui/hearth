# TradeQuest architecture

## Principle

One Android application module, organised by package. Additional Gradle modules
are added only when a concrete benefit appears; none is justified yet.

## Layers

```
com.tradequest.app
  MainActivity, TradeQuestApplication
  app/navigation      top-level navigation targets and host
  app/di              manual composition root (AppContainer)
  core/market         historical data: import, validation, aggregation, repository
  core/simulation     clock, deterministic market path, replay, time warp
  core/trading        orders, executions, positions, closed trades
  core/accounting     fixed-point money, PnL, commission
  core/risk           margin, limits, position sizing
  core/challenge      rules, scoring, badges
  core/chart          renderer, buffer, viewport, coordinates, gestures,
                      drawings, indicators, overlays
  core/database       market.db and tradequest.db
  feature/*           screens, one package per feature area
  ui/theme            colour, type and theme tokens
  ui/components       shared composables
```

## Dependency rules

1. `core/*` must not depend on `feature/*` or on Compose.
2. `core/trading`, `core/accounting`, `core/risk` and `core/challenge` must be
   pure Kotlin: no Android framework types.
3. `core/chart` may depend on Compose, because it is a Compose Canvas renderer,
   but it must not depend on `feature/*`.
4. `feature/*` may depend on `core/*` and `ui/*`. It must not reach into
   `core/database` directly; repositories mediate.
5. Domain models must not be Room entities. Mapping functions live in
   `core/database`.
6. `market.db` is read-only and `tradequest.db` is read-write. No cross-database
   joins.

## Money and price representation

| Quantity | Representation | Example |
| --- | --- | --- |
| Price | integer cents (`Int`) | `$2,450.50` = `245050` |
| Lot size | integer hundredths of a lot (`Int`) | `0.20` lots = `20` |
| Money | integer micro-dollars (`Long`) | `$10,000.00` = `10_000_000_000` |
| Timestamp | UTC epoch milliseconds (`Long`) | |

`Float` and `Double` are permitted for rendering geometry and non-authoritative
ratios only. They must never hold money, price, lot size, margin or PnL.

## Time

Timestamps are stored in UTC. Trading-session logic uses
`America/New_York` through `java.time`, never a fixed UTC offset. The logical
trading day rolls over at 17:00 New York.

## Determinism

The chart and the trading engine consume the same deterministic intraminute
path. Path generation depends only on dataset identity, candle timestamp, OHLC
and the path model version.

## Verification limits

Unit tests run on the JVM. Instrumentation tests and any rendering,
gesture or frame-rate claim require a physical device and are recorded as
unverified until measured.
