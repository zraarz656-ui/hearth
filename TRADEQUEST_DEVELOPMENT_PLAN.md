TRADEQUEST — COMPLETE ANDROID DEVELOPMENT PLAN

# TRADEQUEST — COMPLETE ANDROID DEVELOPMENT PLAN

Version: 2.5
Project: TradeQuest
Platform: Android
Language: Kotlin
UI: Jetpack Compose
Architecture: Offline-first
Development Method: Phase-by-phase AI-assisted implementation

Phase count: 16 (Phase 0 through Phase 15). Phase 15 was added after Phase 2
was accepted, because no earlier phase owned a user-facing import entry point.

---

# 1. PURPOSE

This document defines the implementation roadmap for TradeQuest.

TradeQuest is an offline XAUUSD historical trading simulator featuring:

- Multiple independent three-month historical datasets
- A professional interactive candlestick chart
- Deterministic historical price simulation
- Real historical macroeconomic events
- Chart-based trading
- Prop-firm-style challenges
- Risk management
- Trading journal
- Performance analytics
- Practice and Challenge modes

The application is initially for personal use.

No backend, accounts, or internet connectivity are required.

The architecture must remain extensible for future online features.

## IMPORTANT

This document must be read together with AGENTS.md.

AGENTS.md contains permanent coding rules.

This document defines the development phases and acceptance criteria.

Do not implement multiple phases simultaneously unless explicitly authorized.

---

# 2. NON-NEGOTIABLE PRODUCT RULES

## 2.1 Independent Historical Datasets

The app supports multiple approximately three-month XAUUSD datasets.

Each dataset is a separate historical market environment.

DATASETS MUST NEVER BE COMBINED.

Do not merge, chain, or concatenate datasets.

Do not transfer positions between datasets.

Do not transfer account balances between datasets.

Every new challenge starts with:

Balance: $10,000
Equity: $10,000
Open Positions: 0
Pending Orders: 0
Trading Days: 0
Daily Loss Strikes: 0

Previous challenge attempts remain in history.

The same dataset may be replayed through a new independent attempt.

Only one Challenge Mode season may be active at a time.

## 2.2 Historical Information Protection

Never reveal future historical prices or news outcomes.

All visible market information must respect the current simulated timestamp.

## 2.3 Chart Priority

The custom interactive chart is the most important UI component.

Spend additional engineering time on:

- Rendering
- Viewport mathematics
- Pinch zoom
- Panning
- Crosshair
- Drawing gestures
- Order-line interaction
- Position sizing
- Performance

## 2.4 Simulation Correctness

The chart and trading engine must use the same deterministic simulated market path.

Trading events must be processed chronologically.

## 2.5 Offline Operation

All core features must work without network connectivity.

---

# 3. RECOMMENDED PROJECT STRUCTURE

Use a pragmatic package-based architecture.

com.tradequest.app/

app/
    MainActivity.kt
    TradeQuestApplication.kt
    navigation/
    di/

core/
    market/
        model/
        import/
        validation/
        aggregation/
        calendar/
        repository/

    simulation/
        clock/
        path/
        replay/
        timewarp/

    trading/
        model/
        orders/
        execution/
        positions/

    accounting/
        money/
        pnl/
        commission/

    risk/
        margin/
        limits/
        sizing/

    challenge/
        rules/
        scoring/
        badges/

    chart/
        renderer/
        buffer/
        viewport/
        coordinates/
        gestures/
        drawings/
        indicators/
        overlays/

    database/
        market/
        player/

feature/
    home/
    campaigns/
    trading/
    challenge/
    news/
    journal/
    history/
    settings/

ui/
    theme/
    components/

Initially, these may exist inside one Android application module.

Create additional Gradle modules only when justified.

---

# 4. GLOBAL ENGINEERING DECISIONS

Resolve and document the following before implementing the trading engine.

## 4.1 Financial Units

> ### ⚠ PRICE SCALE DECISION — DECIDED, DO NOT RE-OPEN
>
> **Prices are stored as integer THOUSANDTHS of a US dollar, not cents.**
>
> `PRICE_UNITS_PER_USD = 1000`. A price of `2047.204` is stored as
> `2047204`. See `docs/adr-0001-price-scale.md` for the evidence.
>
> **Why:** XAUUSD quotes to three decimals. Measured over one real trading
> day (174,586 price samples), the finest step present is 0.001 USD and
> 20.3% of quotes are not representable as whole cents. Rounding to cents
> moves the high and low of 100% of bars and can flip a stop-loss trigger.
>
> **This is settled.** Do not convert back to cents, do not add a
> cents-based field, and do not "simplify" by dividing by 100 anywhere.
> One scale, one constant, everywhere.

Use:

- Historical price: **integer thousandths of a USD** (`PRICE_UNITS_PER_USD = 1000`)
- Lot quantity: integer hundredths of a lot
- Account money: integer micro-dollars
- Historical timestamp: UTC epoch milliseconds

Do not use Float or Double for authoritative accounting.

Use checked arithmetic or safe intermediate representations where multiplication could overflow.

**Overflow rule (verified, not theoretical).** Money magnitudes are
independent of the price scale, and the plan's micro-dollar money scale
already overflows Int32 on ordinary gold moves. Measured:

```
1 lot, a 30 USD gold move:
  Int32 result = 3,000,000,000  -> EXCEEDS Int32_MAX (2,147,483,647)
```

The Int32 ceiling is reached after roughly a **21 USD move at 1 lot**, and
gold routinely moves 20–40 USD per day. Therefore:

- **All money arithmetic must use `Long` intermediates.** PnL, margin,
  equity, and the notional product `priceUnits * quantity`.
- Do not store money in `Int`, and do not do money multiplication in `Int`
  and widen afterwards — the overflow happens during the multiply.
- Keep `Int` only for the stored price itself (max ~2,050,000 in
  thousandths, comfortably inside Int32).

Use `Long` for anything that multiplies a price by a quantity.

Document rounding policies. All conversions between thousandths and
micro-dollars are powers of ten and therefore **exact** — there should be no
rounding anywhere in that path. If you find yourself rounding there, a scale
is wrong.

## 4.2 Price Simulation

Bullish:

Open -> Low -> High -> Close

Bearish:

Open -> High -> Low -> Close

Doji:

Use a deterministic documented rule.

Both chart animation and trading execution must consume this path.

## 4.3 Simulation Event Ordering

Create one authoritative event ordering.

Recommended:

1. Advance market price.
2. Apply scheduled spread changes.
3. Detect crossed execution levels.
4. Execute earliest triggered event.
5. Update orders and positions.
6. Update balance and equity.
7. Evaluate mandatory risk events.
8. Continue remaining path movement.
9. Persist completed processing.

