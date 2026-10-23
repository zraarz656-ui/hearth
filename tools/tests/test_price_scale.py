"""Tests for the price scale check.

The check guards the ADR 0001 decision: prices are stored in thousandths of a
USD, never cents. It spent a long time red on a clean checkout because its
`/ 100` rule could not tell a cents regression from two correct idioms that are
everywhere in the formatting code -- converting micro-dollars to cents for
display, and converting lot-hundredths to lots. A check that always fails is
not a check, so the rule was narrowed to require a price-like name.

These tests are mostly negative. A test that only asserts the current, correct
tree passes would not show the check can fail, which is the only thing that
makes it worth running.
"""
from __future__ import annotations

import re
import subprocess
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from tools.check_price_scale import (  # noqa: E402
    ROOT,
    SOURCE_RULES,
)

CENTS_RULE = next(
    pattern for pattern, label in SOURCE_RULES if "cents conversion" in label
)


def flags(line: str) -> bool:
    """Mirror the check: only the code before a trailing `//` is scanned."""
    return bool(re.search(CENTS_RULE, line.split("//", 1)[0]))


class CentsConversionIsCaught(unittest.TestCase):
    """A real regression -- a price divided by 100 -- must be flagged."""

    def test_price_units_divided_by_100(self):
        self.assertTrue(flags("val dollars = priceUnits / 100"))

    def test_each_price_like_prefix(self):
        for name in ("open", "high", "low", "close", "stop", "target",
                     "entry", "exit", "fill", "bid", "ask"):
            with self.subTest(name=name):
                self.assertTrue(flags(f"val d = {name}Units / 100"))

    def test_sl_and_tp_abbreviations(self):
        self.assertTrue(flags("val d = sl / 100"))
        self.assertTrue(flags("val d = tp / 100"))

    def test_no_spaces_around_the_operator(self):
        self.assertTrue(flags("val d = priceUnits/100"))


class CorrectCodeIsNotFlagged(unittest.TestCase):
    """The two idioms that made the old rule unusable must pass."""

    def test_micros_to_cents_for_display(self):
        self.assertFalse(flags("val cents = frac / (MICROS_PER_USD / 100)"))

    def test_micros_to_cents_with_qualified_constant(self):
        self.assertFalse(flags(
            "val cents = (abs % MoneyScale.MICROS_PER_USD) / "
            "(MoneyScale.MICROS_PER_USD / 100)"
        ))

    def test_lot_hundredths_to_lots(self):
        self.assertFalse(flags(
            'lotUnits / 100}.${(lotUnits % 100).toString().padStart(2, \'0\')}'
        ))

    def test_price_units_to_dollars_is_thousandths(self):
        self.assertFalse(flags("val dollars = priceUnits / 1000"))

    def test_unrelated_identifier_divided_by_100(self):
        self.assertFalse(flags("val x = slip / 100"))
        self.assertFalse(flags("val y = totalUnits / 100"))


class TheKeywordEscapeIsClosed(unittest.TestCase):
    """A real hit must not be silenceable by wording in a trailing comment.

    The check previously skipped any line containing "never", "not cents",
    "avoid" or "forbidden", so a regression could be hidden by appending a
    comment. Scanning only the code before `//` closes that.
    """

    def test_trailing_comment_does_not_hide_a_hit(self):
        for comment in ("not cents", "never used", "avoid this", "forbidden"):
            with self.subTest(comment=comment):
                self.assertTrue(flags(f"val priceCents = priceUnits / 100 // {comment}"))

    def test_prose_after_the_slash_slash_is_ignored(self):
        # A comment *describing* the pattern must not itself be flagged.
        self.assertFalse(flags("val x = 1 // never divide a priceUnits / 100"))


class RealTreeIsClean(unittest.TestCase):
    """The repo must pass, or the check is red from its first CI run."""

    def test_check_passes_on_the_real_tree(self):
        result = subprocess.run(
            [sys.executable, str(ROOT / "tools" / "check_price_scale.py")],
            cwd=ROOT, capture_output=True, text=True,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_root_resolves_relative_to_the_script(self):
        # A hardcoded absolute ROOT breaks on any other machine or in CI.
        self.assertTrue((ROOT / "tools" / "check_price_scale.py").exists())
        self.assertTrue((ROOT / "TRADEQUEST_DEVELOPMENT_PLAN.md").exists())


if __name__ == "__main__":
    unittest.main()
