#!/usr/bin/env python3
"""Sanity-check the deterministic Calc integration fixture; does not calculate formulas."""
import json
import sys
from pathlib import Path

path = Path(sys.argv[1] if len(sys.argv) > 1 else "testdata/libreoffice/formula-cases.json")
data = json.loads(path.read_text(encoding="utf-8"))
errors = []
if data.get("schema_version") != 1:
    errors.append("schema_version must be 1")
if data.get("input_formula_grammar") != "EXCEL_A1_ENGLISH":
    errors.append("formula input grammar must be EXCEL_A1_ENGLISH")
sheets = data.get("sheets", {})
expected = data.get("expected", [])
seen = set()
for item in expected:
    key = (item.get("sheet"), item.get("cell"))
    if key in seen:
        errors.append(f"duplicate expected result: {key}")
    seen.add(key)
    if key[0] not in sheets or key[1] not in sheets.get(key[0], {}):
        errors.append(f"expected target is missing from fixture sheets: {key}")
    elif not str(sheets[key[0]][key[1]]).startswith("="):
        errors.append(f"expected target is not a formula: {key}")
required_categories = {"DIV_ZERO", "NUM", "VALUE", "REF"}
actual_categories = {x.get("category") for x in expected if x.get("type") == "error"}
if not required_categories.issubset(actual_categories):
    errors.append(f"missing error categories: {sorted(required_categories - actual_categories)}")
if not data.get("mutation_tests"):
    errors.append("mutation_tests are required")
if data.get("device_acceptance", {}).get("require_no_poi_python_or_kotlin_formula_fallback") is not True:
    errors.append("device acceptance must forbid formula fallback")
result = {"fixture": str(path), "formula_expectations": len(expected), "result": "PASS" if not errors else "FAIL", "errors": errors}
print(json.dumps(result, indent=2))
sys.exit(1 if errors else 0)
