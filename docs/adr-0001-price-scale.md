# ADR 0001 — Price storage scale for XAUUSD

Status: **Proposed** (needs your sign-off; affects Phase 1 field semantics)
Date: 2026-10-21
Supersedes: nothing
Affects: Phase 1 Step 1.1 (`HistoricalCandle`), Phase 4.1 (financial units),
Phase 5 (spread/trading), Phase 6 (PnL/equity), Phase 8 (chart buffers)

## Context

The development plan specifies two things that conflict for gold:

- **Step 1.1** — `HistoricalCandle` stores `openCents`, `highCents`,
  `lowCents`, `closeCents` as `Int`: prices in **integer cents** (2 decimals).
- **Step 8.2** — "Authoritative market data remains integer cents."

But XAUUSD on Dukascopy carries **three** decimals. Measured over one real
trading day (2024-01-15, 87,293 ticks, 174,586 price samples):

```
GCD of (price - min) in 1/1000 USD units : 1
  -> the finest step actually present is 0.001 USD, not 0.005 or 0.010
prices not a multiple of 0.005 USD : 35,474 of 174,586 (20.3%)
last-digit distribution (thousandths):
  ...2  9.95%   ...4  1.21%   ...5  79.68%   ...6  1.24%   ...8  7.92%
```

So the third decimal is real data, not noise or padding. 20% of quotes are
not representable as whole cents at all.

### What rounding to cents actually costs

Measured across 1,228 real 1-minute bars:

| Effect | Result |
| --- | --- |
| Bars whose high moves | 1,228 / 1,228 (100%) |
| Bars whose low moves | 1,228 / 1,228 (100%) |
| Mean error per price | 0.0045 USD |
| Worst-case error per round trip (1 lot) | $1.00 |
| Mean error per round trip (1 lot) | $0.91 |
| Share of one $0.30 spread | 3.3% worst case |

Rounding is monotonic, so **OHLC invariants survive** (`high >= open`, etc.)
and no bar's range collapses to zero on this day. The damage is subtler:

1. **Entry levels shift.** A trade entered at 2047.204 becomes 2047.200.
   Every stop-loss and take-profit placed off that level shifts by up to
   0.005 USD ($0.50 per lot), because stops are set in pips from entry.
2. **PnL is distorted by up to $1.00 per round trip per lot** — about 3.3%
   of the plan's normal $0.30 spread, which is the same order of magnitude
   as the spread effect the simulator is trying to model.
3. **The plan's own precision budget is inconsistent.** Step 4.1 stores
   account money as integer **micro**-dollars (6 decimals) while storing
   price as integer **cents** (2 decimals). Money carries four more decimals
   of precision than the price it is derived from. `PnL = f(price)`, so
   that extra precision is unrealisable — the information was already
   discarded at the price level.

## Decision

Store authoritative prices as **integer thousandths of USD**
(`priceUnits = round(usd * 1000)`), not whole cents.

Rationale:

- It is **lossless** for XAUUSD. Every Dukascopy quote round-trips exactly.
- It is still an **integer**, so the plan's prohibition on `Float`/`Double`
  for authoritative accounting is respected.
- It makes the price scale **consistent with money**: thousandths (3 dp) and
  micro-dollars (6 dp) are the same power-of-ten family, so PnL arithmetic
  needs no mixed-scale gymnastics.
- It removes the silent truncation that would otherwise happen at import,
  where nobody would notice until backtests disagreed with reality.

## Consequence: the multiplication must widen to Long

This is the part that matters, and it is exactly the overflow the plan warns
about in Step 4.1.

Step 4.1 stores lot quantity as integer **hundredths of a lot**. So a
notional product is `priceUnits * quantity`:

| Price scale | `priceUnits` at 2050 USD | Overflow above |
| --- | --- | --- |
| cents (×100) | 205,000 | ~104.8 lots |
| **thousandths (×1000)** | **2,050,000** | **~10.5 lots** |

Switching to thousandths cuts safe headroom from ~105 lots to **~10.5 lots**
if the product stays in `Int`. For a $10,000 account that ceiling is
reachable (1 lot of gold is $205,000 notional, so 1:500 leverage permits
~20 lots).

**Therefore the notional product must be computed in `Long` (Int64).** With
a 64-bit intermediate the ceiling becomes ~4.5×10¹⁰ lots, which no personal
simulator will approach. This is cheap, and Step 4.1 already permits it:
"Use checked arithmetic or safe intermediate representations where
multiplication could overflow."

Summary of the required pair:

- **Store** price as `Int` thousandths of USD — safe, max 2,050,000.
- **Multiply** `priceUnits * quantity` as `Long` — mandatory at this scale.

## Rejected alternatives

**Keep cents, accept the loss.** Rejected. It discards real data, shifts
every stop/TP level, and costs up to $1.00 per lot round trip — comparable
to the spread being simulated. For a *simulator* whose purpose is
realistic fills, that is the wrong trade.

**Use `Double` for price.** Rejected. Step 4.1 forbids it for authoritative
accounting, correctly: binary floating point cannot represent 0.001 exactly,
so repeated PnL accumulation drifts and equality comparisons misbehave.

**Store price as `Float` in the chart only, cents in the model.** Rejected.
Two scales for the same quantity is how silent unit bugs happen. Store one
authoritative scale; convert only at the display boundary.

**Widen to Int64 for storage too.** Unnecessary. Int32 holds any plausible
gold price in thousandths with enormous margin (max ~2.1 million USD).

## Naming

The existing field names `openCents`/`highCents`/`lowCents`/`closeCents`
would become misleading. Rename to `openUnits`/`highUnits`/`lowUnits`/
`closeUnits` with a single documented constant:

```kotlin
/** Price scale: integer thousandths of a US dollar. XAUUSD uses 3 decimals. */
const val PRICE_UNITS_PER_USD = 1000
```

Use one constant everywhere. Do not scatter `1000` or `100` literals.

## Impact on the Dukascopy tool

**None.** The fetcher already writes full 3-decimal USD
(`2047.525`), deliberately without pre-rounding. The loss would occur at
Phase 1 import, which is why this ADR exists. No tool change needed.

## Acceptance check for Phase 1

1. A price of `2047.204` imported from CSV stores as `2047204` and round-trips
   back to `2047.204` exactly.
2. A test asserts that the third decimal is preserved for a value ending in
   `4` or `6` (the digits cents cannot represent).
3. A notional computation with `priceUnits = 2_050_000` and a quantity of
   20 lots does **not** overflow, proving the `Long` intermediate.
4. A test asserts OHLC invariants still hold after storage.
