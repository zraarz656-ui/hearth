# TradeQuest — Dukascopy Fetcher Plan

Status: **DRAFT — awaiting approval. No code written.**
Date: 2026-10-20
Author: senior Android dev (agent), on evidence gathered by live probing.

---

## 1. Objective

Produce authentic, reproducible XAUUSD 1-minute datasets for Phase 1 import,
without hand-fabricating any market data (AGENTS.md forbids this).

Deliverable: a **standalone offline data-preparation tool** that fetches
Dukascopy historical data, converts it to 1-minute candles, and writes
Dukascopy-compatible CSV files that Phase 1 imports.

---

## 2. What I verified by probing (not assumed)

I ran real requests against Dukascopy and decoded real payloads. Every claim
below has evidence.

### 2.1 Endpoints that work

| Endpoint | Shape | Status |
| --- | --- | --- |
| `.../datafeed/XAUUSD/YYYY/MM/DD_ticks.bi5` | 1 file = 1 full day of ticks | 200, works |
| `.../datafeed/XAUUSD/YYYY/MM/DD/HHh_ticks.bi5` | 1 file = 1 hour of ticks | 200, works |
| `.../datafeed/XAUUSD/YYYY/MM/DD/BID_candles_min_1.bi5` | 1 file = 1 day of M1 candles | 200, works |
| `.../datafeed/XAUUSD/YYYY/MM/DD/ASK_candles_min_1.bi5` | 1 file = 1 day of M1 ask candles | 200, works |
| `.../datafeed/XAUUSD/YYYY/MM/BID_candles_day_1.bi5` | 1 file = 1 month of D1 candles | 200, works |
| `.../datafeed/XAUUSD/YYYY/MM/DD/BID_candles_day_1.bi5` | — | **404, does not exist** |

`MM` is **zero-indexed** (January = `00`, December = `11`). Confirmed: `2024/00/15` returned real January data.

### 2.2 Tick file format (20 bytes/record, big-endian)

```
offset  type      field
0       uint32    milliseconds since 00:00 UTC
4       uint32    ask price (scaled)
8       uint32    bid price (scaled)
12      float32   ask volume
16      float32   bid volume
```

LZMA **alone** format (raw stream, 13-byte header) — `lzma.decompress(raw, format=lzma.FORMAT_ALONE)`. Not `.xz`.

Verified on `2024/00/15_ticks.bi5`: 351,849 compressed → 1,745,860 bytes →
**87,293 records**, exactly divisible by 20. Milliseconds monotonic, range
`124 .. 86398826`.

### 2.3 Candle file format (24 bytes/record, big-endian) — NONSTANDARD

```
offset  type      field
0       uint32    seconds since 00:00 UTC
4       uint32    open
8       uint32    close      <-- close BEFORE low/high
12      uint32    low
16      uint32    high
20      float32   volume
```

**The field order is `O, C, L, H` — not the conventional `O, H, L, C`.** I
confirmed this by cross-checking against the tick file: for 10:00 on 2024-01-15,
tick-derived `O=2054.925 H=2055.045 L=2054.485 C=2054.615` matched the candle
file **exactly** under `O,C,L,H` decoding. Decoding as `O,H,L,C` produces
nonsense. This is the single most likely place a naive parser silently corrupts
every dataset.

### 2.4 Price scaling for XAUUSD is `/1000`

Raw `2054525` → `2054.525`. Confirmed by matching tick-derived prices against
the published candle file.

**The Dukascopy wiki's own sample decoder is wrong for XAUUSD, silently.**

I ran their published `get_point_value()` against `"XAUUSD"`:

```
get_point_value('XAUUSD') = 100000        # no warning emitted
raw 2054525  /1000    = 2054.525          # verified true
raw 2054525  /100000  =   20.54525        # 100x wrong
raw 2054525  /100      = 20545.250        # wiki's commented hint, also wrong
```

Their function only warns when the symbol is *not* six alphabetic characters.
`XAUUSD` is six alphabetic characters, so it sails through the sanity check and
returns `100000` **with no warning at all** — a silent 100× price error.

Their own code contains a commented-out `# 'XAUUSD': 100` hint, which is *also*
wrong for this datafeed (100× too high).

Verified value: **1000** (3 decimal places), confirmed by matching
tick-derived prices against the published candle file.

### 2.5 Candle volume semantics

Candle `volume` equals the **sum of bid tick volumes** for that minute.
Verified 12/12 minutes exactly; the ask-sum matched only 1/12. The BID candle
file is bid-side throughout.

### 2.6 Presence semantics (measured across weekends, holidays, months, and years)

