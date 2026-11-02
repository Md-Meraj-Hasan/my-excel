package com.meraj.niceexcel

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.util.Log
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.FormulaError
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.ss.util.CellReference
import org.json.JSONObject
import org.libreoffice.kit.Document
import org.libreoffice.kit.LibreOfficeKit
import org.libreoffice.kit.Office
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * LibreOffice Calc calculation adapter.
 *
 * The Native grid remains the UI. This class sends a workbook snapshot to the
 * actual LibreOffice Calc core through the official Android LibreOfficeKit JNI
 * bootstrap. All LOK initialization and document calls are serialized on one
 * dedicated thread in the isolated :libreoffice service process.
 *
 * POI is used only to create an XLSX snapshot and read Calc-written cached
 * results. No POI FormulaEvaluator is called here.
 */
object LibreOfficeCalcEngine {
    private const val TAG = "NiceExcel-LO-Calc"
    private const val LOK_THREAD_NAME = "NiceExcel-LibreOfficeKit"
    private const val RECALC_COMMAND = ".uno:CalculateHard"
    private val commandTimeoutMinutes = 5L

    private val loThread = Executors.newSingleThreadExecutor { task ->
        Thread(task, LOK_THREAD_NAME).apply { isDaemon = true }
    }

    @Volatile
    private var office: Office? = null

    @Volatile
    private var initializationFailure: Throwable? = null

    /** Create a temporary XLSX containing the current workbook model for Binder file IPC. */
    fun createSnapshotWorkbook(
        context: Context,
        snapshot: Map<String, Map<String, String>>
    ): File {
        val workDir = File(context.cacheDir, "niceexcel-libreoffice").apply {
            check(isDirectory || mkdirs()) { "Cannot create LibreOffice work directory: $absolutePath" }
        }
        val file = File.createTempFile("snapshot-", ".xlsx", workDir)
        try {
            writeSnapshot(file, snapshot)
            check(file.isFile && file.length() > 0L) { "Snapshot XLSX is empty" }
            return file
        } catch (failure: Throwable) {
            file.delete()
            throw failure
        }
    }

    /**
     * Called by the isolated service. The method waits for the dedicated LOK
     * thread; the service/main/UI threads never call native LOK methods.
     */
    fun recalculateXlsxFile(context: Context, input: File, output: File) {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "LibreOffice recalculation must not run on Android's main thread"
        }
        require(input.isFile && input.length() > 0L) { "Input XLSX is missing or empty: $input" }
        require(input.canonicalFile != output.canonicalFile) { "Calc input and output paths must be different" }
        output.parentFile?.let { parent ->
            check(parent.isDirectory || parent.mkdirs()) { "Cannot create output directory: $parent" }
        }
        if (output.exists()) check(output.delete()) { "Cannot replace old Calc output: $output" }

