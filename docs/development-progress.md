# Development progress

Authoritative record of what is implemented, what is verified and what is not.

## Phase 0 — Project foundation — COMPLETE

### Implemented

- Single-module Android application, application id `com.tradequest.app`.
- Gradle version catalog with resolved, published dependency versions.
- Kotlin and Jetpack Compose with Material 3.
- Package boundaries for every `core/*` layer and every feature area.
- `MainActivity` with edge-to-edge layout.
- Top-level navigation: Home, Market Library, Trading, Challenge, Journal,
  News, History, Settings.
- Adaptive navigation: bottom bar below 600dp, navigation rail at or above it.
- Themes: Dark, Light, True-black OLED and Color-blind.
- Centralised colour tokens, including chart tokens separate from Material roles.
- Manual dependency-injection composition root (`AppContainer`).
- Unit-test infrastructure with real assertions.

### Deliberate decisions

- **Manual DI instead of Hilt.** `AGENTS.md` section 4 requires avoiding
  unnecessary third-party dependencies. Revisit if the graph outgrows manual
  wiring.
- **`targetSdk` 36 / `compileSdk` 36.** The development plan does not specify a
  value. 36 is the highest SDK for which the whole dependency stack is
  compatible with AGP 8.x.
- **AGP 8.13.2, not AGP 9.x.** The newest AndroidX releases (core-ktx 1.19.1,
  compose 1.12.x, navigation 2.10.x) declare
  `minAndroidGradlePluginVersion=9.1.0` and `minCompileSdk=37` in their AAR
  metadata. AGP 9.0 also removes the `org.jetbrains.kotlin.android` plugin in
  favour of built-in Kotlin, which is a build-DSL change. Phase 0 pins the
  proven AGP 8.13.2 line and the newest dependency set that supports it.
- **Dependency versions verified against published AAR metadata**, not chosen by
  assumption. See the table below.
- **JUnit 4** rather than JUnit 5: it is the default for Android unit tests and
  needs no extra runner configuration.
- **JDK 21** in this build environment. The Debian image has no JDK 17 package;
  AGP accepts 17 or newer, and the source/target compatibility stays at 17.

### Verified dependency stack

Each version below was confirmed against the published `aar-metadata.properties`
in Google Maven. `minCompileSdk` / `minAGP` are the values the artifact itself
declares.

| Artifact | Version | minCompileSdk | minAGP |
| --- | --- | --- | --- |
| Android Gradle Plugin | 8.13.2 | — | — |
| Gradle | 8.14.3 | — | — |
| Kotlin | 2.2.21 | — | — |
| KSP | 2.2.21-2.0.5 | — | — |
| compose-bom | 2026.06.01 | — | — |
| compose ui | 1.11.4 | 35 | 8.6.0 |
| material3 | 1.4.0 | 34 | 1.0.0 |
| material-icons-core | 1.7.8 | 34 | 1.0.0 |
| androidx.core:core-ktx | 1.18.0 | 36 | 8.9.1 |
| activity-compose | 1.13.0 | 36 | 8.9.1 |
| lifecycle-runtime-ktx | 2.10.0 | 34 | 8.1.1 |
| lifecycle-runtime-compose | 2.10.0 | 35 | 8.6.0 |
| lifecycle-viewmodel-compose | 2.10.0 | 35 | 8.6.0 |
| navigation-compose | 2.9.8 | 35 | 8.6.0 |
| room-runtime | 2.8.5 | 34 | 8.1.1 |
| work-runtime-ktx | 2.11.2 | 35 | 8.6.0 |

Versions deliberately *not* taken: `core-ktx` 1.19.1, `compose-bom` 2026.08.00
and 2026.09.00, `navigation-compose` 2.10.x. Each requires AGP 9.1+ and
compileSdk 37.

### Verification performed

| Command | Result |
| --- | --- |
| `./gradlew clean assembleDebug` | BUILD SUCCESSFUL |
| `./gradlew testDebugUnitTest` | 17 tests, 0 failures, 0 errors |
| `./gradlew lintDebug` | 0 errors, 20 warnings |

All 20 lint warnings are version-currency notices
(`GradleDependency`, `AndroidGradlePluginVersion`, `OldTargetApi`) and follow
directly from the deliberate pins above.

APK produced: `app/build/outputs/apk/debug/app-debug.apk`.

### Unverified requirements

These are **not** verified and must not be reported as working:

- **Rendering, gestures and frame rate.** No emulator or device is available in
  the build environment. Any 60 FPS or allocation claim requires profiling on
  hardware.
- **Instrumentation tests.** None are configured in Phase 0.
- **Theme appearance.** Colour tokens are unit-tested for identity and
  distinctness; visual appearance is not verified.
- **KSP processing.** KSP is applied and version-aligned with Kotlin, but no
  processor runs until Room entities exist in Phase 2. The pairing is therefore
  configured but not yet exercised.
- **Adaptive navigation on a real device.** Breakpoint logic is untested on
  hardware.

### Not started

Phases 1 to 14. No historical data, simulation, trading, chart, challenge,
news or journal logic exists. Every feature screen renders an explicitly
labelled placeholder.

---

## Phase 1 — Historical data preparation — COMPLETE

### Implemented

- `core/market/PriceScale` — the authoritative price scale. Integer
  **thousandths of a USD** (`UNITS_PER_USD = 1000`), pip size
  (`UNITS_PER_PIP = 100`), and display formatting.
- `core/market/PriceParser` — decimal-text to price-unit conversion via
  `BigDecimal`. Never through `Double`. Rounds half-up away from zero when a
  source is finer than 3 decimals, and reports that it did.
- `core/market/HistoricalCandle` — `timestampUtcMs`, `openUnits`, `highUnits`,
  `lowUnits`, `closeUnits`, `tickVolume`. Derived flags for bullish, bearish,
  doji, range, body, and fabricated padding rows.
