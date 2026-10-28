"""Small, safe formula evaluator for Nice Excel's offline prototype.

Supports a deliberately documented subset of Excel formulas. It does not claim
full Excel compatibility; unsupported functions return #NAME? and bad references
return #REF! / #VALUE! instead of executing arbitrary Python.
"""
from __future__ import annotations
import ast
import operator
import re
from typing import Any

_CELL_RE = re.compile(r"^\$?([A-Z]{1,3})\$?([1-9][0-9]*)$", re.I)
_RANGE_RE = re.compile(r"^(?:(?:'((?:[^']|'')+)'|([A-Za-z_][A-Za-z0-9_. ]*))!)?\$?([A-Z]{1,3})\$?([1-9][0-9]*):\$?([A-Z]{1,3})\$?([1-9][0-9]*)$", re.I)
_SHEET_CELL_RE = re.compile(r"^(?:'((?:[^']|'')+)'|([A-Za-z_][A-Za-z0-9_. ]*))!\$?([A-Z]{1,3})\$?([1-9][0-9]*)$", re.I)
_WHOLE_COL_RANGE_RE = re.compile(r"^(?:(?:'((?:[^']|'')+)'|([A-Za-z_][A-Za-z0-9_. ]*))!)?\$?([A-Z]{1,3}):\$?([A-Z]{1,3})$", re.I)


def _range_parts(expression: str):
    match = _RANGE_RE.fullmatch(expression.strip().replace('$', ''))
    if not match:
        return None
    quoted, plain, c1, r1, c2, r2 = match.groups()
    sheet = (quoted.replace("''", "'") if quoted is not None else plain.strip() if plain else None)
    return sheet, c1.upper(), int(r1), c2.upper(), int(r2)


def _is_range(expression: str) -> bool:
    return _range_parts(expression) is not None or _WHOLE_COL_RANGE_RE.fullmatch(expression.strip()) is not None


class FormulaError(Exception):
    def __init__(self, code: str):
        self.code = code
        super().__init__(code)

def _col_num(label: str) -> int:
    n = 0
    for ch in label.upper(): n = n * 26 + ord(ch) - 64
    return n

def _split_args(source: str) -> list[str]:
    out, start, depth, quoted = [], 0, 0, False
    i = 0
    while i < len(source):
        ch = source[i]
        if ch == '"':
            if quoted and i + 1 < len(source) and source[i + 1] == '"': i += 1
            else: quoted = not quoted
        elif not quoted:
            if ch == '(': depth += 1
            elif ch == ')': depth -= 1
            elif ch == ',' and depth == 0:
                out.append(source[start:i].strip()); start = i + 1
        i += 1
    out.append(source[start:].strip())
    return out

