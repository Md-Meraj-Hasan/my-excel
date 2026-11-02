package com.meraj.niceexcel

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * These are real-device integration tests: they initialize the bundled
 * LibreOfficeKit native core in :libreoffice and run Calc's .uno:CalculateHard.
 * They must not be replaced by JVM tests of POI or the Kotlin evaluator.
 */
@RunWith(AndroidJUnit4::class)
class LibreOfficeCalcIntegrationTest {

    private fun context(): Context =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    private fun loadFixture(): JSONObject {
        val instrumentationContext = InstrumentationRegistry.getInstrumentation().context
        val json = instrumentationContext.assets.open("formula-cases.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        return JSONObject(json)
    }

    private fun snapshotFromFixture(root: JSONObject): Map<String, Map<String, String>> {
        val sheetsJson = root.getJSONObject("sheets")
        val snapshot = linkedMapOf<String, Map<String, String>>()
        val names = sheetsJson.keys()
        while (names.hasNext()) {
            val name = names.next()
            val cellsJson = sheetsJson.getJSONObject(name)
            val cells = linkedMapOf<String, String>()
            val addresses = cellsJson.keys()
            while (addresses.hasNext()) {
                val address = addresses.next()
                cells[address] = cellsJson.getString(address)
            }
            snapshot[name] = cells
        }
        return snapshot
    }

    private fun assertNumber(results: Map<String, String>, key: String, expected: Double) {
        val raw = results[key]
        assertNotNull("LibreOffice did not return $key", raw)
        val actual = raw!!.toDoubleOrNull()
        assertNotNull("Expected numeric Calc result for $key, got '$raw'", actual)
        assertEquals(key, expected, actual!!, 0.000001)
    }

    private fun assertFixtureExpected(root: JSONObject, results: Map<String, String>) {
        val expected = root.getJSONArray("expected")
        for (index in 0 until expected.length()) {
            val item = expected.getJSONObject(index)
            val key = item.getString("sheet") + "!" + item.getString("cell")
            when (item.getString("type")) {
                "number" -> assertNumber(results, key, item.getDouble("value"))
                "string" -> assertEquals(key, item.getString("value"), results[key])
                "error" -> {
                    val errorText = when (item.getString("category")) {
                        "DIV_ZERO" -> "#DIV/0!"
                        "NUM" -> "#NUM!"
                        "VALUE" -> "#VALUE!"
                        "REF" -> "#REF!"
                        else -> error("Unknown expected error category for $key")
                    }
                    assertEquals(key, errorText, results[key])
                }
                else -> error("Unknown expected formula result type for $key")
            }
        }
    }

    @Test(timeout = 720000)
    fun calcHardRecalculateReturnsExpectedTypedFormulaResults() {
        val fixture = loadFixture()
        val snapshot = snapshotFromFixture(fixture)
        val results = LibreOfficeServiceClient.recalculateSnapshot(context(), snapshot)

        // This request succeeds only if the isolated process loaded the bundled
        // LOK core, opened a Calc spreadsheet, completed CalculateHard, saved,
        // reopened the XLSX, and returned Calc-written formula caches.
        assertTrue("Calc returned no formula results", results.isNotEmpty())
        assertFixtureExpected(fixture, results)

        val mutated = snapshot.mapValues { (_, cells) -> cells.toMutableMap() }.toMutableMap()
        mutated.getValue("Dependencies")["A1"] = "7"
        val afterEdit = LibreOfficeServiceClient.recalculateSnapshot(context(), mutated)
        assertNumber(afterEdit, "Dependencies!B1", 14.0)
        assertNumber(afterEdit, "Dependencies!C1", 15.0)
    }

    @Test(timeout = 480000)
    fun calcSaveReopenPreservesFormulaTextAndCachedResults() {
        val fixture = loadFixture()
        val snapshot = snapshotFromFixture(fixture)
        val appContext = context()
        val input = LibreOfficeCalcEngine.createSnapshotWorkbook(appContext, snapshot)
        val output = File.createTempFile("niceexcel-calc-roundtrip-", ".xlsx", input.parentFile)
        try {
            LibreOfficeServiceClient.recalculateFile(appContext, input, output)
            val results = LibreOfficeCalcEngine.readFormulaResults(output)
            assertFixtureExpected(fixture, results)

            WorkbookFactory.create(output).use { workbook ->
                val formulaCell = workbook.getSheet("Basic").getRow(0).getCell(1)
                assertEquals("SUM(A1:A3)", formulaCell.cellFormula)
                assertEquals("FORMULA", formulaCell.cellType.name)
            }
        } finally {
            input.delete()
            output.delete()
        }
    }
}