Where events share the same timestamp, use documented stable priorities.

Do not invent different event orders in separate subsystems.

## 4.4 Trading Session Time

Store UTC timestamps.

Use America/New_York for daily trading boundaries.

Daily rollover occurs at 17:00 New York.

Handle DST using zone-aware date/time calculations.

## 4.5 Data Authenticity

Do not fabricate historical datasets or economic news.

Synthetic data is permitted only for clearly labeled tests and development fixtures.

---

# PHASE 0 — PROJECT FOUNDATION

## Objective

Create a clean, compiling Android application.

## Step 0.1 — Inspect Environment

Inspect:

- Existing repository
- Available Android SDK
- Installed Java version
- Gradle environment
- Existing project files

Do not overwrite user files without inspection.

## Step 0.2 — Configure Project

Application ID:

com.tradequest.app

Minimum SDK:

26

Use:

- Kotlin
- Jetpack Compose
- Material 3
- Room
- KSP
- Coroutines
- StateFlow
- WorkManager
- Kotlin Serialization
- JUnit

Choose mutually compatible stable dependency versions.

## Step 0.3 — Gradle Configuration

Create or configure:

settings.gradle.kts
build.gradle.kts
gradle.properties
gradle/libs.versions.toml
app/build.gradle.kts

Configure the Android application and unit-test infrastructure.

## Step 0.4 — Base Architecture

Create package boundaries for:

- Market
- Simulation
- Trading
- Accounting
- Risk
- Challenge
- Chart
- Database
- Features

Avoid unnecessary placeholder abstractions.

## Step 0.5 — Navigation

Create basic navigation for:

- Home
- Market Library
- Trading
- Challenge Dashboard
- Journal
- Settings

Development placeholders are acceptable at this stage.

Clearly identify them as unfinished.

## Step 0.6 — Theme

Implement:

- Dark
- Light
- True-black OLED

Create centralized theme tokens.

## Step 0.7 — Documentation

Create:

README.md
AGENTS.md
TRADEQUEST_DEVELOPMENT_PLAN.md
docs/architecture.md
docs/development-progress.md

Preserve existing AGENTS.md and development plan contents.

## Step 0.8 — Verification

Run:

- Gradle build
- Unit tests
- Available static checks

## Acceptance Criteria

- Project compiles.
- MainActivity launches.
- Navigation works.
- Theme switching works.
- Test infrastructure runs.
- No unnecessary permissions.

STOP AFTER PHASE 0.

---

# PHASE 1 — HISTORICAL DATA PREPARATION

## Objective

Build a reliable pipeline for authentic XAUUSD historical datasets.

## Step 1.1 — Historical Candle Model

Create:

HistoricalCandle

Fields:

- timestampUtcMs: Long
- openUnits: Int
- highUnits: Int
- lowUnits: Int
- closeUnits: Int
- tickVolume: Int

> **Scale:** the `*Units` fields are integer **thousandths of a USD**
> (`PRICE_UNITS_PER_USD = 1000`). `2047.204` is stored as `2047204`.
>
> These were previously named `openCents` / `highCents` / `lowCents` /
> `closeCents` and held whole cents. They were renamed because a field
> named `*Cents` holding thousandths is a silent 10× bug waiting to happen.
> See §4.1 and `docs/adr-0001-price-scale.md`.
>
> Do not reintroduce a `*Cents` field name anywhere.

## Step 1.2 — Import Pipeline

Support Dukascopy-compatible one-minute CSV files.

Inspect the actual input format.

Do not assume every CSV uses the same:

- Timestamp format
- Timezone
- Column order
- Decimal separator
- Header layout

Provide explicit import configuration when necessary.

## Step 1.3 — Candle Validation

Validate:

High >= Open
High >= Close
Low <= Open
Low <= Close
High >= Low

Also validate:

- Positive prices
- Nonnegative volume
- Timestamp ordering
- Duplicate timestamps
- Invalid numeric values
- Unexpected gaps
- Suspicious spikes

## Step 1.4 — Timestamp Conversion

Convert timestamps to UTC epoch milliseconds.

Do not silently interpret timezone-less timestamps.

Require a documented source timezone.

## Step 1.5 — Price Conversion

Convert prices to **integer thousandths of a USD**.

```kotlin
/** Integer price units per US dollar. XAUUSD quotes to 3 decimals. */
const val PRICE_UNITS_PER_USD = 1000

fun usdToUnits(usd: String): Int = (BigDecimal(usd) * PRICE_UNITS_PER_USD).toInt()
```

**Deterministic rounding.** The Dukascopy CSV produced by
`tools/dukascopy/` carries exactly 3 decimals, so conversion is **exact**
and no rounding occurs. Parse the CSV column as decimal text, not as
`Double`, so that no binary-float error is introduced before scaling.

If a future source has finer precision than 0.001, round
**half-up away from zero** at the 3rd decimal and record it. Do not round
silently.

**Precision reduction policy (required by this step):** none. There is no
reduction — 3 decimals are preserved losslessly. This satisfies the
requirement to record the policy.

### Price units and pips

The plan previously defined no pip or point size, while Steps 5.13 and
6.11 refer to "Stop distance" and "Distance". Define it once, here:

```kotlin
/** One pip = 0.10 USD = 100 price units. */
const val PRICE_UNITS_PER_PIP = 100
```

**Store every stop, target, and trailing-stop distance in PIPS, never in
raw price units.** A distance expressed in raw units silently changes
meaning if the storage scale is ever revised; a distance in pips does not.
Convert to units only at the point of arithmetic:

```kotlin
val stopDistanceUnits = stopDistancePips * PRICE_UNITS_PER_PIP
```

## Step 1.6 — Market Closure Detection

Classify:

- Expected weekend closure
- Known holiday
- Unexpected missing data

Do not interpret every missing minute as normal closure.

## Step 1.7 — Dataset Manifest

Generate:

Dataset ID
Instrument
Start Timestamp
End Timestamp
Candle Count
Minimum Price
Maximum Price
Missing Minute Count
Duplicate Count
Validation Status
Source Description
Content Hash

## Step 1.8 — Hashing

Generate a stable SHA-256 hash from canonical dataset contents.

The hash must be reproducible.

## Step 1.9 — Test Fixtures

Create synthetic fixtures for:

- Bullish candles
- Bearish candles
- Doji candles
- Weekend gaps
- Unexpected data gaps
- Price spikes
- DST transitions

Label synthetic data clearly.

## Step 1.10 — Tests

Test:

- Correct import
- Bad timestamps
- Duplicate candles
- Invalid OHLC
- Incorrect timezone
- Price conversion
- Hash stability

## Acceptance Criteria

