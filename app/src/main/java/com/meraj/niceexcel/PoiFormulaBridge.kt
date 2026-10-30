package com.meraj.niceexcel

import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.FormulaError
import java.util.Locale

/** Imported Apache POI evaluator bridge. The existing local evaluator remains fallback. */
internal object PoiFormulaBridge {
    data class Result(val text: String? = null, val number: Double? = null, val error: String? = null)

    /** Backward-compatible single-sheet entry point used by unit tests. */
    fun evaluate(cells: Map<String, String>, formulaAddress: String, expression: String): Result? =
        evaluate(linkedMapOf("Sheet1" to cells), "Sheet1", formulaAddress, expression)

    /**
     * Evaluate formulas against the entire workbook, not only the active sheet.
     * The previous bridge populated just one sheet, so formulas such as
     * SUM(Sales!F2:F11) silently evaluated against an incomplete workbook.
     */
    fun evaluate(
        allSheets: Map<String, out Map<String, String>>,
        currentSheetName: String,
        formulaAddress: String,
        expression: String
    ): Result? = try {
        HSSFWorkbook().use { workbook ->
            val addressPattern = Regex("^([A-Z]+)([0-9]+)$")
            fun coordinates(address: String): Pair<Int, Int>? {
                val m = addressPattern.matchEntire(address.uppercase(Locale.US)) ?: return null
                var col = 0
                m.groupValues[1].forEach { col = col * 26 + (it - 'A' + 1) }
                val row = m.groupValues[2].toIntOrNull() ?: return null
                if (row < 1) return null
                return (row - 1) to (col - 1)
            }
            val safeNames = linkedMapOf<String, String>()
            allSheets.keys.forEach { name ->
                val safeName = runCatching { org.apache.poi.ss.util.WorkbookUtil.createSafeSheetName(name) }
                    .getOrDefault("Sheet${safeNames.size + 1}")
                safeNames[name] = safeName
                if (workbook.getSheet(safeName) == null) workbook.createSheet(safeName)
            }
            val activeName = safeNames[currentSheetName] ?: run {
                val safeName = org.apache.poi.ss.util.WorkbookUtil.createSafeSheetName(currentSheetName)
                safeNames[currentSheetName] = safeName
                workbook.createSheet(safeName)
                safeName
            }
            for ((originalName, cells) in allSheets) {
                val sheet = workbook.getSheet(safeNames[originalName]) ?: continue
                for ((address, raw) in cells) {
                    val pos = coordinates(address) ?: continue
                    val row = sheet.getRow(pos.first) ?: sheet.createRow(pos.first)
                    val cell = row.getCell(pos.second) ?: row.createCell(pos.second)
                    when {
                        raw.startsWith("=") -> cell.cellFormula = normalizeXor(
                            normalizeXlookup(normalizeArgumentSeparators(raw.drop(1)))
                        )
                        raw.toDoubleOrNull() != null -> cell.setCellValue(raw.toDouble())
                        raw.equals("TRUE", ignoreCase = true) -> cell.setCellValue(true)
                        raw.equals("FALSE", ignoreCase = true) -> cell.setCellValue(false)
                        raw.isNotEmpty() -> cell.setCellValue(raw)
                        else -> cell.setBlank()
                    }
                }
            }
            val sheet = workbook.getSheet(activeName)
            val pos = coordinates(formulaAddress) ?: return@use null
            val row = sheet.getRow(pos.first) ?: sheet.createRow(pos.first)
            val formulaCell = row.getCell(pos.second) ?: row.createCell(pos.second)
            formulaCell.cellFormula = normalizeXor(
                normalizeXlookup(normalizeArgumentSeparators(expression.removePrefix("=")))
            )
            val evaluator = workbook.creationHelper.createFormulaEvaluator()
            evaluator.clearAllCachedResultValues()
            val value = evaluator.evaluate(formulaCell) ?: return@use null
            when (value.cellType) {
                CellType.NUMERIC -> Result(number = value.numberValue)
                CellType.STRING -> Result(text = value.stringValue)
                CellType.BOOLEAN -> Result(text = if (value.booleanValue) "TRUE" else "FALSE")
                CellType.ERROR -> Result(error = FormulaError.forInt(value.errorValue.toInt()).string)
                else -> null
            }
        }
    } catch (_: Throwable) {
        // POI does not support every Excel function/version; caller falls back to local engine.
        null
    }


