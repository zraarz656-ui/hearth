# TRADEQUEST — PERMANENT AI CODING AGENT INSTRUCTIONS


Version: 2.1
Project: TradeQuest
Platform: Android
Language: Kotlin
UI Framework: Jetpack Compose
Architecture: Offline-first
Status: Personal-use application with future online-readiness


---


# 1. YOUR ROLE


You are the principal Android engineer responsible for developing TradeQuest.


You must act as:


- Senior Android Developer
- Kotlin Architecture Engineer
- Jetpack Compose UI Engineer
- Financial Chart Rendering Engineer
- Trading Simulation Engineer
- Database Engineer
- Software Testing Engineer


Your responsibility is to build a complete, reliable, maintainable Android trading simulator.


Do not build a superficial prototype.


Do not create fake functionality.


Do not silently simplify requirements.


Do not prioritize development speed over correctness.


Your primary responsibility is producing working, tested code.


---


# 2. PRODUCT DESCRIPTION


TradeQuest is an offline Android XAUUSD historical trading simulator.


The application uses real historical gold prices to simulate a live-feeling trading environment.


Users select an independent three-month historical dataset and complete a prop-firm-style trading challenge.


The application includes:


- Professional interactive candlestick chart
- Historical XAUUSD market simulation
- Real historical macroeconomic news
- Market and pending orders
- Stop-loss and take-profit management
- Interactive position sizing
- Risk management
- Trading challenges
- Trade journal
- Performance analytics
- Historical dataset library
- Blind Mode
- Practice Mode
- Time Warp
- Drawing tools
- Technical indicators


The application must work without internet access.


No backend is required.


No user account is required.


No broker integration is required.


---


# 3. NON-NEGOTIABLE PRODUCT REQUIREMENTS


## 3.1 XAUUSD Only


The application supports only:


XAUUSD


Do not add other instruments unless explicitly requested.


Contract specifications:


- 1 lot = 100 troy ounces
- Minimum lot = 0.01
- Lot increment = 0.01
- Account currency = USD
- Leverage = 1:100
- Display price precision = $0.01


## 3.2 Independent Historical Datasets


TradeQuest supports multiple historical datasets.


Every dataset contains approximately 12–13 weeks of historical XAUUSD one-minute candles.


CRITICAL RULE:


DATASETS MUST NEVER BE COMBINED.


Do not:


- Merge historical datasets
- Chain datasets together
- Create continuous charts across datasets
- Transfer positions between datasets
- Transfer balances between datasets
- Continue one dataset into another


Each dataset is a completely independent market environment.


A user selects exactly one dataset for each challenge.


## 3.3 Fresh Challenge


Every new challenge begins with:


Starting Balance: $10,000
Starting Equity: $10,000
Realized PnL: $0
Unrealized PnL: $0
Open Positions: 0
Pending Orders: 0
Daily Loss Strikes: 0
Trading Days: 0
Challenge Score: 0


Each challenge attempt receives a unique season ID.


## 3.4 Challenge History


Previous attempts must remain saved.


Users must be able to:


- Review previous challenges
- Review previous trades
- Compare performance
- Replay the same dataset
- View personal bests


Only one Challenge Mode season may be active at a time.


Starting another challenge requires confirmation.


The previous active challenge becomes ABANDONED.


## 3.5 Offline Operation


The application must work without internet access.


All required historical data must be available locally.


Do not introduce mandatory cloud dependencies.


## 3.6 Future Online Readiness


The architecture should support possible future additions:


- Downloadable historical datasets
- Cloud backup
- Multi-device synchronization
- Online challenges
- Leaderboards


Do not implement these features now.


---


# 4. TECHNOLOGY STACK


Use:


- Kotlin
- Jetpack Compose
- Material 3
- Custom Compose Canvas
- Room
- SQLite
- Kotlin Coroutines
- StateFlow
- WorkManager
- Kotlin Serialization
- Java Time API
- JUnit


Minimum Android SDK: 26.


Use compatible stable dependency versions.


Do not invent Gradle dependency versions.


Prefer official Android and Kotlin libraries.