- `core/market/CsvImportConfig` — explicit delimiter, header layout, column
  mapping, timestamp format, decimal and grouping separators, source timezone,
  and positional order. Nothing is assumed.
- `core/market/CsvImporter` — the import pipeline. Quote-aware splitting, epoch
  and ISO-8601 and explicit-pattern timestamps, padding-row removal, and a
  line-numbered error on the first unparseable row.
- `core/market/CandleValidator` — the Step 1.3 rule set: the five OHLC
  invariants, positive prices, nonnegative volume, timestamp ordering,
  duplicates, non-numeric values, unexpected gaps, and suspicious spikes.
- `core/market/MarketClosureDetector` — classifies missing periods as weekend,
  holiday, or unexpected, and reports each.
- `core/market/DatasetManifest` / `DatasetManifestFactory` — every Step 1.7
  field, plus a reproducible SHA-256 over canonical candle content.
- `app/src/test/.../SyntheticFixtures` — labelled synthetic fixtures.

### Deliberate decisions

- **Prices are thousandths, not cents.** Owner-approved, recorded in
  `docs/adr-0001-price-scale.md`. Field names are `*Units`, never `*Cents`, so
  a thousandths field cannot be mistaken for a cents field.
- **`BigDecimal`, not `Double`, on the price path.** Parsing through `Double`
  would introduce binary error before the scaling multiply.
- **Exact conversion, no rounding in practice.** Thousandths (1e-3) to
  micro-dollars (1e-6) is a multiply by 1000, a power of ten. There is no
  precision-reduction policy because there is no reduction.
- **Padding rows are dropped, not stored.** Dukascopy emits flat, zero-volume
  rows for closed periods. They are fabricated, so storing them would
  manufacture price history that never traded. A flat row *with* volume is
  kept, because that is a real quiet minute.
- **Gaps are reported, never filled.** Absence of data is a fact. Filling it
  would fabricate history, which `AGENTS.md` forbids.
- **Closure detection is conservative.** Only Saturday and Sunday are excused
  as weekend closure, and only when completely empty. Friday is deliberately
  *not* excused despite its early close, because an empty Friday is unusual and
  should be surfaced. A fully empty weekday is always `UNEXPECTED`.
- **Holidays are not inferred.** Measured on real data, Christmas Day returned
  7.9 KB of ticks rather than nothing, so an empty holiday is not a safe
  assumption. The fixed-holiday list only *labels* a closure already observed.
- **Dataset ID is derived from the content hash**, so identical data always maps
  to the same dataset and replay stays reproducible.
- **The hash covers canonical content only.** UTC epoch milliseconds, prices as
  fixed 3-decimal strings, and volume. Column order, header layout, timestamp
  format, and locale do not affect it.

### Verified by test

`./gradlew testDebugUnitTest` — **79 tests, 0 failures, 0 errors.**

Phase 0 contributed 17 tests; Phase 1 adds 62.

Coverage includes: well-formed import; exact 3-decimal round trip; reordered
columns; semicolon delimiter with comma decimal separator; headerless
positional files; epoch-millisecond timestamps; unparseable, missing, and
timezone-less timestamps; the declared source timezone being applied rather
than UTC assumed; every invalid OHLC relationship; non-positive prices;
negative volume; out-of-order timestamps; duplicate timestamps; unexpected
intraday gaps; weekend closure; an empty weekday staying `UNEXPECTED`; an
empty Friday not being excused; spikes warning without invalidating; padding
row removal; flat rows with volume retained; a continuous UTC timeline across
a DST transition; doji import; and hash stability across repeated builds,
changed prices, changed volume, removed candles, and differing CSV layouts.

The four `adr0001 acceptance` tests assert that price units fit in `Int32` with
headroom, that a 0.001 USD step is representable, that the micro-dollar
conversion is exact, and that money arithmetic needs `Long` regardless of the
price scale.

### Bugs found and fixed during Phase 1

Three of my own initial test expectations were wrong and were corrected against
verified ground truth rather than adjusted until they passed:

- A PnL formula applied a spurious `/100`, giving a 10x error.
- A PnL assertion constant was off by 10x.
- Two hardcoded UTC epoch constants were wrong (one landed on Saturday, one on
  a Monday), and one timezone expectation was wrong by 48 seconds.

Two of these were wrong in the test, not the production code. They are recorded
because they show the tests were checked against an independent source.

### Known issues

- **Import reads the whole file into memory.** `text.lineSequence()` over a
  fully materialised `String`. A multi-year dataset would be large. Streaming
  import is not implemented.
- **Holiday list is two fixed dates.** Variable holidays such as Good Friday and
  US Thanksgiving are not covered.

### Unverified requirements

- **No real Dukascopy file has been imported through the Kotlin pipeline.** The
  Python tool's output format was matched, and fixtures use the same layout,
  but the real captured file has not been run through `CsvImporter`.
- **No Android device or emulator.** Import performance and memory use on real
  hardware are unmeasured.
- **`MarketClosureDetector` DST behaviour at the week boundary.** The 22:00 and
  21:00 UTC constants are approximate and shift with US and European daylight
  saving. They affect gap classification only, never data.
- **Large-dataset import.** Not profiled; the plan's ~90,000-candle target is
  unverified for the importer.

### Not started

Phases 2 to 14. No Room persistence, simulation, trading, chart, challenge,
news or journal logic exists.

---

## Phase 2 — Persistence layer — COMPLETE

### Implemented

- `core/database/market/` — `market.db`, five entities: `MarketDataset`,
  `Candle1m`, `NewsEvent`, `MarketClosure`, `DatasetValidation`.
