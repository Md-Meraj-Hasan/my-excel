from __future__ import annotations

import csv
import io
import json
import os
import re
import tempfile
import uuid
from copy import copy
from typing import Any

from .flask_compat import create_app, File, HTTPException, Query, UploadFile, FileResponse, StreamingResponse, BackgroundTask
from flask import send_from_directory
from pathlib import Path
from openpyxl import Workbook, load_workbook
from openpyxl.cell.cell import ILLEGAL_CHARACTERS_RE
from openpyxl.styles import Alignment, Border, Font, PatternFill, Side
from openpyxl.chart import BarChart, LineChart, PieChart, Reference
from openpyxl.worksheet.table import Table, TableStyleInfo
from .reporting_metadata import empty_metadata, validate_metadata
from .reporting_store import (META_SHEET_NAME as _REPORTING_META_SHEET, get_reporting_metadata as _store_get_reporting_metadata, set_reporting_metadata as _store_set_reporting_metadata, visible_sheetnames as _store_visible_sheetnames, ensure_excel_tables as _ensure_excel_tables)
from openpyxl.utils import get_column_letter, range_boundaries
from pydantic import BaseModel, Field
from .formula_engine import calculate_cell
from .dependency_graph import build_dependency_graph

app = create_app(__name__)
app.version = "0.1.0"
app.config["JSON_SORT_KEYS"] = False

# In-memory sessions for the first API milestone. The Android integration will
# add app-private persistent storage and session recovery in a later milestone.
_workbooks: dict[str, Workbook] = {}
# In-memory undo/redo history for the active process. Recovery workbook files persist separately.
_undo_history: dict[str, list[bytes]] = {}
_redo_history: dict[str, list[bytes]] = {}
_HISTORY_LIMIT = 20
_TABLE_STYLE_MAP = {
    "blue": "TableStyleMedium2", "green": "TableStyleMedium4",
    "purple": "TableStyleMedium5", "orange": "TableStyleMedium6",
    "teal": "TableStyleMedium9", "gray": "TableStyleMedium1",
    "dark": "TableStyleMedium3", "light": "TableStyleLight9",
}
_RECOVERY_DIR = Path(os.environ.get("NICEEXCEL_RECOVERY_DIR", str(Path(__file__).resolve().parent.parent / "data" / "recovery")))
_RECOVERY_DIR.mkdir(parents=True, exist_ok=True)
_TEMP_DIR = Path(os.environ.get("NICEEXCEL_TEMP_DIR", str(_RECOVERY_DIR.parent / "temp"))).expanduser().resolve()
_TEMP_DIR.mkdir(parents=True, exist_ok=True)
os.environ["TMPDIR"] = str(_TEMP_DIR)
os.environ["TMP"] = str(_TEMP_DIR)
os.environ["TEMP"] = str(_TEMP_DIR)
tempfile.tempdir = str(_TEMP_DIR)


def _recovery_path(book_id: str) -> Path:
    # IDs are generated UUIDs; strict validation prevents path traversal.
    try:
        safe_id = str(uuid.UUID(book_id))
    except (ValueError, AttributeError) as exc:
        raise HTTPException(status_code=404, detail="Workbook session not found") from exc
    return _RECOVERY_DIR / f"{safe_id}.xlsx"


def _visible_sheetnames(wb: Workbook) -> list[str]:
    return _store_visible_sheetnames(wb)


def _get_reporting_metadata(wb: Workbook) -> dict[str, Any]:
    return _store_get_reporting_metadata(wb)


def _set_reporting_metadata(wb: Workbook, document: Any) -> dict[str, Any]:
    return _store_set_reporting_metadata(wb, document)


def _persist_workbook(book_id: str) -> None:
    wb = _workbooks.get(book_id)
    if wb is None:
        return
    path = _recovery_path(book_id)
    temp = path.with_suffix(".tmp")
    wb.save(temp)
    temp.replace(path)



def _snapshot(wb: Workbook) -> bytes:
    buffer = io.BytesIO()
    wb.save(buffer)
    return buffer.getvalue()


def _record_history(book_id: str) -> None:
    wb = _book(book_id)
    history = _undo_history.setdefault(book_id, [])
    history.append(_snapshot(wb))
    if len(history) > _HISTORY_LIMIT:
        del history[0]
    _redo_history[book_id] = []


def _restore_snapshot(book_id: str, payload: bytes, persist: bool = True) -> Workbook:
    wb = load_workbook(io.BytesIO(payload), data_only=False)
    _ensure_excel_tables(wb)
    _workbooks[book_id] = wb
    if persist:
        _persist_workbook(book_id)
    return wb


def _load_recovery(book_id: str) -> Workbook | None:
    path = _recovery_path(book_id)
    if not path.exists():
        return None
    try:
        wb = load_workbook(path, data_only=False)
        _ensure_excel_tables(wb)
        return wb
    except Exception:
        return None


class CreateWorkbookRequest(BaseModel):
    sheet_name: str = Field(default="Sheet1", min_length=1, max_length=31)


class CellUpdate(BaseModel):
    cell: str = Field(pattern=r"^[A-Za-z]{1,3}[1-9][0-9]*$")
    value: Any = None


class UpdateCellsRequest(BaseModel):
    sheet: str = "Sheet1"
    cells: list[CellUpdate] = Field(min_items=1, max_items=5000)




class FormulaPreviewRequest(BaseModel):
    sheet: str = "Sheet1"
    cell: str = Field(pattern=r"^[A-Za-z]{1,3}[1-9][0-9]*$")
    formula: str = Field(max_length=10000)