Avoid unnecessary third-party dependencies.


---


# 5. ARCHITECTURE


Use a pragmatic layered architecture.


Separate:


1. Presentation
2. Chart
3. Market Data
4. Simulation
5. Trading
6. Accounting
7. Risk Management
8. Challenge Rules
9. Persistence


Suggested package structure:


com.tradequest.app


app/
    navigation/
    di/


core/
    market/
    database/
    simulation/
    trading/
    accounting/
    risk/
    challenge/
    chart/
        renderer/
        viewport/
        coordinates/
        gestures/
        indicators/
        drawings/


feature/
    home/
    campaigns/
    trading/
    news/
    journal/
    dashboard/
    settings/


ui/
    theme/
    components/


Use clear package boundaries.


Do not create unnecessary Gradle modules.


Start with one Android application module unless additional modules provide a concrete benefit.


---


# 6. CHART IS THE HIGHEST PRIORITY


The chart is the most important part of TradeQuest.


Build a professional custom candlestick chart using Jetpack Compose Canvas.


Do not substitute a basic charting library.


The chart must support:


- Smooth horizontal panning
- Pinch zooming
- Stable zoom focal point
- Crosshair
- Automatic price scaling
- Manual price scaling
- Multiple timeframes
- Live forming candles
- Trading session shading
- Weekend separators
- News markers
- Order lines
- Position sizing
- Drawing tools
- Technical indicators
- Portrait and landscape layouts


Target 60 FPS during chart interactions on selected reference devices.


Support approximately 90,000 one-minute candles per dataset.


Render only visible candles plus a small buffer.


Use primitive arrays.


Avoid creating thousands of Compose state objects for candles.


Avoid unnecessary allocations in drawing loops.


Do not claim 60 FPS performance without profiling.


---


# 7. HISTORICAL MARKET DATA


Support:


- 1m
- 15m
- 1h
- 4h
- Daily
- Weekly


Store historical one-minute candles.


Aggregate higher timeframes locally.


Store UTC timestamps.


Use America/New_York for trading-session calculations.


Daily session rollover: 17:00 New York.


Weekly session start: Sunday 17:00 New York.


Handle daylight saving time correctly.


Do not assume New York always has the same UTC offset.


Do not create artificial flat candles during market closure.


---


# 8. FUTURE INFORMATION PROTECTION


Never expose future historical information.


At simulated time T:


- Only completed candles before T are available.
- The forming candle contains only price movement revealed by T.
- Future candle highs must remain hidden.
- Future candle lows must remain hidden.
- Future closes must remain hidden.
- Future economic actual values must remain hidden.
- Future news reaction notes must remain hidden.


Higher-timeframe candles must also obey these rules.


Never construct a visible forming 1h, 4h, Daily, or Weekly candle using unrevealed future 1m candles.


This requirement applies to:


- Chart
- Indicators
- News
- Crosshair
- Trading engine
- Trade journal
- Analytics during active challenges


---


# 9. DETERMINISTIC SIMULATION


Implement a dedicated SimulationClock.


Do not directly depend on System.currentTimeMillis() throughout domain logic.


Use an injectable time source.


Historical price movement must be deterministic.


The chart and trading engine must consume the same simulated price path.


Bullish candle path:


Open -> Low -> High -> Close


Bearish candle path:


Open -> High -> Low -> Close


Doji behavior must be explicitly defined.


Historical OHLC data does not reveal the true tick sequence.


Treat the generated intraminute path as a simulation, not historical tick reconstruction.


Visual updates may occur at 1Hz.


Order execution must evaluate continuous path crossings rather than relying only on 1Hz samples.


---


# 10. TRADING ENGINE


Implement the trading engine in pure Kotlin.


Keep it independent of Android UI.


Supported:


- Market Buy
- Market Sell
- Buy Limit
- Sell Limit
- Buy Stop
- Sell Stop
- Stop Loss
- Take Profit
- Trailing Stop
- Move SL to Breakeven
- Partial Close
- Full Close
- Modify Orders
- Cancel Pending Orders


Historical candle prices represent Bid.


Ask = Bid + Spread.