| Request | Status | Bytes | Meaning |
| --- | --- | --- | --- |
| daily ticks, Sat 2024-01-13 | 200 | **0** | market closed — no ticks |
| daily ticks, Sun 2024-01-14 | 200 | 14,064 | Sunday reopen (~22:00 UTC), real ticks |
| daily ticks, Mon 2024-01-15 | 200 | 351,849 | full trading day |
| daily ticks, Wed 2024-12-25 (Christmas) | 200 | 7,899 | **non-zero** — partial activity exists |
| M1 candles, Sat 2024-01-13 | 200 | 1,888 | padded 1440 rows — **not** a closure signal |
| daily ticks, 2003-05-05 | 200 | 85,713 | history reaches back to at least 2003 |
| daily ticks, 2030-01-02 (future) | **404** | 0 | out of range |

Rules:

- `404` → date out of range. Never retry.
- `200` + 0 bytes → market closed. Legitimate "no data".
- `200` + data → real ticks.

**Do not use the M1 candle file to detect market closure.** It returns ~1440
padded rows even on a Saturday, so it cannot distinguish "closed" from "open".
Closure detection must use the tick file's 0-byte response, or the
zero-volume-and-flat filter in §2.7.

Month index confirmed zero-based on real payloads: `2024/03` → April,
`2024/11` → December.

Note that Christmas 2024 returned 7,899 bytes rather than zero, so holidays are
**not** reliably empty. The pipeline reports per-day activity and never assumes
a holiday is closed.

Weekend `429`/`503` behaviour is unchanged; these were measured with backoff.

### 2.7 The M1 candle file is padded — this is the biggest trap

`BID_candles_min_1.bi5` always contains **exactly 1440 rows**, including
non-trading time. Weekend Saturday returned 1440 rows, **all flat
(zero-volume, O=H=L=C)**.

On a real trading day (2024-01-15): 1440 rows, of which **1228 real and 212
flat zero-volume padding**. Every one of the 212 padding rows was confirmed
absent from the tick file. Zero OHLC mismatches across the 1228 real minutes.

**Consequence: importing the candle file raw injects ~15% fake flat candles
into every dataset, including entire fake weekend days.** Any importer must
drop rows where `volume == 0 AND O == H == L == C`.

### 2.8 Rate limiting is aggressive and must drive the design

Measured behaviour:

- Rapid sequential requests → `429 Too Many Requests`, then sustained `503`.
- With a 1.0 s delay, 13 of 14 consecutive hour-file requests still failed.
- Rejections are often *fast* (0.35 s), so naive retry loops burn the budget.
- Exponential backoff recovered 8/10 hours (max 5 attempts).

**A naive 1-request-per-hour loop would take days and mostly fail.** Fetching
per-day files instead of per-hour cuts requests 24× and is the key decision.

### 2.9 S3: the officially supported bulk path

Dukascopy's wiki documents `s3://cfg-public-proper-wallaby` (eu-west-1),
**Requester Pays**, as the supported bulk route, with AWS credentials and
billed requests (~$0.0004/1000 GET + $0.02/GB).

I tested **anonymous** S3 access: `403 AccessDenied — Anonymous users cannot
invoke`. It requires an AWS account with billing.

---

## 3. Honest caveat — please read before approving

**I did not verify Dukascopy's terms of service for automated bulk download
from the free `datafeed` endpoints.** Their documented, supported bulk path is
the paid S3 bucket. The `datafeed` endpoints are what their web charts use, and
their wiki mentions download performance and stability — which suggests
throttling is deliberate.

The rate limiting I measured (aggressive `429`/`503`) is consistent with a
service that does not intend heavy automated pulling.

For personal use at the volumes here (~90 days per dataset), a polite,
throttled fetch is modest: roughly **90 requests per 3-month dataset**, a few
MB. But it is not clearly sanctioned, and Dukascopy could block it at any time.
Two honest options:

- **Option A (recommended):** polite throttled `datafeed` fetcher, ~90
  requests/dataset, conservative delay, resumable, caching raw responses.
  Accept that the endpoint may change or block.
- **Option B (fully sanctioned):** S3 Requester Pays fetcher. Needs an AWS
  account; cost is cents for this data volume. Slower to set up, no grey area.

I recommend building **both behind one interface**, with A as default and B as
the sanctioned fallback. Tell me which you want as primary, or say "both".

---

## 4. Architecture decision: separate tool, not in-app

Recommendation: the fetcher is a **standalone tool that runs on the PC**, and
is **not** part of the Android app.

Why:

- Phase 1 (plan lines 403–534) is explicitly an **import pipeline for CSV
  files**. It expects files, not network code. Building the fetcher in-app
  would duplicate work and skip the validated import path.
- AGENTS.md: the app is **offline-first**. Shipping network fetching contradicts
  the design intent and adds an INTERNET permission the app does not need.
