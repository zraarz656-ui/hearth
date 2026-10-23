# ADR 0002 — Intraminute path model

Status: **Accepted**
Date: 2026-10-21
Supersedes: nothing
Affects: Phase 4 (path engine), Phase 5 (execution), Phase 8 (chart rendering),
Phase 9 (crosshair), Phase 12 (time warp)

## Context

A one-minute candle gives four prices — open, high, low, close — and no
information about the order in which they occurred. Product rule 2.4 requires
the chart and the trading engine to consume **the same** simulated path, so an
order that the chart appears to have reached is an order that fills. This ADR
fixes that path and states plainly what it does and does not claim to know.

## Decision

### Shape (Step 4.1)

| Candle | Order |
| --- | --- |
| Bullish (`close > open`) | open → low → high → close |
| Bearish (`close < open`) | open → high → low → close |
| Doji (`close == open`) | open → low → high → close |

The doji rule is a **tie-break, not knowledge.** When `open == close`, OHLC
cannot distinguish a dip-then-rally from a rally-then-dip; no rule can be
correct. The rule is fixed so the chart and execution agree, and it matches the
bullish shape so a doji is not a special case in the renderer.

### Timing (Step 4.2)

Waypoints sit at **0, 15,000, 30,000 and 60,000 ms**.

### Arithmetic (Step 4.3)

Prices are `Int` thousandths of a USD; all path arithmetic is integer. The only
rounding is in `priceAt`, half away from zero, applied to the exact rational
intermediate. There is exactly one implementation, so the chart and execution
agree by construction rather than by two implementations happening to match.

### Crossing (Step 4.5)

`firstTouchElapsedMs` **solves** the crossing

```
t* = t0 + (t1 - t0) * (T - p0) / (p1 - p0)
```

in integers and rounds *up* to the first whole millisecond reached. It is not a
sampled search, so a level touched and left between two 1 Hz ticks is still
found. `firstTouchFrom` additionally respects a start offset, so an order placed
mid-minute is not filled by a crossing that happened before it existed.

### Gaps (Step 4.6)

The engine never interpolates across candles. A break is emitted as a
`MarketGap` carrying the previous close, the next open and a reason, and
`MarketPathWindow.priceAt` returns **null** inside it. A level "reached" by
jumping a weekend is not a fill.

### Identity (Step 4.7)

A path is a pure function of `PathIdentity`: dataset id, candle timestamp, OHLC
and `pathModelVersion`. `PathFingerprint` is FNV-1a over those fields, so it does
not depend on `hashCode()`.

## The honest limitation

**Even quarter-minute spacing is an assumption, not a measurement.**

It means the high of a bullish minute is *always* reached at 30,000 ms and the
low at 15,000 ms, whatever the candle's shape. Real intraminute timing is not
like that. This is not cosmetic: the relative timing of the high and the low
decides which of a stop-loss and a take-profit is hit first, and that is a trade
outcome.

Three consequences, stated rather than hidden:

1. **Which level is hit first is a property of this model, not of the market.**
   On a minute whose range covers both a stop and a target, this engine will
   always resolve the order the same way. A real market would sometimes resolve
   it differently.
2. **It cannot be validated against the stored data.** The dataset is
   1-minute OHLC, so there is no intraminute ground truth to check against.
   The model is not wrong so much as **unfalsifiable** at this resolution.
3. **Therefore it must not be presented as realistic tick behaviour.** It is a
   deterministic replay of the OHLC envelope.

Improving it means weighting waypoint times by price distance travelled, or
using real tick data where available. Either change alters path shape, so it
requires bumping `PathModelVersion` — which is why the version exists.

## Rejected alternatives

**Randomised waypoints per candle.** Rejected. Product rule 2.4 and the plan's
determinism requirement mean the same candle must always produce the same path.
Randomness would also make a bug report irreproducible.

**Interpolate linearly from open straight to close, ignoring high and low.**
Rejected. It discards the two prices that determine whether a stop was hit.

**Place the high and low at a fixed fraction of the minute (for example always
in the middle).** Rejected as no better than the chosen rule, and harder to
explain.

**Derive waypoint timing from a hash of the candle.** Rejected. It is
deterministic and varies per candle, which is superficially attractive, but it
is arbitrary noise dressed as information: it would make some candles resolve
stop-before-target and others the reverse with no basis in the data. A uniform
documented assumption is more honest than a pseudo-random one.

**Use tick data for intraminute paths.** Not available offline for the whole
dataset, and the plan's dataset format is 1-minute OHLC. Recorded as a possible
future improvement, not a current option.

## Consequence for Phase 5

Phase 5 must consume `MarketPathWindow` rather than re-deriving a path. If
execution ever computes price from OHLC itself, the two paths can drift and
product rule 2.4 is broken silently.
