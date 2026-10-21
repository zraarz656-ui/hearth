"""Cross-check tick-derived candles against Dukascopy's own candle files.

This is the primary defence against the nonstandard O,C,L,H candle field
order. If the field order were misread, prices would disagree on every
shared minute. Two independent sources agreeing is much stronger
evidence than either alone.

Dukascopy's candle files are padded to 1440 rows with flat zero-volume
candles, so only minutes present in both sources are compared.
"""

from __future__ import annotations

from dataclasses import dataclass, field

from .candles import MINUTE_MS, MinuteCandle
from .decode import RawCandle


@dataclass
class CrossCheckResult:
    day: str
    compared: int = 0
    mismatches: list[str] = field(default_factory=list)
    padding_rows: int = 0

    @property
    def ok(self) -> bool:
        return not self.mismatches and self.compared > 0


def cross_check(
    day_label: str,
    derived: list[MinuteCandle],
    published: list[RawCandle],
    day_start_ms: int,
) -> CrossCheckResult:
    result = CrossCheckResult(day=day_label)

    published_by_ts: dict[int, RawCandle] = {}
    for candle in published:
        if candle.is_flat_and_empty:
            result.padding_rows += 1
            continue
        published_by_ts[day_start_ms + candle.sec_of_day * 1000] = candle

    for candle in derived:
        published_candle = published_by_ts.get(candle.timestamp_utc_ms)
        if published_candle is None:
            continue
        result.compared += 1
        actual = (
            candle.open_raw,
            candle.high_raw,
            candle.low_raw,
            candle.close_raw,
        )
        expected = (
            published_candle.open_raw,
            published_candle.high_raw,
            published_candle.low_raw,
            published_candle.close_raw,
        )
        if actual != expected:
            result.mismatches.append(
                f"{candle.utc.isoformat()}: ticks O/H/L/C={actual} "
                f"published O/H/L/C={expected}"
            )

    return result