class RetrieveDataRequest(BaseModel):
    source_sheet: str = Field(min_length=1, max_length=31)
    source_range: str = Field(default="A1:D1000", min_length=2, max_length=100)
    match_column: str = Field(default="A", pattern=r"^[A-Za-z]{1,3}$")
    lookup_ids: list[Any] = Field(min_items=1, max_items=5000)
    destination_sheet: str = Field(min_length=1, max_length=31)
    destination_cell: str = Field(default="A1", pattern=r"^[A-Za-z]{1,3}[1-9][0-9]*$")


class RowInsertRequest(BaseModel):
    sheet: str = "Sheet1"
    row: int = Field(ge=1, le=100000)


class SheetRequest(BaseModel):
    name: str = Field(min_length=1, max_length=31)


class RenameSheetRequest(BaseModel):
    old_name: str = Field(min_length=1, max_length=31)
    new_name: str = Field(min_length=1, max_length=31)


class RangeRequest(BaseModel):
    sheet: str
    cell_range: str = Field(alias="range", min_length=2, max_length=100)


class StyleRequest(RangeRequest):
    bold: bool | None = None
    italic: bool | None = None
    font_name: str | None = Field(default=None, max_length=80)
    font_size: float | None = Field(default=None, ge=6, le=96)
    font_color: str | None = Field(default=None, pattern=r"^#?[0-9A-Fa-f]{6}$")
    fill_color: str | None = Field(default=None, pattern=r"^#?[0-9A-Fa-f]{6}$")
    horizontal: str | None = Field(default=None, pattern=r"^(general|left|center|right|fill|justify|centerContinuous|distributed)$")
    number_format: str | None = Field(default=None, max_length=100)


class MergeRequest(RangeRequest):
    pass


class FreezeRequest(BaseModel):
    sheet: str
    cell: str = Field(default="A1", pattern=r"^[A-Za-z]{1,3}[1-9][0-9]*$")


class FindReplaceRequest(BaseModel):
    sheet: str
    find: str = Field(min_length=1, max_length=500)
    replace: str = Field(default="", max_length=500)
    match_case: bool = False


class SortRequest(RangeRequest):
    key_column: str = Field(pattern=r"^[A-Za-z]{1,3}$")
    descending: bool = False


class ReportingMetadataRequest(BaseModel):
    metadata: dict[str, Any]


class SmartTableRequest(BaseModel):
    sheet: str = Field(min_length=1, max_length=31)
    source_range: str = Field(min_length=2, max_length=100)
    name: str = Field(default="Table", min_length=1, max_length=120)
    table_id: str | None = Field(default=None, max_length=64)
    has_header: bool = True
    style: str = Field(default="blue", pattern=r"^(blue|green|purple|orange|teal|gray|dark|light)$")
    filter_enabled: bool = True
    total_row: bool = False


def _book(book_id: str) -> Workbook:
    wb = _workbooks.get(book_id)
    if wb is None:
        wb = _load_recovery(book_id)
        if wb is not None:
            _workbooks[book_id] = wb
    if wb is None:
        raise HTTPException(status_code=404, detail="Workbook session not found")
    return wb


def _sheet(wb: Workbook, name: str):
    if name == _REPORTING_META_SHEET or name not in wb.sheetnames:
        raise HTTPException(status_code=404, detail=f"Worksheet not found: {name}")
    return wb[name]


def _color(value: str | None) -> str | None:
    if value is None:
        return None
    return value.lstrip("#").upper()


def _safe_value(value: Any) -> Any:
    if isinstance(value, str):
        value = ILLEGAL_CHARACTERS_RE.sub("", value)
        # Keep user-entered formulas as formulas. Do not auto-convert arbitrary
        # strings beginning with = in this prototype's CSV-import endpoint.
    return value


def _new_session(wb: Workbook) -> dict[str, Any]:
    book_id = str(uuid.uuid4())
    _ensure_excel_tables(wb)
    _workbooks[book_id] = wb
    _persist_workbook(book_id)
    return {"workbook_id": book_id, "sheets": _visible_sheetnames(wb)}


def _cell_json(cell) -> dict[str, Any]:
    return {
        "cell": cell.coordinate,
        "value": cell.value,
        "data_type": cell.data_type,
        "number_format": cell.number_format,
        "style": {
            "bold": cell.font.bold,
            "italic": cell.font.italic,
            "font_name": cell.font.name,
            "font_size": cell.font.sz,
            "font_color": cell.font.color.rgb if cell.font.color and cell.font.color.type == "rgb" else None,
            "fill_color": cell.fill.fgColor.rgb if cell.fill.fgColor.type == "rgb" else None,
            "horizontal": cell.alignment.horizontal,
        },
    }


@app.get("/health")
def health():
    return {"status": "ok", "service": "nice-excel-api", "version": app.version, "offline_ready": True}


@app.post("/workbooks")
def create_workbook(request: CreateWorkbookRequest):
    name = request.sheet_name.strip()
    if not name or any(ch in name for ch in r"[]:*?/\\"):
        raise HTTPException(status_code=422, detail="Invalid worksheet name")
    wb = Workbook()
    wb.active.title = name
    return _new_session(wb)


@app.post("/workbooks/open")
async def open_workbook(file: UploadFile = File(...)):
    filename = (file.filename or "").lower()
    if not (filename.endswith(".xlsx") or filename.endswith(".xlsm")):
        raise HTTPException(status_code=415, detail="Only .xlsx and .xlsm files are supported here")

    # Stream the uploaded workbook to app-local disk instead of reading the whole
    # upload into a Python bytes/BytesIO buffer. The intermediate file is always removed.
    _TEMP_DIR.mkdir(parents=True, exist_ok=True)
    temp_path: Path | None = None
    total = 0
    try:
        with tempfile.NamedTemporaryFile(prefix="niceexcel_upload_", suffix=(".xlsm" if filename.endswith(".xlsm") else ".xlsx"),
                                         dir=str(_TEMP_DIR), delete=False) as temp_file:
            temp_path = Path(temp_file.name)
            while True:
                chunk = await file.read(1024 * 1024)
                if not chunk:
                    break
                total += len(chunk)
                if total > 50 * 1024 * 1024:
                    raise HTTPException(status_code=413, detail="Workbook exceeds 50 MB prototype limit")
                temp_file.write(chunk)
        if total == 0:
            raise HTTPException(status_code=400, detail="Uploaded file is empty")
        try:
            wb = load_workbook(temp_path, data_only=False, keep_vba=filename.endswith(".xlsm"))
        except Exception as exc:
            raise HTTPException(status_code=400, detail=f"Could not read workbook: {type(exc).__name__}") from exc
        return _new_session(wb)
    finally:
        if temp_path is not None:
            try:
                temp_path.unlink(missing_ok=True)
            except OSError:
                pass
        await file.close()

