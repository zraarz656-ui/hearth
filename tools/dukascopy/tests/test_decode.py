"""Golden-format tests for the .bi5 decoders.

These run offline against payloads captured from Dukascopy. They exist
mainly to lock down the nonstandard candle field order and the padding
behaviour, both of which fail silently if regressed.
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[3]))

from tools.dukascopy.config import get_instrument
from tools.dukascopy.decode import Bi5DecodeError, decode_candles, decode_ticks

FIXTURES = Path(__file__).parent / "fixtures"
DAILY_TICKS = FIXTURES / "xauusd_2024-01-15_daily_ticks.bi5"
HOUR_TICKS = FIXTURES / "xauusd_2024-01-15_10h_ticks.bi5"
BID_CANDLES = FIXTURES / "xauusd_2024-01-15_bid_candles_min_1.bi5"
SATURDAY_CANDLES = FIXTURES / "xauusd_2024-01-13_saturday_bid_candles_min_1.bi5"


class TestTickDecoding(unittest.TestCase):
    def test_daily_tick_count_is_exact(self):
        ticks = decode_ticks(DAILY_TICKS.read_bytes())
        self.assertEqual(len(ticks), 87293)

    def test_hourly_tick_count_is_exact(self):
        ticks = decode_ticks(HOUR_TICKS.read_bytes())
        self.assertEqual(len(ticks), 4022)

    def test_timestamps_within_a_day_and_ordered(self):
        ticks = decode_ticks(DAILY_TICKS.read_bytes())
        self.assertGreaterEqual(ticks[0].ms_of_day, 0)
        self.assertLess(ticks[-1].ms_of_day, 86_400_000)
        self.assertTrue(
            all(a.ms_of_day <= b.ms_of_day for a, b in zip(ticks, ticks[1:]))
        )

    def test_prices_scale_by_1000_for_xauusd(self):
        instrument = get_instrument("XAUUSD")
        ticks = decode_ticks(DAILY_TICKS.read_bytes())
        prices = [instrument.to_usd(t.bid_raw) for t in ticks]
        self.assertAlmostEqual(min(prices), 2045.675, places=3)
        self.assertAlmostEqual(max(prices), 2058.505, places=3)

    def test_ask_is_always_above_bid(self):
        ticks = decode_ticks(DAILY_TICKS.read_bytes())
        self.assertTrue(all(t.ask_raw > t.bid_raw for t in ticks))

    def test_empty_payload_yields_no_ticks(self):
        self.assertEqual(decode_ticks(b""), [])

    def test_misaligned_payload_is_rejected(self):
        with self.assertRaises(Bi5DecodeError):
            decode_ticks(b"not-a-valid-lzma-stream")

    def test_truncated_lzma_stream_is_rejected(self):
        raw = DAILY_TICKS.read_bytes()
        with self.assertRaises(Bi5DecodeError):
            decode_ticks(raw[: len(raw) // 2])


class TestCandleDecoding(unittest.TestCase):
    def test_field_order_is_open_close_low_high(self):
        """The decisive test for the nonstandard layout.

        If the record were read as O,H,L,C, the second and fourth values
        would not form a valid high/low relationship.
        """
        candles = decode_candles(BID_CANDLES.read_bytes())
        first = candles[0]
        self.assertEqual(first.sec_of_day, 0)
        self.assertEqual(first.open_raw, 2047525)
        self.assertEqual(first.close_raw, 2046935)
        self.assertEqual(first.low_raw, 2046815)
        self.assertEqual(first.high_raw, 2047675)

        for candle in candles:
            self.assertGreaterEqual(candle.high_raw, candle.open_raw)
            self.assertGreaterEqual(candle.high_raw, candle.close_raw)
            self.assertLessEqual(candle.low_raw, candle.open_raw)
            self.assertLessEqual(candle.low_raw, candle.close_raw)

    def test_always_padded_to_1440_rows(self):
        candles = decode_candles(BID_CANDLES.read_bytes())
        self.assertEqual(len(candles), 1440)

    def test_padding_rows_are_identified(self):
        candles = decode_candles(BID_CANDLES.read_bytes())
        padding = [c for c in candles if c.is_flat_and_empty]
        real = [c for c in candles if not c.is_flat_and_empty]
        self.assertEqual(len(padding), 212)
        self.assertEqual(len(real), 1228)

    def test_saturday_is_entirely_padding(self):
        """A closed day still returns 1440 rows, all fabricated."""
        candles = decode_candles(SATURDAY_CANDLES.read_bytes())
        self.assertEqual(len(candles), 1440)
        self.assertTrue(all(c.is_flat_and_empty for c in candles))

    def test_seconds_are_minute_aligned(self):
        candles = decode_candles(BID_CANDLES.read_bytes())
        self.assertTrue(all(c.sec_of_day % 60 == 0 for c in candles))


if __name__ == "__main__":
    unittest.main()