- `core/database/player/` — `tradequest.db`, twelve entities: `Season`,
  `TradeOrder`, `Execution`, `Position`, `ClosedTrade`, `DailyStats`,
  `EquitySnapshot`, `Drawing`, `TradeJournal`, `Badge`, `ReplayCheckpoint`,
  `Settings`.
- `core/database/market/WithoutRowid.kt` — stores `Candle1m` WITHOUT ROWID.
- `core/market/MarketRepository.kt` — the read interface, dataset-scoped and
  bounded.
- `core/market/MarketReadModels.kt` — `DatasetSummary`, `NewsItem`,
  `DatasetClosure`, `DatasetValidationInfo`.
- `core/market/MarketFormat.kt` — date, span, count and size formatting.
- `core/database/market/MarketMappers.kt` — entity/domain conversion, plus
  `ImportRequest` and `ImportOutcome`.
- `core/database/market/RoomMarketRepository.kt` — read access and
  transactional import storage.
- `core/database/player/PlayerRepository.kt` — season lifecycle, the
  single-active-season rule, and dataset-existence enforcement.
- `feature/market/MarketLibraryScreen.kt` and `MarketLibraryViewModel.kt` — the
  Market Library, replacing the Phase 0 placeholder.
- `di/AppContainer.kt` — both databases and both repositories, lazily.

### Deliberate decisions

- **Two databases, not one.** `market.db` is read-heavy, effectively immutable
  and rebuildable from a CSV. `tradequest.db` is small, mutable and precious.
  Separate files let player progress survive a market-data wipe and vice versa,
  and keep a large candle table out of the same journal as player writes.
- **`Season.datasetId` is a logical reference, not a foreign key.**
  `docs/architecture.md` rule 6 forbids cross-database joins, so SQLite cannot
  enforce it. `PlayerRepository.startSeason` refuses a season whose dataset does
  not exist, and this is covered by test. The gap is real and is documented
  rather than hidden.
- **Money is `Long` micro-dollars; prices are `Int` thousandths; distances are
  `Int` pips.** Three units, each named for its unit, so none can be mistaken
  for another. `$10,000.00` is `10_000_000_000`.
- **`Candle1m` is stored WITHOUT ROWID.** Room does not do this itself, as the
  plan warned. With a rowid, the `(datasetId, timestampUtcMs)` primary key needs
  a separate index, so every candle is stored twice. Measured on 94,000
  realistic candles: 8,769,536 bytes with a rowid against 5,361,664 without, a
  **38.9% saving**, at no measurable query cost (0.884 ms against 0.933 ms for a
  1,000-candle range read). `WithoutRowid.apply` reads Room's own DDL back from
  `sqlite_master` and appends only the `WITHOUT ROWID` keyword, so no schema is
  duplicated; it runs in a transaction, is idempotent, and its failure is
  non-fatal.
- **Over-range reads throw; they are never truncated.** A silently truncated
  candle series would render a chart with a missing tail and no visible error.
  `candlesInRange` fetches one row past the budget and fails if it finds it.
- **Import is idempotent on content, and refuses to overwrite.** The dataset id
  derives from the content hash, so re-importing identical data is a no-op.
  An existing id with *different* content returns `ContentMismatch` rather than
  overwriting, because overwriting would corrupt any season replaying it.
- **Import is one transaction.** A duplicate minute mid-import rolls the whole
  import back, leaving no partial dataset. Covered by test.
- **Per-dataset storage size is labelled an estimate.** SQLite's `dbstat`
  reports bytes per table and index, never per row: its `path` column is the
  B-tree path. A single dataset's share of the shared candle table therefore
  cannot be read directly, so the summary divides total table bytes by total
  candle count and the UI says "~".
- **No delete action in the Market Library yet.** Deleting a dataset a season is
  replaying must be refused, and that rule needs the Phase 3 season engine. The
  repository method exists and is tested; the button does not, so nothing
  half-implemented is exposed.
- **`AppContainer` holds no placeholders.** Databases and repositories are real.
  They are lazy so app start does not open either file, and are never closed
  because they are process-scoped.

### Verified by test

`./gradlew testDebugUnitTest` — **156 tests, 0 failures, 0 errors, 0 skipped.**

Phase 1 ended at 79 tests; Phase 2 adds 77. New classes:
`MarketDatabaseTest` (34), `PlayerDatabaseTest` (23), `PlayerRepositoryTest` (8),
`MarketLibraryViewModelTest` (7), and the pre-existing `MarketDatabaseSmokeTest`
(5).

Tests run against the real generated Room code and the real SQLite engine
(3.44.3) on the JVM through Robolectric. **Nothing is mocked.**

Coverage includes: inserts and round trips; exact thousandths price round trips
including two values that are not representable as whole cents; the full `Int`
range; half-open range queries; exact-minute lookup; time bounds; duplicate
minute rejection; the same minute in two datasets being allowed; duplicate
dataset ids; re-import idempotency; conflicting-content refusal; cross-dataset
leak prevention for candles and news; foreign-key rejection of orphan rows;
`RESTRICT` blocking deletion of a dataset with children; transactional deletion
of a dataset and all children; data surviving close and reopen; Room
re-validating its schema after the WITHOUT ROWID rewrite; the single-active-
season rule; season history surviving; cascade deletion across all twelve player
tables without touching another season; the dataset-existence check; active
seasons refusing deletion; and the ViewModel's empty, loaded, selection and
selection-fallback states.

### Mutation-tested

Passing tests prove nothing unless they can fail, so four deliberate
regressions were injected and each was confirmed to fail the suite:

| Injected regression | Result |
| --- | --- |
| Disable the WITHOUT ROWID rewrite | `candle table is stored WITHOUT ROWID` FAILED |
| Remove the dataset scope from the candle range query | KSP build error, see below |
| Remove the dataset-existence check | `a season cannot be created on a dataset that does not exist` FAILED |
| Overwrite conflicting content instead of refusing | `same dataset id with different content is refused not overwritten` FAILED |

