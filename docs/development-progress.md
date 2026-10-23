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

## Phase 6 — Accounting, margin, risk and position sizing — COMPLETE

Delivered `core/accounting/` and `core/risk/`, plus the margin arithmetic in
`core/trading/`, and wired risk enforcement into the Phase 5 engine.

> **Backfilled 2026-10-22.** Phase 6 shipped in `de40431` with no progress-doc
> section and no completion report. It was the only phase whose commit did not
> touch this file, so Phase 7 was written against Phase 5 and the hole went
> unnoticed. The content below is reconstructed from the commit, the current
> code, and the Phase 6 hard rules in `AGENTS.md`; the verification figures are
> re-run rather than quoted from memory.

### Implemented

- **Money representation (6.1).** Micro-dollars in a `Long`
  (`MoneyScale.MICROS_PER_USD`). Price thousandths to micro-dollars is a
  multiply by 1000, and both are powers of ten, so the conversion is exact with
  no rounding and no remainder. `CheckedMath.add`/`subtract`/`multiply`/
  `multiplyDivide` wrap each operation and raise on overflow, carrying the
  operation name so a failure says *what* overflowed. All money multiplication
  widens to `Long` first: the plan's Int32 ceiling is reached after roughly a
  21 USD move at 1 lot, and gold moves 20–40 USD a day.
- **Lot representation (6.2).** Integer hundredths of a lot; 0.20 lots is
  `20` lot units.
- **PnL (6.3).** `AccountMath.realizedPnlMicros` accounts for side, entry price,
  exit price and lot size. Spread, slippage and commission reach it through the
  executable prices and commission figures the engine supplies, not by
  re-deriving them.
- **Equity (6.4).** `equityMicros = balanceMicros + unrealizedPnlMicros`. The
  entry commission leaves the balance when the position opens; the close adds
  gross PnL minus the exit commission only. The round trip is charged once.
- **Margin (6.5).** `MarginMath.marginMicrosFor` — gold price x 100 x lots / 100,
  using the executable price for the side.
- **Margin level (6.6).** `MarginMath.marginLevelPercent` = equity / used margin
  x 100, returning `null` when nothing is open.
- **Warning (6.7).** At or below 100%.
- **Stop-out (6.8).** `MarginMath.stopOutOrder` identifies the largest unrealized
  dollar loser; the engine closes it, re-evaluates, and closes again until the
  level is restored or the segment guard is hit.
- **Daily loss (6.9).** Resets at 17:00 New York. Reference is equity at the
  rollover; floor is `dayStartEquity x 0.97`. Breaching it flattens positions,
  cancels pending orders, locks new trading and records one strike.
- **Overall loss (6.10).** Starting balance $10,000, static floor $9,000.
  Reaching the floor fails the challenge.
- **Position sizing (6.11).** `PositionSizing.size` accounts for stop distance,
  spread, commission, entry side, a planned slippage allowance, minimum lot, lot
  step and available margin. It rounds the size **down**.
- **Tests (6.12).** 56 tests across four suites plus `RiskFixtures`.

### Files created

- `core/accounting/AccountMath.kt`
- `core/trading/MarginMath.kt`, `core/trading/RiskPort.kt`
- `core/risk/RiskRules.kt`, `PositionSizing.kt`, `PropFirmRiskPolicy.kt`
- `core/accounting/AccountMathTest.kt`
- `core/risk/RiskRulesTest.kt`, `PositionSizingTest.kt`, `RiskIntegrationTest.kt`,
  `RiskFixtures.kt`

### Files modified

- `core/trading/TradingEngine.kt` — `evaluateRisk` per segment, the stop-out
  re-evaluation loop, and the daily-floor and challenge-failure paths.
- `core/trading/TradingTypes.kt` — the risk verdict and risk snapshot types.
- `AGENTS.md` — the Phase 6 hard rules.

### Decisions worth recording

- **The engine cannot import `core.risk`.** `core.risk` depends on
  `core.trading`, so the engine calls risk through the `RiskPolicy` port and the
  implementation stays in `core.risk`. Position-level arithmetic both sides need
  lives in `core.trading/MarginMath.kt`: arithmetic in `trading`, thresholds in
  `risk`. An engine import of `core.risk` would close the cycle.
- **Risk runs last in the segment, and the engine re-asks after each
  liquidation.** Running it last means it sees the balance the segment actually
  produced. Step 6.8's "repeat if necessary" falls out of re-evaluation rather
  than a loop that closes everything at once.
- **`RiskSnapshot.atSimUtcMs` is the segment end, not the processed time.** The
  policy derives the trading day from it, so passing the segment start would
  roll the day one segment late.