- Valid datasets import successfully.
- Invalid datasets produce clear errors.
- Data conversion is deterministic.
- Dataset validation reports are generated.
- No synthetic data is presented as historical.

STOP AFTER PHASE 1.

---

# PHASE 2 — ROOM DATABASES AND MARKET LIBRARY

## Objective

Persist historical market data and player progress independently.

## Step 2.1 — Market Database

Create:

MarketDatabase

Tables:

MarketDataset
Candle1m
NewsEvent
MarketClosure
DatasetValidation

Candle primary key:

(datasetId, timestampUtcMs)

Create suitable indexes.

Do not assume Room automatically provides WITHOUT ROWID.

## Step 2.2 — Player Database

Create:

PlayerDatabase

Tables:

Season
TradeOrder
Execution
Position
ClosedTrade
DailyStats
EquitySnapshot
Drawing
TradeJournal
Badge
ReplayCheckpoint
Settings

## Step 2.3 — Domain and Database Separation

Do not use Room entities as trading-engine domain models.

Create mapping functions between persistence and domain layers.

## Step 2.4 — Market Repository

Implement a repository interface supporting:

- List datasets
- Get dataset metadata
- Query candles by dataset and time range
- Query news events
- Get validation status

Avoid unbounded database queries.

## Step 2.5 — Dataset Isolation

Every candle query must be scoped to one dataset.

Every news query must be scoped to one dataset.

Every season references exactly one dataset.

No dataset combining APIs are permitted.

## Step 2.6 — Market Library UI

Display:

- Dataset name
- Historical period
- Duration
- Candle count
- News availability
- Validation status
- Storage size

Support selecting one dataset.

## Step 2.7 — Import Storage

Allow validated imported datasets to be added locally.

Do not overwrite an existing dataset with different content under the same immutable version ID.

## Step 2.8 — Database Tests

Test:

- Inserts
- Queries
- Duplicate prevention
- Dataset isolation
- Deletion restrictions
- Database reopening
- Migrations where applicable

## Acceptance Criteria

- Market database works.
- Player database works.
- Dataset isolation is enforced.
- Market Library displays stored datasets.
- Import persistence works.
- Tests pass.

STOP AFTER PHASE 2.

---

# PHASE 3 — SIMULATION CLOCK AND TRADING CALENDAR — COMPLETE (2026-10-21)

> Implemented and verified. See `docs/development-progress.md` for the
> implementation record, the two bugs found by tests, and the unverified list.
> 97 tests added; suite at 253 passing, 0 failures (156 before). Lint clean for new code.
> Both databases remain at schema version 1 — `ReplayCheckpoint` already
> carried every field Step 3.5 required, so no migration was needed.

## Objective

Create the authoritative virtual time system.

## Step 3.1 — Time Source

Create an injectable time source.

Support:

- Wall-clock time
- Monotonic elapsed time
- Test-controlled time

Keep these concepts separate.

## Step 3.2 — Simulation Clock

Create:

SimulationClock

Functions should support:

- Current historical time
- Pause
- Resume
- Advance
- Playback speed
- Persisted state restoration

## Step 3.3 — Challenge Mode

Challenge Mode advances historical time at 1x real elapsed time.

Closing the app does not intentionally pause the season.

On reopening, process elapsed historical time.

## Step 3.4 — Practice Mode

Support:

1x
2x
5x
10x
30x
60x

Support pause and resume.

## Step 3.5 — Clock Anchoring

Persist:

- Historical anchor timestamp
- Real-time anchor
- Current mode
- Playback speed
- Pause state
- Last committed simulation timestamp

Do not depend solely on a fixed seven-day offset.

## Step 3.6 — New York Trading Calendar

Use:

America/New_York

Implement:

- 17:00 daily rollover
- Trading-day identification
- Weekly session identification
- DST handling
- Market closures

## Step 3.7 — Session Classification

Implement:

- Asia
- London
- New York
- London/New York overlap

Use documented timezone-aware schedules.

## Step 3.8 — Dataset Boundaries

The simulation must stop when it reaches the dataset endpoint.

Do not advance into another dataset.

## Step 3.9 — Tests

Test:

- Clock progression
- Pause/resume
- Playback speed
- Restart recovery
- DST
- Daily rollover
- Weekend handling
- Dataset endpoint

## Acceptance Criteria

- Clock behaves deterministically under test.
- Challenge time is recoverable.
- Practice controls work.
- NY rollover is correct.
- Dataset boundaries are respected.

STOP AFTER PHASE 3.

---

# PHASE 4 — DETERMINISTIC MARKET PATH ENGINE — COMPLETE (2026-10-21)

## Objective

Create one simulated intraminute price path shared by chart and execution.

## Step 4.1 — Path Model

Bullish:

Open -> Low -> High -> Close

Bearish:

Open -> High -> Low -> Close

Doji:

Implement a documented deterministic tie-break rule.

## Step 4.2 — Waypoint Timing

Define deterministic waypoint positions across each minute.

For example:

0 ms
20,000 ms
40,000 ms
60,000 ms

Timing may vary deterministically if fully tested.

## Step 4.3 — Price Interpolation

Implement price lookup at any supported elapsed time.

Use fixed-point or rational calculations internally.

Avoid inconsistent rounding between execution and chart.

## Step 4.4 — Forming Candle

Generate visible:

- Open
- High so far
- Low so far
- Current close

Only use already traversed simulated price movement.

## Step 4.5 — Continuous Threshold Crossing

Implement calculations for the earliest crossing of an arbitrary price threshold along a path segment.

Do not rely only on 1Hz displayed samples.

## Step 4.6 — Gap Handling

Represent discontinuities between available candle endpoints explicitly.

Do not interpolate tradable movement across genuine market closures.

## Step 4.7 — Deterministic Identity

Path generation must depend only on defined inputs such as:

- Dataset version
- Candle timestamp
- OHLC
- Path model version

## Step 4.8 — Tests

Test:

- Bullish path
- Bearish path
- Doji
- Exact endpoints
- High/low timing
- Price interpolation
- Threshold crossings
- No future leakage
- Repeatability

## Acceptance Criteria

- Identical inputs generate identical paths.
- Forming OHLC is correct.
- Crossing detection works.
- Future candle extrema remain hidden.
- Tests pass.

### Implementation record

Delivered in `core/simulation/path/`:

- `PathTypes.kt` — `PathIdentity`, `PathWaypoint`, `FormingCandle`,
  `MarketGap`, `GapReason`, `PathModelVersion`, `PathFingerprint` (FNV-1a).
- `WaypointTiming.kt` — waypoint order and even quarter-minute timing
  (0 / 15,000 / 30,000 / 60,000 ms).
