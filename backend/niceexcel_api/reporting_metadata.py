"""Versioned metadata contract for NiceExcel tables, charts and dashboards.

This module deliberately does not mutate workbooks or add UI behavior. It gives
web and Android-side Python code one stable shape to validate before the next
phase wires metadata persistence and rendering into the workbook lifecycle.
"""
from __future__ import annotations

import re
from copy import deepcopy
from typing import Any

SCHEMA_VERSION = 1
CHART_TYPES = frozenset({"bar", "column", "line", "pie", "donut", "combo"})
EXPORT_FORMATS = frozenset({"pdf", "png"})
_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$")
_CELL_RE = re.compile(r"^[A-Za-z]{1,3}[1-9][0-9]*$")
_RANGE_RE = re.compile(r"^\$?([A-Za-z]{1,3})\$?([1-9][0-9]*)(?::\$?([A-Za-z]{1,3})\$?([1-9][0-9]*))?$")


def empty_metadata() -> dict[str, Any]:
    """Return a fresh, versioned reporting metadata document."""
    return {"schema_version": SCHEMA_VERSION, "tables": [], "charts": [], "dashboards": []}


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def _text(value: Any, field: str, *, maximum: int = 120, allow_empty: bool = False) -> str:
    _require(isinstance(value, str), f"{field} must be a string")
    value = value.strip()
    _require(allow_empty or bool(value), f"{field} cannot be empty")
    _require(len(value) <= maximum, f"{field} exceeds {maximum} characters")
    return value


def _id(value: Any, field: str = "id") -> str:
    value = _text(value, field, maximum=64)
    _require(bool(_ID_RE.fullmatch(value)), f"{field} contains unsupported characters")
    return value


def _sheet(value: Any) -> str:
    value = _text(value, "sheet", maximum=31)
    _require("[" not in value and "]" not in value and ":" not in value and "*" not in value and "?" not in value and "/" not in value and "\\" not in value, "sheet contains invalid Excel characters")
    return value


def _range(value: Any, field: str = "source_range") -> str:
    value = _text(value, field, maximum=100).replace(" ", "").replace("$", "")
    _require("!" not in value, f"{field} must not include a sheet name; use the separate sheet field")
    _require(bool(_RANGE_RE.fullmatch(value)), f"{field} must be a cell or rectangular range such as A1:D20")
    return value.upper()


def _unique_ids(items: list[dict[str, Any]], section: str) -> None:
    ids = [item["id"] for item in items]
    _require(len(ids) == len(set(ids)), f"{section} IDs must be unique")


def validate_metadata(document: Any) -> dict[str, Any]:
    """Validate and return a normalized copy of a reporting metadata document.

    Unknown top-level/record properties are retained for forward compatibility;
    required fields and known enum values are checked strictly.
    """
    _require(isinstance(document, dict), "metadata must be an object")
    version = document.get("schema_version", SCHEMA_VERSION)
    _require(version == SCHEMA_VERSION, f"unsupported reporting metadata schema_version: {version}")
    result = deepcopy(document)
    result["schema_version"] = SCHEMA_VERSION
    for section in ("tables", "charts", "dashboards"):
        value = result.get(section, [])
        _require(isinstance(value, list), f"{section} must be a list")
        result[section] = value
        for index, record in enumerate(value):
            prefix = f"{section}[{index}]"
            _require(isinstance(record, dict), f"{prefix} must be an object")
            record["id"] = _id(record.get("id"), f"{prefix}.id")
            if section == "tables":
                record["sheet"] = _sheet(record.get("sheet"))
                record["source_range"] = _range(record.get("source_range"), f"{prefix}.source_range")
                record["name"] = _text(record.get("name", record["id"]), f"{prefix}.name", maximum=120)
                record["has_header"] = record.get("has_header", True)
                _require(isinstance(record["has_header"], bool), f"{prefix}.has_header must be boolean")
                record["style"] = _text(record.get("style", "blue"), f"{prefix}.style", maximum=40)
                record["filter_enabled"] = record.get("filter_enabled", True)
                record["total_row"] = record.get("total_row", False)
                _require(isinstance(record["filter_enabled"], bool), f"{prefix}.filter_enabled must be boolean")
                _require(isinstance(record["total_row"], bool), f"{prefix}.total_row must be boolean")
            elif section == "charts":
                record["sheet"] = _sheet(record.get("sheet"))
                record["source_range"] = _range(record.get("source_range"), f"{prefix}.source_range")
                chart_type = record.get("chart_type", "column")
                _require(chart_type in CHART_TYPES, f"{prefix}.chart_type must be one of {', '.join(sorted(CHART_TYPES))}")
                record["chart_type"] = chart_type
                record["title"] = _text(record.get("title", "Chart"), f"{prefix}.title", maximum=120)
                record["anchor"] = _text(record.get("anchor", "G2"), f"{prefix}.anchor", maximum=8)
                _require(bool(_CELL_RE.fullmatch(record["anchor"])), f"{prefix}.anchor must be a cell address such as G2")
                record["linked"] = record.get("linked", True)
                _require(isinstance(record["linked"], bool), f"{prefix}.linked must be boolean")
                record["series"] = record.get("series", [])
                _require(isinstance(record["series"], list), f"{prefix}.series must be a list")
                for s_index, series in enumerate(record["series"]):
                    _require(isinstance(series, dict), f"{prefix}.series[{s_index}] must be an object")
                    series["name"] = _text(series.get("name", f"Series {s_index + 1}"), f"{prefix}.series[{s_index}].name", maximum=120)
                    if "values_range" in series:
                        series["values_range"] = _range(series["values_range"], f"{prefix}.series[{s_index}].values_range")
                    if "category_range" in series:
                        series["category_range"] = _range(series["category_range"], f"{prefix}.series[{s_index}].category_range")
            else:
                record["name"] = _text(record.get("name", record["id"]), f"{prefix}.name", maximum=120)
                record["title"] = _text(record.get("title", record["name"]), f"{prefix}.title", maximum=120)
                record["components"] = record.get("components", [])
                _require(isinstance(record["components"], list), f"{prefix}.components must be a list")
                component_ids: set[str] = set()
                for c_index, component in enumerate(record["components"]):
                    c_prefix = f"{prefix}.components[{c_index}]"
                    _require(isinstance(component, dict), f"{c_prefix} must be an object")
                    component["id"] = _id(component.get("id"), f"{c_prefix}.id")
                    _require(component["id"] not in component_ids, f"{prefix} component IDs must be unique")
                    component_ids.add(component["id"])
                    component_type = component.get("type")
                    _require(component_type in {"kpi", "chart", "table", "text"}, f"{c_prefix}.type must be kpi, chart, table, or text")
                    component["type"] = component_type
                    for key in ("x", "y", "width", "height"):
                        number = component.get(key, {"x": 0, "y": 0, "width": 4, "height": 3}[key])
                        _require(isinstance(number, (int, float)) and not isinstance(number, bool) and number >= 0, f"{c_prefix}.{key} must be a non-negative number")
                        component[key] = number
                    if component_type in {"chart", "table"} and "ref_id" in component:
                        component["ref_id"] = _id(component["ref_id"], f"{c_prefix}.ref_id")
    for section in ("tables", "charts", "dashboards"):
        _unique_ids(result[section], section)
    return result
