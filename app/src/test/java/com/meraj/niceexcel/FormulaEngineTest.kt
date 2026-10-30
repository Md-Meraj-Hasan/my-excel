package com.meraj.niceexcel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormulaEngineTest {
    private val engine = FormulaEngine(
        mapOf(
            "A1" to "Meraj",
            "A2" to "Hasan",
            "B1" to "10",
            "B2" to "20",
            "B3" to "",
            "C1" to "hello world"
        )
    )

    @Test fun concatenatesText() {
        assertEquals("Meraj Hasan", engine.evalText("CONCAT(A1,\" \",A2)"))
    }

    @Test fun extractsText() {
        assertEquals("Mer", engine.evalText("LEFT(A1,3)"))
        assertEquals("san", engine.evalText("RIGHT(A2,3)"))
        assertEquals("llo", engine.evalText("MID(C1,3,3)"))
    }

    @Test fun transformsText() {
        assertEquals("HELLO WORLD", engine.evalText("UPPER(C1)"))
        assertEquals("hello world", engine.evalText("LOWER(C1)"))
        assertEquals("Hello World", engine.evalText("PROPER(C1)"))
        assertEquals("hello world", engine.evalText("TRIM(\"  hello   world  \")"))
    }

    @Test fun replacesAndSearchesText() {
        assertEquals("Meraj Khan", engine.evalText("SUBSTITUTE(\"Meraj Hasan\",\"Hasan\",\"Khan\")"))
        assertEquals("Meraj Khan", engine.evalText("REPLACE(\"Meraj Hasan\",7,5,\"Khan\")"))
        assertEquals("7", engine.evalText("FIND(\"H\",\"Meraj Hasan\")"))
        assertEquals("1", engine.evalText("SEARCH(\"h\",\"Hello\")"))
    }

    @Test fun joinsRangeAndIgnoresEmptyCells() {
        assertEquals("10,20", engine.evalText("TEXTJOIN(\",\",TRUE,B1:B3)"))
    }

    @Test fun ifErrorUsesFallback() {
        assertEquals("fallback", engine.evalText("IFERROR(1/0,\"fallback\")"))
    }

    @Test fun evaluatesBasicNumericFunctions() {
        assertEquals(30.0, engine.eval("SUM(B1:B2)"), 0.0001)
        assertEquals(15.0, engine.eval("AVERAGE(B1:B2)"), 0.0001)
        assertEquals(2.0, engine.eval("COUNT(B1:B3)"), 0.0001)
    }
    @Test fun xlookupReturnsMatchingTextValue() {
        val e = FormulaEngine(mapOf("A1" to "Nisha", "B1" to "N-01", "A2" to "Ravi", "B2" to "R-02"))
        assertEquals("R-02", e.evalText("XLOOKUP(\"rAvI\",A1:A2,B1:B2)"))
    }

    @Test fun xlookupSupportsNumericTextAndNotFound() {
        val e = FormulaEngine(mapOf("A1" to "101", "B1" to "A", "A2" to "102", "B2" to "B"))
        assertEquals("B", e.evalText("XLOOKUP(102,A1:A2,B1:B2)"))
        assertEquals("missing", e.evalText("XLOOKUP(999,A1:A2,B1:B2,\"missing\")"))
    }

    @Test fun vlookupReturnsMatchingColumn() {
        val e = FormulaEngine(mapOf("A1" to "101", "B1" to "Nisha", "A2" to "102", "B2" to "Ravi"))
        assertEquals("Ravi", e.evalText("VLOOKUP(102,A1:B2,2,FALSE)"))
    }



    @Test fun calculatesDateFunctions() {
        val e = FormulaEngine(emptyMap())
        assertEquals(45292.0, e.eval("DATE(2024,1,1)"), 0.0001)
        assertEquals(2024.0, e.eval("YEAR(45292)"), 0.0001)
        assertEquals(1.0, e.eval("MONTH(45292)"), 0.0001)
        assertEquals(1.0, e.eval("DAY(45292)"), 0.0001)
    }

    @Test fun calculatesCommonMissingAggregateAndCriteriaFunctions() {
        val e = FormulaEngine(mapOf("A2" to "East", "B2" to "100", "A3" to "West", "B3" to "50", "A4" to "East", "B4" to "25", "C1" to "1", "C2" to "9", "C3" to "5"))
        assertEquals(125.0, e.eval("SUMIFS(B2:B4,A2:A4,\"East\")"), 0.0001)
        assertEquals(1.0, e.eval("COUNTIFS(A2:A4,\"East\",B2:B4,\">50\")"), 0.0001)
        assertEquals(62.5, e.eval("AVERAGEIF(A2:A4,\"East\",B2:B4)"), 0.0001)
        assertEquals(5.0, e.eval("MEDIAN(C1:C3)"), 0.0001)
    }

    @Test fun xorReturnsTrueWhenExactlyOneArgumentIsTrue() {
        val e = FormulaEngine(emptyMap())
        assertEquals("TRUE", e.evalText("XOR(TRUE,FALSE)"))
        assertEquals("FALSE", e.evalText("XOR(TRUE,TRUE)"))
        assertEquals("FALSE", e.evalText("XOR(FALSE,FALSE)"))
        assertEquals("TRUE", e.evalText("XOR(TRUE,FALSE,FALSE)"))
    }

    @Test fun vlookupAbsoluteRangeReturnsNAWhenLookupIsMissing() {
        val e = FormulaEngine(mapOf(
            "M2" to "P01", "N2" to "100", "O2" to "Red",
            "M3" to "P02", "N3" to "200", "O3" to "Green",
            "M4" to "P03", "N4" to "300", "O4" to "Yellow",
            "M5" to "P04", "N5" to "400", "O5" to "Blue",
            "M6" to "P05", "N6" to "500", "O6" to "Black"
        ))
        assertEquals("#N/A", e.evalText("VLOOKUP(\"P99\",\$M\$2:\$O\$6,2,FALSE)"))
        assertEquals("300", e.evalText("VLOOKUP(\"P03\",\$M\$2:\$O\$6,2,FALSE)"))
    }

    @Test fun xorTreatsBooleanLiteralsAsBooleans() {
        val e = FormulaEngine(emptyMap())
        assertEquals("TRUE", e.evalText("XOR(TRUE,FALSE)"))
        assertEquals("FALSE", e.evalText("XOR(TRUE,TRUE)"))
        assertEquals("FALSE", e.evalText("XOR(FALSE,FALSE)"))
    }

    @Test fun vlookupAbsoluteRangeReturnsNAForMissingExactMatch() {
        val e = FormulaEngine(mapOf(
            "M2" to "P01", "N2" to "100", "O2" to "Red",
            "M3" to "P02", "N3" to "200", "O3" to "Green",
            "M4" to "P03", "N4" to "300", "O4" to "Yellow",
            "M5" to "P04", "N5" to "400", "O5" to "Blue",
            "M6" to "P05", "N6" to "500", "O6" to "Black"
        ))
        assertEquals("#N/A", e.evalText("VLOOKUP(\"P99\",\$M\$2:\$O\$6,2,FALSE)"))
        assertEquals("300", e.evalText("VLOOKUP(\"P03\",\$M\$2:\$O\$6,2,FALSE)"))
    }


    @Test fun directCellReferencesPreserveBooleanAndExcelErrors() {
        val e = FormulaEngine(mapOf(
            "D29" to "=XOR(TRUE,FALSE)",
            "D31" to "=VLOOKUP(\"P99\",\$M\$2:\$O\$6,2,FALSE)",
            "M2" to "P01", "N2" to "100", "O2" to "Red",
            "M3" to "P02", "N3" to "200", "O3" to "Green",
            "M4" to "P03", "N4" to "300", "O4" to "Yellow",
            "M5" to "P04", "N5" to "400", "O5" to "Blue",
            "M6" to "P05", "N6" to "500", "O6" to "Black"
        ))
        assertEquals("TRUE", e.evalText("D29"))
        assertEquals("#N/A", e.evalText("D31"))
    }

    @Test fun directReferenceOptimizationDoesNotBypassPoiForOtherFormulas() {
        val cells = mapOf(
            "D29" to "=XOR(TRUE,FALSE)",
            "D31" to "=VLOOKUP(\"P99\",\$M\$2:\$O\$6,2,FALSE)",
            "D20" to "=REPT(\"A\",4)",
            "D22" to "=MOD(-5,3)",
            "D26" to "=SUMPRODUCT(A1:A3,B1:B3)",
            "D28" to "=PI()"
        )
        assertTrue(shouldUseLocalReferenceResult("D29", cells))
        assertTrue(shouldUseLocalReferenceResult("D31", cells))
        assertFalse(shouldUseLocalReferenceResult("D20", cells))
        assertFalse(shouldUseLocalReferenceResult("D22", cells))
        assertFalse(shouldUseLocalReferenceResult("D26", cells))
        assertFalse(shouldUseLocalReferenceResult("D28", cells))
    }

}
