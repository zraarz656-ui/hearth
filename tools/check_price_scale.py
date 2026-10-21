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

print("=" * 66)
print("PRICE SCALE CONSISTENCY CHECK")
print("=" * 66)
print(f"documents checked: {len(DOCS)}")
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
