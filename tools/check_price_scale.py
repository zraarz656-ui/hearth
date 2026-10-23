"""Verify the price-scale decision is consistent across all project docs.

Guards against a future edit silently reintroducing cents.
"""
import re
import sys
from pathlib import Path

ROOT = Path("/workspace/project")
DOCS = [
    ROOT / "TRADEQUEST_DEVELOPMENT_PLAN.md",
    ROOT / "AGENTS.md",
    ROOT / "docs" / "adr-0001-price-scale.md",
    ROOT / "docs" / "architecture.md",
    ROOT / "README.md",
]

failures = []
notes = []

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
                failures.append(f"{doc.name}:{i}: live 'cents' instruction: {line.strip()[:90]}")

# 2. The canonical constants must appear in plan and AGENTS.md.
for doc in [ROOT / "TRADEQUEST_DEVELOPMENT_PLAN.md", ROOT / "AGENTS.md"]:
    text = doc.read_text()
    if "PRICE_UNITS_PER_USD = 1000" not in text:
        failures.append(f"{doc.name}: missing PRICE_UNITS_PER_USD = 1000")

if "PRICE_UNITS_PER_PIP" not in (ROOT / "TRADEQUEST_DEVELOPMENT_PLAN.md").read_text():
    failures.append("plan: missing PRICE_UNITS_PER_PIP definition")

# 3. The no-change rule must be in the agent workflow section.
plan = (ROOT / "TRADEQUEST_DEVELOPMENT_PLAN.md").read_text()
if "Never change the price storage scale" not in plan:
    failures.append("plan: missing 'Never change the price storage scale' guard")
if "Never perform money arithmetic in `Int`" not in plan:
    failures.append("plan: missing Int money guard")
if "Never express a stop, target, or trailing-stop distance in raw price units" not in plan:
    failures.append("plan: missing pip-distance guard")

# 4. Step 1.1 fields must be *Units, not *Cents.
s11 = plan.split("## Step 1.1")[1].split("## Step 1.2")[0]
for field in ("openUnits", "highUnits", "lowUnits", "closeUnits"):
    if field not in s11:
        failures.append(f"Step 1.1 missing {field}")
for bad in ("openCents:", "highCents:", "lowCents:", "closeCents:"):
    if bad in s11:
        failures.append(f"Step 1.1 still declares {bad}")

# 5. Kotlin sources must not reintroduce a cents scale or a Double money type.
#
# The doc checks above cannot see code, so a developer could add
# `val priceCents: Int` or a `Double` price field and every doc check would
# still pass. These rules catch that at the source level.
SOURCE_ROOTS = [
    ROOT / "app" / "src" / "main" / "java",
    ROOT / "app" / "src" / "test" / "java",
]
SOURCE_RULES = [
    # A field, parameter or local named like a cents quantity.
    (r"\b(price|open|high|low|close|stop|target|entry|exit|fill|bid|ask|sl|tp)Cents\b",
     "cents-named price field"),
    # Dividing a price by 100 to get dollars is the cents-scale conversion.
    (r"/\s*100\b(?!\s*\d)",
     "possible cents conversion (division by 100)"),
    # Authoritative money must not be Float or Double.
    (r"\b(balance|equity|pnl|profit|loss|margin|commission|swap|drawdown)"
     r"\w*\s*:\s*(Double|Float)\b",
     "money stored as Double/Float"),
]
# PriceScale.kt legitimately contains /100-style pip maths and usdToUnits.
ALLOWED = {ROOT / "app" / "src" / "main" / "java" / "com" / "tradequest" / "app"
           / "core" / "market" / "PriceScale.kt"}

source_files = []
for root in SOURCE_ROOTS:
    if root.exists():
        source_files.extend(sorted(root.rglob("*.kt")))

for src in source_files:
    if src in ALLOWED:
        continue
    for i, line in enumerate(src.read_text().splitlines(), 1):
        stripped = line.strip()
        if stripped.startswith("//") or stripped.startswith("*"):
            continue
        low = line.lower()
        if any(k in low for k in ("never", "not cents", "avoid", "forbidden")):
            continue
        for pat, label in SOURCE_RULES:
            if re.search(pat, line):
                failures.append(f"{src.relative_to(ROOT)}:{i}: {label}: {stripped[:80]}")

print("=" * 66)
print("PRICE SCALE CONSISTENCY CHECK")
print("=" * 66)
print(f"documents checked: {len(DOCS)}")
print(f"kotlin sources scanned: {len(source_files)}")
print()
if failures:
    print(f"FAILED ({len(failures)}):")
    for f in failures:
        print(f"  - {f}")
    sys.exit(1)
else:
    print("PASSED")
    print()
    print("  - no live 'integer cents' instruction remains")
    print("  - PRICE_UNITS_PER_USD = 1000 present in plan and AGENTS.md")
    print("  - PRICE_UNITS_PER_PIP defined")
    print("  - all three 'Never ...' guards present in the agent workflow")
    print("  - Step 1.1 uses *Units fields and declares no *Cents fields")
    print("  - no Kotlin source reintroduces cents or Double money")
