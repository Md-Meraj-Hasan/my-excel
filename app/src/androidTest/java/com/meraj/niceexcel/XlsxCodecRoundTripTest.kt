package com.meraj.niceexcel

import android.graphics.Color
import android.view.Gravity
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class XlsxCodecRoundTripTest {
    @Test
    fun roundTripsValuesFormulasSheetsAndCommonStyles() {
        val sheets = linkedMapOf(
            "Sales" to linkedMapOf(
                "A1" to "Product",
                "B1" to "Amount",
                "A2" to "Notebook",
                "B2" to "125.5",
                "B3" to "=SUM(B2:B2)"
            ) as MutableMap<String, String>,
            "Notes" to linkedMapOf("A1" to "Keep this text") as MutableMap<String, String>
        )
        val styles = linkedMapOf(
            "Sales" to linkedMapOf(
                "A1" to CellStyle(bold = true, fillColor = Color.YELLOW, textColor = Color.BLUE, fontSize = 16f, alignment = Gravity.CENTER, border = true),
                "B2" to CellStyle(numberFormat = "Currency", italic = true)
            ) as MutableMap<String, CellStyle>
        )

        val output = ByteArrayOutputStream()
        XlsxCodec.write(output, sheets, styles)
        val result = XlsxCodec.read(ByteArrayInputStream(output.toByteArray()))

        assertEquals(listOf("Sales", "Notes"), result.sheets.keys.toList())
        assertEquals("Product", result.sheets["Sales"]?.get("A1"))
        assertEquals("125.5", result.sheets["Sales"]?.get("B2"))
        assertEquals("=SUM(B2:B2)", result.sheets["Sales"]?.get("B3"))
        assertEquals("Keep this text", result.sheets["Notes"]?.get("A1"))

        val header = result.styles["Sales"]?.get("A1")
        assertTrue(header?.bold == true)
        assertEquals(Color.YELLOW, header?.fillColor)
        assertEquals(Color.BLUE, header?.textColor)
        assertEquals(16f, header?.fontSize ?: 0f, 0.01f)
        assertTrue(header?.border == true)
        assertTrue(result.styles["Sales"]?.get("B2")?.italic == true)
    }

    @Test
    fun roundTripsMergedRangesForEachSheet() {
        val sheets = linkedMapOf(
            "Attendance" to linkedMapOf("A1" to "Electrical", "A2" to "Electrical", "A3" to "Electrical", "B1" to "Meraj") as MutableMap<String, String>,
            "Sales" to linkedMapOf("A1" to "January", "B1" to "100") as MutableMap<String, String>
        )
        val merges = linkedMapOf(
            "Attendance" to mutableListOf("A1:A3", "B1:C1"),
            "Sales" to mutableListOf("A1:B1")
        )
        val output = ByteArrayOutputStream()
        XlsxCodec.write(output, sheets, linkedMapOf(), mergedRanges = merges)
        val result = XlsxCodec.read(ByteArrayInputStream(output.toByteArray()))
        assertEquals(listOf("A1:A3", "B1:C1"), result.mergedRanges["Attendance"])
        assertEquals(listOf("A1:B1"), result.mergedRanges["Sales"])
        assertEquals("Electrical", result.sheets["Attendance"]?.get("A1"))
    }

}