        runOnLibreOfficeThread {
            ensureOfficeInitialized(context.applicationContext)
            recalculateOnLibreOfficeThread(input, output)
        }
    }

    /** Read formula cached values written by Calc. This method never evaluates formulas. */
    fun readFormulaResults(file: File): Map<String, String> = readCachedFormulaResults(file)

    /** Close the office handle on the same thread that created and used it. */
    fun closeOffice() {
        if (Thread.currentThread().name == LOK_THREAD_NAME) {
            office?.let { runCatching { it.destroy() } }
            office = null
            return
        }
        runOnLibreOfficeThread {
            office?.let { active ->
                runCatching { active.destroy() }
                    .onFailure { Log.e(TAG, "Error while destroying LibreOfficeKit Office", it) }
            }
            office = null
        }
    }

    private fun <T> runOnLibreOfficeThread(block: () -> T): T {
        if (Thread.currentThread().name == LOK_THREAD_NAME) return block()
        return loThread.submit(Callable { block() }).get(10, TimeUnit.MINUTES)
    }

    private fun ensureOfficeInitialized(context: Context) {
        if (office != null) return
        initializationFailure?.let {
            throw IllegalStateException("LibreOfficeKit initialization previously failed", it)
        }
        check(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }) {
            "LibreOffice Calc native runtime is currently packaged for arm64-v8a only"
        }

        try {
            // Class loading runs NativeLibLoader and System.loadLibrary() on this same LOK thread.
            LibreOfficeKit.initializeLibrary()
            LibreOfficeKit.init(context)
            check(LibreOfficeKit.isInitialized()) {
                "LibreOfficeKit.init() returned without completing native initialization"
            }
            val handle = LibreOfficeKit.getLibreOfficeKitHandle()
                ?: error("LibreOfficeKit returned a null native office handle")
            office = Office(handle)
            Log.i(TAG, "LOK_INITIALIZED abi=arm64-v8a pid=" + android.os.Process.myPid())
        } catch (failure: Throwable) {
            initializationFailure = failure
            Log.e(TAG, "LOK_INITIALIZATION_FAILED", failure)
            throw IllegalStateException("Unable to initialize bundled LibreOffice Calc runtime", failure)
        }
    }

    private fun recalculateOnLibreOfficeThread(input: File, output: File) {
        check(Thread.currentThread().name == LOK_THREAD_NAME) {
            "LibreOfficeKit document operation attempted from the wrong thread"
        }
        val activeOffice = office ?: error("LibreOfficeKit Office is not initialized")
        val inputUrl = Uri.fromFile(input).toString()
        val outputUrl = Uri.fromFile(output).toString()
        val document = activeOffice.documentLoad(inputUrl)
            ?: error("LibreOffice could not open XLSX input: ${activeOffice.error}")

        try {
            check(document.documentType == Document.DOCTYPE_SPREADSHEET) {
                "LibreOffice opened document type ${document.documentType}; expected Calc spreadsheet"
            }

            awaitUnoCommand(document, RECALC_COMMAND)
            document.saveAs(outputUrl, "Calc MS Excel 2007 XML", "")
            Log.i(TAG, "CALC_SAVE_COMPLETE outputBytes=${output.length()}")
        } finally {
            runCatching { document.destroy() }
                .onFailure { Log.e(TAG, "Failed to destroy Calc input document", it) }
        }

        check(output.isFile && output.length() > 0L) {
            "LibreOffice CalculateHard completed but XLSX output was not created"
        }

        // Re-open the saved workbook in a fresh LOK document. This proves the
        // Calc-produced XLSX can be parsed again before cached results are read.
        val reopened = activeOffice.documentLoad(outputUrl)
            ?: error("LibreOffice could not reopen its saved XLSX output: ${activeOffice.error}")
        try {
            check(reopened.documentType == Document.DOCTYPE_SPREADSHEET) {
                "Calc output reopened as document type ${reopened.documentType}, not spreadsheet"
            }
            Log.i(TAG, "CALC_SAVE_REOPEN_OK outputBytes=${output.length()}")
        } finally {
            runCatching { reopened.destroy() }
                .onFailure { Log.e(TAG, "Failed to destroy Calc verification document", it) }
        }
    }

    private fun awaitUnoCommand(document: Document, command: String) {
        val completed = CountDownLatch(1)
        val callbackResult = AtomicReference<JSONObject?>()
        val callbackPayload = AtomicReference<String?>()

        document.setMessageCallback { signal, payload ->
            if (signal == Document.CALLBACK_UNO_COMMAND_RESULT) {
                val json = runCatching { JSONObject(payload) }.getOrNull()
                if (json != null && json.optString("commandName") == command) {
                    callbackPayload.set(payload)
                    callbackResult.set(json)
                    completed.countDown()
                }
            }
        }

        try {
            Log.i(TAG, "CALC_COMMAND_START command=$command")
            document.postUnoCommand(command, "{}", true)
            check(completed.await(commandTimeoutMinutes, TimeUnit.MINUTES)) {
                "Timed out waiting for LibreOffice UNO command result: $command"
            }
            val result = callbackResult.get()
                ?: error("LibreOffice returned no parseable UNO command result: ${callbackPayload.get()}")
            check(result.has("success") && result.optBoolean("success")) {
                "LibreOffice UNO command failed: ${callbackPayload.get()}"
            }
            Log.i(TAG, "CALC_COMMAND_SUCCESS command=$command result=${callbackPayload.get()}")
        } finally {
            document.setMessageCallback(null)
        }
    }

    private fun writeSnapshot(file: File, snapshot: Map<String, Map<String, String>>) {
        val normalizedSheets = snapshot.mapValues { (_, cells) ->
            cells.mapValues { (_, raw) ->
                if (raw.trimStart().startsWith("=")) raw.trimStart() else raw
            }.toMutableMap()
        }.toMutableMap()
        if (normalizedSheets.isEmpty()) normalizedSheets["Sheet1"] = mutableMapOf()

        val emptyStyles = normalizedSheets.keys.associateWith { mutableMapOf<String, CellStyle>() }
            .toMutableMap()
        val emptyMerges = normalizedSheets.keys.associateWith { mutableListOf<String>() }
            .toMutableMap()

        FileOutputStream(file).use { output ->
            // Reuse NiceExcel's raw OOXML writer so unknown/new Excel formulas are
            // preserved verbatim; POI is only used to read Calc's saved result cache.
            XlsxCodec.write(output, normalizedSheets, emptyStyles, null, emptyMerges)
            output.fd.sync()
        }
    }

    private fun readCachedFormulaResults(file: File): Map<String, String> {
        require(file.isFile && file.length() > 0L) { "Calc output XLSX is missing or empty: $file" }
        val results = linkedMapOf<String, String>()
        WorkbookFactory.create(file).use { workbook ->
            for (sheet in workbook) {
                for (row in sheet) {
                    for (cell in row) {
                        if (cell.cellType != CellType.FORMULA) continue
                        val address = CellReference(cell.rowIndex, cell.columnIndex.toShort())
                            .formatAsString().replace("$", "")
                        val value = when (cell.cachedFormulaResultType) {
                            CellType.NUMERIC -> cell.numericCellValue.toString()
                            CellType.STRING -> cell.stringCellValue
                            CellType.BOOLEAN -> if (cell.booleanCellValue) "TRUE" else "FALSE"
                            CellType.ERROR -> runCatching {
                                FormulaError.forInt(cell.errorCellValue.toInt()).string
                            }.getOrDefault("#ERROR")
                            CellType.BLANK -> ""
                            else -> cell.toString()
                        }
                        results["${sheet.sheetName}!$address"] = value
                    }
                }
            }
        }
        return results
    }
}