- Datasets are prepared once and reused; they are build inputs, not app
  features.
- Iterating on a fetch script is far faster than on-device, and raw `.bi5`
  caching is trivial on a PC.

If you want on-device fetching later, it can be added as a separate concern
(needs XZ/LZMA for Kotlin, storage management, and a foreground service). I do
not recommend it for v1.

Language: **Python 3**, stdlib only (`lzma`, `struct`, `urllib`, `hashlib`,
`csv`). No third-party dependency, no build step, matches Phase 1's import
tooling.

---

## 5. Source strategy

Tiered, with cross-validation:

| Tier | Source | Requests/day | Role |
| --- | --- | --- | --- |
| 1 | `DD_ticks.bi5` (daily ticks) | 1 | **Primary.** Ground truth. Derives bid+ask candles and spread. |
| 2 | `DD/BID_candles_min_1.bi5` | 1 | **Verification.** Must agree with Tier 1 after padding removal. |
| 3 | `DD/HHh_ticks.bi5` | 24 | Fallback if a daily file is missing/corrupt. |
| 4 | S3 bucket | — | Sanctioned fallback (Option B). |

**Tier 1 is primary** because a daily tick file is a single request that yields
everything: true 1-minute bid and ask candles, realistic spread, and volume.
Tier 2 exists to *independently confirm* Tier 1 — if they disagree, the dataset
is rejected rather than silently wrong.

Fetching Tier 2 as well doubles requests (~180/dataset) but is worth it as an
integrity check. Make it configurable.

---

## 6. Pipeline stages

```
fetch → cache raw → decode → derive M1 candles → validate → cross-check → write CSV → manifest
```

1. **Plan** — enumerate required UTC days for the requested range.
2. **Fetch** — throttled, retried, resumable. Raw `.bi5` written to a cache dir
   keyed by URL. Cached files are never re-fetched.
3. **Decode** — LZMA-alone → fixed-size records → typed ticks.
4. **Derive candles** — bucket ticks by `ms // 60000` into UTC minute candles.
   Bid candles from bid prices + bid volume; ask likewise. Emit spread.
5. **Validate** — the Phase 1 rule set: OHLC invariants, positive prices,
   nonnegative volume, ordering, duplicates, spikes.
6. **Cross-check** — compare against Tier 2 candle file for the same day.
   Any OHLC mismatch on a shared minute → reject the day, report it.
7. **Write CSV** — deterministic column order, UTC ISO-8601 timestamps.
8. **Manifest** — Phase 1 Step 1.7 fields + SHA-256 content hash.

### Minute aggregation rule (must be deterministic)

- `open` = bid of first tick in the minute (by ms)
- `high` = max bid
- `low` = min bid
- `close` = bid of last tick
- `tickVolume` = **count of ticks** in the minute

Note: Phase 1's `HistoricalCandle.tickVolume` is an **`Int`** count, whereas
Dukascopy's volume is a fractional float in millions of units. I therefore
populate `tickVolume` with the **tick count** (an honest integer) and keep
Dukascopy's fractional volume in a separate CSV column for optional later use.
Recording the float into an `Int` field would silently lose the data.

Minutes with no ticks are **omitted**, not emitted as flat candles. Gap
classification then reports them (Phase 1 Step 1.6).

---

## 7. Politeness and rate-limit design

Non-negotiable, because I measured how brittle this is:

- Serial requests only. No concurrency against `datafeed`.
- Configurable delay, **default 2.0 s**, jittered ±50%.
- Retry on `429`/`503`/timeouts with exponential backoff + jitter, capped.
- **Circuit breaker:** N consecutive failures → pause and abort with a clear
  message, rather than hammering.
- Honour `Retry-After` if present.
- Full resumability: re-running continues from cache, never re-fetches.
- Never retry on `404` (a genuinely missing file) — record as "no data".

---

## 8. Output format

One CSV per dataset, plus a manifest JSON.

```csv
timestamp_utc,open,high,low,close,tick_volume,spread,dukascopy_volume
2024-01-15T00:00:00Z,2047.525,2047.675,2046.815,2046.935,91,0.300,0.013440
```