- **A locked day blocks exposure, not exits.** `TRADING_DAY_LOCKED` applies to
  `PlaceOrder` only; closes, cancels and modifications still go through, because
  a loss limit that trapped the user in the position would be worse than no
  limit. A failed challenge blocks everything.
- **Zero used margin means a null margin level**, not zero and not infinity.
  Zero would trip the warning and the stop-out with no position open; infinity
  would need a `Long` sentinel.
- **The daily floor strikes once per day.** Strikes accumulate across days; the
  day lock clears at the 17:00 rollover, the strike count does not. Without
  this, one bad day would exhaust the three-strike allowance by itself.
- **The static floor is checked before the daily floor.** At $9,000 the
  challenge has failed outright, which is terminal; testing the daily floor
  first would report a mere day lock for an account that had actually failed.
  Failure is also the one state a rollover does not clear.
- **Position sizing rounds down and refuses rather than rounds up.**
  `PositionSizeResult.TooSmall` is returned when the budget cannot cover the
  minimum size. Rounding up would exceed the stated risk, which Step 6.11
  forbids. `roundedDown` is true when the division left a remainder *or* the lot
  step cut further, because testing the integer division against the stepped
  value alone can never detect the remainder.
- **Risk tests widen the limits they are not testing.** With the real 3% daily
  limit, a position large enough to approach a stop-out breaches the daily floor
  first and is flattened before the stop-out is visible, so tests targeting one
  rule pass a `RiskLimits` widening the others. The plan's figures are the
  defaults, not the only values.

### Verification

- At commit `de40431`: 514 test methods, 56 new over Phase 5's 458.
- Re-run 2026-10-22 on the current tree: `tests=899 failures=0 errors=0`, of
  which the four Phase 6 suites contribute 56 and all pass.
- `:app:assembleDebug` succeeds; `:app:lintDebug` reports 0 errors.
- The merged manifest still declares no `android.permission.INTERNET`.

### Unverified requirements

- **No swap or rollover financing is charged.** The plan gives no rate, and
  `TradingContext.swapMicrosPerLotPerDay` defaults to zero, so the omission is
  explicit rather than buried.
- **No real economic calendar backs the news spread.** Release instants are
  supplied to the engine; none are imported. That is Phase 10.
- **Slippage is modelled, not measured.** The 200–1,000 unit range is the
  plan's figure, deterministic but not calibrated against real fills.
- **Every risk test builds synthetic candles.** No real dataset has been
  replayed through the risk path end to end.
- **The stop-out repeat is bounded by a segment guard** (`MAX_SEGMENT_STEPS`)
  rather than proved to terminate. In a pathological segment the guard, not the
  margin level, would end the loop.
- **No margin, equity or stop-out figure has been shown on a device.** The
  accounting is correct in test but has no UI until Phase 8 onwards.

### Phase completion report

```
PHASE COMPLETION REPORT

Phase: 6 — Accounting, margin, risk and position sizing
Status: COMPLETE

Implemented:
- Micro-dollar money with checked Long arithmetic and exact thousandths scaling
- Lot units as integer hundredths
- Realized/unrealized PnL, equity, single round-trip commission
- Required margin, margin level with null-on-zero, 100% warning, 50% stop-out
- Daily loss floor (17:00 New York reset, one strike per day)
- Static $9,000 challenge floor, checked before the daily floor
- Downward-rounding position sizing that refuses rather than rounds up
- 56 tests across four suites

Files Created:
- core/accounting/AccountMath.kt
- core/trading/MarginMath.kt, core/trading/RiskPort.kt
- core/risk/RiskRules.kt, PositionSizing.kt, PropFirmRiskPolicy.kt
- core/accounting/AccountMathTest.kt
- core/risk/RiskRulesTest.kt, PositionSizingTest.kt, RiskIntegrationTest.kt, RiskFixtures.kt

Files Modified:
- core/trading/TradingEngine.kt, core/trading/TradingTypes.kt, AGENTS.md

Build Result:
- :app:assembleDebug succeeds

Tests Executed:
- 514 at the Phase 6 commit (56 new); 899 on the current tree

Tests Passed:
- 899

Tests Failed:
- 0

Known Issues:
- Stop-out termination relies on a segment guard, not a proof
- No swap/financing charge (no rate in the plan)

Unverified Requirements:
- No real economic calendar; no real dataset replayed through risk
- Slippage modelled, not calibrated
- No risk figure ever displayed on a device

Next Phase:
- Phase 7 — Replay processor and reliable persistence
```

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


### Phase completion report