The second result was unexpected and is a genuine finding: **Room fails the
build when a query parameter is declared but unused** (`Unused parameter:
datasetId`). Since every candle and news query takes a mandatory `datasetId`,
this makes a cross-dataset read a compile error rather than a runtime bug. That
is a stronger guarantee than the test alone.

### Bugs found and fixed during Phase 2

- **A per-dataset `dbstat` query that could never match.** My first
  `candleStorageBytes` filtered on `path LIKE '%' || :datasetId || '%'`. Verified
  against SQLite that `dbstat.path` is the B-tree path (`/` for a table root,
  the index name for an index) and never contains row values, so the query would
  always have returned NULL. Replaced with a total-bytes query and an explicitly
  labelled estimate.
- **A ViewModel selection that would not have updated the UI.** The first
  `MarketLibraryViewModel` read `selectedId.value` inside a `map` over the
  dataset flow, so changing the selection alone would not re-emit. Fixed by
  `combine`-ing the two flows. The selection tests would have caught it.

### Unverified requirements

- **No Android device or emulator.** No rendering, gesture, frame-rate or
  on-device database performance claim is made. All database testing is on the
  JVM through Robolectric.
- **No migration test.** Both databases are at version 1 with no migrations.
  A destructive-migration or migration-test strategy is not yet chosen.
- **`dbstat` availability on a real device.** Verified on Robolectric's SQLite
  3.44.3 only. It is an optional SQLite module; the code treats its absence as
  "size unavailable" rather than failing, but the real-device path is
  unexercised.
- **Real import performance.** No real Dukascopy file has been stored through
  `RoomMarketRepository.storeImport`. The 94,000-candle storage measurement was
  made with Python's `sqlite3`, not through the Kotlin path.
- **`WithoutRowid` against a large non-empty table.** It only ever runs on the
  empty table created at first open, so the row copy is trivial in practice, but
  the non-empty path is untested.
- **Lint.** Not re-run for Phase 2 in this session.

### Not started

Phases 3 to 14. No simulation, order matching, trading, chart rendering,
challenge engine, news delivery or journal UI exists.

---

## Phase 3 — Simulation clock and trading calendar — COMPLETE

### Implemented

- `core/simulation/clock/TimeSource.kt` — `WallClock`, `MonotonicClock`,
  `TestTimeSource`, plus system-backed implementations. Wall-clock, monotonic
  and test time are three separate interfaces, as Step 3.1 requires.
- `core/simulation/clock/ClockAnchor.kt` — `ClockAnchor` (the persisted
  anchoring state), `ClockMode`, `PlaybackSpeed`, `DatasetBounds`,
  `ClockReading`, `ClockStopReason`, and `ClockMath`.
- `core/simulation/clock/SimulationClock.kt` — the authoritative clock:
  read, pause, resume, setSpeed, advanceBySimMs, seekTo, commit, attach,
  detach.
- `core/simulation/clock/DatasetBoundsResolver.kt` — clock bounds from the
  stored candle extent.
- `core/simulation/calendar/NewYorkTradingCalendar.kt` — 17:00 ET rollover,
  trading-day identification, weekly window, DST, and `SessionClassifier`.
- `core/simulation/calendar/DatasetTradingCalendar.kt` — combines the calendar
  rules with the closures observed in a dataset.
- `core/database/player/RoomClockStore.kt` — `ClockStore` implemented against
  `ReplayCheckpoint`.
- `PlayerDao` — added `deleteCheckpoint` and `countCheckpoints`.
- `di/AppContainer.kt` — `clockStore` and `simulationClock`.

### Design decisions

- **The clock has no ticker.** Historical time is a pure function of the anchor
  and the real clock (`ClockMath.simTimeAt`). A background ticker would drift
  when the main thread is busy and would lose time across a restart; a derived
  value cannot do either. `ClockReading.pendingSimMs` reports how far historical
  time has run ahead of what the pipeline has processed, which is what Step 3.3
  needs after the app was closed.
- **Historical time never moves backwards.** `ClockMath` treats a negative
  elapsed interval as zero, and `SimulationClock` holds a process-scoped
  high-water mark. This was not the first implementation: a test with a device
  clock jumping backwards failed, because the pure derivation returned to the
  anchor. The high-water mark was added in response.
- **The anchor is two timestamps, not an offset.** Step 3.5 forbids relying on a
  fixed seven-day offset. Storing both the historical and the real anchor means
  any closed interval is measured rather than assumed, and a device-clock
  correction moves both readings together.
- **Speed changes re-anchor.** Switching from 1x to 60x after ten real minutes
  must not retroactively credit ten hours. `ClockAnchor.withSpeed` re-anchors
  first, and there is a test for exactly that.
- **Challenge Mode is locked to 1x.** Enforced in `SimulationClock`, including
  on restore, so a tampered or stale stored speed cannot accelerate a challenge.
- **Sessions are defined in their own timezones.** The US and UK change clocks
  on different dates, so for roughly two weeks each spring and autumn the
  London/New York overlap is five hours rather than four. A fixed-offset table
  is wrong for those weeks; the tests pin the mismatch week specifically.
- **17:00 was chosen as the rollover partly because it is DST-safe.** US
  transitions happen at 02:00 local, so a 17:00 boundary always exists and is
  never ambiguous. A midnight boundary would be both on transition days.
- **Rules and observed closures are kept separate.** `NewYorkTradingCalendar`
  knows the rules and cannot know holidays; `MarketClosureDetector` recorded
  what was actually absent at import time. `DatasetTradingCalendar` joins them
  rather than letting either mask the other.