- `IntraminutePath.kt` — `priceAt` (exact integer interpolation, one rounding
  rule), `firstTouchElapsedMs` / `firstTouchFrom` (solved crossings),
  `formingCandleAt` (traversed extrema only).
- `MarketPathBuilder.kt` — candle series to paths, with an explicit
  `MarketGap` for every break.
- `MarketPathWindow.kt` — time-indexed lookup. `priceAt` returns null inside a
  gap, so no caller can interpolate across a closure.

Verified: 332 tests, 0 failures (79 added by this phase; 253 before).
`assembleDebug` and `lintDebug` green; no lint warning against any new file.
No INTERNET permission.

Decision record: `docs/adr-0002-intraminute-path.md`.

Known limitation, recorded rather than hidden: waypoint timing is uniform
quarter-minute spacing, which is an assumption and not a measurement. The
relative order in which a stop and a target are hit on the same candle is
therefore a property of this model, and cannot be validated against 1-minute
OHLC because no intraminute ground truth exists in the dataset.

STOP AFTER PHASE 4.

---

# PHASE 5 — TRADING EXECUTION ENGINE

## Objective

Implement realistic, deterministic trading mechanics.

## Step 5.1 — Trading Domain Models

Create:

Order
Execution
Position
ClosedTrade
AccountState
TradingCommand

Keep domain models independent of Room.

## Step 5.2 — Order Types

Support:

- Market Buy
- Market Sell
- Buy Limit
- Sell Limit
- Buy Stop
- Sell Stop

## Step 5.3 — Position Features

Support:

- Stop Loss
- Take Profit
- Trailing Stop
- Breakeven
- Partial Close
- Full Close
- Order Modification
- Pending Cancellation

## Step 5.4 — Contract Rules

XAUUSD:

1 lot = 100 oz
Minimum lot = 0.01
Lot increment = 0.01
Leverage = 1:100

## Step 5.5 — Bid/Ask

Historical price = Bid.

Ask = Bid + Spread.

Long entry: Ask.
Long exit: Bid.

Short entry: Bid.
Short exit: Ask.

## Step 5.6 — Spread Model

Spread is defined in **USD** and converted to price units once, at the
boundary. Do not scatter `1000` literals through the engine.

```kotlin
val SPREAD_NORMAL_UNITS = 300    // $0.30 = 300 units
val SPREAD_ROLLOVER_UNITS = 600  // $0.60
val SPREAD_NEWS_UNITS = 800      // $0.80
```

Normal: $0.30 (300 units)

Rollover: $0.60 (600 units)

High-impact news: $0.80 (800 units)

Ask = Bid + spread, computed in price units with `Long` intermediates if
multiplied by size. Never in `Float`.

Rollover window:

17:00 NY +/- 10 minutes.

High-impact news window:

Release time +/- 5 minutes.

Use the larger spread when windows overlap.

> **Note:** the spread is small relative to the 0.001 price resolution
> (0.30 USD = 300 units), so spreads were never the precision problem.
> The precision problem is entry levels and stop placement. See §4.1.

## Step 5.7 — Commission

$7 per lot round trip.

Charge proportionally at entry and exit.

## Step 5.8 — Slippage

Normal: $0.00

High-impact news: $0.20–$1.00 adverse.

Use deterministic seeded behavior.

Document which execution types receive slippage.

## Step 5.9 — Event Ordering

Process:

- Market movement
- Spread changes
- Pending-order triggers
- Stop-loss triggers
- Take-profit triggers
- Position modifications
- Risk events

Chronologically.

Do not process all fills first and risk checks afterward.

## Step 5.10 — Same-Candle Execution

Support orders opening and closing during the same historical minute.

Never execute against price movement preceding order activation.

## Step 5.11 — Gaps

Define deterministic gap execution for:

- Stop orders
- Limit orders
- Stop losses
- Take profits

Do not assume fills occur at unavailable prices.

## Step 5.12 — Partial Close

Support closing 50% where the resulting quantities satisfy lot-size rules.

Handle positions too small for a valid 50% partial close explicitly.

## Step 5.13 — Trailing Stops

Define:

- Activation behavior
- Distance
- Update frequency
- Whether changes apply immediately or next segment

Use one consistent rule.

## Step 5.14 — Tests

Test:

- Every order type
- Long/short execution
- Spread
- Commission
- Slippage
- Same-candle entry/exit
- Pending activation timing
- Gap execution
- Partial close
- Trailing stops
- Identical replay outcomes

## Acceptance Criteria

- Orders execute correctly.
- Accounting inputs are reproducible.
- No look-back fills.
- Same-candle event ordering is correct.
- Tests pass.

STOP AFTER PHASE 5.

---

# PHASE 6 — ACCOUNTING, MARGIN, RISK, AND POSITION SIZING

## Objective

Implement mathematically consistent account state.

## Step 6.1 — Money Representation

Use fixed-point monetary values.

Recommended:

Micro-dollars stored as Long.

Document conversions.

Use checked arithmetic for multiplication.

**Required: all money arithmetic uses `Long` intermediates.** This is not
optional. The Int32 ceiling is reached after roughly a **21 USD move at
1 lot**, and gold routinely moves 20–40 USD per day:

```
1 lot, a 30 USD gold move:
  Int32 result = 3,000,000,000  -> EXCEEDS Int32_MAX (2,147,483,647)
```

Do not perform money multiplication in `Int` and widen afterwards — the
overflow occurs during the multiply. Widen first.

**Exact conversion.** Price thousandths (1e-3) to micro-dollars (1e-6) is a
multiply by 1000. Both are powers of ten, so this is an exact integer
operation with no rounding and no remainder:

```
2047.204 USD -> 2047204 units -> 2047204000 micro-dollars  (exact)
```

See §4.1 and `docs/adr-0001-price-scale.md`.

## Step 6.2 — Lot Representation

Represent lot size as integer hundredths of a lot.

Example:

0.20 lots = 20 lot units.

## Step 6.3 — PnL

Calculate realized and unrealized PnL.

Account for:

- Side
- Entry price
- Exit price
- Lot size
- Spread
- Slippage
- Commission

## Step 6.4 — Equity

Equity = Balance + Unrealized PnL.

Define how entry commissions affect balance and equity.

Avoid double charging.

## Step 6.5 — Margin

Required margin:

Gold Price x 100 x Lots / 100

Use the correct executable price convention.

## Step 6.6 — Margin Level

Margin Level = Equity / Used Margin x 100.

Handle zero used margin.

## Step 6.7 — Warning

Trigger margin warning at or below 100%.

## Step 6.8 — Stop-Out

At or below 50%:

- Identify largest unrealized dollar loser
- Close position
- Update balance
- Update equity
- Recalculate used margin
- Repeat if necessary

## Step 6.9 — Daily Loss

Daily reset:

17:00 New York.

Daily reference:

Equity at rollover.

Daily floor:

DayStartEquity x 0.97

If equity reaches or falls below the floor:

- Flatten positions
- Cancel pending orders
- Lock new trading
- Record one strike

## Step 6.10 — Overall Loss

Starting balance:

$10,000.

Static floor:

$9,000.

If equity reaches or falls below $9,000:

Fail the challenge.

## Step 6.11 — Position Sizing

Implement planned-risk calculations.

Account for:

- Stop distance
- Spread
- Commission
- Entry side
- Planned slippage allowance
- Minimum lot
- Lot step
- Available margin

Round lot size downward.

Never silently exceed selected planned risk.

## Step 6.12 — Tests

Test:

- Long PnL
- Short PnL
- Fees
- Partial closes
- Multiple positions
- Margin warning
- Margin stop-out
- Daily loss
- Maximum loss
- Risk sizing
- Rounding
- Overflow boundaries

## Acceptance Criteria

- Account values are consistent.
- Risk checks follow simulated time.
- Position sizing respects limits.
- Margin stop-out works.
- Tests pass.

STOP AFTER PHASE 6.

---

# PHASE 7 — REPLAY PROCESSOR AND RELIABLE PERSISTENCE

## Objective

Ensure foreground, background, and resumed simulation produce identical results.

## Step 7.1 — Replay Processor

Create:

ReplayProcessor

Inputs:

- Dataset ID
- Season ID
- Starting checkpoint
- Target simulated timestamp

## Step 7.2 — Replay Loop

Process:

1. Historical data
2. Generated price path
3. Scheduled actions
4. Trading execution
5. Account updates
6. Risk rules
7. Challenge rules
8. Persistence

## Step 7.3 — Checkpoints

Persist:

- Last processed market time
- Simulation state
- Order state
- Position state
- Account state
- Challenge state

## Step 7.4 — Atomic Transactions

Persist the checkpoint and associated trading changes together.

## Step 7.5 — Idempotency

Prevent duplicate:

- Fills
- Commissions
- Liquidations
- Daily strikes
- Notifications

## Step 7.6 — Batching

Process bounded intervals.

Use background dispatchers.

Keep UI responsive.

## Step 7.7 — App Resume

On resume:

- Restore state
- Determine target historical time
- Catch up
- Publish account state

## Step 7.8 — WorkManager

Use best-effort periodic catch-up.

Do not rely on exact 15-minute scheduling.

## Step 7.9 — Notifications

Prepare notification events for:

- Fills
- SL
- TP
- Margin warnings
- Daily locks
- Challenge results

Deliver notifications only after successful persistence.

## Step 7.10 — Tests

Test:

- Interrupted batch
- Restart
- Duplicate processing
- Multi-day catch-up
- Clock restoration
- Foreground/background equivalence
- Transaction rollback

## Acceptance Criteria

- No duplicate fills.
- No corrupted state.
- Replay is deterministic.
- Recovery works.
- UI is not blocked.

STOP AFTER PHASE 7.

---

# PHASE 8 — CUSTOM PROFESSIONAL CHART RENDERING

## Objective

Build the most important visual component of TradeQuest.

Allocate substantial implementation and testing effort to this phase.

## Step 8.1 — Chart Architecture

Create:

ChartRenderer
CandleBuffer
ChartViewportController
ChartCoordinateTransformer
ChartStyle
ChartRenderState

Separate chart rendering from Compose screen state.

## Step 8.2 — CandleBuffer

Use primitive arrays:

- LongArray timestamps
- FloatArray opens
- FloatArray highs
- FloatArray lows
- FloatArray closes
- IntArray volumes

Authoritative market data remains integer thousandths of a USD
(`PRICE_UNITS_PER_USD = 1000`). See §4.1.

Convert to chart-local coordinates for drawing.

The `FloatArray` buffers are **for drawing only** and are never
authoritative. Float32 represents every price in thousandths exactly
(integers up to 2^24 = 16,777,216; prices peak near 2,050,000), so the
conversion is lossless. Do not compute PnL, fills, or stops from these
buffers — read the integer fields instead.

## Step 8.3 — Viewport

Track:

- Visible bars
- Candle width
- Horizontal position
- Right padding
- Price scale
- Auto-scale state

## Step 8.4 — Visible Candle Culling

Draw only visible candles plus a small buffer.

Do not render all 90,000 candles each frame.

## Step 8.5 — Candlesticks

Render:

- Bullish bodies
- Bearish bodies
- Wicks
- Tiny candle bodies

Aim for crisp one-pixel wicks.

## Step 8.6 — Grid

Render:

- Price grid
- Time grid
- Low-contrast lines

Use adaptive spacing.

## Step 8.7 — Price Axis

Implement:

- Nice-step labels
- Current price label
- Price formatting
- Auto-fit calculations

## Step 8.8 — Time Axis

Display timestamps appropriate to timeframe and zoom.

Use actual historical candle times.

## Step 8.9 — Sessions

Draw:

- Asian session bands
- London session bands
- New York session bands
- Weekend separators

## Step 8.10 — Forming Candle

Connect to the MarketPathEngine.

Update:

- Current price
- High so far
- Low so far
- Current forming close

Do not reveal future OHLC.

## Step 8.11 — Timeframes

Support:

1m
15m
1h
4h
Daily
Weekly

Aggregate locally.

## Step 8.12 — Rendering Optimization

Use:

- Visible-range processing
- Primitive arrays
- Reusable geometry where practical
- Appropriate drawWithCache usage
- Minimal allocation

Profile before adding complex caching.

## Step 8.13 — Performance Testing

Test approximately 90,000 candles.

Measure:

- Frame timing
- Allocation
- Memory
- Data loading
- Timeframe switching

## Acceptance Criteria

- Candles render correctly.
- Higher timeframes work.
- Live forming candles work.
- Axes and grids work.
- Large datasets are supported.
- Rendering remains responsive.

STOP AFTER PHASE 8.

---

# PHASE 9 — CHART GESTURES, VIEWPORT, AND CROSSHAIR

## Objective

Make chart interaction feel professional.

## Step 9.1 — Horizontal Panning

Support one-finger horizontal drag.

## Step 9.2 — Fling

Implement controlled fling inertia.

Stop fling when new gestures begin.

## Step 9.3 — Pinch Zoom

Support two-finger zoom.

Anchor strictly around gesture focal point.

## Step 9.4 — Zoom Limits

Set sensible minimum and maximum candle width.

Recommended range:

2dp to 40dp.