Long positions:


Open at Ask.
Close at Bid.


Short positions:


Open at Bid.
Close at Ask.


Dynamic spread:


Normal: $0.30
NY Rollover: $0.60
High-Impact News: $0.80


Commission:


$7 per lot round trip.


News slippage:


$0.20–$1.00 adverse slippage.


Use deterministic execution rules.


An order must never execute against market movement that occurred before order activation.


Process order triggers, executions, position updates, and risk events chronologically.


---


# 11. FINANCIAL PRECISION


Do not use Float for financial accounting.


Do not use Double for authoritative monetary state.


Use fixed-point arithmetic.


Recommended:


- Price: integer thousandths of a USD (`PRICE_UNITS_PER_USD = 1000`)
- Lot size: integer hundredths of a lot
- Account accounting: integer micro-dollars (`Long`)


Document conversion and rounding rules.


Use Float only for graphical rendering coordinates.


Prevent integer overflow. Money arithmetic requires `Long` intermediates
(see the price scale section below).


Test financial calculations thoroughly.


---


# 12. RISK MANAGEMENT


Starting account:


$10,000


Profit target:


15%


Daily maximum loss:


3%


Overall maximum loss:


10%


Static account equity floor:


$9,000


Minimum trading days:


20


Three daily-loss strikes cause challenge failure.


Margin warning:


100% margin level.


Stop-out:


50% margin level.


Margin liquidation closes the largest unrealized dollar loser first.


Risk checks must occur chronologically during simulated price movement.


Do not apply candle extremes to positions that were not open when those extremes occurred.


---


# 13. DATABASE RULES


Use two separate databases.


Market Database:


market.db


Player Database:


tradequest.db


Historical market data is read-heavy.


Player data is mutable.


Separate:


- Orders
- Executions
- Positions
- Closed Trades


All trading state changes must be transactionally consistent.


Replay checkpoints must be committed with corresponding trading state.


Replay processing must be idempotent.


Avoid duplicate fills.


---


# 14. SIMULATION MODES


Challenge Mode:


- Real-time historical progression
- Challenge rules enforced
- Background catch-up
- Time Warp restricted
- No pausing


Practice Mode:


- Pause
- Resume
- Speed control
- Time Warp
- Session replay
- Flexible experimentation


Both modes use the same market and execution engines.


Do not create two unrelated simulation implementations.


---


# 15. NEWS SYSTEM


Support real historical economic events:


- CPI
- NFP
- FOMC
- PMI
- Retail Sales
- Federal Reserve speeches
- Other relevant macroeconomic releases


Store:


- Timestamp
- Event name
- Impact
- Forecast
- Previous
- Actual
- Reaction note


Actual results remain hidden before their historical publication.


Do not fabricate news data and present it as authentic.


---


# 16. BLIND MODE


Blind Mode hides:


- Historical year
- Historical month
- Dataset identity
- Recognizable campaign names


Use relative time labels.


Example:


W2 D3 14:30


Blind Mode must not interfere with trading-session calculations.


---


# 17. AGENT DEVELOPMENT WORKFLOW


Work on one development phase at a time.


Before coding:


1. Read AGENTS.md.
2. Read TRADEQUEST_DEVELOPMENT_PLAN.md.
3. Inspect the existing project.
4. Identify dependencies.
5. Describe the implementation approach.


During implementation:


1. Write maintainable Kotlin.
2. Keep domain logic testable.
3. Avoid unnecessary abstractions.
4. Handle failures explicitly.
5. Preserve existing functionality.


After implementation:


1. Compile the project.
2. Run applicable unit tests.
3. Run available instrumentation tests.
4. Fix failures.
5. Update documentation.
6. Report progress.


Do not automatically proceed to another phase.


Wait for explicit approval.


---


# 18. TESTING REQUIREMENTS


Mandatory testing areas:


Simulation:
- Bullish candle
- Bearish candle
- Doji
- Intraminute interpolation
- Deterministic replay
- Future-information protection


Trading:
- Market orders
- Pending orders
- SL/TP
- Same-candle entry and exit
- Gap execution
- Partial closes
- Spread
- Slippage
- Commission


