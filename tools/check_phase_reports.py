"""Verify every completed phase is documented, in order, with a report.

Phase 6, 8 and 9 all shipped with code, tests and a commit but with no
`docs/development-progress.md` section and no completion report. Nothing
noticed, because the file simply skipped a heading and the next phase wrote
its own section against whatever came before. The gaps were only found by
reading the document and counting.

This script is the mechanical version of that reading. It checks two things
that are objective and cheap:

1. **Contiguity.** The phase headings in the progress doc run 0, 1, 2, ... with
   no gaps and no duplicates. A missing heading is the failure mode above, and
   it is invisible to any check that looks at phases individually.
2. **Report completeness.** Every phase section that claims COMPLETE carries a
   `PHASE COMPLETION REPORT` block with all the fields AGENTS.md section 20
   requires. A phase that reports "Tests Passed" without "Tests Failed", or
   omits "Unverified Requirements", is not a report.

Phases 0-5 predate the reporting convention and are exempt from check 2, with
the exemption listed explicitly rather than inferred. They are still subject to
contiguity.
"""
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PROGRESS = ROOT / "docs" / "development-progress.md"
AGENTS = ROOT / "AGENTS.md"

# Phases completed before the completion report became a documented
# requirement (AGENTS.md section 20). They are exempt from the report check
# only; contiguity still applies to them.
REPORT_EXEMPT_PHASES = {0, 1, 2, 3, 4, 5}

# Every field AGENTS.md section 20 requires in a report block.
REQUIRED_FIELDS = [
    "Phase:",
    "Status:",
    "Implemented:",
    "Files Created:",
    "Files Modified:",
    "Build Result:",
    "Tests Executed:",
    "Tests Passed:",
    "Tests Failed:",
    "Known Issues:",
    "Unverified Requirements:",
    "Next Phase:",
]

# `## Phase 6 — Accounting, ... — COMPLETE`
PHASE_HEADING = re.compile(r"^## Phase (\d+)\b(.*)$", re.M)
# A fenced ``` block whose first line is the report marker.
REPORT_BLOCK = re.compile(r"```[ \t]*\nPHASE COMPLETION REPORT\b(.*?)```", re.S)


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def resolve_progress(argv: list[str]) -> Path:
    """The document to check: an optional path argument, else the repo default.

    The path argument exists for the pre-commit hook, which validates the
    *staged* blob rather than the working tree — the two differ whenever a file
    is staged only in part, and checking the wrong one would let a bad commit
    through.
    """
    if len(argv) > 1:
        return Path(argv[1])
    return PROGRESS


def phase_headings(text: str) -> list[tuple[int, str, int, int]]:
    """Return (phase number, rest of heading, start offset, line number)."""
    out = []
    for m in PHASE_HEADING.finditer(text):
        line_no = text.count("\n", 0, m.start()) + 1
        out.append((int(m.group(1)), m.group(2), m.start(), line_no))
    return out


def check_contiguity(headings, failures) -> None:
    numbers = [n for n, *_ in headings]
    if not numbers:
        failures.append(f"{PROGRESS.name}: no phase headings found at all")
        return

    if numbers != sorted(numbers):
        failures.append(
            f"{PROGRESS.name}: phase headings are out of order: {numbers}"
        )

    expected = list(range(numbers[0], numbers[-1] + 1))
    seen = set(numbers)
    missing = [n for n in expected if n not in seen]
    duplicates = sorted({n for n in numbers if numbers.count(n) > 1})

    if missing:
        failures.append(
            f"{PROGRESS.name}: phase(s) missing between "
            f"{numbers[0]} and {numbers[-1]}: {missing}"
        )
    if duplicates:
        failures.append(
            f"{PROGRESS.name}: phase heading(s) appear more than once: {duplicates}"
        )


def section_texts(text: str, headings) -> dict[int, str]:
    """Slice the document into one string per phase heading."""
    sections = {}
    for idx, (num, _rest, start, _line) in enumerate(headings):
        end = headings[idx + 1][2] if idx + 1 < len(headings) else len(text)
        sections[num] = text[start:end]
    return sections


