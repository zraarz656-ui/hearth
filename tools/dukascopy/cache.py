"""Raw payload cache.

Every fetched .bi5 file is stored verbatim, keyed by URL, so runs are
resumable and a re-run never re-requests data it already holds. This
matters because the datafeed throttles aggressively.
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path


class RawCache:
    def __init__(self, root: Path) -> None:
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)
        self._index_path = self.root / "index.json"
        self._index: dict[str, dict] = {}
        if self._index_path.exists():
            self._index = json.loads(self._index_path.read_text())

    def _key(self, url: str) -> str:
        return hashlib.sha256(url.encode()).hexdigest()[:32]

    def _path(self, url: str) -> Path:
        return self.root / f"{self._key(url)}.bi5"

    def has(self, url: str) -> bool:
        """True only if this exact URL was fetched and its body stored.

        A recorded 404 counts as cached: the date is out of range and
        re-requesting it wastes the request allowance.
        """
        return url in self._index

    def get(self, url: str) -> bytes | None:
        entry = self._index.get(url)
        if entry is None:
            return None
        if entry["status"] != 200:
            return b""
        path = self._path(url)
        if not path.exists():
            return None
        return path.read_bytes()

    def status_of(self, url: str) -> int | None:
        entry = self._index.get(url)
        return entry["status"] if entry else None

    def put(self, url: str, status: int, body: bytes) -> None:
        if status == 200:
            self._path(url).write_bytes(body)
        self._index[url] = {
            "status": status,
            "bytes": len(body),
            "sha256": hashlib.sha256(body).hexdigest(),
        }
        self._index_path.write_text(json.dumps(self._index, indent=2, sort_keys=True))

    @property
    def cached_count(self) -> int:
        return len(self._index)