```
PHASE COMPLETION REPORT

Phase: 7 — Replay processor and reliable persistence
Status: COMPLETE

Implemented:
- ReplayProcessor driving the Phase 4 path and the Phase 5/6 engine over the
  interval a season owes, from the persisted cursor to a target instant
- ReplayState checkpoints persisting cursor, orders, positions, executions,
  closed trades, balance and challenge state as one row keyed by season
- ReplayStateCodec owning the on-disk format as its own DTOs, separate from the
  domain models
- Catch-up coordinator with a one-shot resume and a flow form, both clamping the
  clock to what was actually processed
- ReplayCatchUpWorker, the WorkManager entry point, with notification gating

Files Created:
- core/replay/ReplayProcessor.kt, ReplayState.kt, ReplayStateCodec.kt, and the
  catch-up coordinator
- platform worker and notification pieces for the background entry point
- core/replay tests including the flow-clamping regression

Files Modified:
- core/trading/TradingEngine.kt and persistence accessors to expose the
  resumable state

Build Result:
- :app:assembleDebug succeeds

Tests Executed:
- 563 at the Phase 7 commit (49 new over Phase 6's 514); 899 on the current tree

Tests Passed:
- 899

Tests Failed:
- 0

Known Issues:
- resumeAsFlow previously committed the clock to the target rather than to what
  was processed; fixed, regression-tested
- Lint cannot see the notification permission check; suppressed with a comment

Unverified Requirements:
- No worker, notification or multi-day catch-up has run on a device
- Notification body text is hardcoded English; the app is not localizable
- No real dataset has been replayed end to end
- Nothing consumes ReplayProgress yet

Next Phase:
- Phase 8 — Custom professional chart rendering
```

> **Report backfilled 2026-10-22.** Phase 7 had a full progress section but no
> completion report; `tools/check_phase_reports.py` found it.

---

## Phase 8 — Custom professional chart rendering — COMPLETE

Delivered `core/chart/` and `ui/chart/`, plus the Trading screen and its
clock-following ViewModel.

> **Backfilled 2026-10-22.** Phase 8 shipped across ten commits (`c016bd5`
> ..`c7f5020`) with no progress-doc section and no completion report. The
> section below is reconstructed from those commits, the current code, and the
> Phase 8 hard rules in `AGENTS.md`.

### Implemented

- **Chart architecture (8.1).** The chart is split into a pure
  `core/chart/` (buffer, viewport, coordinates, renderer, timeframes) and a thin
  Compose `ui/chart/CandlestickChart`. The core is testable without a device;
  the composable owns only gesture plumbing and the Canvas.
- **CandleBuffer (8.2).** Append-only ring buffer of closed candles with
  O(1) append and indexed access.
- **Viewport (8.3).** `ChartViewport` — first visible index, visible bar count,
  auto-scale flag — with pan and zoom expressed as pure functions.
- **Visible candle culling (8.4).** The renderer draws only the window the
  viewport exposes, not the whole buffer.
- **Candlesticks (8.5).** Body and wick rendering with bull/bear colouring from
  chart tokens, separate from Material roles.
- **Grid (8.6).** Price and time gridlines.
- **Price axis (8.7).** Ticks and labels from `PriceAxisTicks`; the axis is drawn
  in a right-hand gutter, outside the plot rectangle.
- **Time axis (8.8).** `TimeAxisTicks` picks label instants at a spacing that
  keeps them legible at the current zoom.
- **Sessions (8.9).** Session shading driven by the Phase 3 trading calendar.
- **Forming candle (8.10).** The in-progress bar is folded into the frame
  separately from the closed buffer, so the axis spans it without the buffer
  being asked to clamp a not-yet-closed index.
- **Timeframes (8.11).** `TimeframeAggregator` builds M1/M5/M15/H1/H4/D1 from
  the M1 base, with the Trading screen opening on H1.
- **Rendering optimisation (8.12).** Draw-time auto-scale re-fit over the
  visible window, explicit `clipRect`, and per-frame axis fitting. The
  auto-scale read is over ~100 bars, not a re-aggregation.
- **Performance testing (8.13).** See *Unverified requirements* — no frame-rate
  figure was ever recorded, which is exactly the gap the Phase 8 diagnostics
  work is closing.

### Files created

- `core/chart/buffer/CandleBuffer.kt`
- `core/chart/viewport/ChartViewport.kt`
- `core/chart/coordinates/ChartCoordinateTransformer.kt`, `PriceAxisRange.kt`,
  `PriceAxisTicks.kt`, `TimeAxisTicks.kt`
- `core/chart/renderer/ChartRenderer.kt`, `ChartFrameAssembler.kt`,
  `ChartRenderState.kt`, `ChartAutoScale.kt`, `ChartRevealPlanner.kt`,
  `ChartStyle.kt`, `FormingCandleBuilder.kt`
