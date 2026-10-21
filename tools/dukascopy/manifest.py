"""CSV writing and dataset manifests.

Output is deterministic: identical input produces byte-identical CSV and
therefore an identical content hash.
"""

from __future__ import annotations

import csv
import hashlib
import json
from dataclasses import asdict, dataclass
from datetime import date, datetime, timezone
from pathlib import Path

from .candles import MinuteCandle

CSV_COLUMNS = [
    "timestamp_utc",
    "open",
    "high",
    "low",
    "close",
    "tick_volume",
    "spread",
    "dukascopy_volume",
]

MANIFEST_VERSION = 1


def format_price(raw: int, decimals: int) -> str:
    """Fixed-point decimal string, no float rounding, no locale dependence."""
    sign = "-" if raw < 0 else ""
    value = abs(raw)
    scale = 10**decimals
    return f"{sign}{value // scale}.{value % scale:0{decimals}d}"


def format_utc(ms: int) -> str:
    moment = datetime.fromtimestamp(ms / 1000, tz=timezone.utc)
    return moment.strftime("%Y-%m-%dT%H:%M:%SZ")


def write_csv(
    path: Path,
    candles: list[MinuteCandle],
    point_value: int,
    decimals: int,
) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="\n", encoding="utf-8") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(CSV_COLUMNS)
        for candle in candles:
            writer.writerow(
                [
                    format_utc(candle.timestamp_utc_ms),
                    format_price(candle.open_raw, decimals),
                    format_price(candle.high_raw, decimals),
                    format_price(candle.low_raw, decimals),
                    format_price(candle.close_raw, decimals),
                    candle.tick_volume,
                    format_price(candle.spread_points, decimals),
                    f"{candle.dukascopy_volume:.6f}",
                ]
            )


@dataclass
class DatasetManifest:
    """Phase 1 Step 1.7 fields, plus provenance for auditability."""

    dataset_id: str
    instrument: str
    start_timestamp_utc: str
    end_timestamp_utc: str
    candle_count: int
    minimum_price: str
    maximum_price: str
    missing_minute_count: int
    duplicate_count: int
    validation_status: str
    source_description: str
    content_hash: str
    manifest_version: int = MANIFEST_VERSION
    generated_at_utc: str = ""
    trading_days: int = 0
    closed_days: int = 0
    gap_summary: list[dict] | None = None

    def write(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(asdict(self), indent=2, sort_keys=True) + "\n")


def content_hash(candles: list[MinuteCandle]) -> str:
    """SHA-256 over a canonical text rendering of the candle series.

    Reproducible: depends only on candle values, not on file paths,
    timestamps of execution, or iteration order.
    """
    digest = hashlib.sha256()
    digest.update(b"tradequest-dataset-v1\n")
    for candle in candles:
        digest.update(
            f"{candle.timestamp_utc_ms},{candle.open_raw},{candle.high_raw},"
            f"{candle.low_raw},{candle.close_raw},{candle.tick_volume},"
            f"{candle.spread_points},{candle.dukascopy_volume:.6f}\n".encode()
        )
    return digest.hexdigest()


def build_manifest(
    candles: list[MinuteCandle],
    instrument_symbol: str,
    point_value: int,
    decimals: int,
    days: list[date],
    day_statuses: dict[date, str],
    missing_minute_count: int,
    duplicate_count: int,
    validation_status: str,
    source_description: str,
) -> DatasetManifest:
    if candles:
        start = format_utc(candles[0].timestamp_utc_ms)
        end = format_utc(candles[-1].timestamp_utc_ms)
        low = min(c.low_raw for c in candles)
        high = max(c.high_raw for c in candles)
    else:
        start = end = ""
        low = high = 0

    closed = sum(1 for status in day_statuses.values() if "closure" in status or "no_data" in status)
    trading = len(day_statuses) - closed

    gaps = [
        {"date": str(day), "status": status}
        for day, status in sorted(day_statuses.items())
        if "gaps" in status or "no_data" in status
    ]

    return DatasetManifest(
        dataset_id=f"{instrument_symbol}_{start[:10]}_{end[:10]}",
        instrument=instrument_symbol,
        start_timestamp_utc=start,
        end_timestamp_utc=end,
        candle_count=len(candles),
        minimum_price=format_price(low, decimals),
        maximum_price=format_price(high, decimals),
        missing_minute_count=missing_minute_count,
        duplicate_count=duplicate_count,
        validation_status=validation_status,
        source_description=source_description,
        content_hash=content_hash(candles),
        generated_at_utc=datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        trading_days=trading,
        closed_days=closed,
        gap_summary=gaps,
    )