@app.get("/workbooks/{book_id}")
def workbook_info(book_id: str):
    wb = _book(book_id)
    return {
        "workbook_id": book_id,
        "sheets": [
            {"name": ws.title, "max_row": ws.max_row, "max_column": ws.max_column,
             "freeze_panes": str(ws.freeze_panes) if ws.freeze_panes else None,
             "merged_ranges": [str(r) for r in ws.merged_cells.ranges]}
            for ws in wb.worksheets if ws.title != _REPORTING_META_SHEET
        ],
        "active_sheet": wb.active.title,
        "formula_calculation": "limited_nice_excel_subset_evaluator",
    }


@app.get("/workbooks/{book_id}/range")
def get_range(book_id: str, sheet: str, cell_range: str = Query(default="A1:Z50", alias="range")):
    wb = _book(book_id)
    ws = _sheet(wb, sheet)
    try:
        min_col, min_row, max_col, max_row = range_boundaries(cell_range)
    except Exception as exc:
        raise HTTPException(status_code=422, detail="Invalid cell range") from exc
    if not all((min_col, min_row, max_col, max_row)):
        raise HTTPException(status_code=422, detail="Range must include start and end cells")
    if (max_row - min_row + 1) * (max_col - min_col + 1) > 10000:
        raise HTTPException(status_code=413, detail="Range is too large; request at most 10,000 cells")
    rows = []
    for r in range(min_row, max_row + 1):
        row_data = []
        for c in range(min_col, max_col + 1):
            cell = ws.cell(r, c)
            item = _cell_json(cell)
            item.update(calculate_cell(wb, sheet, cell.coordinate))
            row_data.append(item)
        rows.append(row_data)
    return {"sheet": sheet, "range": cell_range, "rows": rows, "formula_engine": "Nice Excel subset evaluator"}




@app.post("/workbooks/{book_id}/formula-preview")
def preview_formula(book_id: str, request: FormulaPreviewRequest):
    """Preview a formula without committing it to the workbook or undo history."""
    wb = _book(book_id)
    ws = _sheet(wb, request.sheet)
    cell = ws[request.cell.upper()]
    previous = cell.value
    try:
        cell.value = request.formula if request.formula else None
        result = calculate_cell(wb, request.sheet, cell.coordinate)
        return {"sheet": request.sheet, "cell": cell.coordinate, "formula": request.formula,
                "calculated_value": result.get("calculated_value"),
                "formula_error": result.get("formula_error"), "preview_only": True}
    finally:
        cell.value = previous


@app.patch("/workbooks/{book_id}/cells")
def update_cells(book_id: str, request: UpdateCellsRequest):
    wb = _book(book_id)
    ws = _sheet(wb, request.sheet)
    _record_history(book_id)
    for item in request.cells:
        ws[item.cell.upper()] = _safe_value(item.value)
    _persist_workbook(book_id)
    return {"updated": len(request.cells), "sheet": request.sheet, "autosaved": True}


@app.post("/workbooks/{book_id}/retrieve-data")
def retrieve_data(book_id: str, request: RetrieveDataRequest):
    """Copy every source row whose match-column value is in lookup_ids to a destination range."""
    wb = _book(book_id)
    source = _sheet(wb, request.source_sheet)
    if request.destination_sheet in wb.sheetnames:
        destination = wb[request.destination_sheet]
    else:
        name = request.destination_sheet.strip()
        if not name or any(ch in name for ch in r"[]:*?/\\"):
            raise HTTPException(status_code=422, detail="Invalid destination worksheet name")
        destination = None
    try:
        min_col, min_row, max_col, max_row = range_boundaries(request.source_range)
    except Exception as exc:
        raise HTTPException(status_code=422, detail="Invalid source range; use a range such as A1:D1000") from exc
    if not min_col or not min_row or not max_col or not max_row or (max_col-min_col+1)*(max_row-min_row+1) > 50000:
        raise HTTPException(status_code=422, detail="Source range must be bounded and contain at most 50,000 cells")
    from openpyxl.utils.cell import column_index_from_string
    match_col = column_index_from_string(request.match_column.upper())
    if match_col < min_col or match_col > max_col:
        raise HTTPException(status_code=422, detail="Match column must be inside the source range")
    dest_match = re.fullmatch(r"([A-Za-z]{1,3})([1-9][0-9]*)", request.destination_cell)
    dest_col = column_index_from_string(dest_match.group(1).upper()); dest_row = int(dest_match.group(2))
    def key(value):
        if value is None: return ""
        if isinstance(value, bool): return str(value).casefold()
        if isinstance(value, (int, float)) and float(value).is_integer(): return str(int(value))
        return str(value).strip().casefold()
    lookup = {key(value) for value in request.lookup_ids if key(value)}
    if not lookup: raise HTTPException(status_code=422, detail="Enter at least one non-empty lookup ID")
    matched=[]
    for r in range(min_row, max_row+1):
        if key(source.cell(r, match_col).value) in lookup:
            matched.append([source.cell(r,c).value for c in range(min_col,max_col+1)])
    if dest_row + len(matched)-1 > 1048576 or dest_col + (max_col-min_col) > 16384:
        raise HTTPException(status_code=422, detail="Destination range exceeds worksheet limits")
    _record_history(book_id)
    if destination is None:
        destination = wb.create_sheet(request.destination_sheet.strip())
    for ri, values in enumerate(matched):
        for ci, value in enumerate(values):
            destination.cell(dest_row+ri,dest_col+ci).value=value
    _persist_workbook(book_id)
    return {"source_sheet":source.title,"source_range":request.source_range,"match_column":request.match_column.upper(),
            "lookup_ids":list(request.lookup_ids),"destination_sheet":destination.title,"destination_cell":request.destination_cell,
            "matched_rows":len(matched),"written_cells":len(matched)*(max_col-min_col+1),"rows":matched,"autosaved":True}