- `core/chart/timeframe/Timeframe.kt`, `TimeframeAggregator.kt`
- `core/chart/gestures/ChartGestureController.kt`
- `ui/chart/CandlestickChart.kt`, `ui/chart/ChartDiagnosticsHook.kt`
- `feature/trading/TradingViewModel.kt`, `TradingSessionSource.kt`
- Tests: `ChartFixtures.kt`, `CandleBufferTest.kt`, `ChartViewportTest.kt`,
  `ChartCoordinateTransformerTest.kt`, `AxisTicksTest.kt`,
  `ChartRendererTest.kt`, `ChartFrameAssemblerTest.kt`, `ChartAutoScaleTest.kt`,
  `ChartAxisPlacementTest.kt`, `ChartRevealPlannerTest.kt`,
  `FormingCandleBuilderTest.kt`, `TimeframeTest.kt`,
  `TimeframeAggregatorTest.kt`, `TradingViewModelTest.kt`

### Decisions worth recording

- **The clock is a flow, not a poll.** `TradingSessionSource.simInstants()`
  emits the simulated instant and the ViewModel collects it. A
  `while (isActive) { delay(...) }` inside the ViewModel would hang every unit
  test, because a test scheduler never reaches idle while a `delay` is
  outstanding. Keeping the cadence behind the port leaves the ViewModel a pure
  consumer a test can feed one instant at a time.
- **The forming candle's range is folded separately.** `CandleBuffer.highestHigh`
  /`lowestLow` do not clamp their arguments, so passing the forming candle's
  index throws. `ChartFrameAssembler` therefore computes the range over the
  closed window and folds the forming high and low in separately.
- **Auto-scale is re-fitted at draw time, not on publish.** The price axis spans
  the *visible* extremes, which only the draw lambda knows; the range on
  `ChartRenderState` is fitted to whichever frame was last published and is
  stale after a zoom or pan. A stale range is not cosmetic: `yOfPrice` maps a
  price above the maximum to a negative `y`, drawing candles outside the plot.
- **A Canvas does not clip; the chart clips itself.** Compose `Canvas` does not
  clip its draw commands to its bounds, and the axis gutters sit deliberately
  outside the plot rectangle, so without an explicit `clipRect` the chart paints
  over the timeframe chips above it and the navigation bar below.
- **A light republish must read the latest viewport.** `refreshFormingCandle`
  takes the viewport from `lastViewport` and only falls back to the published
  frame's, because reading the frame's viewport snaps a pan back to the live
  edge on the next tick.
- **Test dispatchers must own every thread the screen touches.** The ViewModel's
  dispatchers must be injected, and the in-memory Room database must be built
  with a direct query and transaction executor, because Room runs queries on its
  own executor that the test scheduler cannot see.
- **A fake clock's flow must be closeable.** A `Channel` + `receiveAsFlow()`,
  not a `MutableSharedFlow`: the real source's flow *completes* at the end of a
  season, and only a channel can be closed to reproduce that.

### Verification

- At the end of Phase 8 (`c7f5020`): 777 test methods, 214 over Phase 7's 563.
- Re-run 2026-10-22 on the current tree: `tests=899 failures=0 errors=0`.
- `:app:assembleDebug` succeeds; `:app:lintDebug` reports 0 errors.
- The merged manifest still declares no `android.permission.INTERNET`.

### Unverified requirements

- **No frame-rate or throughput figure was ever recorded (Step 8.13).** This is
  the one acceptance criterion Phase 8 did not meet. The chart renders on a
  device, but nothing measured how fast; the debug-only diagnostics added
  afterwards exist to close this and are not a Phase 8 deliverable.
- **No on-device visual verification of the chart.** There is no device and no
  emulator in this environment (no `/dev/kvm`), so candle placement, axis
  legibility, session shading and clipping are confirmed by
  `ChartAxisPlacementTest`'s pixel read-back and by inspection, not by eye.
- **No real dataset rendered through the chart end to end.** Tests build
  synthetic candles; the debug seeder is the only path that has put real
  Dukascopy data in front of the renderer.
- **The debug-only seeder and diagnostics are temporary.** Both live in
  `app/src/debug/`, are compiled into debug builds only, and must be deleted —
  not extended — once Phase 15 provides the real import UI.

### Phase completion report