class Evaluator:
    def __init__(self, workbook, sheet_name: str, stack: set[tuple[str, str]] | None = None):
        self.workbook = workbook
        if sheet_name not in workbook.sheetnames:
            raise FormulaError('#REF!')
        self.sheet_name = sheet_name
        self.sheet = workbook[sheet_name]
        self.stack = stack if stack is not None else set()

    def cell(self, address: str, sheet_name: str | None = None) -> Any:
        address = address.replace('$', '').upper()
        target_sheet = sheet_name or self.sheet_name
        if target_sheet not in self.workbook.sheetnames or not _CELL_RE.fullmatch(address):
            raise FormulaError('#REF!')
        key = (target_sheet, address)
        if key in self.stack: raise FormulaError('#CIRCULAR!')
        value = self.workbook[target_sheet][address].value
        if isinstance(value, str) and value.startswith('='):
            self.stack.add(key)
            try: return Evaluator(self.workbook, target_sheet, self.stack).evaluate(value)
            finally: self.stack.remove(key)
        return value

    def normalize_range(self, expression: str) -> str:
        """Expand Excel whole-column references (for example A:B) to used rows."""
        match = _WHOLE_COL_RANGE_RE.fullmatch(expression.strip())
        if not match:
            return expression
        quoted, plain, c1, c2 = match.groups()
        target_sheet = quoted.replace("''", "'") if quoted is not None else plain.strip() if plain else self.sheet_name
        if target_sheet not in self.workbook.sheetnames:
            raise FormulaError('#REF!')
        last_row = max(1, self.workbook[target_sheet].max_row)
        return f"'{target_sheet.replace(chr(39), chr(39)*2)}'!{c1.upper()}1:{c2.upper()}{last_row}" if quoted is not None else (f"{target_sheet}!{c1.upper()}1:{c2.upper()}{last_row}" if plain else f"{c1.upper()}1:{c2.upper()}{last_row}")

    def range(self, expression: str) -> list[Any]:
        expression = self.normalize_range(expression)
        parts = _range_parts(expression)
        if not parts: raise FormulaError('#REF!')
        target_sheet, c1, r1, c2, r2 = parts
        target_sheet = target_sheet or self.sheet_name
        if target_sheet not in self.workbook.sheetnames: raise FormulaError('#REF!')
        x1, x2 = sorted((_col_num(c1), _col_num(c2)))
        y1, y2 = sorted((r1, r2))
        if (x2-x1+1)*(y2-y1+1) > 10000: raise FormulaError('#VALUE!')
        vals = []
        for row in range(y1, y2+1):
            for col in range(x1, x2+1):
                from openpyxl.utils import get_column_letter
                vals.append(self.cell(f'{get_column_letter(col)}{row}', target_sheet))
        return vals

    def arg(self, token: str) -> Any:
        token = token.strip()
        if not token: return 0
        if token.startswith('"') and token.endswith('"'):
            return token[1:-1].replace('""', '"')
        if token.startswith('='): return self.evaluate(token)
        if re.fullmatch(r'[A-Z][A-Z0-9.]*\s*\(.*\)', token, re.I | re.S): return self.evaluate('=' + token)
        if _is_range(token): return self.range(token)
        sheet_cell = _SHEET_CELL_RE.fullmatch(token)
        if sheet_cell:
            quoted, plain, col, row = sheet_cell.groups()
            target = quoted.replace("''", "'") if quoted is not None else plain.strip()
            return self.cell(f'{col}{row}', target)
        if _CELL_RE.fullmatch(token): return self.cell(token)
        upper = token.upper()
        if upper == 'TRUE': return True
        if upper == 'FALSE': return False
        try: return float(token) if '.' in token else int(token)
        except ValueError: pass
        return self.arithmetic(token)

    @staticmethod
    def _number(value: Any) -> float:
        if value is None or value == '': return 0.0
        if isinstance(value, bool): return float(value)
        if isinstance(value, (int, float)): return float(value)
        try: return float(value)
        except (ValueError, TypeError): return 0.0

    @staticmethod
    def _truth(value: Any) -> bool:
        if isinstance(value, str): return value.strip().upper() not in ('', 'FALSE', '0')
        return bool(value)

    def arithmetic(self, expr: str) -> Any:
        # Replace cell references with numeric/string literals; only a strict AST
        # allow-list is evaluated, so formula text can never execute Python code.
        sheet_pattern = re.compile(r"(?<![A-Z0-9_])(?:'((?:[^']|'')+)'|([A-Za-z_][A-Za-z0-9_. ]*))!\$?([A-Z]{1,3})\$?([1-9][0-9]*)", re.I)
        def literal(value):
            if value is None: value = 0
            if isinstance(value, str): return repr(value)
            if isinstance(value, (int, float, bool)): return repr(value)
            raise FormulaError('#VALUE!')
        def sub_sheet(m):
            quoted, plain, col, row = m.groups()
            target = quoted.replace("''", "'") if quoted is not None else plain.strip()
            return literal(self.cell(f'{col}{row}', target))
        expr = sheet_pattern.sub(sub_sheet, expr)
        pattern = re.compile(r"""(?<![A-Z0-9_'\"])(?<![!])\$?[A-Z]{1,3}\$?[1-9][0-9]*(?![A-Z0-9_'\"])""", re.I)
        def sub(m): return literal(self.cell(m.group(0)))
        expr = pattern.sub(sub, expr).replace('^', '**').replace('<>', '!=')
        # Excel's = comparison is accepted only in formula expressions.
        expr = re.sub(r'(?<![<>=!])=(?!=)', '==', expr)
        try: node = ast.parse(expr, mode='eval').body
        except Exception as e: raise FormulaError('#VALUE!') from e
        ops = {ast.Add: operator.add, ast.Sub: operator.sub, ast.Mult: operator.mul, ast.Div: operator.truediv,
               ast.Pow: operator.pow, ast.Mod: operator.mod, ast.Eq: operator.eq, ast.NotEq: operator.ne,
               ast.Lt: operator.lt, ast.LtE: operator.le, ast.Gt: operator.gt, ast.GtE: operator.ge}
        def walk(n):
            if isinstance(n, ast.Constant) and isinstance(n.value, (int, float, str, bool)): return n.value
            if isinstance(n, ast.UnaryOp) and isinstance(n.op, (ast.UAdd, ast.USub)):
                v = walk(n.operand); return +v if isinstance(n.op, ast.UAdd) else -v
            if isinstance(n, ast.BinOp) and type(n.op) in ops:
                a, b = walk(n.left), walk(n.right)
                try: return ops[type(n.op)](a, b)
                except ZeroDivisionError as e: raise FormulaError('#DIV/0!') from e
                except Exception as e: raise FormulaError('#VALUE!') from e
            if isinstance(n, ast.Compare) and len(n.ops) == 1 and len(n.comparators) == 1 and type(n.ops[0]) in ops:
                try: return ops[type(n.ops[0])](walk(n.left), walk(n.comparators[0]))
                except Exception as e: raise FormulaError('#VALUE!') from e
            raise FormulaError('#VALUE!')
        return walk(node)

    def evaluate(self, formula: str) -> Any:
        src = formula.strip()
        if not src.startswith('='): return src
        src = src[1:].strip()
        m = re.fullmatch(r'([A-Z][A-Z0-9.]*)\((.*)\)', src, re.I | re.S)
        if not m: return self.arithmetic(src)
        fn, body = m.group(1).upper(), m.group(2)
        args = _split_args(body)
        try:
            if fn in ('TODAY', 'NOW'):
                from datetime import date, datetime
                epoch = date(1899, 12, 30)
                now = datetime.now()
                days = (now.date() - epoch).days
                return days if fn == 'TODAY' else days + (now.hour*3600 + now.minute*60 + now.second)/86400.0
            if fn in ('DATE', 'YEAR', 'MONTH', 'DAY'):
                from datetime import date
                epoch = date(1899, 12, 30)
                if fn == 'DATE':
                    if len(args) != 3: raise FormulaError('#VALUE!')
                    year, month, day = [int(self.arg(a)) for a in args]
                    if year < 1900: year += 1900
                    result_date = date(year, 1, 1)
                    from datetime import timedelta
                    result_date = result_date + timedelta(days=day-1) + timedelta(days=(month-1)*30) if month < 1 or month > 12 else date(year, month, 1) + timedelta(days=day-1)
                    return (result_date - epoch).days
                if len(args) != 1: raise FormulaError('#VALUE!')
                serial = int(self._number(self.arg(args[0])))
                from datetime import timedelta
                result_date = epoch + timedelta(days=serial)
                return result_date.year if fn == 'YEAR' else result_date.month if fn == 'MONTH' else result_date.day
            if fn in ('SUM', 'AVERAGE', 'MIN', 'MAX', 'COUNT', 'MEDIAN', 'PRODUCT'):
                vals = []
                for a in args:
                    part = self.range(a) if _is_range(a) else [self.arg(a)]
                    vals.extend(part)
                nums = [self._number(v) for v in vals if isinstance(v, (int, float)) and not isinstance(v, bool)]
                if fn == 'SUM': return sum(nums)
                if fn == 'AVERAGE': return sum(nums)/len(nums) if nums else FormulaError('#DIV/0!').code
                if fn == 'MIN': return min(nums) if nums else 0
                if fn == 'MAX': return max(nums) if nums else 0
                if fn == 'MEDIAN':
                    if not nums: return 0
                    nums.sort(); mid = len(nums)//2
                    return nums[mid] if len(nums)%2 else (nums[mid-1]+nums[mid])/2
                if fn == 'PRODUCT':
                    result = 1.0
                    for number in nums: result *= number
                    return result
                return len(nums)
            if fn == 'IF':
                if len(args) not in (2, 3): raise FormulaError('#VALUE!')
                return self.arg(args[1] if self._truth(self.arg(args[0])) else (args[2] if len(args) == 3 else 'FALSE'))
            if fn in ('SUMIFS', 'COUNTIFS', 'AVERAGEIFS', 'MINIFS', 'MAXIFS'):
                if len(args) < 3 or (len(args) - (1 if fn in ('SUMIFS','AVERAGEIFS','MINIFS','MAXIFS') else 0)) % 2 != 0:
                    raise FormulaError('#VALUE!')
                if fn in ('SUMIFS', 'AVERAGEIFS', 'MINIFS', 'MAXIFS'):
                    value_range = self.range(args[0]); pairs = args[1:]
                else:
                    value_range = None; pairs = args
                if len(pairs) < 2 or len(pairs) % 2:
                    raise FormulaError('#VALUE!')
                criteria_ranges = [self.range(pairs[i]) for i in range(0, len(pairs), 2)]
                criteria = [self.arg(pairs[i]) for i in range(1, len(pairs), 2)]
                expected = len(value_range) if value_range is not None else len(criteria_ranges[0])
                if any(len(r) != expected for r in criteria_ranges) or (value_range is not None and any(len(r) != expected for r in criteria_ranges)):
                    raise FormulaError('#VALUE!')
                selected = [i for i in range(expected) if all(self.matches(r[i], c) for r, c in zip(criteria_ranges, criteria))]
                if fn == 'COUNTIFS': return len(selected)
                nums = [self._number(value_range[i]) for i in selected if value_range[i] is not None and value_range[i] != '']
                if fn == 'SUMIFS': return sum(nums)
                if fn == 'AVERAGEIFS': return sum(nums) / len(nums) if nums else FormulaError('#DIV/0!').code
                if fn == 'MINIFS': return min(nums) if nums else 0
                if fn == 'MAXIFS': return max(nums) if nums else 0
            if fn in ('AVERAGEIF',):
                if len(args) not in (2, 3): raise FormulaError('#VALUE!')
                criteria_range = self.range(args[0]); criterion = self.arg(args[1])
                values = self.range(args[2]) if len(args) == 3 else criteria_range
                if len(values) != len(criteria_range): raise FormulaError('#VALUE!')
                nums = [self._number(values[i]) for i, value in enumerate(criteria_range) if self.matches(value, criterion) and values[i] is not None and values[i] != '']
                return sum(nums) / len(nums) if nums else FormulaError('#DIV/0!').code
            if fn in ('COUNTIF', 'SUMIF'):
                if len(args) not in (2, 3): raise FormulaError('#VALUE!')
                vals = self.range(args[0])
                criteria = self.arg(args[1]); sum_vals = self.range(args[2]) if fn == 'SUMIF' and len(args) == 3 else vals
                if len(sum_vals) != len(vals): raise FormulaError('#VALUE!')
                matched = [i for i, v in enumerate(vals) if self.matches(v, criteria)]
                return len(matched) if fn == 'COUNTIF' else sum(self._number(sum_vals[i]) for i in matched)
            if fn == 'FILTER':
                if len(args) not in (2, 3): raise FormulaError('#VALUE!')
                array_expr = self.normalize_range(args[0])
                parts = _range_parts(array_expr)
                if not parts: raise FormulaError('#VALUE!')
                target_sheet, c1, r1, c2, r2 = parts
                target_sheet = target_sheet or self.sheet_name
                left, right = _col_num(c1), _col_num(c2)
                # Support the common Excel pattern FILTER(array,ISNUMBER(MATCH(id_range,ids,0)),[if_empty]).
                include = args[1].strip()
                mm = re.fullmatch(r'ISNUMBER\s*\(\s*MATCH\s*\((.*)\)\s*\)', include, re.I | re.S)
                if mm:
                    ma = _split_args(mm.group(1))
                    if len(ma) not in (2, 3): raise FormulaError('#VALUE!')
                    ids = self.range(ma[0]); lookup_ids = self.range(ma[1]) if _is_range(ma[1]) else [self.arg(ma[1])]
                    matched_keys = {str(v).strip().casefold() for v in lookup_ids if v is not None and str(v).strip()}
                    keep = [v is not None and str(v).strip().casefold() in matched_keys for v in ids]
                else:
                    include_values = self.range(include) if _is_range(include) else self.arg(include)
                    if isinstance(include_values, list): keep = [bool(v) for v in include_values]
                    else: raise FormulaError('#VALUE!')
                height = r2-r1+1
                if len(keep) != height: raise FormulaError('#VALUE!')
                result=[]
                for offset, flag in enumerate(keep):
                    if flag:
                        result.append([self.cell(f'{__import__("openpyxl").utils.get_column_letter(col)}{r1+offset}', target_sheet) for col in range(left,right+1)])
                if not result:
                    if len(args) == 3: return self.arg(args[2])
                    raise FormulaError('#CALC!')
                return result
            if fn in ('VLOOKUP', 'XLOOKUP'):
                if fn == 'VLOOKUP':
                    if len(args) not in (3, 4): raise FormulaError('#VALUE!')
                    lookup = self.arg(args[0]); normalized_table = self.normalize_range(args[1]); table = self.range(normalized_table); mrange = _range_parts(normalized_table)
                    target_sheet,c1,top,c2,bottom = mrange; target_sheet = target_sheet or self.sheet_name
                    left,right = _col_num(c1),_col_num(c2)
                    idx = int(self.arg(args[2])); approx = self._truth(self.arg(args[3])) if len(args)==4 else False
                    if idx < 1 or idx > right-left+1: raise FormulaError('#REF!')
                    found = None
                    for row in range(top,bottom+1):
                        val = self.cell(f'{__import__("openpyxl").utils.get_column_letter(left)}{row}', target_sheet)
                        if val == lookup or (approx and isinstance(val,(int,float)) and val <= lookup): found=row
                    if found is None: raise FormulaError('#N/A')
                    return self.cell(f'{__import__("openpyxl").utils.get_column_letter(left+idx-1)}{found}', target_sheet)
                if len(args) not in (3, 4, 5, 6): raise FormulaError('#VALUE!')
                lookup = self.arg(args[0])
                lookup_vals = self.range(args[1])
                return_vals = self.range(args[2])
                if len(lookup_vals) != len(return_vals): raise FormulaError('#VALUE!')
                not_found = self.arg(args[3]) if len(args) >= 4 else FormulaError('#N/A').code
                match_mode = int(self.arg(args[4])) if len(args) >= 5 else 0
                search_mode = int(self.arg(args[5])) if len(args) >= 6 else 1
                if match_mode not in (-1, 0, 1, 2): raise FormulaError('#VALUE!')
                if search_mode not in (-2, -1, 1, 2): raise FormulaError('#VALUE!')

                def comparable(value):
                    if value is None: return ''
                    if isinstance(value, str):
                        stripped = value.strip()
                        if stripped:
                            try: return float(stripped)
                            except ValueError: return stripped.casefold()
                        return ''
                    if isinstance(value, bool): return value
                    if isinstance(value, (int, float)): return float(value)
                    return str(value).casefold()

                def exact_match(value):
                    left, right = comparable(value), comparable(lookup)
                    if left == right: return True
                    if match_mode == 2 and isinstance(lookup, str):
                        pattern = re.escape(lookup).replace(r'\*', '.*').replace(r'\?', '.')
                        return re.fullmatch(pattern, '' if value is None else str(value), re.I) is not None
                    return False

                indices = range(len(lookup_vals) - 1, -1, -1) if search_mode == -1 else range(len(lookup_vals))
                for i in indices:
                    if exact_match(lookup_vals[i]): return return_vals[i]

                if match_mode in (-1, 1):
                    target = comparable(lookup)
                    candidates = []
                    for i, value in enumerate(lookup_vals):
                        candidate = comparable(value)
                        try:
                            eligible = candidate <= target if match_mode == -1 else candidate >= target
                            if eligible: candidates.append((candidate, i))
                        except TypeError:
                            continue
                    if candidates:
                        chosen = max(candidates, key=lambda item: item[0]) if match_mode == -1 else min(candidates, key=lambda item: item[0])
                        return return_vals[chosen[1]]
                return not_found
            if fn == 'MATCH':
                if len(args) not in (2,3): raise FormulaError('#VALUE!')
                target=self.arg(args[0]); vals=self.range(args[1]); mode=int(self.arg(args[2])) if len(args)==3 else 1
                for i,v in enumerate(vals,1):
                    if v == target or (mode == 0 and isinstance(v,str) and isinstance(target,str) and v.lower()==target.lower()): return i
                raise FormulaError('#N/A')
            if fn == 'INDEX':
                if len(args) not in (2,3): raise FormulaError('#VALUE!')
                normalized_index_range=self.normalize_range(args[0]); vals=self.range(normalized_index_range); row=int(self.arg(args[1])); col=int(self.arg(args[2])) if len(args)==3 else 1
                mrange=_range_parts(normalized_index_range); target_sheet,c1,r1,c2,r2=mrange; width=_col_num(c2)-_col_num(c1)+1
                if row<1 or col<1 or col>width: raise FormulaError('#REF!')
                return vals[(row-1)*width+col-1]
            if fn == 'CONCATENATE': return ''.join('' if (v:=self.arg(a)) is None else str(v) for a in args)
            if fn == 'COUNTBLANK':
                vals = []
                for a in args:
                    vals.extend(self.range(a) if _is_range(a) else [self.arg(a)])
                return sum(v is None or v == '' for v in vals)
            if fn == 'COUNTA':
                vals = []
                for a in args:
                    vals.extend(self.range(a) if _is_range(a) else [self.arg(a)])
                return sum(v is not None and v != '' for v in vals)
            if fn in ('ABS', 'ROUND', 'ROUNDUP', 'ROUNDDOWN', 'INT', 'MOD', 'POWER', 'SQRT'):
                if not args: raise FormulaError('#VALUE!')
                nums = [self._number(self.arg(a)) for a in args]
                if fn == 'ABS': return abs(nums[0])
                if fn == 'INT': return int(nums[0] // 1)
                if fn == 'MOD':
                    if len(nums) != 2 or nums[1] == 0: raise FormulaError('#DIV/0!')
                    return nums[0] % nums[1]
                if fn == 'POWER': return nums[0] ** nums[1]
                if fn == 'SQRT':
                    if nums[0] < 0: raise FormulaError('#NUM!')
                    return nums[0] ** 0.5
                digits = int(nums[1]) if len(nums) > 1 else 0
                factor = 10 ** digits
                scaled = nums[0] * factor
                if fn == 'ROUND': return round(nums[0], digits)
                if fn == 'ROUNDUP':
                    import math
                    return (math.ceil(scaled) if scaled >= 0 else math.floor(scaled)) / factor
                import math
                return (math.floor(scaled) if scaled >= 0 else math.ceil(scaled)) / factor
            if fn in ('LEN', 'UPPER', 'LOWER', 'PROPER', 'TRIM', 'LEFT', 'RIGHT', 'MID'):
                if not args: raise FormulaError('#VALUE!')
                value = self.arg(args[0])
                value = '' if value is None else str(value)
                if fn == 'LEN': return len(value)
                if fn == 'UPPER': return value.upper()
                if fn == 'LOWER': return value.lower()
                if fn == 'PROPER': return value.title()
                if fn == 'TRIM': return ' '.join(value.split())
                if fn in ('LEFT', 'RIGHT'):
                    count = int(self.arg(args[1])) if len(args) > 1 else 1
                    if count < 0: raise FormulaError('#VALUE!')
                    return value[:count] if fn == 'LEFT' else (value[-count:] if count else '')
                if len(args) != 3: raise FormulaError('#VALUE!')
                start, count = int(self.arg(args[1])), int(self.arg(args[2]))
                if start < 1 or count < 0: raise FormulaError('#VALUE!')
                return value[start-1:start-1+count]
            if fn in ('IFERROR', 'IFNA'):
                if len(args) != 2: raise FormulaError('#VALUE!')
                try:
                    result = self.arg(args[0])
                except FormulaError as exc:
                    result = exc.code
                is_error = isinstance(result, str) and result.startswith('#')
                return self.arg(args[1]) if (is_error and (fn == 'IFERROR' or result == '#N/A')) else result
            if fn == 'AND': return all(self._truth(self.arg(a)) for a in args)
            if fn == 'OR': return any(self._truth(self.arg(a)) for a in args)
            if fn == 'NOT': return not self._truth(self.arg(args[0]))
            raise FormulaError('#NAME?')
        except FormulaError as e: return e.code
        except Exception: return '#VALUE!'

    @staticmethod
    def matches(value: Any, criteria: Any) -> bool:
        if isinstance(criteria, str):
            m = re.fullmatch(r'(>=|<=|<>|>|<|=)(.*)', criteria)
            if m:
                op, raw = m.groups()
                raw = raw.strip()
                try:
                    right: Any = float(raw) if raw else 0
                except ValueError:
                    right = raw
                left = value
                if isinstance(right, (int, float)):
                    try:
                        left = float(value)
                    except (TypeError, ValueError):
                        return op == '<>'
                elif left is None:
                    left = ''
                try:
                    return {
                        '=': lambda: left == right or str(left).casefold() == str(right).casefold(),
                        '<>': lambda: left != right and str(left).casefold() != str(right).casefold(),
                        '>': lambda: left > right,
                        '<': lambda: left < right,
                        '>=': lambda: left >= right,
                        '<=': lambda: left <= right,
                    }[op]()
                except (TypeError, ValueError):
                    return False
            # Excel COUNTIF/SUMIF support * and ? wildcards, with ~ escaping.
            if '*' in criteria or '?' in criteria or '~' in criteria:
                pattern = re.escape(criteria).replace(r'\*', '.*').replace(r'\?', '.')
                pattern = pattern.replace(r'\~\*', r'\*').replace(r'\~\?', r'\?')
                return re.fullmatch(pattern, '' if value is None else str(value), re.I) is not None
        if value is None:
            value = ''
        return str(value).casefold() == str(criteria).casefold()

def calculate_cell(workbook, sheet_name: str, address: str) -> dict[str, Any]:
    value = workbook[sheet_name][address].value
    if not (isinstance(value, str) and value.startswith('=')):
        return {'calculated_value': value, 'formula_error': None}
    try:
        result = Evaluator(workbook, sheet_name).evaluate(value)
        return {'calculated_value': result, 'formula_error': result if isinstance(result, str) and result.startswith('#') else None}
    except FormulaError as e:
        return {'calculated_value': e.code, 'formula_error': e.code}
    except Exception:
        return {'calculated_value': '#VALUE!', 'formula_error': '#VALUE!'}