@app.post("/workbooks/{book_id}/rows")
def insert_row(book_id: str, request: RowInsertRequest):
    """Insert one worksheet row at the requested 1-based row index."""
    wb = _book(book_id)
    ws = _sheet(wb, request.sheet)
    _record_history(book_id)
    ws.insert_rows(request.row, 1)
    # Keep reporting table ranges aligned with inserted worksheet rows. A row
    # inserted inside a table, or immediately before its first data row/after
    # its last row, becomes part of that table. Rows inserted above a table
    # shift the entire table down without changing its size.
    metadata = _get_reporting_metadata(wb)
    changed = False
    for definition in metadata.get("tables", []):
        if definition.get("sheet") != request.sheet:
            continue
        try:
            min_col, min_row, max_col, max_row = range_boundaries(definition["source_range"])
        except Exception:
            continue
        old_ref = definition["source_range"]
        if request.row <= min_row:
            min_row += 1
            max_row += 1
        elif min_row <= request.row <= max_row + 1:
            max_row += 1
        else:
            continue
        definition["source_range"] = f"{get_column_letter(min_col)}{min_row}:{get_column_letter(max_col)}{max_row}"
        if definition.get("data_range"):
            try:
                dmin_col, dmin_row, dmax_col, dmax_row = range_boundaries(definition["data_range"])
                if request.row <= dmin_row:
                    dmin_row += 1
                    dmax_row += 1
                elif dmin_row <= request.row <= dmax_row + 1:
                    dmax_row += 1
                definition["data_range"] = f"{get_column_letter(dmin_col)}{dmin_row}:{get_column_letter(dmax_col)}{dmax_row}"
            except Exception:
                pass
        # Update any in-memory native table object as well as the persisted metadata.
        excel_name = definition.get("excel_table_name")
        if excel_name and excel_name in ws.tables:
            ws.tables[excel_name].ref = definition["source_range"]
        changed = changed or old_ref != definition["source_range"]
    if changed:
        _set_reporting_metadata(wb, metadata)
    _persist_workbook(book_id)
    return {"inserted_row": request.row, "sheet": request.sheet, "tables_updated": changed, "autosaved": True}


@app.post("/workbooks/{book_id}/sheets")
def add_sheet(book_id: str, request: SheetRequest):
    wb = _book(book_id)
    name = request.name.strip()
    if not name or any(ch in name for ch in r"[]:*?/\\"):
        raise HTTPException(status_code=422, detail="Invalid worksheet name")
    if name in wb.sheetnames:
        raise HTTPException(status_code=409, detail="Worksheet name already exists")
    _record_history(book_id)
    ws = wb.create_sheet(name)
    _persist_workbook(book_id)
    return {"name": ws.title, "sheets": _visible_sheetnames(wb), "autosaved": True}


@app.patch("/workbooks/{book_id}/sheets")
def rename_sheet(book_id: str, request: RenameSheetRequest):
    wb = _book(book_id)
    _sheet(wb, request.old_name)
    name = request.new_name.strip()
    if not name or any(ch in name for ch in r"[]:*?/\\"):
        raise HTTPException(status_code=422, detail="Invalid worksheet name")
    if name != request.old_name and name in wb.sheetnames:
        raise HTTPException(status_code=409, detail="Worksheet name already exists")
    _record_history(book_id)
    wb[request.old_name].title = name
    _persist_workbook(book_id)
    return {"sheets": _visible_sheetnames(wb), "autosaved": True}


@app.delete("/workbooks/{book_id}/sheets/{sheet_name}")
def delete_sheet(book_id: str, sheet_name: str):
    wb = _book(book_id)
    ws = _sheet(wb, sheet_name)
    if len(_visible_sheetnames(wb)) == 1:
        raise HTTPException(status_code=409, detail="A workbook must retain at least one worksheet")
    _record_history(book_id)
    wb.remove(ws)
    _persist_workbook(book_id)
    return {"sheets": _visible_sheetnames(wb), "active_sheet": wb.active.title, "autosaved": True}


@app.patch("/workbooks/{book_id}/style")
def style_range(book_id: str, request: StyleRequest):
    ws = _sheet(_book(book_id), request.sheet)
    try:
        min_col, min_row, max_col, max_row = range_boundaries(request.cell_range)
    except Exception as exc:
        raise HTTPException(status_code=422, detail="Invalid cell range") from exc
    if not all((min_col, min_row, max_col, max_row)) or (max_row-min_row+1)*(max_col-min_col+1) > 10000:
        raise HTTPException(status_code=422, detail="Invalid or excessively large range")
    _record_history(book_id)
    for row in ws.iter_rows(min_row=min_row, max_row=max_row, min_col=min_col, max_col=max_col):
        for cell in row:
            f = copy(cell.font)
            if request.bold is not None: f.bold = request.bold
            if request.italic is not None: f.italic = request.italic
            if request.font_name is not None: f.name = request.font_name
            if request.font_size is not None: f.sz = request.font_size
            if request.font_color is not None: f.color = _color(request.font_color)
            cell.font = f
            if request.fill_color is not None:
                cell.fill = PatternFill(fill_type="solid", fgColor=_color(request.fill_color))
            if request.horizontal is not None:
                a = copy(cell.alignment); a.horizontal = request.horizontal; cell.alignment = a
            if request.number_format is not None: cell.number_format = request.number_format
    _persist_workbook(book_id)
    return {"styled_range": request.cell_range, "autosaved": True}