- **The clock mode is not the season mode.** `ClockMode` governs how time
  advances; `SeasonMode` governs which rules apply. They are separate enums
  mapped in one place, so a future divergence cannot corrupt a restored clock.
- **No migration.** `ReplayCheckpoint` already carried every field Step 3.5
  needs, so the schema is unchanged and both databases stay at version 1.

### Bugs found by tests

- **A device clock moving backwards rewound the simulation.** The first
  implementation derived historical time purely from the anchor, so a negative
  interval returned the anchor and the simulation jumped back. Caught by
  `a device clock jumping backwards does not rewind the simulation`. Fixed with
  the high-water mark described above.
- **Two of this phase's own test expectations were wrong, not the code.** The
  spring-forward and fall-back trading days are 23 and 25 hours; the first draft
  asserted this of the wrong dates. The US transition falls inside the trading
  day *labelled* with the transition date, because that day runs from 17:00 the
  previous day. Recomputed independently from the IANA database and corrected
  the tests. Recorded here because it is exactly the kind of off-by-one-day
  error the calendar exists to prevent.

### Verification

- `tests=253 failures=0 errors=0` (97 new in this phase; 156 before).
- `:app:assembleDebug` succeeds.
- `:app:lintDebug` reports 0 errors and 20 warnings, all pre-existing
  (dependency-version notices and a launcher-icon hint). No warning is raised
  against any file added in this phase.
- The merged manifest still declares no `android.permission.INTERNET`.

### Unverified requirements

- **No Android device or emulator.** No claim is made about clock behaviour
  under real process death, doze mode, or a device reboot. All testing is on the
  JVM through Robolectric and plain JUnit.
- **Process death is modelled, not experienced.** `SimulationClockTest` proves a
  new `SimulationClock` over a persisted anchor recovers elapsed time. It does
  not prove Android actually preserves the row before killing the process.
- **No holiday calendar is asserted.** `DatasetTradingCalendar` reads closures
  that a dataset stored; no dataset with holiday closures has been exercised end
  to end, because no real dataset has been imported through the Kotlin path yet.
- **Time Warp is not implemented.** `advanceBySimMs` and `nextMarketOpenUtcMs`
  are the primitives Phase 12 will build on; the warp commands themselves are
  not in this phase.
- **Real-time drift under a long run.** Nothing measures whether a 60x practice
  season holds its rate over hours of real use.

### Not started

Phases 6 to 15. There is no margin or stop-out enforcement, chart rendering,
challenge engine, news delivery or journal.

---

## Phase 4 — Deterministic market path engine — COMPLETE

Delivered `core/simulation/path/`: one intraminute price path shared by the
chart and the trading engine, as product rule 2.4 requires.

### What was built

- **Path shape (Step 4.1).** Bullish is open → low → high → close;
  bearish is open → high → low → close; doji takes the bullish shape
  as a documented tie-break. `WaypointTiming` is the only place that decides
  order, so a renderer cannot quietly disagree with execution.
- **Timing (Step 4.2).** Waypoints at 0, 15,000, 30,000 and 60,000 ms.
- **Interpolation (Step 4.3).** Integer thousandths throughout. One rounding
  rule (half away from zero), applied to the exact rational product, in one
  function. The chart and execution call the same function, so they agree by
  construction rather than by two implementations matching.
- **Forming candle (Step 4.4).** `formingCandleAt` reports only traversed
  extrema. Price is linear between waypoints, so the extreme over a traversed
  prefix is at a waypoint or at the interpolated point; the scan is therefore
  exact rather than a sample. There is deliberately no volume field, because
  `tickVolume` is a whole-minute total and would leak the future.
- **Crossing (Step 4.5).** `firstTouchElapsedMs` solves
  `t* = t0 + (t1 - t0) * (T - p0) / (p1 - p0)` in integers and rounds up to the
  first whole millisecond reached. Not a sampled search: a level touched and
  left between two 1 Hz ticks is still found. `firstTouchFrom` respects a start
  offset, so an order placed mid-minute is not filled by a crossing that
  preceded it.
- **Gaps (Step 4.6).** The engine never interpolates across candles. Each break
  is an explicit `MarketGap` with the previous close, the next open and a
  reason, and `MarketPathWindow.priceAt` returns **null** inside it. A level
  "reached" by jumping a weekend is not a fill.
- **Identity (Step 4.7).** A path is a pure function of dataset id, candle
  timestamp, OHLC and `pathModelVersion`. The fingerprint is FNV-1a, chosen so
  it does not depend on `hashCode()`, which is easy to break by reordering
  fields.

### Decisions worth recording

- **A gap is a value, not an absence.** Modelling gaps only as "missing rows"
  would leave the decision of how to draw or simulate across them to whoever
  consumed the data. Making the break explicit means the default answer is
  "there is no price here", which is the only defensible one.
- **`MarketPathWindow` returns null rather than throwing inside a gap.** Time
  legitimately passes through a closure, so a position query during a weekend is
  a normal question with a normal answer, not an error.
- **Candle order is validated, not repaired.** Out-of-order or duplicate
  timestamps are rejected. A chart that silently sorted them would render a
  picture that does not match the dataset.
- **The fingerprint is pinned in a test.** If the algorithm or the model changes
  without a version bump, that test fails, which is the point.

### Bugs found by tests

- **Five of this phase's own test expectations were wrong, not the code.** All
  five concerned threshold crossings, and all five were recomputed independently
  in Python before the code was touched. The most instructive: on a bullish
  candle the rally from the low to the high passes back *through* the close
  level, so the close is first touched at 26,250 ms, not at the end of the
  minute. The final leg of a bearish candle rises to meet its close, so a level
  below the close is crossed on the descent, not at the close. The code was
  correct in every case; the expectations were not.
