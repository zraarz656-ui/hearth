# core.market

Import, validation, aggregation and read access for historical XAUUSD data.
Owns `HistoricalCandle` and the dataset manifest. Never writes trading state.

Implemented in Phase 1. See `docs/architecture.md` for the dependency rules
that govern this package.

## Price scale

Prices are integer **thousandths of a US dollar**, not cents.
`PriceScale.UNITS_PER_USD = 1000`, so `2047.204` is stored as `2047204`.

Do not convert to cents, divide by 100, or add a `*Cents` field. See
`docs/adr-0001-price-scale.md`.

Distances such as stops and targets are stored in **pips**
(`PriceScale.UNITS_PER_PIP = 100`, 1 pip = 0.10 USD), never in raw price
units.

## Files

| File | Responsibility |
| --- | --- |
| `PriceScale.kt` | Scale constants, pip conversion, display formatting. |
| `PriceParser.kt` | Decimal text to price units via `BigDecimal`. Never `Double`. |
| `HistoricalCandle.kt` | The candle model and its derived flags. |
| `CsvImportConfig.kt` | Explicit CSV layout and timezone declaration. |
| `CsvImporter.kt` | The import pipeline. |
| `CandleValidator.kt` | The Step 1.3 rule set and the validation report. |
| `MarketClosureDetector.kt` | Weekend, holiday, and unexpected gap classification. |
| `DatasetManifest.kt` | Manifest fields and reproducible SHA-256 hashing. |

## Rules this package follows

- **Never parse a price through `Double`.** Binary rounding error would be
  introduced before the scaling multiply. Use `PriceParser`.
- **Never fill a gap.** Absence of data is reported, not invented. Filling
  would fabricate history.
- **Never assume a timestamp's timezone.** `CsvImportConfig.sourceTimeZone` has
  no default, so a caller must state it.
- **Never assume a CSV layout.** Delimiter, header, column order, timestamp
  format, and decimal separator are all explicit.
- **Never treat every gap as normal closure.** Only empty weekends are excused;
  an empty weekday is reported as `UNEXPECTED`.
- **Drop fabricated padding.** Dukascopy's flat, zero-volume rows for closed
  periods are removed. A flat row with volume is kept, because it is real.
