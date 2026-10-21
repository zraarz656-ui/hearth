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

Phases 4 to 15. There is no market path engine, order matching, chart rendering,
challenge engine, news delivery or journal.
