from openpyxl import Workbook
from niceexcel_api.formula_engine import calculate_cell


def run_formula(formula, values):
    wb = Workbook()
    ws = wb.active
    ws.title = "Sheet1"
    for address, value in values.items():
        ws[address] = value
    ws["Z1"] = formula
    return calculate_cell(wb, "Sheet1", "Z1")


def test_xlookup_text_is_case_insensitive():
    result = run_formula('=XLOOKUP("rAvI",A2:A4,B2:B4)', {
        "A2": "Nisha", "B2": "N-01", "A3": "Ravi", "B3": "R-02", "A4": "Omar", "B4": "O-03"
    })
    assert result["calculated_value"] == "R-02"
    assert result["formula_error"] is None


def test_xlookup_matches_numeric_text_and_number():
    result = run_formula("=XLOOKUP(102,A1:A3,B1:B3)", {
        "A1": "101", "B1": "A", "A2": "102", "B2": "B", "A3": "103", "B3": "C"
    })
    assert result["calculated_value"] == "B"


def test_xlookup_if_not_found():
    result = run_formula('=XLOOKUP("missing",A1:A2,B1:B2,"not found")', {
        "A1": "A", "B1": 1, "A2": "B", "B2": 2
    })
    assert result["calculated_value"] == "not found"


def test_xlookup_reverse_search():
    result = run_formula('=XLOOKUP("A",A1:A3,B1:B3,"",0,-1)', {
        "A1": "A", "B1": 1, "A2": "A", "B2": 2, "A3": "A", "B3": 3
    })
    assert result["calculated_value"] == 3
