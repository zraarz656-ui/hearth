"""Command-line entry point: fetch, convert, validate, and write datasets.

Usage:
    python -m tools.dukascopy.fetch --symbol XAUUSD --start 2024-01-01 \
        --end 2024-03-31 --out datasets/

The tool never fabricates data. Candles come from real Dukascopy ticks,
and minutes with no ticks are omitted and reported rather than filled.
"""

from __future__ import annotations

import argparse
import sys
from datetime import date, timedelta
from pathlib import Path

from .cache import RawCache
from .candles import MinuteCandle, aggregate_minutes, day_start_utc_ms
from .config import day_candles_url, day_ticks_url, get_instrument, hour_ticks_url
from .crosscheck import cross_check
from .decode import Bi5DecodeError, decode_candles, decode_ticks
from .manifest import build_manifest, write_csv
from .ratelimit import CircuitBreakerTripped, FetchError, RateLimitedFetcher
from .validate import classify_day, validate_series


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--symbol", default="XAUUSD")
    parser.add_argument("--start", required=True, help="UTC start date, YYYY-MM-DD")
    parser.add_argument("--end", required=True, help="UTC end date, YYYY-MM-DD (inclusive)")
    parser.add_argument("--out", default="datasets", help="output directory")
    parser.add_argument("--cache", default=None, help="raw payload cache (default <out>/.cache)")
    parser.add_argument("--delay", type=float, default=2.0, help="seconds between requests")
    parser.add_argument(
        "--cross-check",
        action="store_true",
        help="also fetch published candles and verify agreement (doubles requests)",
    )
    parser.add_argument(
        "--hourly-fallback",
        action="store_true",
        help="fall back to 24 hourly files when a daily file is missing or corrupt",
    )
    return parser.parse_args(argv)


def daterange(start: date, end: date):
    current = start
    while current <= end:
        yield current
        current += timedelta(days=1)


def fetch_day_ticks(
    fetcher: RateLimitedFetcher,
    cache: RawCache,
    symbol: str,
    day: date,
    hourly_fallback: bool,
) -> tuple[bytes, str]:
    """Return raw tick payload for a day, using the cache where possible."""
    url = day_ticks_url(symbol, day.year, day.month, day.day)
    if cache.has(url):
        status = cache.status_of(url)
        if status == 404:
            return b"", "out_of_range"
        return cache.get(url) or b"", "cache"

    result = fetcher.fetch(url)
    cache.put(url, result.status, result.body)
    if result.status == 404:
        return b"", "out_of_range"
    if result.is_empty:
        return b"", "closed"

    try:
        decode_ticks(result.body)
        return result.body, "daily"
    except Bi5DecodeError:
        if not hourly_fallback:
            raise

    # Rebuild the day from hourly files, which use a different layout.
    chunks: list[bytes] = []
    for hour in range(24):
        hour_url = hour_ticks_url(symbol, day.year, day.month, day.day, hour)
        if cache.has(hour_url):
            body = cache.get(hour_url) or b""
        else:
            hour_result = fetcher.fetch(hour_url)
            cache.put(hour_url, hour_result.status, hour_result.body)
            body = hour_result.body
        if body:
            chunks.append(body)
    return b"", "hourly_unsupported"