## Step 9.5 — Crosshair

Activate through long press.

Recommended threshold:

280ms.

Display:

- Timestamp
- OHLC
- Price change
- Price label
- Time label

## Step 9.6 — Haptic Feedback

Provide subtle haptics when crossing candle boundaries.

## Step 9.7 — Manual Price Scaling

Drag right price axis to scale vertically.

Double-tap to restore auto-fit.

## Step 9.8 — Jump to Latest

Show when user is scrolled away from the live candle.

## Step 9.9 — Timeframe Switching

Keep the approximate center historical timestamp.

## Step 9.10 — Gesture Priority

Implement coordinated hit testing:

1. Price axis
2. Order lines
3. Drawing handles
4. Active drawing tool
5. Pinch zoom
6. Crosshair
7. Pan

## Step 9.11 — Gesture Tests

Test:

- Zoom anchoring
- Pan boundaries
- Coordinate inversion
- Crosshair selection
- Timeframe transitions
- Gesture cancellation

## Acceptance Criteria

- Smooth panning.
- Stable pinch zoom.
- Accurate crosshair.
- Correct scaling.
- No major gesture conflicts.

STOP AFTER PHASE 9.

---

# PHASE 10 — INTERACTIVE CHART TRADING

## Objective

Enable complete chart-based trade placement and management.

## Step 10.1 — Buy/Sell Controls

Implement functional:

Buy
Sell

Connect to trading engine.

## Step 10.2 — Order Ticket

Support:

- Market
- Limit
- Stop
- Lot size
- SL
- TP

## Step 10.3 — Position Lines

Display:

- Entry
- SL
- TP
- Live PnL

## Step 10.4 — Draggable Orders

Allow dragging:

- Stop Loss
- Take Profit
- Pending Entry

## Step 10.5 — Position Sizer

Implement three-line bracket:

Entry
SL
TP

Show:

- Risk zone
- Reward zone
- Risk amount
- Reward amount
- R:R ratio

## Step 10.6 — Auto-Lot Risk Lock

Support:

0.25%
0.50%
1.00%
1.50%
2.00%
Custom

Recalculate lot size while dragging SL.

## Step 10.7 — Position Actions

Support:

- Close
- 50% Partial Close
- Breakeven
- Modify SL
- Modify TP

## Step 10.8 — Trade Markers

Display entry and exit arrows.

## Step 10.9 — Trading Panels

Create:

- Open Positions
- Pending Orders
- Order History

Use expandable bottom sheets to preserve chart area.

## Step 10.10 — Tests

Verify:

- Order placement
- Line dragging
- Risk sizing
- Position closing
- UI/domain synchronization
- No accidental trades from chart gestures

## Acceptance Criteria

- Chart-based orders work.
- Position sizing works.
- Live PnL works.
- Order modifications work.
- Visual state matches domain state.

STOP AFTER PHASE 10.

---

# PHASE 11 — INDEPENDENT CHALLENGE SYSTEM

## Objective

Implement complete prop-firm-style campaigns.

## Step 11.1 — New Season

Selecting a dataset creates a new season.

Starting balance:

$10,000.

## Step 11.2 — Single Active Challenge

Allow only one active Challenge Mode season.

## Step 11.3 — Dataset Switching

Require confirmation.

Mark previous active challenge ABANDONED.

Save its history.

Start the selected dataset from its configured campaign start.

## Step 11.4 — Challenge Rules

Profit target:

15%.

Minimum trading days:

20.

Daily loss:

3%.

Maximum overall loss:

10%.

Daily strikes:

3 maximum.

## Step 11.5 — Pass/Fail

Implement deterministic pass/fail evaluation.

## Step 11.6 — Challenge Scoring

Maximum 100 points.

Profit:

40.

Risk Control:

30.

Consistency:

20.

Discipline:

10.

## Step 11.7 — Titles

Rookie: 0–39
Trader: 40–64
Pro: 65–84
Gold Master: 85–100

## Step 11.8 — Dashboard

Display:

- Balance
- Equity
- Profit
- Target progress
- Daily loss remaining
- Maximum loss remaining
- Trading days
- Strikes
- Score
- Season status

## Step 11.9 — Challenge History

Display previous:

- Passed
- Failed
- Abandoned
- Completed

## Step 11.10 — Replay

Allow replaying the same dataset through a fresh season.

## Step 11.11 — Personal Best

Track best performance per dataset.

Keep first-attempt results distinguishable from repeat attempts.

## Step 11.12 — Tests

Test:

- Starting state
- Dataset isolation
- Abandonment
- Pass/fail
- Scoring
- Replay
- History

## Acceptance Criteria

- Every season starts fresh.
- Datasets never combine.
- Challenge rules work.
- Previous history remains saved.
- Replay works.

STOP AFTER PHASE 11.

---

# PHASE 12 — HISTORICAL NEWS, BLIND MODE, AND TIME WARP

## Objective

Add realistic historical catalysts and flexible replay.

## Step 12.1 — News Models

Support:

- Event ID
- Dataset ID
- Timestamp
- Title
- Category
- Impact
- Forecast
- Previous
- Actual
- Reaction Note

## Step 12.2 — News Calendar

Show scheduled events.

## Step 12.3 — News Flags

Display:

Red = High Impact
Amber = Medium Impact

Allow tapping markers.

## Step 12.4 — Release Visibility

Before release:

- Show event name
- Show scheduled time
- Show historically available forecast
- Show historically available previous

After release:

- Reveal actual
- Reveal reaction note when its required price window has elapsed

## Step 12.5 — Blind Mode

Hide:

- Historical year
- Historical month
- Dataset identity
- Recognizable campaign names

Display relative coordinates.

Example:

W2 D3 14:30

## Step 12.6 — Time Warp

Implement:

- Skip Weekend
- Next London Session
- Next New York Session
- Next High-Impact News T-5m

## Step 12.7 — Challenge Restrictions

Challenge Mode:

Time Warp only when no open positions or pending orders.

Practice Mode:

Allow Time Warp with positions.

Process all intervening market and trading events.

## Step 12.8 — Tests

Test:

- News timestamps
- Hidden actual values
- Blind date masking
- Time Warp
- Position processing during warp
- Dataset boundaries

## Acceptance Criteria

- News works.
- Future results remain hidden.
- Blind Mode works.
- Time Warp is deterministic.

STOP AFTER PHASE 12.

---

# PHASE 13 — INDICATORS AND DRAWING TOOLS

## Objective

Provide advanced technical chart analysis.

## Step 13.1 — Indicators

Implement:

- SMA
- EMA
- Bollinger Bands
- RSI
- MACD

## Step 13.2 — Incremental Updates

Cache completed indicator values.