@app.post("/workbooks/{book_id}/merge")
def merge_range(book_id: str, request: MergeRequest):
    ws = _sheet(_book(book_id), request.sheet)
    try:
        min_col, min_row, max_col, max_row = range_boundaries(request.cell_range)
    except Exception as exc:
        raise HTTPException(status_code=422, detail="Invalid cell range") from exc
    if not all((min_col, min_row, max_col, max_row)) or (min_col == max_col and min_row == max_row):
        raise HTTPException(status_code=422, detail="Merge requires a valid multi-cell range")
    _record_history(book_id)
    ws.merge_cells(request.cell_range)
    _persist_workbook(book_id)
    return {"merged_range": request.cell_range, "autosaved": True}


@app.patch("/workbooks/{book_id}/freeze")
def freeze_panes(book_id: str, request: FreezeRequest):
    ws = _sheet(_book(book_id), request.sheet)
    _record_history(book_id)
    ws.freeze_panes = None if request.cell.upper() == "A1" else request.cell.upper()
    _persist_workbook(book_id)
    return {"sheet": request.sheet, "freeze_panes": str(ws.freeze_panes) if ws.freeze_panes else None, "autosaved": True}


@app.post("/workbooks/{book_id}/find-replace")
def find_replace(book_id: str, request: FindReplaceRequest):
    ws = _sheet(_book(book_id), request.sheet)
    count = 0
    _record_history(book_id)
    for row in ws.iter_rows():
        for cell in row:
            if isinstance(cell.value, str):
                haystack = cell.value if request.match_case else cell.value.lower()
                needle = request.find if request.match_case else request.find.lower()
                if needle in haystack:
                    cell.value = cell.value.replace(request.find, request.replace) if request.match_case else re.sub(re.escape(request.find), lambda _: request.replace, cell.value, flags=re.IGNORECASE)
                    count += 1
    if count:
        _persist_workbook(book_id)
    return {"replaced_cells": count, "autosaved": bool(count)}


@app.post("/workbooks/{book_id}/sort")
def sort_range(book_id: str, request: SortRequest):
    ws = _sheet(_book(book_id), request.sheet)
    try:
        min_col, min_row, max_col, max_row = range_boundaries(request.cell_range)
    except Exception as exc:
        raise HTTPException(status_code=422, detail="Invalid cell range") from exc
    if not all((min_col, min_row, max_col, max_row)) or (max_row-min_row+1)*(max_col-min_col+1) > 20000:
        raise HTTPException(status_code=422, detail="Invalid or excessively large range")
    key_col = ord(request.key_column.upper()[0]) - ord('A') + 1
    if not min_col <= key_col <= max_col:
        raise HTTPException(status_code=422, detail="Sort key column must be inside the selected range")
    _record_history(book_id)
    rows = [[ws.cell(r, c).value for c in range(min_col, max_col + 1)] for r in range(min_row, max_row + 1)]
    key_index = key_col - min_col
    def sort_key(row):
        val = row[key_index]
        return (val is None, type(val).__name__, val if isinstance(val, (int, float, str)) else str(val))
    rows.sort(key=sort_key, reverse=request.descending)
    for offset, values in enumerate(rows):
        for j, value in enumerate(values):
            ws.cell(min_row + offset, min_col + j).value = value
    _persist_workbook(book_id)
    return {"sorted_range": request.cell_range, "rows": len(rows), "autosaved": True}


@app.get("/workbooks/{book_id}/reporting/metadata")
def get_reporting_metadata(book_id: str):
    wb = _book(book_id)
    return {"metadata": _get_reporting_metadata(wb)}


@app.put("/workbooks/{book_id}/reporting/metadata")
def put_reporting_metadata(book_id: str, request: ReportingMetadataRequest):
    wb = _book(book_id)
    try:
        normalized = validate_metadata(request.metadata)
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    _record_history(book_id)
    _set_reporting_metadata(wb, normalized)
    _persist_workbook(book_id)
    return {"metadata": normalized, "autosaved": True}