    /**
     * Apache POI's HSSF evaluator may not support XOR in all supported versions.
     * Rewrite XOR arguments into equivalent AND/OR/NOT expressions before
     * loading/evaluating formulas. Handles nested calls and more than two args.
     */
    private fun normalizeXor(expression: String): String {
        var result = expression
        var searchFrom = 0
        while (true) {
            val start = Regex("(?i)XOR\\s*\\(").find(result, searchFrom) ?: break
            val open = result.indexOf('(', start.range.first)
            var depth = 0
            var quoted = false
            var end = -1
            var i = open
            while (i < result.length) {
                val ch = result[i]
                if (ch == '"') {
                    if (quoted && i + 1 < result.length && result[i + 1] == '"') {
                        i += 2
                        continue
                    }
                    quoted = !quoted
                }
                if (!quoted) {
                    if (ch == '(') depth++
                    if (ch == ')') {
                        depth--
                        if (depth == 0) {
                            end = i
                            break
                        }
                    }
                }
                i++
            }
            if (end < 0) break

            val args = splitFormulaArgs(result.substring(open + 1, end)).map { normalizeXor(it) }
            if (args.size < 2) {
                searchFrom = end + 1
                continue
            }

            // XOR is associative: XOR(a,b,c) = XOR(XOR(a,b),c).
            var replacement = args[0]
            for (arg in args.drop(1)) {
                replacement = "OR(AND(($replacement),NOT(($arg))),AND(NOT(($replacement)),($arg)))"
            }
            result = result.substring(0, start.range.first) + replacement + result.substring(end + 1)
            searchFrom = start.range.first + replacement.length
        }
        return result
    }

    /**
     * Accept formulas copied from Excel locales that use semicolons between
     * function arguments. Only separators outside quoted text and nested
     * string literals are converted; semicolons inside strings are preserved.
     */
    private fun normalizeArgumentSeparators(expression: String): String {
        val out = StringBuilder(expression.length)
        var quoted = false
        var i = 0
        while (i < expression.length) {
            val ch = expression[i]
            if (ch == '"') {
                out.append(ch)
                if (quoted && i + 1 < expression.length && expression[i + 1] == '"') {
                    out.append(expression[i + 1])
                    i += 2
                    continue
                }
                quoted = !quoted
            } else {
                out.append(if (!quoted && ch == ';') ',' else ch)
            }
            i++
        }
        return out.toString()
    }

    /** POI 5.4.1's HSSF evaluator does not implement XLOOKUP. Translate the common
     * exact-match form to INDEX/MATCH so cross-sheet lookup ranges still work. */
    private fun normalizeXlookup(expression: String): String {
        var result = expression
        var searchFrom = 0
        while (true) {
            val start = Regex("(?i)XLOOKUP\\s*\\(").find(result, searchFrom) ?: break
            val open = result.indexOf('(', start.range.first)
            var depth = 0
            var quoted = false
            var end = -1
            for (i in open until result.length) {
                val ch = result[i]
                if (ch == '"') {
                    if (quoted && i + 1 < result.length && result[i + 1] == '"') continue
                    quoted = !quoted
                }
                if (!quoted) {
                    if (ch == '(') depth++
                    if (ch == ')') {
                        depth--
                        if (depth == 0) { end = i; break }
                    }
                }
            }
            if (end < 0) break
            val args = splitFormulaArgs(result.substring(open + 1, end))
            if (args.size < 3 || args.size > 6) {
                searchFrom = end + 1
                continue
            }
            val notFound = args.getOrNull(3)?.takeIf { it.isNotBlank() } ?: "\"#N/A\""
            val replacement = "IFERROR(INDEX(${args[2]},MATCH(${args[0]},${args[1]},0)),${notFound})"
            result = result.substring(0, start.range.first) + replacement + result.substring(end + 1)
            searchFrom = start.range.first + replacement.length
        }
        return result
    }

    private fun splitFormulaArgs(source: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var quoted = false
        var i = 0
        while (i < source.length) {
            val ch = source[i]
            if (ch == '"') {
                if (quoted && i + 1 < source.length && source[i + 1] == '"') {
                    current.append("\"\"")
                    i++
                } else quoted = !quoted
            }
            if (!quoted) {
                if (ch == '(') depth++
                if (ch == ')') depth--
            }
            if (ch == ',' && depth == 0 && !quoted) {
                out.add(current.toString().trim())
                current.setLength(0)
            } else current.append(ch)
            i++
        }
        out.add(current.toString().trim())
        return out
    }
}