def check_reports(text: str, headings, failures) -> None:
    sections = section_texts(text, headings)
    for num, body in sections.items():
        is_complete = "COMPLETE" in body.split("\n", 1)[0]
        reports = REPORT_BLOCK.findall(body)

        if reports and len(reports) > 1:
            failures.append(
                f"{PROGRESS.name}: Phase {num} has {len(reports)} report blocks"
            )

        if not is_complete or num in REPORT_EXEMPT_PHASES:
            continue

        if not reports:
            failures.append(
                f"{PROGRESS.name}: Phase {num} is COMPLETE but has no "
                f"PHASE COMPLETION REPORT block"
            )
            continue

        block = reports[0]
        for field in REQUIRED_FIELDS:
            if field not in block:
                failures.append(
                    f"{PROGRESS.name}: Phase {num} report is missing '{field}'"
                )
        if not re.search(r"^Status:\s*\S", block, re.M):
            failures.append(
                f"{PROGRESS.name}: Phase {num} report has an empty Status"
            )


def phases_from_git(failures) -> set[int]:
    """Phase numbers that have a commit, from subjects like `Phase 6: ...`.

    Contiguity alone cannot see a phase that is missing from the *end* of the
    document: a file that stops at Phase 8 has no gap, and nothing in the file
    says Phase 9 was ever expected. The commit subjects do say. This returns the
    set of phases with a commit so the caller can compare.

    A missing git, or a checkout with no history, is not a failure — the check
    degrades to document-only rather than reporting a false positive.
    """
    try:
        out = subprocess.run(
            ["git", "log", "--format=%s"],
            cwd=ROOT,
            capture_output=True,
            text=True,
            timeout=30,
        )
    except (OSError, subprocess.SubprocessError):
        return set()
    if out.returncode != 0:
        return set()

    phases = set()
    for line in out.stdout.splitlines():
        m = re.match(r"^Phase (\d+)\b", line)
        if m:
            phases.add(int(m.group(1)))
    return phases


def check_no_committed_phase_is_undocumented(headings, failures) -> None:
    committed = phases_from_git(failures)
    documented = {n for n, *_ in headings}
    undocumented = sorted(committed - documented)
    if undocumented:
        failures.append(
            f"{PROGRESS.name}: phase(s) have commits but no section: "
            f"{undocumented}"
        )


def check_requirement_is_documented(failures) -> None:
    """The rule this script enforces must still be stated in AGENTS.md."""
    agents = read(AGENTS)
    if "PHASE COMPLETION REPORT" not in agents:
        failures.append(
            "AGENTS.md: no longer documents the PHASE COMPLETION REPORT requirement"
        )


def main() -> int:
    progress = resolve_progress(sys.argv)
    text = read(progress)
    headings = phase_headings(text)

    failures: list[str] = []
    check_contiguity(headings, failures)
    check_no_committed_phase_is_undocumented(headings, failures)
    check_reports(text, headings, failures)
    check_requirement_is_documented(failures)

    print("=" * 66)
    print("PHASE REPORT CHECK")
    print("=" * 66)
    print(f"document:     {progress}")
    print(f"phases found: {[n for n, *_ in headings]}")
    print(f"report-exempt: {sorted(REPORT_EXEMPT_PHASES)}")
    print()
    if failures:
        print(f"FAILED ({len(failures)}):")
        for f in failures:
            print(f"  - {f}")
        return 1

    print("PASSED")
    print()
    print("  - phase headings are contiguous, ordered and unique")
    print("  - no phase with a commit is missing from the document")
    print("  - every COMPLETE phase (from 6) carries a PHASE COMPLETION REPORT")
    print("  - every report has all 12 required fields")
    print("  - AGENTS.md still documents the requirement")
    return 0


if __name__ == "__main__":
    sys.exit(main())
