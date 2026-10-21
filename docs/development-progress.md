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