- Prices in USD with 3 decimals (XAUUSD's true precision) — no lossy rounding.
- Phase 1 converts to integer cents deterministically. XAUUSD's 3 decimals
  exceed cent precision, so Phase 1's documented precision-reduction policy
  (Step 1.5) applies. **Decision needed:** see §11 Q1.
- Timestamps are UTC, `Z`-suffixed, unambiguous. Never timezone-less.
- Column order fixed; header always present.

Synthetic fixtures (Phase 1 Step 1.9) are written to a **separate directory**
with `synthetic_` prefixes and never mixed into historical output.

---

## 9. Gap classification

Reported per day, not silently filled:

- **Expected weekend closure** — Sat/Sun in UTC.
- **Known holiday** — explicit table (New Year, Christmas, Good Friday, etc.).
- **Unexpected missing minutes** — everything else, reported with times.

Real example found: 2024-01-15 (a Tuesday) has 212 missing minutes, including
`18:21`, `18:38`, and `19:30–19:35`. These are genuine source gaps in the
quiet pre-Asia window. The pipeline reports them; it does not invent candles.

---

## 10. Verification and tests

Correctness is proven, not asserted:

1. **Format tests** — decode a known-good cached payload; assert exact record
   counts and a known first/last value.
2. **Cross-source test** — Tier 1 vs Tier 2 must match on all shared minutes.
   This is the strongest guard against the `O,C,L,H` trap.
3. **Padding test** — assert zero-volume flat rows are dropped.
4. **Determinism test** — same input twice → byte-identical CSV and identical
   SHA-256.
5. **Rate-limit test** — simulated `429`/`503` → backoff, no hammering, resume.
6. **Gap test** — weekend and holiday produce reports, not errors.
7. **Golden fixture** — one small day checked into `tools/tests/` with expected
   output, so regressions are caught without network access.

Note: `datafeed` is a third-party service, so tests must run against **cached
fixtures**, not live requests. Only the manual fetch command touches the network.

---

## 11. Open questions — I need answers before coding

1. **XAUUSD precision vs integer cents.** Gold trades at 3 decimals
   (e.g. `2047.525`), but Phase 1's `HistoricalCandle` stores **integer cents**.
   Converting 3-decimal gold to cents loses the third decimal. Three options:
   (a) store **tenths of a cent** (`priceCents = round(usd * 1000)`), which
   preserves Dukascopy precision exactly; (b) round to whole cents and accept
   the loss; (c) keep Phase 1 as-is and document the reduction.
   **My recommendation: (a)**, because it is lossless and still integer. But it
   changes Phase 1's field semantics, so I need your call.

2. **`tickVolume` semantics** — confirm you accept "tick count" for the `Int`
   field, with Dukascopy's fractional volume kept in a separate column. (§6)

3. **Fetcher source** — Option A (polite `datafeed`), Option B (S3 Requester
   Pays), or both? (§3)

4. **Which datasets?** The plan wants *multiple independent ~3-month* datasets
   (AGENTS: never combined). Which date ranges, and how many? This drives
   request counts and the runtime budget.

5. **Cross-check tier** — include Tier 2 verification (≈2× requests) or
   Tier 1 only for speed?

---

## 12. Proposed deliverables

```
tools/
  dukascopy/
    fetch.py          # CLI entry: fetch + cache raw .bi5
    decode.py         # bi5 → ticks; bi5 → candles (both formats)
    candles.py        # deterministic tick → M1 aggregation
    validate.py       # Phase 1 rule set + gap classification
    crosscheck.py     # Tier 1 vs Tier 2 agreement
    manifest.py       # manifest + SHA-256
    ratelimit.py      # throttle, backoff, circuit breaker
    config.py         # instrument table (XAUUSD point value, etc.)
    tests/
      fixtures/       # cached .bi5 + golden CSVs
      test_*.py
    README.md         # usage, caveats, ToS note
  README.md
```

Instrument scaling lives in an explicit table. XAUUSD = 1000, verified. Unknown
instruments **fail loudly** rather than defaulting to a wrong scale.

---

## 13. Sequencing

1. `decode.py` + format tests against cached fixtures (no network).
2. `candles.py` + determinism tests.
3. `ratelimit.py` + backoff tests.
4. `fetch.py` with resume + cache.
5. `validate.py`, gap classification.
6. `crosscheck.py`.
7. `manifest.py`.
8. Fetch one real 3-month dataset end-to-end; hand to Phase 1.

Steps 1–3 are fully testable offline. Only step 8 touches the network.

---

## 14. What this plan does NOT do

- It does not fabricate data. Every candle traces to a real Dukascopy tick.
- It does not modify the Android app. Phase 1 consumes the CSV.
- It does not add network permissions to the app.
- It does not silently fill gaps or invent weekend candles.
- It does not merge datasets.

---

## 15. Summary of the three traps this plan defends against

1. **`O,C,L,H` field order** in candle files — nonstandard, silently corrupts
   everything. Defended by cross-source verification.
2. **1440-row padding** — injects fake flat candles and fake weekend days.
   Defended by zero-volume+flat filtering.
3. **`point_value=100000`** from Dukascopy's own sample code — 100× price error
   for XAUUSD. Defended by the verified instrument table and explicit failure on
   unknown instruments.

---

**Awaiting approval and answers to §11 before writing code.**
