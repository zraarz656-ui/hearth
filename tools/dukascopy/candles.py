"""Deterministic aggregation of ticks into 1-minute candles.

Aggregation rules (fixed, so results are reproducible):

    open        bid of the first tick in the minute
    high        maximum bid
    low         minimum bid
    close       bid of the last tick in the minute
    tick_volume count of ticks in the minute
    spread      mean (ask - bid) in raw points
    volume      sum of bid volumes, in Dukascopy's fractional units

Minutes containing no ticks are omitted rather than emitted as flat
candles. Inventing a candle for a minute with no ticks would fabricate
market data, which the project forbids; gaps are reported separately by
validate.py.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone

from .decode import Tick

MINUTE_MS = 60_000


@dataclass(frozen=True, slots=True)
class MinuteCandle:
    """A 1-minute candle derived from ticks.

    Prices are raw integer points so no floating-point rounding occurs.
    Use `to_usd` to obtain display values.
    """

    timestamp_utc_ms: int
    open_raw: int
    high_raw: int
    low_raw: int
    close_raw: int
    tick_volume: int
    spread_points: int
    dukascopy_volume: float

    @property
    def utc(self) -> datetime:
        return datetime.fromtimestamp(self.timestamp_utc_ms / 1000, tz=timezone.utc)


def day_start_utc_ms(year: int, month: int, day: int) -> int:
    start = datetime(year, month, day, tzinfo=timezone.utc)
    return int(start.timestamp() * 1000)


def aggregate_minutes(
    ticks: list[Tick],
    day_start_ms: int,
    point_value: int,
) -> list[MinuteCandle]:
    """Group ticks into 1-minute candles, ordered by timestamp."""
    if not ticks:
        return []

    buckets: dict[int, dict] = {}
    for tick in ticks:
        minute_index = tick.ms_of_day // MINUTE_MS
        bucket = buckets.get(minute_index)
        if bucket is None:
            buckets[minute_index] = {
                "open": tick.bid_raw,
                "high": tick.bid_raw,
                "low": tick.bid_raw,
                "close": tick.bid_raw,
                "count": 1,
                "spread_total": tick.ask_raw - tick.bid_raw,
                "volume": tick.bid_volume,
            }
        else:
            if tick.bid_raw > bucket["high"]:
                bucket["high"] = tick.bid_raw
            if tick.bid_raw < bucket["low"]:
                bucket["low"] = tick.bid_raw
            bucket["close"] = tick.bid_raw
            bucket["count"] += 1
            bucket["spread_total"] += tick.ask_raw - tick.bid_raw
            bucket["volume"] += tick.bid_volume

    candles: list[MinuteCandle] = []
    for minute_index in sorted(buckets):
        bucket = buckets[minute_index]
        count = bucket["count"]
        candles.append(
            MinuteCandle(
                timestamp_utc_ms=day_start_ms + minute_index * MINUTE_MS,
                open_raw=bucket["open"],
                high_raw=bucket["high"],
                low_raw=bucket["low"],
                close_raw=bucket["close"],
                tick_volume=count,
                spread_points=_round_half_up(bucket["spread_total"] / count),
                dukascopy_volume=bucket["volume"],
            )
        )
    return candles


def _round_half_up(value: float) -> int:
    """Deterministic rounding, independent of Python's banker's rounding."""
    return int(value + 0.5) if value >= 0 else -int(-value + 0.5)
