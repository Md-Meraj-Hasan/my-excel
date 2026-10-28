import pytest
from niceexcel_api.reporting_metadata import (
    CHART_TYPES, empty_metadata, validate_metadata,
)


def test_empty_metadata_has_stable_versioned_shape():
    assert empty_metadata() == {
        "schema_version": 1, "tables": [], "charts": [], "dashboards": []
    }


def test_table_metadata_normalizes_range_and_defaults():
    result = validate_metadata({"tables": [{
        "id": "sales-table", "name": "Sales", "sheet": "Sheet1",
        "source_range": "$a$1:$d$20"
    }]})
    table = result["tables"][0]
    assert table["source_range"] == "A1:D20"
    assert table["has_header"] is True
    assert table["filter_enabled"] is True
    assert table["total_row"] is False


def test_all_six_chart_types_are_accepted():
    doc = {"charts": [{
        "id": f"chart-{kind}", "sheet": "Sheet1", "source_range": "A1:B4",
        "chart_type": kind
    } for kind in sorted(CHART_TYPES)]}
    result = validate_metadata(doc)
    assert {chart["chart_type"] for chart in result["charts"]} == CHART_TYPES
    assert all(chart["linked"] is True for chart in result["charts"])


@pytest.mark.parametrize("doc", [
    {"schema_version": 2},
    {"charts": [{"id": "bad chart", "sheet": "Sheet1", "source_range": "A1:B4"}]},
    {"charts": [{"id": "chart1", "sheet": "Sheet1", "source_range": "A1:B4", "chart_type": "radar"}]},
    {"tables": [{"id": "table1", "sheet": "Sheet1", "source_range": "A1:B0"}]},
    {"dashboards": [{"id": "dash1", "components": [{"id": "c1", "type": "chart", "x": -1}]}]},
])
def test_invalid_metadata_is_rejected(doc):
    with pytest.raises(ValueError):
        validate_metadata(doc)


def test_dashboard_component_geometry_and_reference_are_preserved():
    doc = {"dashboards": [{"id": "overview", "name": "Overview", "components": [
        {"id": "monthly-sales", "type": "chart", "ref_id": "sales-chart", "x": 2, "y": 1, "width": 6, "height": 4}
    ]}]}
    component = validate_metadata(doc)["dashboards"][0]["components"][0]
    assert (component["x"], component["y"], component["width"], component["height"]) == (2, 1, 6, 4)
    assert component["ref_id"] == "sales-chart"