```
PHASE COMPLETION REPORT

Phase: 8 — Custom professional chart rendering
Status: COMPLETE (one acceptance criterion unmet: 8.13 performance measurement)

Implemented:
- core/chart split from ui/chart; core testable without a device
- CandleBuffer, ChartViewport, visible-window culling
- Candlesticks, grid, price axis, time axis, session shading, forming candle
- Timeframes M1..D1 aggregated from M1; Trading screen opens on H1
- Draw-time auto-scale re-fit, explicit clipRect, per-frame axis fitting
- Trading screen and clock-following TradingViewModel via TradingSessionSource

Files Created:
- core/chart/{buffer,viewport,coordinates,renderer,timeframe,gestures}/*.kt
- ui/chart/CandlestickChart.kt, ui/chart/ChartDiagnosticsHook.kt
- feature/trading/TradingViewModel.kt, feature/trading/TradingSessionSource.kt
- 14 chart/trading test suites

Files Modified:
- app/build.gradle.kts (Compose, test dispatchers), AGENTS.md, AndroidManifest

Build Result:
- :app:assembleDebug succeeds

Tests Executed:
- 777 at the end of Phase 8 (214 new); 899 on the current tree

Tests Passed:
- 899

Tests Failed:
- 0

Known Issues:
- Step 8.13 performance testing was not performed; no frame-rate figure exists

Unverified Requirements:
- No device or emulator available; chart pixels confirmed only by test read-back
- No real dataset rendered through the chart end to end
- Debug-only seeder and diagnostics are temporary and must be deleted at Phase 15

Next Phase:
- Phase 9 — Chart gestures, viewport and crosshair
```


---

## Phase 9 — Chart gestures, viewport and crosshair — COMPLETE

Delivered the interaction layer over the Phase 8 renderer. Parts 1–4 landed
inside the Phase 8 commits; `025e12e` carried parts 5–10.

> **Backfilled 2026-10-22.** Phase 9 shipped in `025e12e` with no progress-doc
> section and no completion report. Reconstructed from that commit, the current
> code and its step markers, and the Phase 8 hard rules that govern it.

### Implemented

- **Horizontal panning (9.1).** A one-finger horizontal drag moves the viewport;
  a pan is the only gesture that drags the time axis.
- **Fling (9.2).** `ChartFling` — a `VelocityTracker` decides whether a release
  is a flick, and `runFling` decelerates to a stop. A new touch cancels the
  previous fling.
- **Pinch zoom (9.3).** Two-finger zoom strictly around the focal point.
- **Zoom limits (9.4).** `ChartZoomLimits` bounds candle width to 2dp..40dp,
  derived from the plot the user is actually looking at.
- **Crosshair (9.5).** A long press that is held still promotes the gesture to
  `CROSSHAIR`; `CrosshairReadout` supplies the label. A fling is cancelled
  underneath it.
- **Haptic feedback (9.6).** `HapticFeedbackType.TextHandleMove` fires as the
  crosshair crosses onto a new candle.
- **Manual price scaling (9.7).** A vertical drag in the price gutter rescales
  the axis and sets `autoScale` false; the draw-time re-fit honours that flag.
- **Jump to latest (9.8).** A double tap on the plot restores the auto-fitted
  live edge; a control is shown only when the newest candle has scrolled off the
  right.
- **Timeframe switching (9.9).** `ChartTimeframeAnchor` keeps the window centre
  on the same moment across a timeframe change, rather than on the same index.
- **Gesture priority (9.10).** Where the gesture *starts* decides what it is;
  the gesture is re-resolved each event but only ever promoted.

### Files created

- `core/chart/gestures/ChartFling.kt`, `ChartZoomLimits.kt`,
  `ChartGesturePriority.kt`
- `core/chart/crosshair/CrosshairPosition.kt`, `CrosshairReadout.kt`
- `core/chart/renderer/ChartPriceScale.kt`
- `core/chart/viewport/ChartTimeframeAnchor.kt`
- Tests: `CrosshairReadoutTest.kt`, `ChartFlingTest.kt`,
  `ChartGesturePriorityTest.kt`, `ChartZoomLimitsTest.kt`,
  `ChartPriceScaleTest.kt`, `ChartTimeframeAnchorTest.kt`

### Files modified

- `ui/chart/CandlestickChart.kt` — the gesture loop, crosshair, haptics, axis
  drag, jump control and double-tap detection.
- `core/chart/gestures/ChartGestureController.kt` — promotion.
- `core/chart/renderer/ChartFrameAssembler.kt`, `ChartRenderState.kt` — carry
  the crosshair and manual scale.
- `feature/trading/TradingViewModel.kt` — timeframe selection.

### Decisions worth recording

- **The anchor is a timestamp, not an index.** A timeframe change re-buckets the
  data, so index 400 of M1 and index 400 of H1 are different moments. Anchoring
  on the centre *timestamp* keeps the user looking at the same market event.
  `centreTimestamp` returns null for an empty buffer, a window before the data,
  and a window panned into empty space — all three are tested.
- **Gesture identity is decided at touch-down and only ever promoted.** A pan
  that becomes a long-press crosshair does not flip back, so a small wobble
  during a hold cannot change what the gesture is.
- **Zoom limits are in candle widths, not price.** They are derived from the
  plot the user is looking at, so the same limit means the same visual density
  on any screen.
- **Manual scale disables the draw-time re-fit rather than fighting it.**
  `ChartViewport.autoScale` is the single flag; the re-fit honours it, so a
  manually scaled axis keeps its range while everything else still re-fits.