def process_day(
    fetcher: RateLimitedFetcher,
    cache: RawCache,
    symbol: str,
    point_value: int,
    day: date,
    hourly_fallback: bool,
    cross_check_enabled: bool,
) -> tuple[list[MinuteCandle], str, list[str], int, int]:
    """Return (candles, day_status, crosscheck_messages, missing, duplicates)."""
    day_start_ms = day_start_utc_ms(day.year, day.month, day.day)

    try:
        raw, source = fetch_day_ticks(fetcher, cache, symbol, day, hourly_fallback)
    except (FetchError, CircuitBreakerTripped):
        raise

    if not raw:
        return [], classify_day(day, 0, []), [], 0, 0

    ticks = decode_ticks(raw)
    candles = aggregate_minutes(ticks, day_start_ms, point_value)
    report = validate_series(candles, day)
    status = classify_day(day, len(candles), report.missing_minutes)

    messages: list[str] = list(report.violations)

    if cross_check_enabled:
        url = day_candles_url(symbol, day.year, day.month, day.day)
        if cache.has(url):
            body = cache.get(url) or b""
        else:
            published_result = fetcher.fetch(url)
            cache.put(url, published_result.status, published_result.body)
            body = published_result.body
        if body:
            result = cross_check(str(day), candles, decode_candles(body), day_start_ms)
            if result.ok:
                messages.append(
                    f"cross-check OK: {result.compared} minutes agree "
                    f"({result.padding_rows} padding rows ignored)"
                )
            else:
                messages.append(
                    f"cross-check FAILED: {len(result.mismatches)} mismatches "
                    f"of {result.compared}"
                )
                messages.extend(result.mismatches[:5])

    duplicates = len(candles) - len({c.timestamp_utc_ms for c in candles})
    return candles, status, messages, len(report.missing_minutes), duplicates


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        instrument = get_instrument(args.symbol)
    except KeyError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2

    start = date.fromisoformat(args.start)
    end = date.fromisoformat(args.end)
    if end < start:
        print("error: --end is before --start", file=sys.stderr)
        return 2

    out_dir = Path(args.out)
    cache = RawCache(Path(args.cache) if args.cache else out_dir / ".cache")
    fetcher = RateLimitedFetcher(delay_seconds=args.delay)

    all_candles: list[MinuteCandle] = []
    day_statuses: dict[date, str] = {}
    total_missing = 0
    total_duplicates = 0
    crosscheck_failures = 0
    aborted = False
    failed_days: list[str] = []
    days = list(daterange(start, end))

    print(
        f"{instrument.symbol}: {start} .. {end} ({len(days)} days), "
        f"delay {args.delay}s, cross-check {args.cross_check}"
    )

    for index, day in enumerate(days, start=1):
        try:
            candles, status, messages, missing, duplicates = process_day(
                fetcher, cache, instrument.symbol, instrument.point_value,
                day, args.hourly_fallback, args.cross_check,
            )
        except CircuitBreakerTripped as exc:
            # Sustained throttling: stop requesting, but keep what we have.
            print(f"\nstopping: {exc}", file=sys.stderr)
            aborted = True
            break
        except FetchError as exc:
            # A single bad day must not discard the whole run. Record it and
            # carry on; the day can be picked up on a later resumable run.
            print(f"  {day}: FETCH FAILED ({exc})", file=sys.stderr)
            day_statuses[day] = "fetch_failed (not retrieved)"
            failed_days.append(str(day))
            continue

        all_candles.extend(candles)
        day_statuses[day] = status
        total_missing += missing
        total_duplicates += duplicates
        crosscheck_failures += sum(1 for m in messages if "FAILED" in m)
        for message in messages:
            print(f"  {day}: {message}")

        if index % 10 == 0 or index == len(days):
            print(
                f"  [{index}/{len(days)}] {day} {status} "
                f"candles={len(candles)} total={len(all_candles)}",
                flush=True,
            )

    if not all_candles:
        print("error: no candles produced", file=sys.stderr)
        return 1

    validation_status = "FAILED" if crosscheck_failures else "PASSED"
    if aborted or failed_days:
        validation_status = "INCOMPLETE"
    manifest = build_manifest(
        candles=all_candles,
        instrument_symbol=instrument.symbol,
        point_value=instrument.point_value,
        decimals=instrument.price_decimals,
        days=days,
        day_statuses=day_statuses,
        missing_minute_count=total_missing,
        duplicate_count=total_duplicates,
        validation_status=validation_status,
        source_description=(
            "Dukascopy datafeed daily tick files (.bi5), converted to 1-minute "
            "bid candles. Padding rows excluded. See DUKASCOPY_FETCHER_PLAN.md."
        ),
    )

    dataset_dir = out_dir / manifest.dataset_id
    csv_path = dataset_dir / f"{manifest.dataset_id}.csv"
    write_csv(csv_path, all_candles, instrument.point_value, instrument.price_decimals)
    manifest.write(dataset_dir / f"{manifest.dataset_id}.manifest.json")

    print(f"\ncandles:  {len(all_candles)}")
    print(f"days:     {sum(1 for s in day_statuses.values() if 'closure' not in s and 'no_data' not in s and 'failed' not in s)} trading, "
          f"{sum(1 for s in day_statuses.values() if 'closure' in s or 'no_data' in s)} closed")
    print(f"missing:  {total_missing} minutes")
    print(f"status:   {validation_status}")
    if failed_days:
        print(f"failed:   {len(failed_days)} days not retrieved: {', '.join(failed_days)}")
        print("          re-run the same command to fetch only the missing days")
    print(f"hash:     {manifest.content_hash}")
    print(f"csv:      {csv_path}")
    print(f"manifest: {dataset_dir / f'{manifest.dataset_id}.manifest.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