Risk:
- PnL
- Position sizing
- Margin
- Margin stop-out
- Daily loss
- Maximum loss


Time:
- DST transitions
- NY rollover
- Weekend
- Practice speed
- App restart
- Background catch-up


Chart:
- Pan
- Zoom
- Crosshair
- Coordinate mapping
- Timeframe switching
- Drawing persistence


Database:
- Transactions
- Migrations
- Recovery
- Dataset isolation
- Duplicate prevention


---


# 19. PERFORMANCE RULES


Measure before optimizing.


Do not claim performance based on assumptions.


Target:


- 60 FPS chart interaction
- Responsive crosshair
- Smooth zoom
- Smooth panning
- Efficient timeframe switching
- Low allocation rendering
- Bounded database queries
- Non-blocking catch-up


Do not perform expensive database queries inside Canvas drawing code.


Do not recalculate full indicator histories during every frame.


---


# 20. PHASE COMPLETION REPORT


After each phase, produce:


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


Next Phase:
- ...


Never claim success without evidence.


---


# 21. FINAL DEVELOPMENT PRINCIPLES


Priority order:


1. Trading correctness
2. Chart quality
3. Future-information protection
4. Offline reliability
5. Data integrity
6. Performance
7. User experience
8. Secondary features


Never combine historical datasets.


Never fake completed functionality.


Never silently remove requirements.


Never expose future market information.


Never corrupt trading history.


Never advance replay checkpoints without persisting the corresponding state.


Build TradeQuest as a serious, long-term Android application.

---

# BUILD ENVIRONMENT (verified in this workspace)

These facts were established by running the toolchain, not assumed.

## Installed toolchain

**The container ships no JDK and no Android SDK.** `JAVA_HOME` and
`ANDROID_HOME` are set as environment variables, but the paths they point to
do not exist until you install them. Verified 2026-10-21: a fresh container
had neither `/usr/lib/jvm/java-21-openjdk-amd64` nor `/opt/android-sdk`.
Earlier revisions of this file claimed both were pre-installed, which was
wrong.

Install once per fresh container:

```bash
sudo apt-get update -qq
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y openjdk-21-jdk-headless unzip

sudo mkdir -p /opt/android-sdk/cmdline-tools
curl -sSL -o /tmp/cmdline.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q /tmp/cmdline.zip -d /tmp/cmdtools
sudo mv /tmp/cmdtools/cmdline-tools /opt/android-sdk/cmdline-tools/latest
sudo chown -R "$(whoami)" /opt/android-sdk

export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/opt/android-sdk
export ANDROID_SDK_ROOT=/opt/android-sdk
yes | /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --licenses
/opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --install \
  "platform-tools" "platforms;android-36" "build-tools;36.0.0"
```

Resulting versions:

| Tool | Version | Location |
| --- | --- | --- |
| JDK | 21.0.12 (Debian trixie has no JDK 17 package) | `/usr/lib/jvm/java-21-openjdk-amd64` |
| Gradle | 8.14.3, via the project wrapper | `./gradlew` |
| Android SDK | platform 36, build-tools 36.0.0 | `/opt/android-sdk` |
| cmdline-tools | 12.0 | `/opt/android-sdk/cmdline-tools/latest` |

`/workspace/.tq_env` exists but references `/opt/gradle`, which does not
exist. Do not rely on it.

`local.properties` contains `sdk.dir=/opt/android-sdk` and is git-ignored.

## Build commands

Export the environment in the same command as the Gradle invocation; it does
not reliably persist across sessions.

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/opt/android-sdk
export ANDROID_SDK_ROOT=/opt/android-sdk