- **A window lookup bug was introduced and caught by its own test.** The first
  `indexOfCandleContaining` matched only exact candle starts, so a mid-minute
  time resolved to no candle at all. Found by
  `a mid-minute time resolves to the containing candle` and fixed by falling
  back to the preceding candle while the time is still inside its minute.

### Verification

- `tests=332 failures=0 errors=0` (79 new in this phase; 253 before).
- `:app:assembleDebug` succeeds.
- `:app:lintDebug` reports 0 errors and 20 warnings, all pre-existing. No
  warning is raised against any file added in this phase.
- The merged manifest still declares no `android.permission.INTERNET`.

### Unverified requirements

- **Waypoint timing is an assumption, not a measurement.** Even quarter-minute
  spacing means the high of a bullish minute is always at 30,000 ms and the low
  at 15,000 ms. Which of a stop and a target is hit first is therefore a
  property of this model. It cannot be validated against the stored data,
  because 1-minute OHLC contains no intraminute ground truth. See
  `docs/adr-0002-intraminute-path.md`.
- **No rendering has been exercised.** Nothing in this phase draws anything, so
  no claim is made about how the path looks on screen. That is Phase 8.
- **No real dataset has been replayed.** Every test uses synthetic candles
  built for the case under test. A real imported dataset has not been walked
  minute by minute.
- **Performance is unmeasured.** `priceAt` is O(waypoints) and window lookup is
  O(log n), which is adequate by inspection, but no frame-rate or throughput
  figure has been taken on a device.

---

## Phase 5 — Trading execution engine — COMPLETE

Delivered `core/trading/`: deterministic order execution against the Phase 4
path, plus the money primitives in `core/accounting/`.

### Implemented

- **Domain models (5.1).** `TradeOrder`, `Execution`, `Position`, `ClosedTrade`,
  `AccountState`, `TradingState`, `TradingCommand`, `CommandResult`,
  `TradingEvent`. No Room annotations; Room entities stay persistence shapes.
- **Order types (5.2).** Market buy/sell, buy/sell limit, buy/sell stop.
  Market orders fill at once at the executable price for the side; pending
  orders fill when their level is crossed.
- **Position features (5.3).** Stop loss, take profit, trailing stop, breakeven,
  partial close, full close, order modification, pending cancellation.
- **Contract rules (5.4).** XAUUSD: 1 lot = 100 oz, minimum and increment
  0.01 lots, leverage 1:100.
- **Bid/ask (5.5).** Historical price is the bid; ask = bid + spread. A long
  enters at the ask and exits at the bid; a short is the reverse.
- **Spread model (5.6).** 300 units normal, 600 at rollover, 800 during news,
  with the larger taken when the windows overlap.
- **Commission (5.7).** $7 per lot round trip, charged per side, apportioned on
  a partial close.
- **Slippage (5.8).** Zero normally; 200–1,000 units adverse during news from a
  seeded per-order hash. Zero for limit orders and for any gap fill.
- **Event ordering (5.9).** Forward, segment-by-segment walk. Gaps, then pending
  orders, then stops and targets, then protective rules.
- **Same-candle execution (5.10).** An order can fill and its position can be
  stopped out inside one minute, in that order. No look-back fills: a command
  cannot be inserted before the processed time, and an order is never filled by
  a crossing that preceded its placement.
- **Gaps (5.11).** `GapExecution` — a stop-like trigger fills at the first price
  available after the gap; a limit-like trigger fills at its own level.
- **Partial close (5.12).** 50% supported where the remainder satisfies the lot
  step; an impossible split is refused explicitly rather than rounded.
- **Trailing stops (5.13).** Activation distance, distance, evaluated once per
  path segment, ratchet-only, applied immediately at the segment's end.
- **Tests (5.14).** 126 tests across four suites.

### Files created

- `core/accounting/MoneyMath.kt`
- `core/trading/TradingTypes.kt`, `ContractSpec.kt`, `SpreadModel.kt`,
  `SpreadTimeline.kt`, `SlippageModel.kt`, `OrderTrigger.kt`, `GapExecution.kt`,
  `Modification.kt`, `TradingEngine.kt`, `README.md`
- `core/trading` tests: `TradeFixtures.kt`, `PricingRulesTest.kt`,
  `OrderExecutionTest.kt`, `PositionFeaturesTest.kt`, `GapExecutionTest.kt`

### Files modified

- `core/simulation/path/MarketPathWindow.kt` — added `bidRangeIn`.
- `core/simulation/path/IntraminutePath.kt` — added `firstTouchInRange`.

### Decisions worth recording

- **The replay walk is forward and per-segment, not two-pass.** A fill and a
  stop-out can happen in the same minute, so all fills cannot be resolved before
  the stop checks. The engine takes the next segment, resolves what happens in
  it, and moves on.
- **Trailing stops are ratcheted after the exit check, not before.** Price is
  monotonic between waypoints, so ratcheting first would move a long's stop up
  to just under the segment's own end price and then immediately close the
  position against that segment. Applying the trail afterwards means a new level
  first applies from the next segment.
- **Stops and targets are stored on the exit side.** A short exits at the ask,
  so its stop is one spread higher in bid terms. Storing the raw level would
  fire a short's stop one spread early and its target one spread late — the
  latter in the user's favour.
- **Gaps split triggers by which side the user is protected on.** A stop-like
  trigger takes the post-gap price because the market moved against the user; a
  limit-like trigger takes its own level because a resting order there was
  genuinely crossed. This is the only split that is fair in both directions.
- **A gap fill never slips.** The gap price already contains the adverse move.
- **Commission is charged on entry and again on exit, never twice for one
  fill.** The entry charge leaves the balance when the position opens; the close
  adds gross PnL minus the exit charge only. The closed trade still reports the
  full round-trip cost for the journal.

### Bugs found by tests

