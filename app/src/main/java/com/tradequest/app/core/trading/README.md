# Trading Execution Engine (Phase 5)

`core/trading` turns a market path into fills, positions and closed trades. It is
pure Kotlin: no Android, no Compose, no Room. It reads price only through
`MarketPathWindow` and it never consults a wall clock.

## The one rule that shapes everything

**The engine must produce the same result every time it replays the same path.**
Product rule 2.4 requires it, and the whole app depends on it: the chart, the
equity curve and the challenge verdict are all derived from this state, so if a
replay could differ from itself none of them could be trusted.

Two things follow from that, and they explain most of the design:

1. **Price comes from one place.** The engine asks `MarketPathWindow` for a price
   at an instant. It never re-derives a path and never interpolates on its own.
   The chart asks the same object, so they cannot disagree.
2. **Nothing depends on wall-clock time or iteration order.** All comparisons
   fall through to a stable tie-break (trigger kind, then id), so a change in
   list order cannot change an outcome.

## How a replay advances

`advanceTo(instant)` walks the path **forward, segment by segment**, where a
segment ends at the next waypoint, the next candle, or the edge of a gap. Within
each segment it does things in this order:

1. **Gap** — if the segment is inside a break, only the post-gap price is usable.
2. **Pending orders** — a resting order fills when its level is crossed.
3. **Stops and targets** — against the stop as it stands when the segment began.
4. **Protective rules** — trailing and breakeven, applied to the price reached.

### Why that order, and not "all fills, then all risk checks"

The plan forbids the two-pass shape, and the reason is concrete: an order can
fill and its position can be stopped out **inside one minute**. A two-pass
engine would either report the position open after it was already closed, or
close it against price movement that happened before it existed.

There is a second, subtler reason for step 4 coming *after* step 3. Price is
linear between two waypoints, so a segment is monotonic. If a trailing stop were
advanced before the exit check, a rising segment would move a long's stop up to
just under its own end price — and the exit check would then find that stop
*inside the segment that created it*, closing the position against a price it
had only just reached. Ratcheting afterwards means a new level first applies from
the next segment, which is the earliest instant at which it was genuinely in
force.

## Gaps (Step 5.11)

Between two candles there is no path. A trigger whose level sits inside the jump
was crossed at no observable instant, and the plan says not to assume a fill at
an unavailable price. `GapExecution` splits triggers by **which side the user is
protected on**:

| Kind | Examples | Gap fill price | Why |
| --- | --- | --- | --- |
| Stop-like | stop order, stop-loss | first price available after the gap | the market moved against the user; hiding it would flatter every backtest |
| Limit-like | limit order, take-profit | the trigger level itself | a resting order at that price was genuinely crossed; the worse post-gap price would penalise the user for a gap that helped them |

A gap fill never adds slippage. The gap price already contains the adverse move;
slipping as well would charge twice for the same event.

## Spread and price sides

Historical price is the **bid**. `ask = bid + spread`. A long enters at the ask
and exits at the bid; a short enters at the bid and exits at the ask. The spread
is therefore paid once per round trip, and the engine charges it exactly once
because entries and exits each take their own side.

Stops and targets are stored as **exit-side** levels. For a long, that is the bid
and needs no adjustment. For a short the exit side is the ask, so a stop 50 pips
above the entry sits 50 pips above the entry *on the ask*. `TriggerDerivation`
shifts it into a bid level once, when it builds the trigger. Getting this wrong
would make a short's stop fire one spread early and its target one spread late —
the second in the user's favour, which is the kind of error that makes a backtest
look better than reality.

## Money

All money is `Long` micro-dollars (`MoneyScale`). The plan's own example, a $30
move at one lot, is 3e9 micro-dollars — past `Int32.MAX_VALUE` — so every
intermediate is `Long`. This matters more than it looks: a 32-bit intermediate
overflows at about $21 on one lot, which is a normal day's move on gold.

Commission is $7 per lot round trip, charged per side. The entry side leaves the
balance when the position opens, so a position that never closes still costs
something. On a partial close the entry commission is **apportioned** by volume,
so the parts together charge exactly the original amount.

## What is deliberately absent

Margin, equity, stop-out and daily-loss limits are Steps 6.5–6.9 and belong to
Phase 6. `balanceMicros` moves only by realised PnL and commission, which is this
phase's responsibility. `AccountState.equityMicros` is computed, not enforced.

## Files

| File | Responsibility |
| --- | --- |
| `TradingEngine.kt` | Commands, the forward replay walk, fills, protective rules |
| `TradingTypes.kt` | Domain models: order, execution, position, closed trade, commands |
| `ContractSpec.kt` | XAUUSD contract rules and pip conversion |
| `SpreadModel.kt` | Spread regimes and their windows |
| `SpreadTimeline.kt` | The instants at which the spread changes |
| `SlippageModel.kt` | Deterministic, seeded adverse slippage |
| `OrderTrigger.kt` | Pure derivation of bid triggers from orders and positions |
| `GapExecution.kt` | How a trigger fills across a closure |
| `Modification.kt` | Keep / Set / Clear for a field a command may or may not change |
| `../accounting/MoneyMath.kt` | Micro-dollar scale and PnL arithmetic |

## Tests

`app/src/test/java/com/tradequest/app/core/trading/` holds four suites, 126 tests:

- `PricingRulesTest` — contract, bid/ask, spread regimes, commission, slippage.
- `OrderExecutionTest` — order types, event ordering, same-candle entry/exit, no look-back fills, determinism.
- `PositionFeaturesTest` — partial close, trailing stops, breakeven, stop ratcheting.
- `GapExecutionTest` — stop-like vs limit-like fills across a closure.

Run them with:

```
./gradlew :app:testDebugUnitTest --tests "com.tradequest.app.core.trading.*"
```