### Verification

- At commit `025e12e`: 884 test methods, 107 over Phase 8's 777.
- Re-run 2026-10-22 on the current tree: `tests=899 failures=0 errors=0`; the
  Phase 9 suites contribute 139 executed tests and all pass.
- `:app:assembleDebug` succeeds; `:app:lintDebug` reports 0 errors.
- The merged manifest still declares no `android.permission.INTERNET`.

### Unverified requirements

- **No gesture has been performed on a device.** There is no device and no
  emulator in this environment (no `/dev/kvm`). The gesture logic is pure and
  tested, but touch latency, fling feel and haptic timing are unconfirmed by me.
- **Haptic feedback is fire-and-forget.** The call is made; whether the device
  has a haptic motor, and whether the tester feels the tick, is unverified.
- **No frame-rate figure exists for a gesture-heavy session.** This is the gap
  the Phase 8 diagnostics work targets, and it is still open.
- **Double-tap and long-press timing use fixed constants**
  (`ChartGesturePriority`, `DOUBLE_TAP_MS`). They are not tuned against a real
  user's cadence.

### Phase completion report

```
PHASE COMPLETION REPORT

Phase: 9 — Chart gestures, viewport and crosshair
Status: COMPLETE

Implemented:
- Horizontal pan, fling with velocity tracking, pinch zoom around the focal point
- Zoom limits in candle widths (2dp..40dp) derived from the live plot
- Long-press crosshair with readout; fling cancelled underneath it
- Boundary haptics as the crosshair crosses a candle
- Manual price scaling via a gutter drag; autoScale flag honoured by the re-fit
- Jump to latest by double tap, with a control shown only when scrolled off
- Timeframe switching anchored on the centre timestamp
- Gesture priority decided at touch-down, promotion-only
- 139 executed tests across seven suites

Files Created:
- core/chart/gestures/ChartFling.kt, ChartZoomLimits.kt, ChartGesturePriority.kt
- core/chart/crosshair/CrosshairPosition.kt, CrosshairReadout.kt
- core/chart/renderer/ChartPriceScale.kt
- core/chart/viewport/ChartTimeframeAnchor.kt
- core/chart/**/CrosshairReadoutTest.kt, ChartFlingTest.kt,
  ChartGesturePriorityTest.kt, ChartZoomLimitsTest.kt, ChartPriceScaleTest.kt,
  ChartTimeframeAnchorTest.kt

Files Modified:
- ui/chart/CandlestickChart.kt, core/chart/gestures/ChartGestureController.kt,
  core/chart/renderer/ChartFrameAssembler.kt, ChartRenderState.kt,
  feature/trading/TradingViewModel.kt

Build Result:
- :app:assembleDebug succeeds

Tests Executed:
- 884 at the Phase 9 commit (107 new over Phase 8); 899 on the current tree

Tests Passed:
- 899

Tests Failed:
- 0

Known Issues:
- No on-device gesture or haptic verification (no device or emulator available)
- Double-tap and long-press timings are fixed constants, not user-tuned

Unverified Requirements:
- Touch latency and fling feel unmeasured
- No frame-rate figure for a gesture-heavy session
- Haptic motor presence and perceived timing unverified

Next Phase:
- Phase 10 — Interactive chart trading
```

## Phase 10 — Interactive chart trading — COMPLETE

Put the trade lifecycle on the chart: place from a ticket, see the bracket drawn,
drag a line, close from the panel. The renderer stays ignorant of trading; the
feature layer is the only place the two meet.

> **Gate.** The plan asks to stop after Phase 10. Nothing in Phase 11 or beyond is
> started here.

### Implemented

- **Live session at the composition root (10.1).** `TradingSessionSource` gained
  `createLiveSession()`, mirroring `replayProcessorFor`, so the screen is handed a
  session rather than building one. `AppContainer` is the only place that knows how
  a season becomes a tradeable session.
- **Market and pending orders (10.1, 10.2).** `LiveTradingSession.placeMarket`,
  `placePending` and `submit` drive the existing `TradingEngine`. The ticket seeds a
  market order with the current bid so its preview can size before submission.
- **Order-line overlays (10.3).** `TradeOverlayMapper` turns positions, pending
  orders and the ticket preview into `OverlayGroup`s. It is the seam rule 3
  requires: the mapper is in the feature, the render types are in `core/chart`, and
  neither knows about the other.
- **Order-line dragging (10.4).** A touch that lands within tolerance of a
  draggable line grabs it; the drag reports prices; the release submits one command.
  `OverlayLineHitTester` (in `core/chart`, over drawn types) picks the line;
  `ChartPriceScale.priceAt` and `toleranceUnits` convert pixels to price units.
