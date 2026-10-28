package com.meraj.niceexcel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PoiFormulaBridgeTest {
    @Test fun sumifsUsesImportedEvaluator() {
        val cells = mapOf("A1" to "Region", "B1" to "Sales", "A2" to "East", "B2" to "100", "A3" to "West", "B3" to "50", "A4" to "East", "B4" to "25")
        val result = PoiFormulaBridge.evaluate(cells, "D1", "SUMIFS(B2:B4,A2:A4,\"East\")")
        assertNotNull(result)
        assertEquals(125.0, result!!.number!!, 0.00001)
    }

    @Test fun vlookupUsesImportedEvaluator() {
        val cells = mapOf("A1" to "101", "B1" to "Apple", "A2" to "102", "B2" to "Orange")
        val result = PoiFormulaBridge.evaluate(cells, "D1", "VLOOKUP(102,A1:B2,2,FALSE)")
        assertNotNull(result)
        assertEquals("Orange", result!!.text)
    }

    @Test fun indexMatchUsesImportedEvaluator() {
        val cells = mapOf("A1" to "101", "B1" to "Apple", "A2" to "102", "B2" to "Orange")
        val result = PoiFormulaBridge.evaluate(cells, "D1", "INDEX(B1:B2,MATCH(102,A1:A2,0))")
        assertNotNull(result)
        assertEquals("Orange", result!!.text)
    }

    @Test fun crossSheetSumUsesWorkbookSheets() {
        val sheets = linkedMapOf(
            "Formula Tests" to mapOf("B2" to "=SUM(Sales!F2:F4)"),
            "Sales" to mapOf("F2" to "100.5", "F3" to "200", "F4" to "50")
        )
        val result = PoiFormulaBridge.evaluate(sheets, "Formula Tests", "B2", "SUM(Sales!F2:F4)")
        assertNotNull(result)
        assertEquals(350.5, result!!.number!!, 0.00001)
    }

    @Test fun crossSheetXlookupUsesIndexMatchFallback() {
        val sheets = linkedMapOf(
            "Formula Tests" to mapOf("B2" to "=XLOOKUP(\"Nisha\",Lookup!A2:A3,Lookup!B2:B3,\"Not found\")"),
            "Lookup" to mapOf("A2" to "Ravi", "B2" to "Ravi ID", "A3" to "Nisha", "B3" to "Nisha ID")
        )
        val result = PoiFormulaBridge.evaluate(sheets, "Formula Tests", "B2", "XLOOKUP(\"Nisha\",Lookup!A2:A3,Lookup!B2:B3,\"Not found\")")
        assertNotNull(result)
        assertEquals("Nisha ID", result!!.text)
    }


    @Test fun acceptsExcelLocaleSemicolonArgumentSeparators() {
        val cells = mapOf(
            "A1" to "East", "B1" to "100",
            "A2" to "West", "B2" to "50",
            "A3" to "East", "B3" to "25"
        )
        val result = PoiFormulaBridge.evaluate(cells, "D1", "SUMIF(A1:A3;\"East\";B1:B3)")
        assertNotNull(result)
        assertEquals(125.0, result!!.number!!, 0.00001)
    }

    @Test fun supportsAbsoluteReferencesAndFormulaDependencies() {
        val cells = mapOf("A1" to "10", "A2" to "20", "B1" to "=\$A\$1+\$A\$2", "B2" to "=B1*2")
        val result = PoiFormulaBridge.evaluate(cells, "C1", "B2+5")
        assertNotNull(result)
        assertEquals(65.0, result!!.number!!, 0.00001)
    }

    @Test fun supportsQuotedSheetNamesWithSpaces() {
        val sheets = linkedMapOf(
            "Formula Tests" to mapOf("B2" to "=SUM('Sales Data'!\$B\$2:\$B\$3)"),
            "Sales Data" to mapOf("B2" to "10.5", "B3" to "20")
        )
        val result = PoiFormulaBridge.evaluate(sheets, "Formula Tests", "B2", "SUM('Sales Data'!\$B\$2:\$B\$3)")
        assertNotNull(result)
        assertEquals(30.5, result!!.number!!, 0.00001)
    }

}
