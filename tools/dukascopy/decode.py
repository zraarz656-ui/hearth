"""Decoders for Dukascopy .bi5 binary payloads.

Both formats were established by decoding real payloads, not from
documentation. The candle field order in particular is nonstandard.

Tick record (20 bytes, big-endian):
    0  uint32  milliseconds since 00:00 UTC
    4  uint32  ask price (scaled by point value)
    8  uint32  bid price (scaled by point value)
   12  float32 ask volume
   16  float32 bid volume

Candle record (24 bytes, big-endian):
    0  uint32  seconds since 00:00 UTC
    4  uint32  open
    8  uint32  close      <-- close precedes low/high
   12  uint32  low
   16  uint32  high
   20  float32 volume
"""

from __future__ import annotations

import lzma
import struct
from dataclasses import dataclass

from .config import CANDLE_RECORD_SIZE, TICK_RECORD_SIZE

_TICK = struct.Struct(">IIIff")
_CANDLE = struct.Struct(">IIIIIf")


class Bi5DecodeError(Exception):
    """Raised when a payload cannot be decoded as the expected format."""


@dataclass(frozen=True, slots=True)
class Tick:
    ms_of_day: int
    ask_raw: int
    bid_raw: int
    ask_volume: float
    bid_volume: float


@dataclass(frozen=True, slots=True)
class RawCandle:
    """As stored in the file: bid-side, prices scaled, volume fractional."""

    sec_of_day: int
    open_raw: int
    close_raw: int
    low_raw: int
    high_raw: int
    volume: float

    @property
    def is_flat_and_empty(self) -> bool:
        """True for Dukascopy's padding rows.

        Closed periods are filled to a full 1440 rows with a repeated
        price and zero volume. Treating these as real candles injects
        fake data and fake weekend trading days.
        """
        return (
            self.volume == 0.0
            and self.open_raw == self.high_raw == self.low_raw == self.close_raw
        )


def decompress(raw: bytes) -> bytes:
    """Decompress a .bi5 payload.

    Dukascopy uses a raw LZMA stream with a 13-byte header (FORMAT_ALONE),
    not the .xz container format.
    """
    if not raw:
        return b""
    try:
        return lzma.decompress(raw, format=lzma.FORMAT_ALONE)
    except lzma.LZMAError as exc:
        raise Bi5DecodeError(f"payload is not a valid LZMA stream: {exc}") from exc


def decode_ticks(raw: bytes) -> list[Tick]:
    """Decode a tick payload. An empty payload yields no ticks (closed market)."""
    data = decompress(raw)
    if len(data) % TICK_RECORD_SIZE != 0:
        raise Bi5DecodeError(
            f"tick payload is {len(data)} bytes, not a multiple of "
            f"{TICK_RECORD_SIZE}"
        )
    ticks = [
        Tick(*_TICK.unpack_from(data, offset))
        for offset in range(0, len(data), TICK_RECORD_SIZE)
    ]
    for previous, current in zip(ticks, ticks[1:]):
        if current.ms_of_day < previous.ms_of_day:
            raise Bi5DecodeError(
                f"ticks are not time-ordered: {previous.ms_of_day} then "
                f"{current.ms_of_day}"
            )
    return ticks


def decode_candles(raw: bytes) -> list[RawCandle]:
    """Decode a 1-minute candle payload, including Dukascopy's padding rows."""
    data = decompress(raw)
    if len(data) % CANDLE_RECORD_SIZE != 0:
        raise Bi5DecodeError(
            f"candle payload is {len(data)} bytes, not a multiple of "
            f"{CANDLE_RECORD_SIZE}"
        )
    return [
        RawCandle(*_CANDLE.unpack_from(data, offset))
        for offset in range(0, len(data), CANDLE_RECORD_SIZE)
    ]
