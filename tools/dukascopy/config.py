"""Instrument configuration and endpoint constants.

Point values are NOT guessed. Dukascopy's own published sample decoder
defaults non-JPY instruments to 100000, which is wrong for XAUUSD by a
factor of 100 and emits no warning because "XAUUSD" passes its
six-alphabetic-character sanity check. The value here (1000) was
established by decoding real payloads and matching them against
Dukascopy's published 1-minute candle files.
"""

from dataclasses import dataclass

DATAFEED_BASE = "https://datafeed.dukascopy.com/datafeed"

S3_BUCKET = "cfg-public-proper-wallaby"
S3_REGION = "eu-west-1"

TICK_RECORD_SIZE = 20
CANDLE_RECORD_SIZE = 24

USER_AGENT = "TradeQuest-DatasetFetcher/1.0 (personal historical research)"


@dataclass(frozen=True)
class Instrument:
    symbol: str
    point_value: int
    price_decimals: int
    description: str

    def to_usd(self, raw: int) -> float:
        return raw / self.point_value


# Every entry must be verified against real payloads before being added.
# An unlisted symbol raises, rather than silently assuming a scale.
INSTRUMENTS: dict[str, Instrument] = {
    "XAUUSD": Instrument(
        symbol="XAUUSD",
        point_value=1000,
        price_decimals=3,
        description="Gold vs US Dollar. Verified against published candles.",
    ),
}


class UnknownInstrumentError(KeyError):
    """Raised for an instrument with no verified point value."""


def get_instrument(symbol: str) -> Instrument:
    key = symbol.upper()
    try:
        return INSTRUMENTS[key]
    except KeyError:
        raise UnknownInstrumentError(
            f"No verified point value for {key!r}. Refusing to guess: an "
            f"incorrect scale produces wrong prices with no error. Verify "
            f"the scale against a real payload, then add it to "
            f"INSTRUMENTS in config.py."
        ) from None


def day_ticks_url(symbol: str, year: int, month: int, day: int) -> str:
    """One file = one full day of ticks. Month is zero-indexed."""
    return f"{DATAFEED_BASE}/{symbol}/{year}/{month - 1:02d}/{day:02d}_ticks.bi5"


def hour_ticks_url(symbol: str, year: int, month: int, day: int, hour: int) -> str:
    """One file = one hour of ticks. Fallback when a daily file is unusable."""
    return (
        f"{DATAFEED_BASE}/{symbol}/{year}/{month - 1:02d}/{day:02d}/"
        f"{hour:02d}h_ticks.bi5"
    )


def day_candles_url(symbol: str, year: int, month: int, day: int, side: str = "BID") -> str:
    """One file = one day of 1-minute candles, padded to 1440 rows."""
    return (
        f"{DATAFEED_BASE}/{symbol}/{year}/{month - 1:02d}/{day:02d}/"
        f"{side}_candles_min_1.bi5"
    )
