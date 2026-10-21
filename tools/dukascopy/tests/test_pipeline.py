"""Tests for tick-to-minute aggregation, cross-checking, and validation."""

from __future__ import annotations

import sys
import unittest
from datetime import date
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[3]))

from tools.dukascopy.candles import aggregate_minutes, day_start_utc_ms
from tools.dukascopy.config import get_instrument
from tools.dukascopy.crosscheck import cross_check
from tools.dukascopy.decode import Tick, decode_candles, decode_ticks
from tools.dukascopy.ratelimit import CircuitBreakerTripped, RateLimitedFetcher
from tools.dukascopy.validate import classify_day, validate_series

FIXTURES = Path(__file__).parent / "fixtures"
DAILY_TICKS = FIXTURES / "xauusd_2024-01-15_daily_ticks.bi5"
BID_CANDLES = FIXTURES / "xauusd_2024-01-15_bid_candles_min_1.bi5"
DAY = date(2024, 1, 15)
DAY_START = day_start_utc_ms(2024, 1, 15)


def tick(ms, bid, ask=None, bvol=0.01, avol=0.01):
    return Tick(ms_of_day=ms, ask_raw=ask if ask is not None else bid + 300,
                bid_raw=bid, ask_volume=avol, bid_volume=bvol)


class TestAggregation(unittest.TestCase):
    def test_ohlc_uses_first_max_min_last(self):
        ticks = [tick(0, 1000), tick(1000, 1200), tick(2000, 900), tick(3000, 1100)]
        candle = aggregate_minutes(ticks, DAY_START, 1000)[0]
        self.assertEqual(candle.open_raw, 1000)
        self.assertEqual(candle.high_raw, 1200)
        self.assertEqual(candle.low_raw, 900)
        self.assertEqual(candle.close_raw, 1100)
        self.assertEqual(candle.tick_volume, 4)

    def test_ticks_split_into_separate_minutes(self):
        ticks = [tick(0, 1000), tick(59_999, 1100), tick(60_000, 2000)]
        candles = aggregate_minutes(ticks, DAY_START, 1000)
        self.assertEqual(len(candles), 2)
        self.assertEqual(candles[0].close_raw, 1100)
        self.assertEqual(candles[1].open_raw, 2000)
        self.assertEqual(candles[1].timestamp_utc_ms - candles[0].timestamp_utc_ms, 60_000)

    def test_minutes_without_ticks_are_omitted_not_filled(self):
        """Gaps must never be invented as flat candles."""
        ticks = [tick(0, 1000), tick(5 * 60_000, 1100)]
        candles = aggregate_minutes(ticks, DAY_START, 1000)
        self.assertEqual(len(candles), 2)

    def test_volume_sums_bid_side_only(self):
        ticks = [tick(0, 1000, bvol=0.5, avol=9.0), tick(1000, 1000, bvol=0.25, avol=9.0)]
        candle = aggregate_minutes(ticks, DAY_START, 1000)[0]
        self.assertAlmostEqual(candle.dukascopy_volume, 0.75, places=6)

    def test_spread_is_mean_of_ask_minus_bid(self):
        ticks = [tick(0, 1000, ask=1300), tick(1000, 1000, ask=1500)]
        candle = aggregate_minutes(ticks, DAY_START, 1000)[0]
        self.assertEqual(candle.spread_points, 400)

    def test_empty_input_produces_no_candles(self):
        self.assertEqual(aggregate_minutes([], DAY_START, 1000), [])


class TestCrossCheckAgainstPublishedCandles(unittest.TestCase):
    """The strongest guard against the O,C,L,H field-order trap."""

    def setUp(self):
        self.ticks = decode_ticks(DAILY_TICKS.read_bytes())
        self.instrument = get_instrument("XAUUSD")
        self.derived = aggregate_minutes(self.ticks, DAY_START, self.instrument.point_value)
        self.published = decode_candles(BID_CANDLES.read_bytes())

    def test_every_shared_minute_agrees(self):
        result = cross_check("2024-01-15", self.derived, self.published, DAY_START)
        self.assertTrue(result.ok, f"mismatches: {result.mismatches[:3]}")
        self.assertEqual(result.compared, 1228)

    def test_padding_rows_are_excluded_from_comparison(self):
        result = cross_check("2024-01-15", self.derived, self.published, DAY_START)
        self.assertEqual(result.padding_rows, 212)

    def test_corrupted_field_order_is_detected(self):
        """Swap high/low to simulate reading the record in the wrong order."""
        from tools.dukascopy.decode import RawCandle

        wrong = [
            RawCandle(c.sec_of_day, c.open_raw, c.high_raw, c.close_raw, c.low_raw, c.volume)
            for c in self.published
        ]
        result = cross_check("2024-01-15", self.derived, wrong, DAY_START)
        self.assertFalse(result.ok)