Update forming values incrementally.

Do not access future candles.

## Step 13.3 — Indicator Panes

RSI and MACD should appear in resizable lower panes.

Share horizontal viewport and crosshair.

## Step 13.4 — Tier 1 Drawings

Implement:

- Horizontal Line
- Trendline
- Ray
- Rectangle
- Fibonacci Retracement
- Long/Short Position Tool

## Step 13.5 — Drawing Coordinates

Store:

Timestamp
Price

Do not store only screen coordinates.

## Step 13.6 — Drawing Interaction

Support:

- Select
- Drag
- Resize
- Lock
- Hide
- Delete

## Step 13.7 — Magnifier

Display a magnifier while moving drawing handles.

## Step 13.8 — Magnet Snap

Support snapping to:

- Open
- High
- Low
- Close

## Step 13.9 — Undo/Redo

Implement undo and redo for drawing modifications.

## Step 13.10 — Tier 2 Drawings

Implement after Tier 1 is stable:

- Parallel Channel
- Vertical Line
- Fibonacci Extension
- Text
- Arrow
- Measure Tool

## Step 13.11 — Tests

Test:

- Indicator calculations
- No future leakage
- Drawing persistence
- Timeframe switching
- Hit testing
- Gesture interaction
- Undo/redo

## Acceptance Criteria

- Indicators calculate correctly.
- Drawings work.
- Drawings persist.
- Timeframe switching preserves logical coordinates.
- Chart remains responsive.

STOP AFTER PHASE 13.

---

# PHASE 14 — JOURNAL, ANALYTICS, BACKUP, AND FINAL POLISH

## Objective

Complete TradeQuest and validate the application.

## Step 14.1 — Trade Journal

Store:

- Entry Time
- Exit Time
- Direction
- Lot Size
- Entry Price
- Exit Price
- Initial SL
- Final SL
- TP
- Commission
- Slippage
- Realized PnL
- R-Multiple
- MFE
- MAE
- Holding Time
- Trading Session
- Nearby News
- Journal Tag
- Review Note

## Step 14.2 — Automatic Mistake Detection

Implement:

No Stop Loss

Excessive Risk (>2%)

Widened Stop in Drawdown

Revenge Trade

News Gamble

Use documented heuristic rules.

## Step 14.3 — Performance Analytics

Calculate:

- Win Rate
- Profit Factor
- Average R
- Maximum Drawdown
- Average Holding Time
- Best Session
- Worst Session
- News Performance

## Step 14.4 — Trade Replay

Allow reviewing completed trades.

Replay should show the price path and relevant order events.

Do not alter the original saved trade.

## Step 14.5 — Badges

Implement:

- First Week Survived
- Iron Risk Shield
- 10 Trades With Stops
- First Profitable Week
- Journal Habit

## Step 14.6 — Backup

Support local export.

Recommended:

- JSON for structured season history
- CSV for tabular trade records

Implement a separate validated restore format if full restore is supported.

## Step 14.7 — Notifications

Support:

- Order Filled
- Stop Loss Hit
- Take Profit Hit
- Margin Warning
- Daily Loss Lock
- Challenge Completed

Handle Android notification permissions.

## Step 14.8 — Visual Polish

Refine:

- Portrait layout
- Landscape layout
- Dark theme
- Light theme
- OLED theme
- Color-blind theme
- Loading states
- Error states
- Empty states

## Step 14.9 — Performance Profiling

Profile:

- Chart FPS
- Memory
- Database access
- Long replay
- App startup
- Timeframe switching
- Gesture latency

## Step 14.10 — Reliability Testing

Test:

- App restart
- Process death
- Device reboot
- Long background interval
- Time Warp
- Dataset switching
- Database recovery
- Import errors
- Missing historical data

## Step 14.11 — Final Acceptance Tests

Verify:

- Offline operation
- Dataset independence
- Deterministic simulation
- No future leakage
- Correct trading execution
- Correct challenge accounting
- Responsive chart
- Persistent journal
- Working backups
- Successful build

## Acceptance Criteria

- All required features are implemented.
- Critical tests pass.
- No known critical accounting or replay defects remain.
- Chart performance is measured.
- App works offline.
- Documentation is current.

STOP AFTER PHASE 14.

---

# PHASE 15 — DATASET IMPORT AND LIBRARY MANAGEMENT

## Objective

Give the app a user-facing way to bring a validated historical dataset in, and
to manage datasets afterwards.

This phase is deliberately LAST. It is the only phase that may not be started
until Phases 0-14 are complete and accepted, because it depends on:

- the market schema and the import transaction (Phase 2),
- the validated import pipeline (Phase 1),
- the trading calendar and dataset boundaries (Phase 3),
- the season engine, which is what makes deletion and overwrite dangerous
  (Phase 11).

Until this phase exists, no dataset can enter the app through the UI. The
Market Library is correctly empty, and every phase before this one is verified
against synthetic fixtures and repository-level tests, not against a real
imported file on a device.

## Step 15.1 — Import Entry Point

Provide an import action in two places:

1. Market Library: a toolbar action, and a primary action inside the empty
   state, so the empty state is actionable rather than a dead end.
2. Settings: a secondary entry, so import is reachable from the expected
   system location.

Both routes must call the same import flow. Do not duplicate logic.

Do not add import to any other screen.

## Step 15.2 — File Selection

Use the Storage Access Framework (`ACTION_OPEN_DOCUMENT`).

- Request no broad storage permission. Persisted read access must be taken
  only for the chosen document, and released afterwards.
- Accept a single file. Reject multiple selection.
- State the accepted formats in the picker filter and in the UI.
- Never write to the selected location. Import is read-only from the source.

The app remains fully offline. Do not add `INTERNET`.

## Step 15.3 — Import Configuration

The CSV column layout is not always self-describing, so before parsing:

- Read the header row and map columns by name.
- Require the source timezone. It has no default, and the UI must not invent
  one. `CsvImportConfig.sourceTimeZone` is required on purpose (Step 1.4).
- Show the detected columns and the selected timezone for confirmation before
  a large import begins.

## Step 15.4 — Import Progress and Cancellation

A full dataset is tens of thousands of candles.

- Parse, validate, and store off the main thread.
- Show determinate progress where the total is known, indeterminate otherwise.
- Allow cancellation, and leave no partial dataset behind. The store step is
  already a single transaction (Step 2.7); cancellation before it must roll
  back cleanly.
- Do not block navigation during import.

## Step 15.5 — Import Outcomes

Report each outcome distinctly and honestly. Do not collapse them into a
generic failure:

- Stored.
- Already present: identical content, nothing changed.
- Content mismatch: the same dataset id with different content. Explain that
  the stored dataset was kept, and why overwriting is refused.