@app.post("/workbooks/{book_id}/tables")
def create_smart_table(book_id: str, request: SmartTableRequest):
    """Create a formatted Excel table and persist its editable NiceExcel definition."""
    wb = _book(book_id)
    ws = _sheet(wb, request.sheet)
    try:
        min_col, min_row, max_col, max_row = range_boundaries(request.source_range.replace("$", "").replace(" ", ""))
    except Exception as exc:
        raise HTTPException(status_code=422, detail="Invalid table source range") from exc
    if not all((min_col, min_row, max_col, max_row)) or max_row < min_row or max_col < min_col:
        raise HTTPException(status_code=422, detail="Table source must be a rectangular cell range")
    if (max_row - min_row + 1) * (max_col - min_col + 1) > 100000:
        raise HTTPException(status_code=413, detail="Smart Table is limited to 100,000 cells per table")
    data_range = f"{get_column_letter(min_col)}{min_row}:{get_column_letter(max_col)}{max_row}"
    table_end_row = max_row + 1 if request.total_row else max_row
    if request.total_row and any(ws.cell(max_row + 1, col).value is not None for col in range(min_col, max_col + 1)):
        raise HTTPException(status_code=409, detail="Cannot add a totals row because the next row already contains data in the selected columns")
    source_range = f"{get_column_letter(min_col)}{min_row}:{get_column_letter(max_col)}{table_end_row}"
    if request.has_header and max_row == min_row:
        raise HTTPException(status_code=422, detail="A table with headers needs at least one data row")
    # Excel table headers must be non-empty and unique. Repair blanks/duplicates deterministically.
    headers = []
    if request.has_header:
        seen = set()
        for col in range(min_col, max_col + 1):
            raw = ws.cell(min_row, col).value
            header = str(raw).strip() if raw is not None else ""
            if not header:
                header = f"Column{col - min_col + 1}"
            base = header
            suffix = 2
            while header.casefold() in seen:
                header = f"{base}_{suffix}"
                suffix += 1
            seen.add(header.casefold())
            headers.append(header)
    else:
        headers = [f"Column{i + 1}" for i in range(max_col - min_col + 1)]
    if not request.has_header:
        raise HTTPException(status_code=422, detail="Smart Table currently requires a header row so sorting and filters remain reliable. Add column headers first.")
    meta = _get_reporting_metadata(wb)
    table_id = request.table_id or f"table_{uuid.uuid4().hex[:12]}"
    if any(item["id"] == table_id for item in meta["tables"]):
        raise HTTPException(status_code=409, detail="A Smart Table with this ID already exists")
    excel_name = "tbl_" + re.sub(r"[^A-Za-z0-9_]", "_", table_id)
    existing_names = {t.displayName.casefold() for sheet in wb.worksheets for t in getattr(sheet, "tables", {}).values()}
    if excel_name.casefold() in existing_names:
        excel_name = f"{excel_name}_{uuid.uuid4().hex[:6]}"
    _record_history(book_id)
    try:
        # Normalize invalid/duplicate headers only after recording the undo snapshot.
        for col, header in zip(range(min_col, max_col + 1), headers):
            ws.cell(min_row, col).value = header
        if request.total_row:
            ws.cell(table_end_row, min_col).value = "Total"
            for col in range(min_col + 1, max_col + 1):
                letter = get_column_letter(col)
                ws.cell(table_end_row, col).value = f"=SUBTOTAL(109,{letter}{min_row + 1}:{letter}{max_row})"
        table = Table(displayName=excel_name, ref=source_range)
        table.tableStyleInfo = TableStyleInfo(name=_TABLE_STYLE_MAP[request.style], showFirstColumn=False,
                                              showLastColumn=False, showRowStripes=True, showColumnStripes=False)
        table.totalsRowShown = request.total_row
        if request.filter_enabled:
            ws.add_table(table)
        else:
            # Keep the structured table definition but disable the worksheet AutoFilter.
            table.autoFilter = None
            ws.add_table(table)
        # Apply native cell styles as well as the Excel table style. This keeps the
        # design visible in NiceExcel's lightweight Android grid, which does not
        # render OOXML table-style metadata directly.
        palette = {
            "blue": ("1F4E78", "D9EAF7", "F2F7FC"),
            "green": ("217346", "E2F0D9", "F4F9F0"),
            "purple": ("7030A0", "E4DFEC", "F6F2FA"),
            "orange": ("C65911", "FCE4D6", "FFF7F0"),
            "teal": ("0F6B78", "DDEBF7", "F1F8FA"),
            "gray": ("595959", "D9E1F2", "F5F6F8"),
            "dark": ("263746", "D6E0E8", "EEF2F5"),
            "light": ("4472C4", "EAF2F8", "FFFFFF"),
        }
        header_hex, stripe_hex, alternate_hex = palette[request.style]
        for row in range(min_row, table_end_row + 1):
            for col in range(min_col, max_col + 1):
                cell = ws.cell(row, col)
                if row == min_row:
                    cell.fill = PatternFill(fill_type="solid", fgColor=header_hex)
                    cell.font = copy(cell.font)
                    cell.font = Font(name=cell.font.name, sz=cell.font.sz, b=True, i=cell.font.i,
                                     color="FFFFFF", underline=cell.font.underline)
                elif request.total_row and row == table_end_row:
                    cell.fill = PatternFill(fill_type="solid", fgColor=header_hex)
                    cell.font = Font(name=cell.font.name, sz=cell.font.sz, b=True, color="FFFFFF")
                elif (row - min_row) % 2 == 1:
                    cell.fill = PatternFill(fill_type="solid", fgColor=stripe_hex)
                else:
                    cell.fill = PatternFill(fill_type="solid", fgColor=alternate_hex)
        definition = {
            "id": table_id, "name": request.name.strip(), "sheet": request.sheet,
            "source_range": source_range, "data_range": data_range, "has_header": request.has_header,
            "style": request.style, "filter_enabled": request.filter_enabled,
            "total_row": request.total_row, "excel_table_name": excel_name,
        }
        meta["tables"].append(definition)
        _set_reporting_metadata(wb, meta)
        _persist_workbook(book_id)
    except Exception as exc:
        # Restore from the last pre-operation snapshot rather than leave a partial table behind.
        history = _undo_history.get(book_id, [])
        if history:
            _restore_snapshot(book_id, history.pop())
        if isinstance(exc, HTTPException):
            raise
        raise HTTPException(status_code=422, detail=f"Could not create Smart Table: {type(exc).__name__}") from exc
    return {"table": definition, "autosaved": True,
            "header_adjustments": [headers[i] for i in range(len(headers))] if request.has_header else []}


class ChartRequest(BaseModel):
    sheet: str
    data_range: str = Field(alias="range", min_length=3, max_length=100)
    chart_type: str = Field(default="bar", pattern=r"^(bar|line|pie)$")
    title: str = Field(default="Nice Excel Chart", max_length=120)
    anchor: str = Field(default="G2", pattern=r"^[A-Za-z]{1,3}[1-9][0-9]*$")
    from_rows: bool = False


