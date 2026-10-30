package com.meraj.niceexcel

import org.junit.Assert.assertEquals
import org.junit.Test

class PoiFormulaBridgeStatusComparisonTest {
    @Test fun booleanCellIsWrittenAsBooleanForStatusComparison() {
        val cells = mapOf(
            "D29" to "=XOR(TRUE,FALSE)",
            "E29" to "TRUE",
            "F29" to "=D29"
        )
        val result = PoiFormulaBridge.evaluate(
            cells,
            "G29",
            "IF(IFERROR(F29=E29,FALSE),\"PASS\",\"FAIL\")"
        )
        assertEquals("PASS", result?.text)
    }

    @Test fun displayedMissingLookupErrorCanBeComparedAsTextInStatusFormula() {
        val cells = mapOf(
            "D31" to "=VLOOKUP(\"P99\",\$M\$2:\$O\$6,2,FALSE)",
            "E31" to "#N/A",
            "F31" to "=D31",
            "M2" to "P01", "N2" to "100", "O2" to "Red",
            "M3" to "P02", "N3" to "200", "O3" to "Green",
            "M4" to "P03", "N4" to "300", "O4" to "Yellow",
            "M5" to "P04", "N5" to "400", "O5" to "Blue",
            "M6" to "P05", "N6" to "500", "O6" to "Black"
        )
        val result = PoiFormulaBridge.evaluate(
            cells,
            "G31",
            "IF(IFERROR(F31,\"#N/A\")=E31,\"PASS\",\"FAIL\")"
        )
        assertEquals("PASS", result?.text)
    }
}
