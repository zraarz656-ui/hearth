"""Tests for the phase report check.

The check exists to catch a specific historical failure: Phases 6, 7, 8 and 9
each shipped with code and a commit but with no completion report, and nothing
noticed. So the tests below are mostly *negative* — they remove a section, or a
report, or a field, and assert the check notices. A test that only passes on the
current, correct document would not prove the check can fail.
"""
from __future__ import annotations

import os
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from tools.check_phase_reports import (  # noqa: E402
    PROGRESS,
    REQUIRED_FIELDS,
    ROOT,
    check_contiguity,
    check_no_committed_phase_is_undocumented,
    check_reports,
    phase_headings,
    resolve_progress,
)

REAL = (Path(__file__).resolve().parents[2]
        / "docs" / "development-progress.md").read_text(encoding="utf-8")

MINIMAL_REPORT = "```\nPHASE COMPLETION REPORT\n" + "\n".join(
    f"{f}\n- x" for f in REQUIRED_FIELDS
) + "\n```"


def all_failures(text: str) -> list[str]:
    heads = phase_headings(text)
    failures: list[str] = []
    check_contiguity(heads, failures)
    check_reports(text, heads, failures)
    return failures


class HeadingParsing(unittest.TestCase):
    def test_real_document_has_the_ten_phases(self):
        self.assertEqual([n for n, *_ in phase_headings(REAL)],
                         [0, 1, 2, 3, 4, 5, 6, 7, 8, 9])

    def test_heading_offsets_are_increasing(self):
        offsets = [off for _, _, off, _ in phase_headings(REAL)]
        self.assertEqual(offsets, sorted(offsets))


class Contiguity(unittest.TestCase):
    def test_a_gap_is_caught(self):
        # The original bug: Phase 6's heading and section were never written.
        i, j = REAL.index("## Phase 6"), REAL.index("## Phase 7")
        failures = all_failures(REAL[:i] + REAL[j:])
        self.assertTrue(any("missing" in f and "[6]" in f for f in failures),
                        failures)

    def test_a_trailing_phase_is_caught_by_the_git_crosscheck(self):
        # Removing the last phase leaves no gap, so contiguity alone cannot see
        # it; only the commit subjects can. This is the case that escaped the
        # first version of the check.
        text = REAL[:REAL.index("## Phase 9")]
        heads = phase_headings(text)
        failures: list[str] = []
        check_no_committed_phase_is_undocumented(heads, failures)
        self.assertTrue(any("[9]" in f for f in failures), failures)

    def test_a_duplicate_is_caught(self):
        text = REAL.replace("## Phase 5 — Trading",
                            "## Phase 4 — Duplicate\n\n## Phase 5 — Trading", 1)
        self.assertTrue(any("more than once" in f for f in all_failures(text)))

    def test_out_of_order_is_caught(self):
        i = REAL.index("## Phase 6")
        j = REAL.index("## Phase 7")
        k = REAL.index("## Phase 8")
        text = REAL[:i] + REAL[j:k] + REAL[i:j] + REAL[k:]
        self.assertTrue(any("out of order" in f for f in all_failures(text)))


class Reports(unittest.TestCase):
    def test_real_document_passes(self):
        self.assertEqual(all_failures(REAL), [])

    def test_a_complete_phase_without_a_report_is_caught(self):
        i, j = REAL.index("## Phase 8"), REAL.index("## Phase 9")
        seg = REAL[i:j]
        # Cut the fenced report block out of the Phase 8 section.
        start = seg.rindex("```", 0, seg.index("PHASE COMPLETION REPORT"))
        end = seg.index("```", seg.index("Next Phase:")) + 3
        text = REAL[:i] + seg[:start] + seg[end:] + REAL[j:]
        failures = all_failures(text)
        self.assertTrue(any("Phase 8" in f and "no PHASE COMPLETION REPORT" in f
                            for f in failures), failures)

    def test_a_report_missing_a_field_is_caught(self):
        text = REAL.replace("Tests Failed:\n- 0\n\nKnown Issues:\n- Step 8.13",
                            "Known Issues:\n- Step 8.13", 1)
        failures = all_failures(text)
        self.assertTrue(any("missing 'Tests Failed:'" in f for f in failures),
                        failures)

    def test_exempt_phases_need_no_report(self):
        # Phase 5 predates the convention and legitimately has no report block.
        self.assertNotIn("PHASE COMPLETION REPORT",
                         REAL[REAL.index("## Phase 5"):REAL.index("## Phase 6")])

    def test_a_minimal_valid_report_satisfies_the_field_check(self):
        text = ("## Phase 6 — X — COMPLETE\n\n" + MINIMAL_REPORT + "\n\n"
                "## Phase 7 — Y — COMPLETE\n\n" + MINIMAL_REPORT + "\n")
        heads = phase_headings(text)
        failures: list[str] = []
        check_reports(text, heads, failures)
        self.assertEqual(failures, [])


class GitCrossCheck(unittest.TestCase):
    def test_degrades_quietly_when_git_is_unavailable(self):
        # A source tarball has no .git. That must not be reported as a failure.
        failures: list[str] = []
        check_no_committed_phase_is_undocumented(phase_headings(REAL), failures)
        self.assertEqual(failures, [])


class PathArgument(unittest.TestCase):
    """The pre-commit hook passes an explicit path (the staged blob)."""

    def test_default_is_the_repo_document(self):
        self.assertEqual(resolve_progress(["prog"]), PROGRESS)

    def test_explicit_path_wins(self):
        self.assertEqual(resolve_progress(["prog", "/tmp/other.md"]),
                         Path("/tmp/other.md"))

    def test_check_runs_against_an_explicit_path(self):
        import subprocess
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            bad = Path(d) / "development-progress.md"
            bad.write_text(REAL, encoding="utf-8")
            ok = subprocess.run(
                [sys.executable, "tools/check_phase_reports.py", str(bad)],
                cwd=ROOT, capture_output=True, text=True,
            )
            self.assertEqual(ok.returncode, 0, ok.stdout + ok.stderr)


class HookInstallable(unittest.TestCase):
    """The hook must be tracked and executable, or it protects nothing."""

    def test_hook_exists_and_is_executable(self):
        hook = ROOT / ".githooks" / "pre-commit"
        self.assertTrue(hook.is_file(), "pre-commit hook is missing")
        self.assertTrue(os.access(hook, os.X_OK),
                        "pre-commit hook is not executable")

    def test_hook_reads_the_staged_blob_not_the_working_tree(self):
        # A hook that validated the working tree would pass a commit whose
        # staged content is still broken.
        hook = (ROOT / ".githooks" / "pre-commit").read_text(encoding="utf-8")
        self.assertIn("git show", hook)


if __name__ == "__main__":
    unittest.main()
