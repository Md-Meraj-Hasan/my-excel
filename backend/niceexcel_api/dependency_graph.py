"""Build a bounded dependency graph for supported A1-style formula references."""
from __future__ import annotations
import re
from collections import defaultdict
from openpyxl.utils.cell import range_boundaries, get_column_letter

# Supports A1, $A$1, A1:B5, Sheet2!A1, and 'Sales Data'!A1:B5.
_REF = re.compile(r"(?:(?:'((?:[^']|'')+)'|([A-Za-z_][A-Za-z0-9_. ]*))!)?(\$?[A-Z]{1,3}\$?[1-9][0-9]*)(?::(\$?[A-Z]{1,3}\$?[1-9][0-9]*))?", re.I)
_STRING = re.compile(r'"(?:[^"]|"")*"')

def _address(value: str) -> str:
    return value.replace('$', '').upper()

def _references(formula: str, current_sheet: str, workbook) -> list[tuple[str, str]]:
    # Ignore quoted string constants to avoid treating text like "A1" as a reference.
    source = _STRING.sub(lambda m: ' ' * len(m.group(0)), formula)
    found: set[tuple[str, str]] = set()
    for match in _REF.finditer(source):
        quoted, plain, first, last = match.groups()
        sheet = (quoted.replace("''", "'") if quoted is not None else plain.strip() if plain else current_sheet)
        if sheet not in workbook.sheetnames:
            continue
        first = _address(first)
        last = _address(last) if last else first
        try:
            min_col, min_row, max_col, max_row = range_boundaries(f'{first}:{last}')
        except ValueError:
            continue
        # Bound workbooks with enormous references; preserve a range's corner refs as a safe summary.
        if (max_col-min_col+1)*(max_row-min_row+1) > 10000:
            found.add((sheet, first)); found.add((sheet, last)); continue
        for row in range(min_row, max_row + 1):
            for col in range(min_col, max_col + 1):
                found.add((sheet, f'{get_column_letter(col)}{row}'))
    return sorted(found)

def build_dependency_graph(workbook) -> dict:
    formulas: dict[tuple[str, str], dict] = {}
    dependents: dict[tuple[str, str], set[tuple[str, str]]] = defaultdict(set)
    for ws in workbook.worksheets:
        for row in ws.iter_rows():
            for cell in row:
                if isinstance(cell.value, str) and cell.value.startswith('='):
                    key = (ws.title, cell.coordinate)
                    refs = _references(cell.value, ws.title, workbook)
                    formulas[key] = {'sheet': ws.title, 'cell': cell.coordinate, 'formula': cell.value,
                                     'dependencies': [{'sheet': s, 'cell': a} for s, a in refs]}
                    for ref in refs:
                        dependents[ref].add(key)
    # Detect cycles only among formula cells, with a bounded DFS over graph nodes.
    formula_keys = set(formulas)
    state: dict[tuple[str, str], int] = {}
    stack: list[tuple[str, str]] = []
    cycles: list[list[dict[str, str]]] = []
    cycle_signatures: set[tuple[tuple[str, str], ...]] = set()
    def visit(node):
        state[node] = 1; stack.append(node)
        for dep in [(x['sheet'], x['cell']) for x in formulas[node]['dependencies'] if (x['sheet'], x['cell']) in formula_keys]:
            if state.get(dep, 0) == 0:
                visit(dep)
            elif state.get(dep) == 1:
                start = stack.index(dep)
                cycle = stack[start:] + [dep]
                body = cycle[:-1]
                # Canonicalize by rotating to lexicographically smallest node, avoiding duplicate reports.
                if body:
                    idx = min(range(len(body)), key=lambda i: body[i])
                    rotated = body[idx:] + body[:idx]
                    signature = tuple(rotated)
                    if signature not in cycle_signatures:
                        cycle_signatures.add(signature)
                        cycles.append([{'sheet': s, 'cell': a} for s, a in rotated] + [{'sheet': rotated[0][0], 'cell': rotated[0][1]}])
        stack.pop(); state[node] = 2
    for node in sorted(formula_keys):
        if state.get(node, 0) == 0:
            visit(node)
    nodes = []
    for key in sorted(formulas):
        item = formulas[key]
        item['dependents'] = [{'sheet': s, 'cell': a} for s, a in sorted(dependents.get(key, set()))]
        item['in_cycle'] = any(any(p['sheet'] == key[0] and p['cell'] == key[1] for p in cycle[:-1]) for cycle in cycles)
        nodes.append(item)
    return {'formula_count': len(nodes), 'dependency_edges': sum(len(n['dependencies']) for n in nodes),
            'nodes': nodes, 'cycles': cycles, 'cycle_count': len(cycles),
            'note': 'Dependency analysis covers A1-style references and the supported formula subset; it does not execute formulas.'}