@app.post("/workbooks/{book_id}/charts")
def create_chart(book_id: str, request: ChartRequest):
    wb = _book(book_id)
    ws = _sheet(wb, request.sheet)
    try:
        min_col, min_row, max_col, max_row = range_boundaries(request.data_range)
    except Exception as exc:
        raise HTTPException(status_code=422, detail="Invalid chart data range") from exc
    if not all((min_col, min_row, max_col, max_row)) or max_row - min_row < 1 or max_col - min_col < 1:
        raise HTTPException(status_code=422, detail="Chart range must include labels and at least one data point")
    if (max_row-min_row+1)*(max_col-min_col+1) > 5000:
        raise HTTPException(status_code=413, detail="Chart range is too large")
    data = Reference(ws, min_col=min_col + 1, min_row=min_row, max_col=max_col, max_row=max_row) if request.from_rows else Reference(ws, min_col=min_col + 1, min_row=min_row, max_col=max_col, max_row=max_row)
    # The first column is used as category labels; remaining columns are data series.
    data = Reference(ws, min_col=min_col + 1, min_row=min_row, max_col=max_col, max_row=max_row)
    cats = Reference(ws, min_col=min_col, min_row=min_row + 1, max_row=max_row)
    _record_history(book_id)
    if request.chart_type == "bar":
        chart = BarChart()
        chart.type = "col"
    elif request.chart_type == "line":
        chart = LineChart()
    else:
        chart = PieChart()
    chart.title = request.title
    chart.add_data(data, titles_from_data=True)
    chart.set_categories(cats)
    chart.height = 8
    chart.width = 15
    ws.add_chart(chart, request.anchor.upper())
    _persist_workbook(book_id)
    return {"chart_type": request.chart_type, "sheet": request.sheet, "anchor": request.anchor.upper(), "autosaved": True}



@app.post("/workbooks/{book_id}/undo")
def undo_workbook_change(book_id: str):
    _book(book_id)
    history = _undo_history.get(book_id, [])
    if not history:
        return {"undone": False, "can_undo": False, "can_redo": bool(_redo_history.get(book_id))}
    current = _snapshot(_book(book_id))
    _redo_history.setdefault(book_id, []).append(current)
    if len(_redo_history[book_id]) > _HISTORY_LIMIT:
        del _redo_history[book_id][0]
    previous = history.pop()
    _restore_snapshot(book_id, previous)
    return {"undone": True, "can_undo": bool(history), "can_redo": True, "autosaved": True}


@app.post("/workbooks/{book_id}/redo")
def redo_workbook_change(book_id: str):
    _book(book_id)
    history = _redo_history.get(book_id, [])
    if not history:
        return {"redone": False, "can_undo": bool(_undo_history.get(book_id)), "can_redo": False}
    current = _snapshot(_book(book_id))
    _undo_history.setdefault(book_id, []).append(current)
    if len(_undo_history[book_id]) > _HISTORY_LIMIT:
        del _undo_history[book_id][0]
    next_state = history.pop()
    _restore_snapshot(book_id, next_state)
    return {"redone": True, "can_undo": True, "can_redo": bool(history), "autosaved": True}

@app.post("/workbooks/{book_id}/history-batch")
def workbook_history_batch(book_id: str, request: dict[str, Any]):
    """Apply queued undo/redo actions and write recovery data only once."""
    _book(book_id)
    actions = request.get("actions", [])
    if not isinstance(actions, list):
        raise HTTPException(status_code=400, detail="actions must be a list")
    # Bound the batch so a runaway UI queue cannot monopolize the server.
    actions = [a for a in actions[:20] if a in ("undo", "redo")]
    results = []
    changed = False
    for action in actions:
        if action == "undo":
            history = _undo_history.get(book_id, [])
            if not history:
                results.append({"action": action, "changed": False})
                continue
            _redo_history.setdefault(book_id, []).append(_snapshot(_book(book_id)))
            if len(_redo_history[book_id]) > _HISTORY_LIMIT:
                del _redo_history[book_id][0]
            previous = history.pop()
            _restore_snapshot(book_id, previous, persist=False)
            changed = True
            results.append({"action": action, "changed": True})
        else:
            history = _redo_history.get(book_id, [])
            if not history:
                results.append({"action": action, "changed": False})
                continue
            _undo_history.setdefault(book_id, []).append(_snapshot(_book(book_id)))
            if len(_undo_history[book_id]) > _HISTORY_LIMIT:
                del _undo_history[book_id][0]
            next_state = history.pop()
            _restore_snapshot(book_id, next_state, persist=False)
            changed = True
            results.append({"action": action, "changed": True})
    if changed:
        _persist_workbook(book_id)
    return {
        "results": results,
        "changed": changed,
        "can_undo": bool(_undo_history.get(book_id)),
        "can_redo": bool(_redo_history.get(book_id)),
        "autosaved": changed,
    }


@app.get("/recovery")
def list_recovery_workbooks():
    items = []
    for path in sorted(_RECOVERY_DIR.glob("*.xlsx"), key=lambda p: p.stat().st_mtime, reverse=True)[:100]:
        try:
            wb = load_workbook(path, read_only=True, data_only=False)
            items.append({"workbook_id": path.stem, "sheets": _visible_sheetnames(wb), "modified": path.stat().st_mtime})
            wb.close()
        except Exception:
            continue
    return {"items": items, "limit": 100}


@app.post("/recovery/{book_id}/restore")
def restore_recovery_workbook(book_id: str):
    wb = _load_recovery(book_id)
    if wb is None:
        raise HTTPException(status_code=404, detail="Recovery workbook not found")
    _workbooks[book_id] = wb
    return {"workbook_id": book_id, "sheets": _visible_sheetnames(wb), "restored": True}


@app.get("/workbooks/{book_id}/dependencies")
def workbook_dependencies(book_id: str):
    """Return formula dependencies, reverse dependents, and detected formula cycles."""
    wb = _book(book_id)
    return {"workbook_id": book_id, **build_dependency_graph(wb)}