- **The balance double-charged entry commission.** `closePartially` added the
  trade's net PnL to the balance, but that net already subtracted the apportioned
  entry commission, which had been taken when the position opened. Caught by
  `a round trip charges seven dollars per lot in total`, which saw a $35
  discrepancy on 0.10 lots.
- **A short's trailing extreme used the wrong sign.** The best exit-side price
  for a short is the lowest ask, which is the lowest bid *plus* the spread; the
  code subtracted it, putting the trail below the market. Caught by
  `a short trailing stop uses the ask side`.
- **`ModifyPosition` reset a trailed stop.** A `Keep` on the stop field
  recomputed the level from the original distance, silently undoing every
  ratchet. Caught by `removing a trailing stop leaves the current stop level in
  place`. The `Keep` branch now leaves `activeStopUnits` untouched.
- **`advanceTo` skipped a partial trailing range.** A segment was only processed
  when a waypoint boundary fell strictly inside the advance, so an advance
  smaller than the waypoint spacing evaluated nothing. Caught by
  `advancing in smaller steps gives the same result as one large step`.
- **The rollover spread window looked the wrong way.** `isInRolloverWindow`
  compared against the *previous* trading day's start, so every instant in the
  ten minutes *before* a rollover — the common case — was missed. Caught by
  `ten minutes before rollover is still inside the window`. `SpreadTimeline` had
  the same gap and was corrected with it.
- **Five test expectations were wrong, not the code**, and were recomputed by
  hand before anything was changed. The most instructive: a buy stop placed
  *below* the market is already satisfied, so it fills at once rather than on a
  later dip — a test that looked like it proved same-minute ordering actually
  proved nothing until the stop was moved above the market.

### Verification

- `tests=458 failures=0 errors=0` (126 new in this phase; 332 before).
- `:app:assembleDebug` succeeds.
- `:app:lintDebug` reports 0 errors.
- The merged manifest still declares no `android.permission.INTERNET`.

### Unverified requirements

- **Waypoint timing still decides which of a stop and a target is hit first.**
  This phase inherits Phase 4's uniform quarter-minute spacing, so a same-candle
  stop-versus-target race is a property of the model. It cannot be validated
  against 1-minute OHLC.
- **No swap or rollover financing is charged.** The plan gives no rate.
  `TradingContext.swapMicrosPerLotPerDay` exists and defaults to zero, so the
  omission is explicit and visible rather than buried.
- **No real economic calendar backs the news spread.** Release instants are
  supplied to the engine; none are imported. That is Phase 10.
- **Slippage is modelled, not measured.** The 200–1,000 unit range is the plan's
  figure. It is deterministic, but not calibrated against any real fill data.
- **No margin, equity enforcement or stop-out.** `equityMicros` is computed and
  never acted on. Steps 6.5–6.9 own that, so a replay can currently carry a
  position past the point a real account would be liquidated.
- **No real dataset has been replayed end to end.** Every test builds synthetic
  candles for the case under test.
- **Performance is unmeasured.** The walk is bounded by waypoint count per
  advance, which is adequate by inspection, but no throughput figure exists.

---

## Phase 7 — Replay processor and reliable persistence — COMPLETE

Delivered `core/replay/` plus the persistence and platform pieces that make a
catch-up survive a restart, and the WorkManager entry point that runs one in the
background.

### Implemented

- **Replay processor (7.1-7.2).** `ReplayProcessor` drives the Phase 4 path and
  the Phase 5/6 engine over the interval a season still owes, from the persisted
  cursor to a target instant. Season-scoped, so the dataset, contract, commission
  schedule and risk policy are fixed once at construction and a run cannot replay
  one season's interval against another's data.
- **Checkpoints (7.3).** `ReplayState` persists the whole resumable state —
  cursor, orders, positions, executions, closed trades, balance and challenge
  state — as one row keyed by season. `ReplayStateCodec` owns the on-disk format
  as its own set of DTOs, separate from the domain models, so a domain rename
  cannot silently change what an existing row means.
- **Atomic transactions (7.4).** `ReplayStore.commit` is a single call, and the
  Room implementation wraps it in a transaction. Exposing one call rather than
  two makes "state advanced but cursor not" unrepresentable.
- **Idempotency (7.5).** The engine refuses to re-process an instant it has
  already walked, so a batch that was already committed is a no-op rather than a
  duplicate. Notifications are held until after the commit that covers them.
- **Batching (7.6).** One-minute batches by default, with progress emitted per
  batch through `processAsFlow` so the UI can update during a catch-up.
- **App resume (7.7).** `ReplayCoordinator` restores the state, reads the clock's
  target, catches up, and commits the clock — in that order.
- **WorkManager (7.8).** `ReplayCatchUpWorker` runs a best-effort 15-minute
  periodic catch-up. The interval is a request, not a promise.
- **Notifications (7.9).** `ReplayNotifications` maps engine events to text and
  `AndroidReplayNotifier` posts them, gated on `POST_NOTIFICATIONS`.
- **Tests (7.10).** 49 new tests across five suites.

### Files created

- `core/replay/ReplayProcessor.kt` (7.1-7.2, 7.6-7.7)
- `core/replay/ReplayStateCodec.kt` (7.3) — the persisted shape
- `core/replay/ReplayStore.kt` (7.3-7.4) — the port, plus `InMemoryReplayStore`
- `core/replay/ReplayCoordinator.kt` (7.7)
- `core/replay/ReplayNotifications.kt` (7.9)
- `core/database/player/RoomReplayStore.kt` (7.3-7.4) — the Room adapter
- `core/database/player/PlayerMigrations.kt` (7.3) — `MIGRATION_1_2`
- `platform/AndroidReplayNotifier.kt` (7.9)
- `platform/ReplayCatchUpWorker.kt` (7.8)
- `res/drawable/ic_notification.xml` (7.9)
- tests: `ReplayFixtures.kt`, `ReplayProcessorTest.kt`, `ReplayCoordinatorTest.kt`,
  `ReplayNotificationsTest.kt`, `PlayerMigrationTest.kt`, `RoomReplayStoreTest.kt`

