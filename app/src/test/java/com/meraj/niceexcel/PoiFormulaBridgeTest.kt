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
}