class TestValidation(unittest.TestCase):
    def test_gaps_are_reported(self):
        ticks = decode_ticks(DAILY_TICKS.read_bytes())
        candles = aggregate_minutes(ticks, DAY_START, 1000)
        report = validate_series(candles, DAY)
        self.assertTrue(report.ok, report.violations[:3])
        self.assertEqual(len(report.missing_minutes), 212)

    def test_inverted_candle_is_caught(self):
        from tools.dukascopy.candles import MinuteCandle

        bad = MinuteCandle(DAY_START, open_raw=100, high_raw=90, low_raw=95,
                           close_raw=100, tick_volume=1, spread_points=1,
                           dukascopy_volume=0.1)
        report = validate_series([bad], DAY)
        self.assertFalse(report.ok)
        self.assertTrue(any("high < open" in v for v in report.violations))

    def test_non_positive_price_is_caught(self):
        from tools.dukascopy.candles import MinuteCandle

        bad = MinuteCandle(DAY_START, 0, 0, 0, 0, 1, 1, 0.1)
        report = validate_series([bad], DAY)
        self.assertTrue(any("non-positive" in v for v in report.violations))

    def test_classification_of_weekend_and_gaps(self):
        self.assertIn("weekend", classify_day(date(2024, 1, 13), 0, []))
        self.assertIn("weekend", classify_day(date(2024, 1, 14), 0, []))
        self.assertIn("holiday", classify_day(date(2024, 12, 25), 0, []))
        self.assertIn("no_data", classify_day(date(2024, 1, 16), 0, []))
        self.assertIn("gaps", classify_day(DAY, 1228, [1, 2]))
        self.assertEqual(classify_day(DAY, 1440, []), "complete")


class TestRateLimiter(unittest.TestCase):
    def test_retries_then_succeeds(self):
        sleeps: list[float] = []
        attempts = {"n": 0}

        def fake_urlopen(request, timeout=None):
            attempts["n"] += 1
            if attempts["n"] < 3:
                import urllib.error

                raise urllib.error.HTTPError(request.full_url, 503, "busy", {}, None)
            return _FakeResponse(b"payload")

        fetcher = RateLimitedFetcher(sleep=sleeps.append, max_attempts=5)
        original = _patch_urlopen(fake_urlopen)
        try:
            result = fetcher.fetch("https://example.invalid/x")
        finally:
            _restore_urlopen(original)

        self.assertEqual(result.body, b"payload")
        self.assertEqual(attempts["n"], 3)
        self.assertTrue(sleeps)

    def test_404_is_not_retried(self):
        attempts = {"n": 0}

        def fake_urlopen(request, timeout=None):
            import urllib.error

            attempts["n"] += 1
            raise urllib.error.HTTPError(request.full_url, 404, "gone", {}, None)

        fetcher = RateLimitedFetcher(sleep=lambda _: None)
        original = _patch_urlopen(fake_urlopen)
        try:
            result = fetcher.fetch("https://example.invalid/missing")
        finally:
            _restore_urlopen(original)

        self.assertEqual(result.status, 404)
        self.assertEqual(attempts["n"], 1)

    def test_circuit_breaker_stops_after_repeated_failures(self):
        def always_fail(request, timeout=None):
            import urllib.error

            raise urllib.error.HTTPError(request.full_url, 503, "busy", {}, None)

        fetcher = RateLimitedFetcher(
            sleep=lambda _: None, max_attempts=1, circuit_break_after=2
        )
        original = _patch_urlopen(always_fail)
        try:
            with self.assertRaises(Exception):
                fetcher.fetch("https://example.invalid/a")
            with self.assertRaises(Exception):
                fetcher.fetch("https://example.invalid/b")
            with self.assertRaises(CircuitBreakerTripped):
                fetcher.fetch("https://example.invalid/c")
        finally:
            _restore_urlopen(original)


class _FakeResponse:
    def __init__(self, body: bytes, status: int = 200):
        self._body = body
        self.status = status

    def read(self) -> bytes:
        return self._body

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


def _patch_urlopen(fake):
    import urllib.request

    original = urllib.request.urlopen
    urllib.request.urlopen = fake
    return original


def _restore_urlopen(original):
    import urllib.request

    urllib.request.urlopen = original


if __name__ == "__main__":
    unittest.main()