### Files modified

- `core/database/player/PlayerDatabase.kt` — `ReplayState` entity, version 1 to 2,
  migrations wired.
- `core/database/player/PlayerEntities.kt` — `ReplayStateEntity`.
- `core/database/player/PlayerDao.kt` — replay state upsert/read/count/delete.
- `core/trading/TradingEngine.kt` — exposes the state the processor checkpoints.
- `di/AppContainer.kt`, `TradeQuestApplication.kt` — wiring and worker enqueue.
- `app/build.gradle.kts`, `gradle/libs.versions.toml` — `room-testing`, and the
  schema directory added to the debug assets so `MigrationTestHelper` can read it.
- `AndroidManifest.xml`, `strings.xml` — notification permission and strings.
- `app/schemas/.../2.json` — the exported schema the migration is verified against.

### Decisions worth recording

- **The coordinator commits the clock after the replay, never before.** Committing
  first would tell the clock the pipeline had processed time it had not, and the
  next resume would skip that interval. `ReplayCoordinatorTest` pins the order by
  failing the replay mid-way and asserting the clock still owes time.
- **The clock is committed to what was processed, not to what was asked for.** A
  dataset that ends before the target stops the replay early; committing the
  target would mark the shortfall as done and it would never be replayed.
- **A missing checkpoint is not the same as an empty one.** Null means "never
  processed" and seeds a fresh state at the dataset's first minute; a stored
  state at the first minute means "processed nothing yet" and resumes there.
- **The processor tests run against an in-memory store, and the Room store has
  its own suite.** Driving the batch-boundary tests through SQLite would make
  them test SQLite's timing; the one test that ties the two together replays the
  same candles both ways and asserts the results match.
- **`ReplayState` is a new table, so the migration is purely additive.** A season
  created before this phase simply has no row, which is exactly "nothing
  processed yet". No existing data is read or rewritten.
- **The DDL is hand-written and verified against the exported schema.** Room
  refuses to open a database whose schema does not match the entity, so a drift
  between the two is caught at open time rather than by a silent data loss.
- **Trailing stops, day rollovers and position openings do not notify.** A
  trailing stop would otherwise notify on every segment, and a position opening
  is already covered by its fill.

### Bugs found by tests

- **`WorkManager.isInitialized()` was the wrong guard.** It reports false until
  WorkManager is first used in a process, so a real app would have skipped the
  worker. Replaced with a try/catch around the enqueue, which is the actual
  failure the guard was meant to catch.
- **The processor's dataset-end clamp used the wrong bound.** `timeBounds`
  returns the last candle's *start*, not an exclusive end, so clamping to it
  dropped the final minute. The window already carries the true exclusive end,
  and the clamp now uses that.
- **The migration test failed for a reason unrelated to the migration.** Room's
  `MigrationTestHelper` reads the exported schema through the *target context's*
  assets, and the schema directory had been added to the `test` source set, which
  unit tests do not use. It is now on `debug`, and scoped there so the schemas are
  not shipped in a release build.
- **The cascade assertion failed against a raw helper connection.** SQLite
  disables foreign keys by default and Room enables them on its own connections;
  the raw connection the helper hands back has not. The test now sets the pragma
  explicitly, which makes it assert the declared cascade rather than SQLite's
  default.
- **Lint could not see the notification permission check.** It does not follow
  the guard into a helper method, so it flagged a check that is present and is the
  first thing the method does. Suppressed with a comment recording why, rather
  than restructured to satisfy the analyser.
- **`resumeAsFlow` committed the clock to the target rather than to what was
  processed.** The one-shot `resume` clamped correctly, but the flow path had
  its own copy of the commit and passed `reading.simUtcMs`. A catch-up the
  dataset ended early would therefore mark the unprocessed remainder as done and
  it would never be replayed — the exact failure the one-shot path was written to
  avoid, duplicated in a second entry point. Caught by
  `the flow form clamps the clock too when the dataset runs out`. The flow now
  tracks the furthest committed batch and commits that.

### Verification

- `tests=563 failures=0 errors=0` (49 new in this phase; 514 before).
- `:app:assembleDebug` succeeds.
- `:app:lintDebug` reports 0 errors.
- The merged manifest still declares no `android.permission.INTERNET`.

### Unverified requirements

- **The 15-minute catch-up interval is a request.** WorkManager batches and defers
  periodic work, so a real device may run it less often. Step 7.8 says not to rely
  on exact scheduling, and nothing here does — but no on-device timing has been
  measured.
- **No worker has run on a device.** `ReplayCatchUpWorker` is covered by the
  guard logic and the coordinator it calls, not by a WorkManager integration test.
- **Notification delivery has not been seen on a device.** The mapping and the
  permission gate are tested; the posted notification is not.
- **Notification body text is hardcoded English.** Only the channel name and
  description are string resources. The plan does not specify localization, and
  the notification text is assembled from engine values, so moving it to
  resources would mean passing a `Context` into `ReplayNotifications` and losing
  the pure-Kotlin testability it currently has. Flagged rather than silently
  accepted: the app is not localizable as it stands.
- **No real dataset has been replayed end to end.** Every test builds synthetic
  candles.
- **Nothing consumes `ReplayProgress` yet.** The flow emits per-batch progress,
  but no screen observes it; Phase 8 onwards own the UI that will.
- **A multi-day catch-up is tested only across a synthetic gap.** The plan's
  "multi-day catch-up" is exercised as a several-hour gap, not a real weekend of
  market closure.