@app.post("/workbooks/{book_id}/calculate")
def calculate_workbook(book_id: str):
    """Evaluate supported formulas across the workbook without replacing formula cells."""
    wb = _book(book_id)
    results = []
    errors = []
    for ws in wb.worksheets:
        for row in ws.iter_rows():
            for cell in row:
                if isinstance(cell.value, str) and cell.value.startswith("="):
                    result = calculate_cell(wb, ws.title, cell.coordinate)
                    item = {"sheet": ws.title, "cell": cell.coordinate, **result}
                    results.append(item)
                    if result.get("formula_error"):
                        errors.append(item)
                    # FILTER returns a 2-D array. Materialize it into adjacent cells
                    # so the web/native grid can display the dynamic-array spill.
                    array_value = result.get("calculated_value")
                    if (isinstance(array_value, list) and array_value and
                            isinstance(cell.value, str) and cell.value.lstrip().upper().startswith("=FILTER(")):
                        from openpyxl.utils.cell import coordinate_to_tuple
                        from openpyxl.utils import get_column_letter
                        anchor_row, anchor_col = coordinate_to_tuple(cell.coordinate)
                        matrix = array_value if isinstance(array_value[0], list) else [[v] for v in array_value]
                        spill_cells = []
                        blocked = None
                        for ri, row_values in enumerate(matrix):
                            for ci, spill_value in enumerate(row_values):
                                if ri == 0 and ci == 0: continue
                                target = ws.cell(anchor_row + ri, anchor_col + ci)
                                if target.value not in (None, ""):
                                    blocked = target.coordinate
                                    break
                                spill_cells.append((target, spill_value))
                            if blocked: break
                        if blocked:
                            item["formula_error"] = "#SPILL!"
                            errors.append({"sheet": ws.title, "cell": blocked, "formula_error": "#SPILL!", "formula": cell.value})
                        else:
                            for target, spill_value in spill_cells:
                                target.value = spill_value
    # Tell desktop Excel to recalculate formulas it understands when the file opens.
    try:
        wb.calculation.calcMode = "auto"
        wb.calculation.fullCalcOnLoad = True
        wb.calculation.forceFullCalc = True
    except Exception:
        pass
    _persist_workbook(book_id)
    return {"workbook_id": book_id, "formulas_evaluated": len(results),
            "errors": len(errors), "results": results, "formula_errors": errors,
            "engine": "Nice Excel limited formula evaluator", "autosaved": True}


@app.get("/workbooks/{book_id}/save")
def save_workbook(book_id: str):
    wb = _book(book_id)
    try:
        wb.calculation.calcMode = "auto"
        wb.calculation.fullCalcOnLoad = True
        wb.calculation.forceFullCalc = True
    except Exception:
        pass
    buffer = io.BytesIO()
    wb.save(buffer)
    buffer.seek(0)
    return StreamingResponse(buffer, media_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", headers={"Content-Disposition": 'attachment; filename="NiceExcel.xlsx"'})


@app.post("/workbooks/{book_id}/csv/{sheet_name}")
def export_csv(book_id: str, sheet_name: str):
    ws = _sheet(_book(book_id), sheet_name)
    out = io.StringIO(newline="")
    writer = csv.writer(out)
    for row in ws.iter_rows():
        writer.writerow([cell.value for cell in row])
    raw = io.BytesIO(out.getvalue().encode("utf-8-sig"))
    return StreamingResponse(raw, media_type="text/csv; charset=utf-8", headers={"Content-Disposition": f'attachment; filename="{sheet_name}.csv"'})


@app.post("/workbooks/{book_id}/csv/{sheet_name}/import")
async def import_csv(book_id: str, sheet_name: str, file: UploadFile = File(...)):
    if not (file.filename or "").lower().endswith(".csv"):
        raise HTTPException(status_code=415, detail="Please upload a .csv file")
    content = await file.read()
    if len(content) > 20 * 1024 * 1024:
        raise HTTPException(status_code=413, detail="CSV exceeds 20 MB prototype limit")
    try:
        text = content.decode("utf-8-sig")
    except UnicodeDecodeError as exc:
        raise HTTPException(status_code=400, detail="CSV must be UTF-8 encoded") from exc
    ws = _sheet(_book(book_id), sheet_name)
    _record_history(book_id)
    ws.delete_rows(1, ws.max_row)
    for r, row in enumerate(csv.reader(io.StringIO(text)), start=1):
        if r > 100000:
            raise HTTPException(status_code=413, detail="CSV exceeds 100,000 row prototype limit")
        for c, value in enumerate(row, start=1):
            # CSV imports are text/data only; strings beginning with '=' remain text
            # to prevent untrusted CSV formula injection.
            ws.cell(r, c).value = value
    _persist_workbook(book_id)
    return {"sheet": sheet_name, "rows": ws.max_row, "columns": ws.max_column, "autosaved": True}


# Serve the Phase 2 browser UI from the same local-only Flask origin.
_STATIC_DIR = Path(os.environ.get("NICEEXCEL_STATIC_DIR", str(Path(__file__).parent / "static")))
@app.route("/", defaults={"path": ""})
@app.route("/<path:path>")
def serve_niceexcel_ui(path):
    """Serve the bundled UI from the same local-only Flask origin."""
    if path and (Path(path).is_absolute() or ".." in Path(path).parts):
        raise HTTPException(status_code=404, detail="File not found")
    candidate = _STATIC_DIR / path if path else _STATIC_DIR / "index.html"
    if candidate.is_file():
        return send_from_directory(str(_STATIC_DIR), path or "index.html")
    if not path or path.endswith("/"):
        return send_from_directory(str(_STATIC_DIR), "index.html")
    raise HTTPException(status_code=404, detail="File not found")
