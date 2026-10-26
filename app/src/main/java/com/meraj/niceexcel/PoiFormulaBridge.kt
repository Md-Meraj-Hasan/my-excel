package com.meraj.niceexcel

import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.FormulaError
import java.util.Locale

/** Imported Apache POI evaluator bridge. The existing local evaluator remains fallback. */
internal object PoiFormulaBridge {
    data class Result(val text: String? = null, val number: Double? = null, val error: String? = null)

    fun evaluate(cells: Map<String, String>, formulaAddress: String, expression: String): Result? = try {
        HSSFWorkbook().use { workbook ->
            val sheet = workbook.createSheet("Sheet1")
            val addressPattern = Regex("^([A-Z]+)([0-9]+)$")
            fun coordinates(address: String): Pair<Int, Int>? {
                val m = addressPattern.matchEntire(address.uppercase(Locale.US)) ?: return null
                var col = 0
                m.groupValues[1].forEach { col = col * 26 + (it - 'A' + 1) }
                val row = m.groupValues[2].toIntOrNull() ?: return null
                if (row < 1) return null
                return (row - 1) to (col - 1)
            }
            for ((address, raw) in cells) {
                val pos = coordinates(address) ?: continue
                val row = sheet.getRow(pos.first) ?: sheet.createRow(pos.first)
                val cell = row.createCell(pos.second)
                when {
                    raw.startsWith("=") -> cell.cellFormula = raw.drop(1)
                    raw.toDoubleOrNull() != null -> cell.setCellValue(raw.toDouble())
                    raw.isNotEmpty() -> cell.setCellValue(raw)
                    else -> cell.setBlank()
                }
            }
            val pos = coordinates(formulaAddress) ?: return@use null
            val row = sheet.getRow(pos.first) ?: sheet.createRow(pos.first)
            val formulaCell = row.getCell(pos.second) ?: row.createCell(pos.second)
            formulaCell.cellFormula = expression.removePrefix("=")
            val value = workbook.creationHelper.createFormulaEvaluator().evaluate(formulaCell) ?: return@use null
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
}
