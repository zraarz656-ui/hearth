"""Validation and gap classification for derived candles.

Implements the Phase 1 rule set (development plan Steps 1.3 and 1.6) and
classifies missing minutes instead of treating every gap as normal.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, timedelta

from .candles import MINUTE_MS, MinuteCandle

MINUTES_PER_DAY = 1440


@dataclass
class DayReport:
    day: date
    candle_count: int
    expected_minutes: int
    missing_minutes: list[int] = field(default_factory=list)
    violations: list[str] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return not self.violations


def check_candle_invariants(candle: MinuteCandle) -> list[str]:
    """Return a list of invariant violations for a single candle.

    Phase 1 Step 1.3 requires High >= Open, High >= Close, Low <= Open,
    Low <= Close, High >= Low, positive prices, and nonnegative volume.
    """
    problems: list[str] = []
    if min(candle.open_raw, candle.high_raw, candle.low_raw, candle.close_raw) <= 0:
        problems.append("non-positive price")
    if candle.high_raw < candle.open_raw:
        problems.append("high < open")
    if candle.high_raw < candle.close_raw:
        problems.append("high < close")
    if candle.low_raw > candle.open_raw:
        problems.append("low > open")
    if candle.low_raw > candle.close_raw:
        problems.append("low > close")
    if candle.high_raw < candle.low_raw:
        problems.append("high < low")
    if candle.tick_volume < 0:
        problems.append("negative volume")
    if candle.spread_points < 0:
        problems.append("negative spread")
    return problems


def validate_series(candles: list[MinuteCandle], day: date) -> DayReport:
    """Validate one day's candles: ordering, duplicates, invariants, gaps."""
    report = DayReport(day=day, candle_count=len(candles), expected_minutes=MINUTES_PER_DAY)

    for previous, current in zip(candles, candles[1:]):
        if current.timestamp_utc_ms <= previous.timestamp_utc_ms:
            report.violations.append(
                f"timestamps not strictly increasing at {current.utc.isoformat()}"
            )

    seen: set[int] = set()
    for candle in candles:
        if candle.timestamp_utc_ms in seen:
            report.violations.append(f"duplicate timestamp {candle.utc.isoformat()}")
        seen.add(candle.timestamp_utc_ms)
        for problem in check_candle_invariants(candle):
            report.violations.append(f"{candle.utc.isoformat()}: {problem}")

    report.missing_minutes = find_missing_minutes(candles)
    return report


def find_missing_minutes(candles: list[MinuteCandle]) -> list[int]:
    """Minute-of-day indices with no candle between the first and last."""
    if not candles:
        return []
    first = candles[0].timestamp_utc_ms
    last = candles[-1].timestamp_utc_ms
    present = {c.timestamp_utc_ms for c in candles}
    return [
        (ts - first) // MINUTE_MS
        for ts in range(first, last + MINUTE_MS, MINUTE_MS)
        if ts not in present
    ]


# Fixed-date holidays. Movable feasts (Good Friday, Easter Monday) are
# listed explicitly per year rather than computed, so behaviour is
# auditable. These are reported for review, never auto-filled.
FIXED_HOLIDAYS = {
    (1, 1): "New Year's Day",
    (12, 25): "Christmas Day",
    (12, 26): "Boxing Day",
}


def classify_day(day: date, candle_count: int, missing: list[int]) -> str:
    """Classify a day's data availability.

    Never reports a day as normally closed merely because minutes are
    missing; missing minutes inside an active day are reported as
    unexpected.
    """
    if day.weekday() == 5 and candle_count == 0:
        return "weekend_closure (Saturday, no ticks)"
    if day.weekday() == 6 and candle_count == 0:
        return "weekend_closure (Sunday, no ticks)"
    if candle_count == 0:
        holiday = FIXED_HOLIDAYS.get((day.month, day.day))
        if holiday:
            return f"likely_holiday ({holiday}, no ticks)"
        return "no_data (non-weekend, no ticks: investigate)"
    if missing:
        return f"active_with_gaps ({len(missing)} missing minutes)"
    return "complete"