- **Bracket preview (10.5).** The ticket shows risk, reward and R:R from
  `BracketEconomics` while the trader types.
- **Risk-lock sizing (10.6).** The `RiskLockBps` presets size the trade from its
  stop, via the existing `PositionSizing` arithmetic; the size field is replaced by
  the computed lot size.
- **Position management (10.7).** Close, cancel and move-to-breakeven are wired to
  the panel, each with the engine's refusal surfaced as a message.
- **Entry and exit markers (10.8).** `TradeOverlayMapper.markers` resolves a closed
  trade's open and close times to buffer indices; the renderer draws arrows.
- **Account strip and positions panel (10.9).** Balance, floating PnL, position
  count and the open/closed lists.
- **No accidental trades (10.10).** Buy and Sell open a ticket rather than
  submitting; an incomplete ticket is refused in the feature with a message and
  never reaches the engine.

### Files created

- `feature/trading/TradingPanelState.kt` — the panel's state model (pure).
- `feature/trading/TradeOverlayMapper.kt` — trading types → chart render types.
- `feature/trading/TradingControls.kt` — account strip, buy/sell bar, risk-lock row.
- `feature/trading/OrderTicketSheet.kt` — the ticket and its parsing helpers.
- `feature/trading/PositionsPanel.kt` — open positions, pending orders, history.
- `core/chart/renderer/OverlayLineHitTester.kt` — the line hit test (pure).
- Tests: `OverlayLineHitTesterTest.kt`, `TradeOverlayMapperTest.kt`,
  `OrderTicketParsingTest.kt`, plus the Phase 10 cases in `TradingViewModelTest.kt`.

### Files modified

- `feature/trading/TradingViewModel.kt` — panel state, ticket, sheets, command
  methods, the grab/drag/release gesture, and re-marking on each clock tick.
- `feature/trading/LiveTradingSession.kt` — `placeMarket`, `placePending`,
  `modifyOrder`, `modifyPosition`, `closePosition`, `cancelOrder`, `armBreakeven`,
  `bidPrices`, `markToMarket`.
- `core/trading/TradingEngine.kt` — a public per-position mark accessor.
- `core/chart/renderer/ChartPriceScale.kt` — `priceAt`, `toleranceUnits`,
  `TOUCH_TOLERANCE_DP`.
- `core/chart/renderer/ChartRenderState.kt` — carries overlays and markers.
- `ui/chart/CandlestickChart.kt` — order-line gesture resolution and drag.
- `di/AppContainer.kt` — the live-session factory.

### Decisions worth recording

- **The hit tester lives in `core/chart`, not `core/trading`.** It operates on
  `OverlayGroup`, the renderer's own type. Putting it in the trading domain would
  require that domain to know the renderer's types, which inverts the dependency the
  seam exists to prevent. It is pure geometry and is tested with hand-built overlays
  and no engine.
- **The tolerance is converted to price units by the chart, not accepted as
  pixels.** The hit test must not know about a screen — it is pure Kotlin. The chart
  owns the visible range, so `ChartPriceScale.toleranceUnits` does the conversion and
  the tester works in prices. `TOUCH_TOLERANCE_DP` is 24dp, half the Material minimum
  touch target, rather than the 8dp a touch-slop check would use: a level a trader
  aims at needs a target a finger can hit.
- **A drag submits one command on release, not one per frame.** A hundred frames
  would be a hundred commands, each stamped with the same instant and each rejected
  after the first as a look-back. The engine is told once, at the price the finger
  finished on. The ViewModel holds the grabbed line's identity across the gesture
  because the chart, by design, does not know what an order is.
- **A stop wins a tie against a target, and against list order.** Two lines can be
  equidistant — a stop and a target bracketing a narrow range. Taking the first
  within tolerance would make the drag depend on insertion order, which is arbitrary
  from the trader's point of view, and the same touch could pick different lines on
  different frames. Nearest wins; a stop breaks the tie, because it bounds the loss
  and is the safer reading of an ambiguous touch.
- **A preview bracket is never grabbable, and a non-draggable line is never hit.**
  The `isPreview` and `isDraggable` flags the renderer already draws from are the
  same flags the hit test respects, so a level the trader can see is a level they can
  grab, and one drawn as a preview is not.
- **Buy and Sell open a ticket rather than submitting.** A one-tap market order
  turns a mis-tap into a live position; for a deliberate-practice app the extra tap
  is worth a much smaller class of mistake. The plan's 10.10 "no accidental trades"
  is enforced by refusing an incomplete ticket in the feature, before the engine.
- **Markers index the drawn buffer, not the loaded minutes.** The renderer maps an
  index to a pixel in the aggregated series. Resolving a trade's time against the
  minutes would put every arrow on the wrong bar on any timeframe above M1.