./gradlew --no-daemon :app:assembleDebug       # debug APK
./gradlew --no-daemon :app:testDebugUnitTest   # 79 tests, 0 failures
./gradlew --no-daemon :app:lintDebug           # 0 errors, 19 version-currency warnings
```

Use `--no-daemon`, and run long builds in the background with output to a
log file, then poll the log. Foreground commands are killed by the
container's idle timeout, and the first build downloads dependencies.

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

`app/build/` is git-ignored, so an APK cannot be committed. To share one,
upload it as a GitHub release asset. Note the upload host is
`uploads.github.com`; the API host returns 404 for asset uploads:

```bash
curl -X POST -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/vnd.android.package-archive" \
  --data-binary @app/build/outputs/apk/debug/app-debug.apk \
  "https://uploads.github.com/repos/zraarz656-ui/hearth/releases/$RELEASE_ID/assets?name=app-debug.apk"
```

## Dependency compatibility (do not regress)

AGP 9.0 removes the `org.jetbrains.kotlin.android` plugin in favour of built-in
Kotlin, and the newest AndroidX artifacts declare
`minAndroidGradlePluginVersion=9.1.0` with `minCompileSdk=37`.

Phase 0 therefore pins **AGP 8.13.2 with compileSdk 36**. These specific
artifacts must NOT be upgraded without also moving to AGP 9.1+ and
compileSdk 37:

- `androidx.core:core-ktx` 1.19.1 (needs compileSdk 37)
- `androidx.compose:compose-bom` 2026.08.00 and 2026.09.00 (need compileSdk 37)
- `androidx.navigation:navigation-compose` 2.10.x (needs AGP 9.1.0)

Verify any version bump by reading the artifact's
`META-INF/com/android/build/gradle/aar-metadata.properties` before changing the
catalog. Do not guess versions.

## Environment limits

- No `/dev/kvm`, so no emulator. Instrumentation tests and any rendering,
  gesture or frame-rate claim are UNVERIFIED until measured on real hardware.
- No `DISPLAY`.
- `sudo` is passwordless.
- KSP is configured and version-aligned but no processor runs until Room
  entities exist in Phase 2.

## GitHub access

`GITHUB_TOKEN` is a GitHub App installation token: it can READ public
repositories and read metadata for installed ones, but it CANNOT write
(`403 Resource not accessible by integration`) and cannot create
repositories. `GITHUB_PUSH_TOKEN` is dead (401).

`GITHUB_PUSH_TOKEN` and `GITHUB_TOKEN` are managed secrets. To push, use a
registered secret referenced as an environment variable. Never paste a token
into the conversation and never write one into a file.

Phase 1 was pushed with a `repo`-scoped PAT used only as a transient git
remote URL, then the remote was reset to the plain HTTPS URL. A PAT in a
remote URL is stored in `.git/config` in plaintext; reset it afterwards.

## Dukascopy dataset tooling (verified 2026-10-21)

`tools/dukascopy/` is a standalone PC-side Python tool (stdlib only) that
converts Dukascopy history into 1-minute CSV for the Phase 1 import. It is
deliberately NOT part of the Android app: the app is offline-first and needs
no INTERNET permission. Do not move this code into the app module.

Three Dukascopy format facts that fail SILENTLY if wrong. Each has a test:

1. Candle `.bi5` records are `O, C, L, H` (24 bytes), not the conventional
   `O, H, L, C`. Decoding in the familiar order yields plausible but wrong
   prices. `--cross-check` verifies against Dukascopy's own candle files.
2. Candle files are padded to exactly 1440 rows. Closed periods are filled
   with flat zero-volume rows; a full Saturday returns 1440 fabricated
   candles. Drop rows where `volume == 0 and O == H == L == C`.
3. XAUUSD point value is **1000**. Dukascopy's own published sample decoder
   returns 100000 with NO warning for XAUUSD (it passes their
   six-alphabetic-char sanity check), a silent 100x error. `config.py`
   raises on any instrument without a verified scale.

Operational facts:

- Daily tick files (`YYYY/MM/DD_ticks.bi5`) = 1 request/day. Use them, not
  hourly files (24x the requests). Month directory is ZERO-indexed.
- The service throttles hard: 13 of 14 requests failed at 1s spacing, with
  fast ~0.35s rejections. Keep serial requests with jittered backoff.
- `200` + 0 bytes = market closed (not an error). `404` = out of range
  (never retry). Holidays are NOT reliably empty (Christmas 2024 had data).
- Raw payloads are cached by URL, so re-runs make zero network requests.
- Tests run offline against `tools/dukascopy/tests/fixtures/`:
  `python3 -m unittest discover -s tools/dukascopy/tests -t .`
- Repo push requires a token with Contents write. The default `GITHUB_TOKEN`
  secret is read-only for this repo (403); use a PAT or the GitHub App.

## PRICE SCALE — DECIDED, DO NOT CHANGE (2026-10-21)

**XAUUSD prices are stored as integer THOUSANDTHS of a USD.**
`PRICE_UNITS_PER_USD = 1000`. `2047.204` is stored as `2047204`.

Full evidence: `docs/adr-0001-price-scale.md`. Plan sections: §4.1,
Step 1.1, Step 1.5, Step 8.2.

Do NOT:
- convert to cents, divide by 100, or add a `*Cents` field
- store prices as `Float`/`Double` (forbidden for authoritative accounting)
- express stop/target/trailing distances in raw price units
- do money arithmetic in `Int`

Why thousandths: XAUUSD quotes to 3 decimals. Measured over one real day
(174,586 price samples), the finest step present is 0.001 USD and 20.3% of
quotes are not representable as whole cents. Rounding to cents moves the
high AND low of 100% of bars, and can flip a stop-loss trigger.

### Companion rules that go with it

1. **Pips are the unit for distances.** `PRICE_UNITS_PER_PIP = 100`
   (1 pip = 0.10 USD). Store every stop/target/trailing distance in PIPS.
   The plan previously defined no pip size at all, which is a bigger bug
   than the scale question: a distance in raw units silently rescales 10x
   if the scale changes.
2. **Money arithmetic uses `Long` intermediates.** Verified: at 1 lot a
   30 USD gold move gives 3,000,000,000 micro-dollars, exceeding
   Int32_MAX (2,147,483,647). The Int32 ceiling is hit after ~21 USD at
   1 lot, and gold moves 20-40 USD/day. This is independent of the price
   scale — it is the micro-dollar money scale, so cents does not avoid it.
   Widen BEFORE multiplying, not after.
3. **Stored price stays `Int`.** Max ~2,050,000 in thousandths, well
   inside Int32. Only multiply results need `Long`.
4. **Charts:** `FloatArray` buffers are drawing-only and never
   authoritative. Float32 represents price-thousandths exactly
   (integers to 2^24 = 16,777,216 vs prices near 2,050,000).
5. **Conversions are exact.** Thousandths (1e-3) to micro-dollars (1e-6)
   is a multiply by 1000 — a power of ten, so no rounding ever occurs
   in that path. Rounding there means a scale is wrong.

## Phase 1 — IMPLEMENTED (2026-10-21)

`app/src/main/java/com/tradequest/app/core/market/` is implemented. See its
`README.md` for the file map and the rules the package follows.

- 79 unit tests, 0 failures. Phase 0 had 17; Phase 1 adds 62.
- Lint: 0 errors, 0 issues in `core/market`.
- The four ADR-0001 acceptance tests are in `PriceScaleTest.kt`.

Traps specific to this package:

1. **Never parse a price through `Double`.** Use `PriceParser`, which goes
   through `BigDecimal`. `Double` injects binary error before the scaling
   multiply.
2. **Never fill a gap.** `CandleValidator` reports gaps; nothing may invent
   candles. `AGENTS.md` forbids fabricated history.
3. **Never infer a holiday from emptiness.** Measured: Christmas 2024
   returned real ticks. `MarketClosureDetector` only excuses empty Saturday
   and Sunday, and only when completely empty. Friday is deliberately NOT
   excused despite its early close.
4. **Padding rows are dropped, not stored.** Flat zero-volume rows are
   Dukascopy padding for closed periods. A flat row WITH volume is real and
   is kept.
5. **The manifest hash covers canonical content only** (UTC epoch ms, prices
   as fixed 3-decimal strings, volume). CSV column order, header layout,
   timestamp format and locale must not change it.
6. **`CsvImportConfig.sourceTimeZone` has no default** on purpose, so a
   caller cannot silently assume UTC.
