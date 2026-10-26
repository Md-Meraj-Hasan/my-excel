package com.meraj.niceexcel

import org.junit.Assert.assertEquals
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
}