- **The panel and ticket are an overlay, not a column entry.** Opening one must not
  resize the chart, or the price scale would change under the trader's finger
  mid-gesture.

### Bugs found by tests

- **The tick republish dropped the overlays.** The light path in
  `refreshFormingCandle` rebuilt the frame and wrote it to state without re-applying
  the trading overlays, so every new minute erased the position lines until the next
  command. Found while wiring the markers; fixed by re-applying the overlays and
  re-reading the marks on each moving tick.
- **The hit tester was first written in `core/trading` over `TradeOverlay`.** It
  compiled only until the chart tried to call it with the drawn `OverlayGroup` list,
  which is the type the renderer actually has. The compiler enforced the seam: the
  tester was moved to `core/chart` and rewritten over the render types.

### Verification

- `:app:testDebugUnitTest`: `tests=1011 failures=0 errors=0 skipped=0` across 62
  suites. Phase 10 adds 54 test methods (47 in new suites, 7 to
  `ChartPriceScaleTest` and `TradingViewModelTest`); the pre-Phase-10 count on
  this tree was not measured separately, and Phase 9's section records 899.
- The Phase 10 suites: `OverlayLineHitTesterTest` (12), `OrderTicketParsingTest`
  (8), `TradeOverlayMapperTest` (11), `LiveTradingSessionTest` (20),
  `BracketMathTest` (13), `TradeOverlayTest` (15), `RiskLockTest` (14), 7 new cases
  in `TradingViewModelTest` (17 total) driving the ticket through a real
  `LiveTradingSession` over the production Room repository, and 7 new cases in
  `ChartPriceScaleTest` (27 total) covering `priceAt` and `toleranceUnits`.
- `:app:assembleDebug` and `:app:lintDebug` both succeed.
- `tools/check_phase_reports.py` and `tools/check_price_scale.py` both pass.

### Unverified requirements

- **No gesture has been performed on a device.** There is no device and no emulator
  (no `/dev/kvm`). The hit test, the price conversion and the drag state machine are
  pure and tested, but that a finger on a real chart grabs the line it meant is
  unconfirmed by me.
- **The 24dp touch tolerance is a judgement, not a measurement.** It is argued from
  the Material minimum touch target, not tuned against a user.
- **Order-line drag and chart pan can both claim a touch-down.** The grab is tried
  first and returns false when nothing is near, so an empty touch pans; but whether
  that ordering *feels* right — whether the tolerance is wide enough to grab and
  narrow enough to still pan near a line — is unverified without a device.
- **No visual check of the overlays or markers.** That the lines, shading and
  arrows are drawn where and in the colours intended is asserted only through the
  mapper's output, not seen.
- **The ticket's numeric keyboard and locale behaviour is untested on a device.**
  The comma-decimal path is unit-tested; the on-screen keyboard is not.

### Phase completion report

```
PHASE COMPLETION REPORT

Phase: 10 — Interactive chart trading
Status: COMPLETE

Implemented:
- Live session created at the composition root via TradingSessionSource
- Market and pending orders from an order ticket; incomplete tickets refused
- Order-line overlays via TradeOverlayMapper (the rule 3 seam)
- Order-line dragging: hit test, pixel-to-price conversion, one command on release
- Bracket preview (risk, reward, R:R) and risk-lock sizing
- Close, cancel and move-to-breakeven from the positions panel
- Entry and exit markers on closed trades
- Account strip: balance, floating PnL, exposure counts
- 54 new tests; 1011 total, 0 failures

Files Created:
- feature/trading/TradingPanelState.kt, TradeOverlayMapper.kt, TradingControls.kt,
  OrderTicketSheet.kt, PositionsPanel.kt
- core/chart/renderer/OverlayLineHitTester.kt
- Tests: OverlayLineHitTesterTest.kt, TradeOverlayMapperTest.kt,
  OrderTicketParsingTest.kt

Files Modified:
- feature/trading/TradingViewModel.kt, LiveTradingSession.kt
- core/trading/TradingEngine.kt
- core/chart/renderer/ChartPriceScale.kt, ChartRenderState.kt
- ui/chart/CandlestickChart.kt
- di/AppContainer.kt

Build Result:
- :app:compileDebugKotlin succeeds

Tests Executed:
- 1011

Tests Passed:
- 1011

Tests Failed:
- 0

Known Issues:
- No on-device verification of the drag gesture or the ticket keyboard
- The 24dp touch tolerance is a reasoned default, not a tuned one
- Drag-versus-pan arbitration is untested against a real finger

Unverified Requirements:
- That a touch grabs the line the trader aimed at, on a real chart
- Visual placement and colour of overlays and markers
- Locale-specific numeric keyboard behaviour

Next Phase:
- None. Phase 10 is the gate; Phase 11 is not started.
```
