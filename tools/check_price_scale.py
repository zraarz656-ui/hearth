"""Verify the price-scale decision is consistent across all project docs.

Guards against a future edit silently reintroducing cents.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DOCS = [
    ROOT / "TRADEQUEST_DEVELOPMENT_PLAN.md",
    ROOT / "AGENTS.md",
    ROOT / "docs" / "adr-0001-price-scale.md",
    ROOT / "docs" / "architecture.md",
    ROOT / "README.md",
]

# 1. No live instruction should say prices are cents.
LIVE_PATTERNS = [
    r"price:?\s+integer cents",
    r"convert prices to integer cents",
    r"remains integer cents",
    r"openCents",
    r"closeCents",
    # Table rows such as: | Price | integer cents (`Int`) | ...
    # The first patterns missed this shape, which is how architecture.md
    # kept a live cents instruction undetected.
    r"\|\s*price\s*\|[^|]*\bcents\b",
    r"^\s*price\s*[:|].*\bcents\b",
]

SOURCE_SUBDIRS = ("app/src/main/java", "app/src/test/java")
# PriceScale.kt legitimately contains /100-style pip maths and usdToUnits.
# Repo-relative, so the same set applies to the working tree and to a staged
# snapshot scanned under a temp root.
ALLOWED = {"app/src/main/java/com/tradequest/app/core/market/PriceScale.kt"}
SOURCE_RULES = [
    # A field, parameter or local named like a cents quantity.
    (r"\b(price|open|high|low|close|stop|target|entry|exit|fill|bid|ask|sl|tp)Cents\b",
     "cents-named price field"),
    # Dividing a *price* by 100 is the cents-scale conversion.
    #
    # This deliberately requires a price-like name on the left of the divide.
    # A bare `/ 100` cannot tell the bug from two correct idioms: converting
    # micro-dollars to cents for display (`frac / (MICROS_PER_USD / 100)`, which
    # is micros-per-cent, not a price scale) and converting lot-hundredths to
    # lots (`lotUnits / 100`). Both are all over the formatting code, so a bare
    # pattern reports three false positives on a clean checkout and the check
    # can never go green. Rule 1 above already names the price-like prefixes;
    # this reuses that vocabulary so the two rules agree.
    (r"(?:\b(?:price|open|high|low|close|stop|target|entry|exit|fill|bid|ask)\w*"
     r"|\bsl\b|\btp\b)\s*/\s*100\b",
     "possible cents conversion (price divided by 100)"),
    # Authoritative money must not be Float or Double.
    (r"\b(balance|equity|pnl|profit|loss|margin|commission|swap|drawdown)"
     r"\w*\s*:\s*(Double|Float)\b",
     "money stored as Double/Float"),
]


def check_docs(failures: list[str]) -> None:
    """Checks 1-4: the documents must not describe prices as cents."""
    for doc in DOCS:
        text = doc.read_text()
        for i, line in enumerate(text.splitlines(), 1):
            low = line.lower()
            for pat in LIVE_PATTERNS:
                if re.search(pat, low):
                    # Allow explicit negations / historical explanations.
                    if any(k in low for k in (
                        "not cents", "not integer cents", "previously",
                        "renamed", "do not", "never", "instead of",
                    )):
                        continue
                    failures.append(
                        f"{doc.name}:{i}: live 'cents' instruction: {line.strip()[:90]}")

    # 2. The canonical constants must appear in plan and AGENTS.md.
    for doc in [ROOT / "TRADEQUEST_DEVELOPMENT_PLAN.md", ROOT / "AGENTS.md"]:
        if "PRICE_UNITS_PER_USD = 1000" not in doc.read_text():
            failures.append(f"{doc.name}: missing PRICE_UNITS_PER_USD = 1000")

    plan = (ROOT / "TRADEQUEST_DEVELOPMENT_PLAN.md").read_text()
    if "PRICE_UNITS_PER_PIP" not in plan:
        failures.append("plan: missing PRICE_UNITS_PER_PIP definition")

    # 3. The no-change rule must be in the agent workflow section.
    if "Never change the price storage scale" not in plan:
        failures.append("plan: missing 'Never change the price storage scale' guard")
    if "Never perform money arithmetic in `Int`" not in plan:
        failures.append("plan: missing Int money guard")
    if ("Never express a stop, target, or trailing-stop distance in raw price units"
            not in plan):
        failures.append("plan: missing pip-distance guard")

    # 4. Step 1.1 fields must be *Units, not *Cents.
    s11 = plan.split("## Step 1.1")[1].split("## Step 1.2")[0]
    for field in ("openUnits", "highUnits", "lowUnits", "closeUnits"):
        if field not in s11:
            failures.append(f"Step 1.1 missing {field}")
    for bad in ("openCents:", "highCents:", "lowCents:", "closeCents:"):
        if bad in s11:
            failures.append(f"Step 1.1 still declares {bad}")


def scan_sources(source_root: Path, failures: list[str]) -> int:
    """Check 5: Kotlin sources must not reintroduce cents or Double money.

    The doc checks above cannot see code, so a developer could add
    `val priceCents: Int` or a `Double` price field and every doc check would
    still pass. These rules catch that at the source level.

    [source_root] is the tree to scan: the repo root normally, or a snapshot of
    the staged blobs when the pre-commit hook calls this. Returns the number of
    sources scanned.
    """
    source_files: list[Path] = []
    for sub in SOURCE_SUBDIRS:
        root = source_root / sub
        if root.exists():
            source_files.extend(sorted(root.rglob("*.kt")))

    for src in source_files:
        rel = src.relative_to(source_root).as_posix()
        if rel in ALLOWED:
            continue
        for i, line in enumerate(src.read_text().splitlines(), 1):
            stripped = line.strip()
            if stripped.startswith("//") or stripped.startswith("*"):
                continue
            # Only the code before a trailing `//` can be a regression. Scanning
            # the comment too would flag prose that *describes* the forbidden
            # pattern -- including the comment above that explains why the rule
            # is shaped this way. The old escape skipped any line containing
            # "never", "avoid" or similar, which meant a real hit could be
            # silenced by wording, e.g. `val priceCents = priceUnits / 100`.
            code = line.split("//", 1)[0]
            for pat, label in SOURCE_RULES:
                if re.search(pat, code):
                    failures.append(f"{rel}:{i}: {label}: {stripped[:80]}")
    return len(source_files)


def main(argv: list[str] | None = None) -> int:
    argv = sys.argv if argv is None else argv
    failures: list[str] = []

    # `--sources DIR` scans a source tree instead of the working tree, skipping
    # the doc checks. The pre-commit hook uses it to scan the *staged* blobs: a
    # file staged in part differs from what is on disk, so scanning the working
    # tree would pass a commit whose staged content still divides a price by 100.
    if len(argv) > 2 and argv[1] == "--sources":
        source_root = Path(argv[2])
        scanned = scan_sources(source_root, failures)
        print(f"kotlin sources scanned: {scanned}")
        if failures:
            print(f"FAILED ({len(failures)}):")
            for f in failures:
                print(f"  - {f}")
            return 1
        print("PASSED")
        return 0

    check_docs(failures)
    scanned = scan_sources(ROOT, failures)

    print("=" * 66)
    print("PRICE SCALE CONSISTENCY CHECK")
    print("=" * 66)
    print(f"documents checked: {len(DOCS)}")
    print(f"kotlin sources scanned: {scanned}")
    print()
    if failures:
        print(f"FAILED ({len(failures)}):")
        for f in failures:
            print(f"  - {f}")
        return 1

    print("PASSED")
    print()
    print("  - no live 'integer cents' instruction remains")
    print("  - PRICE_UNITS_PER_USD = 1000 present in plan and AGENTS.md")
    print("  - PRICE_UNITS_PER_PIP defined")
    print("  - all three 'Never ...' guards present in the agent workflow")
    print("  - Step 1.1 uses *Units fields and declares no *Cents fields")
    print("  - no Kotlin source reintroduces cents or Double money")
    return 0


if __name__ == "__main__":
    sys.exit(main())
