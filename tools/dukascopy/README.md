# Dukascopy dataset fetcher

Standalone tool that downloads authentic Dukascopy XAUUSD history and
converts it into 1-minute CSV datasets for the TradeQuest Phase 1 import
pipeline.

This is a **PC-side data-preparation tool**. It is not part of the Android
app. The app is offline-first and imports CSV files, so it needs no
network code and no `INTERNET` permission.

- Python 3.10+, standard library only. No dependencies, no build step.

## Quick start

```bash
# Convert an already-cached range with no network access
python3 -m tools.dukascopy.fetch \
    --symbol XAUUSD \
    --start 2024-01-01 --end 2024-03-31 \
    --out datasets/ \
    --delay 2.0 \
    --cross-check
```

Output:

```
datasets/XAUUSD_2024-01-01_2024-03-31/
    XAUUSD_2024-01-01_2024-03-31.csv
    XAUUSD_2024-01-01_2024-03-31.manifest.json
datasets/.cache/           # raw .bi5 payloads, safe to keep
```

## Options

| Flag | Default | Meaning |
| --- | --- | --- |
| `--symbol` | `XAUUSD` | Instrument. Unlisted symbols fail loudly. |
| `--start` / `--end` | required | Inclusive UTC date range, `YYYY-MM-DD`. |
| `--out` | `datasets` | Output directory. |
| `--cache` | `<out>/.cache` | Raw payload cache. |
| `--delay` | `2.0` | Seconds between requests, jittered ±50%. |
| `--cross-check` | off | Also fetch published candles and verify agreement. Doubles requests. |
| `--hourly-fallback` | off | Rebuild a day from 24 hourly files if the daily file is unusable. |

## Why the code looks the way it does

Every format decision below was established by decoding real payloads, not
from documentation. Three of them fail **silently** if you get them wrong,
which is why each has a test.

### Candle files store `O, C, L, H`

Not the conventional `O, H, L, C`. Decoding in the familiar order produces
plausible-looking but wrong prices. `test_field_order_is_open_close_low_high`
pins this down, and `--cross-check` verifies the conversion against
Dukascopy's own candle files on every shared minute.

### Candle files are padded to 1440 rows

Closed periods are filled with flat, zero-volume rows. A full Saturday
returns 1440 rows of fabricated data. On a real trading day, 212 of 1440
rows were padding. Padding rows are detected by `volume == 0` combined with
`O == H == L == C` and excluded. Minutes with no ticks are **omitted**, never
invented.

### XAUUSD scales by 1000, not 100000

Dukascopy's own published sample decoder returns `100000` for `XAUUSD`
**without warning**, because `XAUUSD` passes its six-alphabetic-character
sanity check. That is a silent 100× price error. The verified value is
`1000`. `config.py` raises on any instrument without a verified scale rather
than guessing.

### The service throttles hard

Measured: 13 of 14 consecutive requests failed with `429`/`503` at 1-second
spacing, and rejections often returned in ~0.35s, so naive retry loops burn
the allowance fast. Requests are therefore serial, jittered, backed off, and
guarded by a circuit breaker.

### Fetch one file per day, not per hour

A daily tick file is one request for a full day (87,293 ticks). Fetching
hourly would be 24× the requests against a service that is already
throttling.

## Response semantics

| Response | Meaning | Behaviour |
| --- | --- | --- |
| `200` + data | Real ticks | Decoded and used |
| `200` + 0 bytes | Market closed | Recorded as "no data", not an error |
| `404` | Date out of range | Recorded, never retried |

Do **not** use the candle files to detect market closure: they return 1440
padded rows even on a Saturday.

Holidays are not reliably empty either. Christmas 2024 returned 7,899 bytes
rather than nothing, so the tool reports per-day activity instead of assuming
a holiday is closed.

## Output columns

```csv
timestamp_utc,open,high,low,close,tick_volume,spread,dukascopy_volume
2024-01-15T00:00:00Z,2047.525,2047.675,2046.815,2046.935,91,0.300,0.013440
```

- Prices are USD with 3 decimals, the true XAUUSD precision. No lossy
  rounding to cents happens here.
- `tick_volume` is the **count of ticks** in the minute (an honest integer).
  Dukascopy's fractional volume is kept separately in `dukascopy_volume`,
  because storing it in an integer field would silently lose it.
- `spread` is the mean `(ask - bid)` in USD.
- Timestamps are UTC and explicit. Never timezone-less.

## Resumability

Every raw payload is cached by URL. Re-running the same command makes **zero
network requests** for data already held, so an interrupted run can be
resumed simply by repeating it. A day that fails to fetch is recorded in the
manifest and skipped rather than discarding the whole run; re-run to pick it
up.

## Validation and gaps

Implements the Phase 1 rule set: OHLC invariants, positive prices,
nonnegative volume, timestamp ordering, duplicate detection, and gap
classification. Days are classified as weekend closure, likely holiday, or
active with unexpected gaps. Gaps are reported with their times and never
filled.

Real example: 2024-01-15 (a Tuesday) has 212 missing minutes, including
`18:21`, `18:38` and `19:30–19:35`. These are genuine source gaps in the
quiet pre-Asia window.

## Determinism

The same input produces byte-identical CSV and an identical SHA-256 content
hash. Verified by running the conversion twice and comparing.

## Tests

```bash
python3 -m unittest discover -s tools/dukascopy/tests -t .
```

Tests run **offline** against captured payloads in `tests/fixtures/`. Only a
live fetch touches the network.

## Legal and fair-use note

Dukascopy's officially documented bulk route is the paid
`s3://cfg-public-proper-wallaby` bucket (eu-west-1, Requester Pays), which
requires an AWS account. The free `datafeed` endpoints used here are what
their web charts use.

**The terms governing automated bulk download from `datafeed` are not
documented clearly, and the aggressive throttling suggests it is not
intended for heavy automated pulling.** For a personal project at this scale
(about 90 requests per 3-month dataset) the load is small, but this is not
clearly sanctioned, and the endpoint may change or block at any time. Use
conservative delays, and prefer the S3 route if you need bulk history.