- Validation failure: report the specific issues, with counts and the first
  offending rows. Do not offer to import invalid data.

## Step 15.6 — Dataset Management

- Delete a dataset. Refuse when a season references it, and say which season.
  This restriction could not be implemented before Phase 11, because the
  season engine is what makes it enforceable.
- Rename the display name only. The dataset id, content hash, and candle data
  are immutable.
- Show candle count, covered period, validation status, and storage size.

## Step 15.7 — Storage Reporting

The per-dataset byte count is an estimate, not a measurement. `dbstat.path` is
the B-tree path and never contains a row value, so a per-dataset figure cannot
be read directly. Label it as an estimate in the UI. Do not present it as
exact.

## Step 15.8 — Tests

Test:

- Column mapping, including a file whose columns are reordered.
- A missing required timezone is rejected.
- Cancellation leaves no dataset and no candles behind.
- Each import outcome, including content mismatch.
- Deletion refused while a season references the dataset.
- Deletion succeeds once no season references it.
- Rename does not alter the id or the hash.
- Re-importing the same file is idempotent.

## Step 15.9 — Device Verification

On a real device or emulator:

- Import the reference dataset end to end.
- Confirm the Market Library reports the correct period, candle count, and
  validation status.
- Start a season against it and confirm the chart renders.
- Confirm the app still declares no `INTERNET` permission and works in
  airplane mode.

## Acceptance Criteria

- A dataset can be imported from the UI without a developer tool.
- Invalid data is refused with a specific reason.
- An existing dataset is never silently overwritten.
- A dataset in use by a season cannot be deleted.
- The app remains fully offline.
- Tests pass.

STOP AFTER PHASE 15.

---

# 5. FINAL SYSTEM INTEGRATION CHECKLIST

Before declaring TradeQuest complete, verify the complete user journey.

## Journey A — New Challenge

1. Launch app.
2. Open Market Library.
3. Import a historical dataset, if none is present yet (Phase 15).
4. Select one historical dataset.
5. Enable or disable Blind Mode.
6. Start a fresh $10,000 challenge.
7. Open the chart.
8. Observe historical price progression.
9. Place a market order.
10. Attach SL/TP.
11. Watch PnL change.
12. Close trade.
13. Review journal.
14. Open challenge dashboard.

Expected:

All state is consistent.

## Journey B — Dataset Switching

1. Start Dataset A.
2. Place trades.
3. Select Dataset B.
4. Confirm abandonment.
5. Start Dataset B.

Expected:

- Dataset A history remains saved.
- Dataset B starts with $10,000.
- Dataset A positions do not appear.
- Dataset A drawings do not appear.
- Dataset A news does not appear.
- No market data is merged.

## Journey C — App Restart

1. Start a challenge.
2. Open positions.
3. Close application.
4. Wait.
5. Reopen application.

Expected:

- Clock recovers.
- Replay catches up.
- Trades process correctly.
- Risk rules apply.
- No duplicate fills.
- State persists.

## Journey D — Time Warp

1. Open Practice Mode.
2. Place a position.
3. Warp forward.

Expected:

- All intervening price movement is evaluated.
- SL/TP can trigger.
- Margin events can occur.
- PnL remains consistent.
- No trading consequences are skipped.

## Journey E — Blind Mode

1. Start blind challenge.
2. Inspect chart.
3. Inspect news calendar.
4. Switch timeframes.
5. Inspect journal.

Expected:

No original historical dates or unreleased results are exposed.

---

# 6. PERFORMANCE TARGETS

Targets must be measured.

Chart:

60 FPS target on selected reference devices.

Historical candles:

Approximately 90,000 per dataset.

Live price:

Approximately 1Hz visual updates.

Rendering:

Visible candles only.

Database:

Indexed, bounded queries.

Replay:

Background processing without blocking the main thread.

Timeframe switching:

Preferably under 200ms on reference hardware.

Memory:

Avoid large boxed candle collections and unnecessary copies.

Do not claim targets are achieved without profiling.

---

# 7. FUTURE ONLINE ARCHITECTURE

The current app must remain offline.

Do not implement online features during these phases.

However, preserve:

- Stable dataset IDs
- Dataset content hashes
- Dataset versioning
- Simulation engine versioning
- Challenge rules versioning
- Repository interfaces
- Exportable player data

Future possibilities:

- Downloadable datasets
- Cloud backups
- Account synchronization
- Shared challenges
- Online leaderboards

Online competitive results will require stronger verification than local database records.

Historical market data redistribution may require licensing.

---

# 8. FINAL AI AGENT WORKFLOW

For every phase:

1. Read AGENTS.md.
2. Read this development plan.
3. Inspect existing code.
4. State the phase objective.
5. Identify dependencies.
6. Implement the requested phase.
7. Add tests.
8. Run the build.
9. Run available tests.
10. Fix failures.
11. Update development progress.
12. Report results.
13. Stop and wait for approval.

Never automatically move to the next phase.

Never silently remove features.

Never fabricate historical data.

Never combine datasets.

Never claim unverified performance.

Never change the price storage scale. Prices are integer thousandths of a
USD (`PRICE_UNITS_PER_USD = 1000`) — decided, see §4.1 and
`docs/adr-0001-price-scale.md`. Do not convert to cents, do not divide by
100, and do not introduce a `*Cents` field. If you believe the scale is
wrong, stop and ask; do not change it silently.

Never express a stop, target, or trailing-stop distance in raw price units.
Use pips (`PRICE_UNITS_PER_PIP = 100`), per Step 1.5.

Never perform money arithmetic in `Int`. Use `Long` intermediates, per
Step 6.1.

---

# 9. REQUIRED PHASE COMPLETION REPORT

After each phase, respond using:

PHASE COMPLETION REPORT

Phase:
Status:

Implemented:
- ...

Files Created:
- ...

Files Modified:
- ...

Build Result:
- ...

Tests Executed:
- ...

Tests Passed:
- ...

Tests Failed:
- ...

Known Issues:
- ...

Unverified Requirements:
- ...

Next Recommended Phase:
- ...

---

# 10. FIRST INSTRUCTION TO THE CODING AGENT

Read AGENTS.md and TRADEQUEST_DEVELOPMENT_PLAN.md completely.

Treat these files as the authoritative TradeQuest project specification.

Inspect the current Android repository.

Start PHASE 0 ONLY.

Do not implement Phase 1 or later phases.

Create the Android project foundation, configure compatible dependencies,
establish the architecture, implement navigation and themes,
and configure testing.

Build the application.

Run available tests.

Fix any compilation errors.

Update docs/development-progress.md.

Report the completed work.

Stop and wait for explicit approval before proceeding.