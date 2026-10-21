"""Polite request pacing for the Dukascopy datafeed.

Measured behaviour that motivates this module: 13 of 14 consecutive
requests failed with 429/503 at one-second spacing, and rejections were
often returned in ~0.35s. A naive retry loop therefore burns the
allowance quickly. Requests are serial, jittered, backed off, and halted
by a circuit breaker rather than hammering a service that is clearly
throttling.
"""

from __future__ import annotations

import random
import time
import urllib.error
import urllib.request
from dataclasses import dataclass

from .config import USER_AGENT

RETRYABLE_STATUS = {429, 500, 502, 503, 504}


class FetchError(Exception):
    """A request failed in a way that should be reported to the caller."""


class CircuitBreakerTripped(FetchError):
    """Too many consecutive failures; stopping rather than hammering."""


@dataclass
class FetchResult:
    url: str
    status: int
    body: bytes
    attempts: int

    @property
    def is_empty(self) -> bool:
        """A 0-byte 200 response means the market was closed, not an error."""
        return self.status == 200 and not self.body


class RateLimitedFetcher:
    def __init__(
        self,
        delay_seconds: float = 2.0,
        jitter: float = 0.5,
        max_attempts: int = 5,
        backoff_base: float = 2.0,
        max_backoff: float = 60.0,
        circuit_break_after: int = 8,
        timeout: float = 120.0,
        sleep=time.sleep,
        monotonic=time.monotonic,
    ) -> None:
        self.delay_seconds = delay_seconds
        self.jitter = jitter
        self.max_attempts = max_attempts
        self.backoff_base = backoff_base
        self.max_backoff = max_backoff
        self.circuit_break_after = circuit_break_after
        self.timeout = timeout
        self._sleep = sleep
        self._monotonic = monotonic
        self._consecutive_failures = 0
        self._last_request_at: float | None = None
        self.request_count = 0

    def _pace(self) -> None:
        """Wait out the inter-request delay, jittered to avoid lockstep."""
        if self._last_request_at is None:
            return
        target = self.delay_seconds * (1.0 + random.uniform(-self.jitter, self.jitter))
        elapsed = self._monotonic() - self._last_request_at
        remaining = target - elapsed
        if remaining > 0:
            self._sleep(remaining)

    def fetch(self, url: str, allow_missing: bool = True) -> FetchResult:
        """Fetch a URL, retrying only transient failures.

        A 404 is returned as a result rather than retried: it means the
        date is outside Dukascopy's range, not a transient fault.
        """
        if self._consecutive_failures >= self.circuit_break_after:
            raise CircuitBreakerTripped(
                f"{self._consecutive_failures} consecutive failures. Stopping to "
                f"avoid hammering the service. Re-run later; cached files are kept."
            )

        last_error: Exception | None = None
        for attempt in range(1, self.max_attempts + 1):
            self._pace()
            request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            try:
                with urllib.request.urlopen(request, timeout=self.timeout) as response:
                    body = response.read()
                self.request_count += 1
                self._last_request_at = self._monotonic()
                self._consecutive_failures = 0
                return FetchResult(url=url, status=response.status, body=body, attempts=attempt)
            except urllib.error.HTTPError as exc:
                self.request_count += 1
                self._last_request_at = self._monotonic()
                if exc.code == 404 and allow_missing:
                    self._consecutive_failures = 0
                    return FetchResult(url=url, status=404, body=b"", attempts=attempt)
                if exc.code not in RETRYABLE_STATUS:
                    self._consecutive_failures += 1
                    raise FetchError(f"{url} returned HTTP {exc.code}") from exc
                last_error = exc
                self._wait_backoff(attempt, exc.headers.get("Retry-After"))
            except Exception as exc:  # timeouts, DNS, connection resets
                self.request_count += 1
                self._last_request_at = self._monotonic()
                last_error = exc
                self._wait_backoff(attempt, None)

        self._consecutive_failures += 1
        raise FetchError(f"{url} failed after {self.max_attempts} attempts: {last_error}")

    def _wait_backoff(self, attempt: int, retry_after: str | None) -> None:
        if retry_after:
            try:
                self._sleep(min(float(retry_after), self.max_backoff))
                return
            except ValueError:
                pass
        delay = min(self.backoff_base**attempt, self.max_backoff)
        self._sleep(delay * (1.0 + random.uniform(-0.25, 0.25)))
