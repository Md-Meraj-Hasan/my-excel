package com.meraj.niceexcel

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.*
import android.widget.FrameLayout
import java.util.Locale
import kotlin.math.max

class MainActivity : Activity() {
    private var rowCount = 50
    private val colLimit = 26
    private val sheets = linkedMapOf<String, MutableMap<String,String>>()
    private val styles = linkedMapOf<String, MutableMap<String, CellStyle>>()
    private var reportingMetadata: String? = null
    private var filterColumn: String? = null
    private var filterText: String? = null
    private var tableHeaderRow: Int? = null
    private var sheetName = "Sheet1"
    private var selected = "A1"
    private var activeTopSection = "Home"
    private var ribbonToolsVisible = false
    private val undoHistory = mutableMapOf<String, MutableList<Map<String, String>>>()
    private val redoHistory = mutableMapOf<String, MutableList<Map<String, String>>>()
    private lateinit var grid: LinearLayout
    private lateinit var verticalScroll: ScrollView
    private lateinit var headerHorizontal: HorizontalScrollView
    private lateinit var frozenRowHorizontal: HorizontalScrollView
    private lateinit var bodyHorizontal: HorizontalScrollView
    private lateinit var columnHeader: LinearLayout
    private lateinit var frozenFirstRow: LinearLayout
    private lateinit var cellName: TextView
    private lateinit var formula: EditText
    private lateinit var status: TextView
    private lateinit var sheetTabs: LinearLayout
    private lateinit var ribbonTools: LinearLayout
    private var pendingCsv = ""
    private var selectedCellView: TextView? = null
    private val cellViews = mutableMapOf<String, TextView>()
    // Cached pixel-independent column widths from the last full layout.
    private var lastColumnWidths: List<Int> = List(26) { 39 }
    private var lastTapAddress: String? = null
    private var lastTapAt = 0L
    private var editingAddress: String? = null
    private var selectedRangeCells: List<String> = emptyList()
    private var selectedRangeLabel: String? = null
    // Rows in this set wrap text into at most two lines; other rows stay single-line.
    private val wrappedRowsBySheet = mutableMapOf<String, MutableSet<Int>>()
    private val wrappedRows get() = wrappedRowsBySheet.getOrPut(sheetName) { mutableSetOf() }

    private val data get() = sheets.getOrPut(sheetName) { mutableMapOf() }
    private val sheetStyles get() = styles.getOrPut(sheetName) { mutableMapOf() }
    private fun styleFor(address: String) = sheetStyles.getOrPut(address) { CellStyle() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(17, 94, 57)
        window.navigationBarColor = Color.rgb(20, 30, 40)
        sheets["Sheet1"] = mutableMapOf(
            "A1" to "Item", "B1" to "Qty", "C1" to "Price", "D1" to "Total",
            "A2" to "Notebook", "B2" to "3", "C2" to "12.5", "D2" to "=B2*C2",
            "A3" to "Pen", "B3" to "5", "C3" to "2", "D3" to "=B3*C3",
            "A4" to "Grand Total", "D4" to "=SUM(D2:D3)"
        )
        sheets["Sheet2"] = mutableMapOf()
        sheets["Sheet3"] = mutableMapOf()
        styles["Sheet1"] = mutableMapOf()
        styles["Sheet2"] = mutableMapOf()
        styles["Sheet3"] = mutableMapOf()
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(245,247,249)) }
        // Compact spreadsheet-style menu bar replaces the large app title.
        val menuScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Color.rgb(17, 94, 57))
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(5), dp(4), dp(5))
            setBackgroundColor(Color.rgb(17, 94, 57))
        }
        listOf("File", "Home", "Insert", "Format", "Data", "Formula", "View").forEach { label ->
            val button = tool(label) { showTopMenu(label) }.apply {
                textSize = 13f
                setPadding(dp(11), dp(9), dp(11), dp(9))
                setBackgroundColor(if (label == activeTopSection) Color.rgb(209, 250, 229) else Color.rgb(17, 94, 57))
                setTextColor(if (label == activeTopSection) Color.rgb(6, 78, 59) else Color.WHITE)
                if (label == activeTopSection) typeface = Typeface.DEFAULT_BOLD
                setOnClickListener {
                    val sameSection = label == activeTopSection
                    if (sameSection && ribbonToolsVisible) {
                        ribbonTools.visibility = android.view.View.GONE
                        (ribbonTools.parent as? android.view.View)?.visibility = android.view.View.GONE
                        ribbonToolsVisible = false
                    } else {
                        activeTopSection = label
                        for (i in 0 until toolbar.childCount) {
                            val tab = toolbar.getChildAt(i) as? TextView ?: continue
                            val active = tab.text.toString() == activeTopSection
                            tab.setBackgroundColor(if (active) Color.rgb(209, 250, 229) else Color.rgb(17, 94, 57))
                            tab.setTextColor(if (active) Color.rgb(6, 78, 59) else Color.WHITE)
                            tab.typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                        }
                        showTopMenu(label)
                        (ribbonTools.parent as? android.view.View)?.visibility = android.view.View.VISIBLE
                        ribbonTools.visibility = android.view.View.VISIBLE
                        ribbonToolsVisible = true
                    }
                }
            }
            toolbar.addView(button, LinearLayout.LayoutParams(-2, -2))
        }
        menuScroll.addView(toolbar)
        root.addView(menuScroll)

        // The active section's tools stay in a slim horizontal row directly below the tabs.
        // This is part of the screen layout, not a dialog, so touching cells won't dismiss it.
        val toolScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setFillViewport(false)
            setBackgroundColor(Color.rgb(239, 246, 241))
        }
        ribbonTools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(5), dp(2), dp(5), dp(2))
            setBackgroundColor(Color.rgb(239, 246, 241))
        }
        toolScroll.addView(ribbonTools, android.widget.FrameLayout.LayoutParams(-2, dp(34)))
        root.addView(toolScroll, LinearLayout.LayoutParams(-1, dp(36)))
        toolScroll.visibility = android.view.View.GONE

        val formulaBar=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(dp(6),dp(4),dp(6),dp(4)); setBackgroundColor(Color.rgb(255,255,255)) }
        cellName=TextView(this).apply { text=selected; textSize=14f; typeface=Typeface.DEFAULT_BOLD; gravity=Gravity.CENTER; setTextColor(Color.rgb(17,94,57)); setPadding(dp(10),dp(11),dp(10),dp(11)); setBackgroundColor(Color.rgb(225,242,232)) }
        formulaBar.addView(cellName)
        formula=EditText(this).apply { setSingleLine(true); textSize=14f; hint="Enter value or formula"; inputType=InputType.TYPE_CLASS_TEXT; setPadding(dp(8),dp(3),dp(8),dp(3)); setBackgroundColor(Color.WHITE); setText(data[selected].orEmpty()); setOnEditorActionListener { _,_,_ -> applyFormula(); true } }
        formulaBar.addView(formula,LinearLayout.LayoutParams(0,-2,1f))
        formulaBar.addView(TextView(this).apply { text="ƒx ▾"; textSize=12f; setTextColor(Color.rgb(17,94,57)); setPadding(dp(7),dp(10),dp(7),dp(10)); setOnClickListener { formulaPickerMenu() } })
        formulaBar.addView(TextView(this).apply { text="✓"; textSize=23f; setTextColor(Color.rgb(17,125,72)); setPadding(dp(10),dp(4),dp(10),dp(4)); setOnClickListener { applyFormula() } })
        root.addView(formulaBar)

        // Keep the column letters and worksheet row 1 outside the vertical scroller.
        // Their horizontal scroll positions are synchronized with the scrolling body.
        // Column letters and frozen row 1 must touch edge-to-edge: no margins,
        // padding, or spacer views between the two header strips.
        columnHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 0)
            clipToPadding = false
        }
        headerHorizontal = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = false
            setPadding(0, 0, 0, 0)
            clipToPadding = false
            clipChildren = false
            addView(columnHeader, FrameLayout.LayoutParams(-2, dp(24)))
        }
        root.addView(headerHorizontal, LinearLayout.LayoutParams(-1, dp(24)).apply {
            setMargins(0, 0, 0, 0)
        })

        frozenFirstRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 0)
            clipToPadding = false
        }
        frozenRowHorizontal = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = false
            setPadding(0, 0, 0, 0)
            clipToPadding = false
            clipChildren = false
            addView(frozenFirstRow, FrameLayout.LayoutParams(-2, dp(24)))
        }
        root.addView(frozenRowHorizontal, LinearLayout.LayoutParams(-1, dp(24)).apply {
            setMargins(0, 0, 0, 0)
        })

        bodyHorizontal = HorizontalScrollView(this).apply { isFillViewport=false; isHorizontalScrollBarEnabled=false }
        grid=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        bodyHorizontal.addView(grid)
        verticalScroll=ScrollView(this).apply { isFillViewport=true; isVerticalScrollBarEnabled=true; addView(bodyHorizontal) }
        root.addView(verticalScroll,LinearLayout.LayoutParams(-1,0,1f))
        val syncScroll = { source: HorizontalScrollView, targets: List<HorizontalScrollView> ->
            source.setOnScrollChangeListener { _, scrollX, _, _, _ ->
                targets.forEach { target -> if (target.scrollX != scrollX) target.scrollTo(scrollX, 0) }
            }
        }
        syncScroll(headerHorizontal, listOf(frozenRowHorizontal, bodyHorizontal))
        syncScroll(frozenRowHorizontal, listOf(headerHorizontal, bodyHorizontal))
        syncScroll(bodyHorizontal, listOf(headerHorizontal, frozenRowHorizontal))

        sheetTabs=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(dp(4),dp(5),dp(4),dp(5)); setBackgroundColor(Color.rgb(231,237,233)) }
        root.addView(sheetTabs)
        status=TextView(this).apply { text="Ready • Offline workbook"; textSize=12f; setTextColor(Color.WHITE); setPadding(dp(10),dp(7),dp(10),dp(7)); setBackgroundColor(Color.rgb(20,30,40)) }
        root.addView(status)
        setContentView(root)
        render()
    }

    private fun showTopMenu(section: String) {
        val items: List<Pair<String, () -> Unit>> = when (section) {
            "File" -> listOf(
                "New workbook" to { newWorkbook() },
                "Open workbook…" to { openMenu() },
                "Save" to { saveMenu() },
                "Save As…" to { saveMenu() },
                "Export CSV…" to { saveCsv() }
            )
            "Home" -> listOf(
                "Undo" to { undo() }, "Redo" to { redo() },
                "Copy selected cell" to { copySelectedCell() },
                "Paste range from clipboard" to { pasteRangeFromClipboard() },
                "Clear selected cell" to { clearSelectedCell() },
                "Cell formatting…" to { formattingMenu() },
                "Find / Replace…" to { findReplaceMenu() }
            )
            "Insert" -> listOf(
                "Add row" to { addOneRow() }, "Add worksheet" to { addSheet() },
                "Table tools…" to { tableMenu() }, "Merge / table heading tools…" to { tableMenu() }
            )
            "Format" -> listOf(
                "Format cells…" to { formattingMenu() },
                "Range formatting…" to { rangeToolsMenu() },
                "Font size…" to { chooseFontSize() },
                "Text alignment…" to { chooseAlignment() },
                "Number format…" to { chooseNumberFormat() },
                "Selected row: Single / Double line…" to { selectedRowTextModeMenu() },
                "Table styles…" to { tableMenu() }
            )
            "Data" -> listOf(
                "Sort column A → Z" to { sortColumn() },
                "Filter column…" to { filterMenu() },
                "Clear filter" to { filterColumn = null; filterText = null; render(); status.text = "Filter cleared" },
                "Find / Replace…" to { findReplaceMenu() },
                "Workbook data range info" to { val used = usedRange(); status.text = "Used range: A1:${colName(used.second)}${used.first}" }
            )
            "Formula" -> listOf("Insert function…" to { formulaPickerMenu() }, "Formula help" to { formulaHelp() }, "Formula examples" to { formulaHelp() })
            else -> listOf(
                "Web Engine" to { startActivity(Intent(this, FlaskActivity::class.java)) },
                "Workbook info" to { status.text = "${sheets.size} sheet(s) • $rowCount rows • Offline workbook" }
            )
        }
        ribbonTools.removeAllViews()
        val compactLabels = mapOf(
            "New workbook" to "＋ New", "Open workbook…" to "Open", "Save" to "Save", "Save As…" to "Save As",
            "Export CSV…" to "CSV", "Undo" to "↶ Undo", "Redo" to "↷ Redo", "Copy selected cell" to "Copy",
            "Clear selected cell" to "Clear", "Cell formatting…" to "Format", "Paste range from clipboard" to "Paste Range",
            "Find / Replace…" to "Find", "Add row" to "＋ Row", "Add worksheet" to "＋ Sheet", "Table tools…" to "Table",
            "Format cells…" to "Cells", "Range formatting…" to "Range", "Font size…" to "Size", "Text alignment…" to "Align",
            "Number format…" to "Number", "Selected row: Single / Double line…" to "↵ Wrap", "Table styles…" to "Styles",
            "Sort column A → Z" to "Sort A→Z", "Filter column…" to "Filter", "Clear filter" to "Clear filter", "Find / Replace…" to "Find",
            "Workbook data range info" to "Range info", "Insert function…" to "ƒx Insert", "Formula help" to "ƒx Help",
            "Web Engine" to "Web Excel", "Workbook info" to "Info"
        )
        items.forEach { item ->
            val label = compactLabels[item.first] ?: item.first
            val button = TextView(this).apply {
                text = label
                contentDescription = item.first
                textSize = 11f
                gravity = Gravity.CENTER
                maxLines = 1
                setTextColor(Color.rgb(35, 70, 50))
                setPadding(dp(7), dp(3), dp(7), dp(3))
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener { item.second.invoke() }
            }
            ribbonTools.addView(button, LinearLayout.LayoutParams(-2, dp(30)).apply {
                marginEnd = dp(3)
            })
        }
    }

    private fun copySelectedCell() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("$sheetName!$selected", data[selected].orEmpty()))
        status.text = "Copied $sheetName!$selected"
    }

    private fun clearSelectedCell() {
        AlertDialog.Builder(this).setTitle("Clear cell")
            .setMessage("Clear contents of $sheetName!$selected?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ ->
                putCell(selected, "")
                render(); formula.setText("")
                status.text = "Cleared $sheetName!$selected"
            }.show()
    }

    /** Safely paste tab/newline-separated clipboard text into a rectangular range. */
    private fun pasteRangeFromClipboard() {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            val clip = clipboard?.primaryClip
            if (clip == null || clip.itemCount == 0) {
                status.text = "Clipboard is empty. Copy a table first, then tap Paste Range."
                return
            }

            // Android clipboard items can be plain text, styled text, or URI-backed items.
            // Convert safely and report a recoverable message instead of crashing the app.
            val text = try {
                clip.getItemAt(0).coerceToText(this)?.toString().orEmpty()
            } catch (_: Exception) {
                ""
            }
            if (text.isBlank()) {
                status.text = "Clipboard has no readable text. Copy the table again and retry."
                return
            }

            val normalized = text.replace("\r\n", "\n").replace('\r', '\n').trimEnd('\n')
            val rows = normalized.split('\n').map { line -> line.split('\t') }
            val startMatch = Regex("^([A-Z]+)([1-9][0-9]*)$", RegexOption.IGNORE_CASE).matchEntire(selected)
            if (startMatch == null) {
                status.text = "Select a starting cell first"
                return
            }
            val startCol = columnIndex(startMatch.groupValues[1].uppercase(Locale.ROOT))
            val startRow = startMatch.groupValues[2].toIntOrNull() ?: 1
            val maxCols = rows.maxOfOrNull { it.size } ?: 0
            if (maxCols == 0 || rows.isEmpty()) {
                status.text = "No table data found"
                return
            }
            if (startCol < 0 || startCol + maxCols > colLimit) {
                AlertDialog.Builder(this)
                    .setMessage("Pasted data exceeds the ${colLimit}-column grid. Select an earlier starting column.")
                    .setPositiveButton("OK", null).show()
                return
            }
            if (startRow < 1 || startRow + rows.size - 1 > 10000) {
                status.text = "Pasted data exceeds the 10,000-row safety limit"
                return
            }

            // Keep one undo snapshot before making any changes.
            undoHistory.getOrPut(sheetName) { mutableListOf() }.add(data.toMap())
            redoHistory.getOrPut(sheetName) { mutableListOf() }.clear()
            rows.forEachIndexed { ri, row ->
                row.forEachIndexed { ci, value ->
                    val address = "${colName(startCol + ci)}${startRow + ri}"
                    if (value.isEmpty()) data.remove(address) else data[address] = value
                }
            }
            rowCount = max(rowCount, startRow + rows.size - 1)
            selected = "${colName(startCol)}$startRow"
            render()
            cellName.text = selected
            formula.setText(data[selected].orEmpty())
            status.text = "Pasted ${rows.size} rows × $maxCols columns into $sheetName!$selected"
        } catch (e: Exception) {
            // Clipboard failures must never close the workbook.
            status.text = "Paste failed safely: ${e.message ?: "unsupported clipboard content"}"
        }
    }

    private fun selectedRowTextModeMenu() {
        val row = Regex("^[A-Z]+(\\d+)$").matchEntire(selected)?.groupValues?.get(1)?.toIntOrNull()
        if (row == null) {
            status.text = "Select a cell in the row you want to format first"
            return
        }
        val isWrapped = row in wrappedRows
        AlertDialog.Builder(this)
            .setTitle("Row $row text layout")
            .setSingleChoiceItems(arrayOf("Single line (auto-expand column)", "Double line (wrap text)"), if (isWrapped) 1 else 0) { dialog, which ->
                if (which == 1) wrappedRows.add(row) else wrappedRows.remove(row)
                dialog.dismiss()
                render()
                status.text = if (which == 1) "Row $row set to double-line text" else "Row $row set to single-line text"
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun newWorkbook() {
        AlertDialog.Builder(this)
            .setTitle("New workbook")
            .setMessage("Create a blank workbook? Current unsaved changes will be cleared.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create") { _, _ ->
                sheets.clear(); styles.clear()
                sheetName = "Sheet1"; selected = "A1"; rowCount = 50
                sheets["Sheet1"] = mutableMapOf(); styles["Sheet1"] = mutableMapOf()
                sheets["Sheet2"] = mutableMapOf(); styles["Sheet2"] = mutableMapOf()
                sheets["Sheet3"] = mutableMapOf(); styles["Sheet3"] = mutableMapOf()
                selectedRangeCells = emptyList(); selectedRangeLabel = null
                render(); cellName.text = selected; formula.setText("")
                status.text = "New blank workbook"
            }.show()
    }

    private fun tool(label:String, action:()->Unit)=TextView(this).apply {
        text=label; textSize=14f; gravity=Gravity.CENTER; setTextColor(Color.WHITE); setPadding(dp(9),dp(9),dp(9),dp(9)); setOnClickListener { action() }
    }
    private fun ribbonButton(label:String, action:()->Unit)=TextView(this).apply {
        text=label; textSize=12f; gravity=Gravity.CENTER; setTextColor(Color.rgb(34,75,54)); setBackgroundColor(Color.rgb(236,246,239)); setOnClickListener { action() }
    }

    private fun render() {
        selectedCellView = null
        cellViews.clear()
        grid.removeAllViews()
        columnHeader.removeAllViews()
        frozenFirstRow.removeAllViews()
        val header=columnHeader
        header.addView(makeCell("",48,true,null))
        // Default width is about three characters. Non-wrapped cell content expands the
        // whole column automatically, one character-space at a time.
        val longestByColumn = IntArray(colLimit) { c -> colName(c).length }
        data.keys.forEach { address ->
            val match = Regex("^([A-Z]+)(\\d+)$").matchEntire(address) ?: return@forEach
            val column = columnIndex(match.groupValues[1])
            val row = match.groupValues[2].toIntOrNull() ?: return@forEach
            if (column in 0 until colLimit && row !in wrappedRows) {
                val shownLength = display(address).length
                if (shownLength > longestByColumn[column]) longestByColumn[column] = shownLength
            }
        }
        val columnWidths = longestByColumn.map { longest ->
            max(28, minOf(360, longest * 7 + 18))
        }
        lastColumnWidths = columnWidths
        for(c in 0 until colLimit) {
            val headerCell = makeCell(colName(c),columnWidths[c],true,null)
            headerCell.setOnClickListener { selectColumn(c) }
            header.addView(headerCell)
        }
        frozenRowHorizontal.visibility = android.view.View.VISIBLE
        if (rowPassesFilter(1)) {
            frozenFirstRow.addView(createRowView(1, columnWidths))
        } else {
            frozenRowHorizontal.visibility = android.view.View.GONE
        }
        for(r in 2..rowCount) {
            if (!rowPassesFilter(r)) continue
            grid.addView(createRowView(r, columnWidths))
        }
        val addRowBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            setBackgroundColor(Color.rgb(245, 249, 246))
        }
        val addRowButton = TextView(this).apply {
            text = "+"
            textSize = 25f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(17, 94, 57))
            contentDescription = "Add one row"
            setOnClickListener { addOneRow() }
        }
        addRowBar.addView(addRowButton, LinearLayout.LayoutParams(dp(44), dp(40)))
        addRowBar.addView(TextView(this).apply {
            text = "Add 1 row"; textSize = 13f; setTextColor(Color.rgb(35, 70, 50)); setPadding(dp(10), 0, 0, 0)
            setOnClickListener { addOneRow() }
        }, LinearLayout.LayoutParams(-2, dp(40)))
        addRowBar.tag = "add-row-bar"
        grid.addView(addRowBar)
        renderTabs()
    }

    private fun rowPassesFilter(row: Int): Boolean {
        if (filterColumn == null || filterText == null || row <= 1) return true
        return data["${filterColumn}$row"].orEmpty().contains(filterText!!, ignoreCase = true)
    }

    private fun createRowView(row: Int, columnWidths: List<Int>): LinearLayout {
        val rowHeight = if (row in wrappedRows) 52 else 24
        // Give the row itself an explicit height matching every child cell. Otherwise
        // Android can measure a wrapped TextView taller than its siblings and expose
        // an inset-looking second rectangle around only the multi-line cells.
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            // Do not baseline-align cells in a row. A two-line TextView has a different
            // baseline from its one-line siblings; baseline alignment vertically offsets
            // its background and creates the apparent extra/inset rectangle.
            setBaselineAligned(false)
            gravity = Gravity.TOP
            layoutParams = LinearLayout.LayoutParams(-2, dp(rowHeight))
            minimumHeight = dp(rowHeight)
        }
        val rowHeader = makeCell(row.toString(), 48, true, null, rowHeight)
        rowHeader.setOnClickListener { selectRow(row) }
        line.addView(rowHeader)
        for (c in 0 until colLimit) {
            val address = "${colName(c)}$row"
            line.addView(makeCell(display(address), columnWidths[c], false, address, rowHeight))
        }
        return line
    }

    private fun refreshAfterCellEdit(address: String, value: String): Boolean {
        // Keep the existing grid and keyboard responsive for ordinary edits. Only rebuild
        // the 1,300-view grid when a value needs more width than the current column provides.
        val editedText = display(address)
        val match = Regex("^([A-Z]+)(\\d+)$").matchEntire(address) ?: return false
        val col = columnIndex(match.groupValues[1])
        val currentWidth = lastColumnWidths.getOrNull(col) ?: return false
        if (editedText.length * 7 + 18 > currentWidth) return false
        val editedView = cellViews[address] ?: return false
        editedView.text = editedText
        var requiresLayout = false
        // Re-evaluate formula cells in-place so simple dependencies stay current without
        // destroying/recreating every cell view after each Enter key press.
        data.keys.filter { data[it].orEmpty().startsWith("=") }.forEach { formulaAddress ->
            val formulaText = display(formulaAddress)
            val formulaMatch = Regex("^([A-Z]+)(\\d+)$").matchEntire(formulaAddress) ?: return@forEach
            val formulaCol = columnIndex(formulaMatch.groupValues[1])
            if (formulaText.length * 7 + 18 > (lastColumnWidths.getOrNull(formulaCol) ?: 0)) {
                requiresLayout = true
            } else {
                cellViews[formulaAddress]?.text = formulaText
            }
        }
        return !requiresLayout
    }

    private fun calculatedColumnWidth(column: Int): Int {
        val name = colName(column)
        val longest = data.entries.asSequence()
            .filter { it.key.startsWith(name) && it.key.drop(name.length).toIntOrNull() != null }
            .map { display(it.key).length }
            .maxOrNull() ?: name.length
        // Three-character default; auto-expand by one character-space per extra character.
        return max(28, minOf(360, longest * 7 + 18))
    }

    private fun makeCell(value:String,width:Int,header:Boolean,key:String?,cellHeight:Int = 24):TextView {
        val tv=TextView(this).apply {
            text=value; textSize=13f; gravity=if(header||key==null) Gravity.CENTER else Gravity.CENTER_VERTICAL or Gravity.START
            setSingleLine(false)
            val cellRow = key?.let { Regex("^[A-Z]+(\\d+)$").matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
            maxLines = if (cellRow != null && cellRow in wrappedRows) 2 else 1
            ellipsize = if (maxLines == 2) null else android.text.TextUtils.TruncateAt.END
            setHorizontallyScrolling(false)
            // Preserve complete glyph ascenders/descenders and use consistent line spacing.
            // Without font padding Android can clip the lower line on some device fonts.
            includeFontPadding = true
            setLineSpacing(0f, 1.0f)
            gravity = if (header || key == null) Gravity.CENTER else Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(dp(3), 0, dp(3), 0)
            val st = if (key != null) sheetStyles[key] else null
            setTextColor(if(header) Color.rgb(55,67,77) else st?.textColor ?: Color.rgb(24,32,39))
            textSize = st?.fontSize ?: 13f
            typeface = Typeface.create(Typeface.DEFAULT, (if (st?.bold == true || header) Typeface.BOLD else Typeface.NORMAL) or (if (st?.italic == true) Typeface.ITALIC else Typeface.NORMAL))
            paint.isUnderlineText = st?.underline == true
            gravity = st?.alignment ?: if(header||key==null) Gravity.CENTER else Gravity.CENTER_VERTICAL or Gravity.START
            tag = key
            applyCellBackground(this, key, header)
            if (key != null) cellViews[key] = this
            if (key == selected && key != null) selectedCellView = this
            setOnClickListener {
                if (key != null) {
                    val now = SystemClock.elapsedRealtime()
                    val isDoubleTap = lastTapAddress == key && now - lastTapAt <= 350L
                    select(key)
                    if (isDoubleTap) {
                        lastTapAddress = null
                        lastTapAt = 0L
                        startInlineEdit(this)
                    } else {
                        lastTapAddress = key
                        lastTapAt = now
                    }
                }
            }
            setOnLongClickListener {
                if (key != null) {
                    lastTapAddress = null
                    lastTapAt = 0L
                    select(key)
                    startInlineEdit(this)
                }
                true
            }
        }
        val lp=LinearLayout.LayoutParams(dp(width),dp(cellHeight)); lp.setMargins(0,0,0,0); tv.layoutParams=lp
        return tv
    }

    private fun renderTabs() {
        sheetTabs.removeAllViews()
        sheets.keys.toList().forEach { name ->
            val t=TextView(this).apply {
                text=name; textSize=13f; gravity=Gravity.CENTER; setPadding(dp(15),dp(9),dp(15),dp(9))
                setTextColor(if(name==sheetName) Color.WHITE else Color.rgb(35,70,50))
                setBackgroundColor(if(name==sheetName) Color.rgb(17,94,57) else Color.rgb(213,229,218))
                setOnClickListener { sheetName=name; selected="A1"; cellName.text=selected; formula.setText(data[selected].orEmpty()); render() }
                setOnLongClickListener { renameSheet(name); true }
            }
            sheetTabs.addView(t)
        }
        sheetTabs.addView(tool("+") { addSheet() }.apply { setTextColor(Color.rgb(17,94,57)) })
    }

    private fun addOneRow() {
        rowCount += 1
        if (filterColumn != null && filterText != null) {
            render()
        } else {
            // Insert just the new row immediately before the add-row bar. Existing cells,
            // selection and scroll position are retained; no full-grid reconstruction.
            val insertAt = (grid.childCount - 1).coerceAtLeast(1)
            grid.addView(createRowView(rowCount, lastColumnWidths), insertAt)
        }
        status.text = "Added row $rowCount"
        verticalScroll.post { verticalScroll.smoothScrollTo(0, grid.height) }
    }

    private fun applyCellBackground(view: TextView, address: String?, header: Boolean) {
        val st = if (address != null) sheetStyles[address] else null
        val isSelected = address != null && (address == selected || address in selectedRangeCells)
        val fill = when {
            header -> Color.rgb(224, 232, 239)
            isSelected -> Color.rgb(207, 239, 220)
            st != null -> st.fillColor
            else -> Color.WHITE
        }
        val strokeColor = when {
            header -> Color.rgb(174, 187, 198)
            isSelected -> Color.rgb(17, 125, 72)
            st?.border == true -> Color.rgb(70, 80, 88)
            else -> Color.rgb(198, 207, 214)
        }
        val strokeWidth = when {
            isSelected -> 1
            st?.border == true && st.thickBorder -> 2
            else -> 1
        }
        view.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(fill)
            setStroke(if (strokeWidth == 1) 1 else dp(strokeWidth), strokeColor)
        }
    }

    private fun selectColumn(column: Int) {
        val col = colName(column)
        selectedRangeLabel = "$col:$col"
        selectedRangeCells = (1..rowCount).map { "$col$it" }
        selected = "${col}1"
        cellName.text = selectedRangeLabel
        formula.setText("")
        render()
        status.text = "Selected entire column $col · ${selectedRangeCells.size} cells. Open Format or Range to apply changes."
    }

    private fun selectRow(row: Int) {
        selectedRangeLabel = "$row:$row"
        selectedRangeCells = (0 until colLimit).map { "${colName(it)}$row" }
        selected = "A$row"
        cellName.text = selectedRangeLabel
        formula.setText("")
        render()
        status.text = "Selected entire row $row · ${selectedRangeCells.size} cells. Open Format or Range to apply changes."
    }

    private fun select(address:String) {
        val previousRange = selectedRangeCells
        val previousRangeLabel = selectedRangeLabel
        selectedRangeCells = emptyList()
        selectedRangeLabel = null
        if (selected != address || previousRange.isNotEmpty()) {
            val oldView = selectedCellView
            val oldAddress = selected
            selected = address
            // Remove highlight from every cell in a previously selected column/range.
            previousRange.forEach { cellViews[it]?.let { view -> applyCellBackground(view, it, false) } }
            oldView?.let { applyCellBackground(it, oldAddress, false) }
            selectedCellView = cellViews[address]
            selectedCellView?.let { applyCellBackground(it, address, false) }
        }
        cellName.text = address
        if (editingAddress == null) formula.setText(data[address].orEmpty())
    }

    private fun startInlineEdit(cell: TextView) {
        val address = cell.tag as? String ?: return
        if (editingAddress != null) return
        val parent = cell.parent as? LinearLayout ?: return
        val index = parent.indexOfChild(cell)
        if (index < 0) return
        val params = cell.layoutParams
        val editor = EditText(this).apply {
            setSingleLine(true)
            textSize = cell.textSize / resources.displayMetrics.scaledDensity
            setText(data[address].orEmpty())
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setPadding(dp(3), 0, dp(3), 0)
            setBackgroundColor(Color.rgb(207, 239, 220))
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            minHeight = dp(18)
        }
        editingAddress = address
        parent.removeViewAt(index)
        parent.addView(editor, index, params)
        editor.requestFocus()
        editor.post {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
        }
        var finished = false
        fun finish(save: Boolean) {
            if (finished) return
            finished = true
            val newValue = editor.text.toString()
            if (save) putCell(address, newValue)
            editingAddress = null
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(editor.windowToken, 0)

            // Restore the original TextView before trying the fast in-place refresh.
            // Previously the editor replaced this view, but the fast path updated the
            // detached TextView and skipped render(), leaving the cell stuck in edit mode.
            val editorIndex = parent.indexOfChild(editor)
            if (editorIndex >= 0) {
                parent.removeViewAt(editorIndex)
                parent.addView(cell, editorIndex, params)
            }
            cell.text = display(address)
            val fastUpdated = save && refreshAfterCellEdit(address, newValue)
            if (!fastUpdated) render()
            select(address)
            status.text = if (save) "Updated $sheetName!$address" else "Edit cancelled"
        }
        editor.setOnEditorActionListener { _, actionId, event ->
            val enterPressed =
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO ||
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT ||
                (event != null && event.keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                    event.action == android.view.KeyEvent.ACTION_UP)
            if (enterPressed) {
                // One Enter/Done press saves the cell, dismisses the keyboard and exits inline edit.
                finish(true)
                true
            } else false
        }
        editor.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_ESCAPE && event.action == android.view.KeyEvent.ACTION_UP) {
                finish(false)
                true
            } else false
        }
        editor.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) finish(true) }
    }
    private fun putCell(address:String,value:String) {
        undoHistory.getOrPut(sheetName) { mutableListOf() }.add(data.toMap())
        redoHistory.getOrPut(sheetName) { mutableListOf() }.clear()
        if (value.isEmpty()) data.remove(address) else data[address]=value
    }

    private fun formulaPickerMenu() {
        val formulas = arrayOf("SUM", "AVERAGE", "COUNT", "COUNTA", "MAX", "MIN", "MEDIAN", "PRODUCT", "IF", "IFERROR", "IFNA", "AND", "OR", "ROUND", "ROUNDUP", "ROUNDDOWN", "ABS", "SQRT", "POWER", "SUMIF", "SUMIFS", "COUNTIF", "COUNTIFS", "AVERAGEIF", "AVERAGEIFS", "MINIFS", "MAXIFS", "CONCAT", "CONCATENATE", "LEFT", "RIGHT", "MID", "LEN", "TRIM", "UPPER", "LOWER", "PROPER", "TODAY", "NOW", "DATE", "YEAR", "MONTH", "DAY")
        AlertDialog.Builder(this).setTitle("Choose formula")
            .setItems(formulas) { _, which ->
                formula.setText("=" + formulas[which] + "(")
                formula.setSelection(formula.text.length)
                formula.requestFocus()
                status.text = "Formula inserted: ${formulas[which]}"
            }.show()
    }
    private fun applyFormula() {
        putCell(selected,formula.text.toString()); render(); status.text="Updated $sheetName!$selected"
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(formula.windowToken,0)
    }
    private fun undo() {
        val stack = undoHistory.getOrPut(sheetName) { mutableListOf() }
        if (stack.isEmpty()) { status.text = "Nothing to undo"; return }
        redoHistory.getOrPut(sheetName) { mutableListOf() }.add(data.toMap())
        val previous = stack.removeAt(stack.lastIndex)
        data.clear(); data.putAll(previous)
        render(); formula.setText(data[selected].orEmpty()); status.text = "Undo complete"
    }
    private fun redo() {
        val stack = redoHistory.getOrPut(sheetName) { mutableListOf() }
        if (stack.isEmpty()) { status.text = "Nothing to redo"; return }
        undoHistory.getOrPut(sheetName) { mutableListOf() }.add(data.toMap())
        val next = stack.removeAt(stack.lastIndex)
        data.clear(); data.putAll(next)
        render(); formula.setText(data[selected].orEmpty()); status.text = "Redo complete"
    }
    private fun findReplaceMenu() {
        val panel=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(8),dp(20),0) }
        val find=EditText(this).apply { hint="Find text"; setSingleLine(true) }
        val replace=EditText(this).apply { hint="Replace with"; setSingleLine(true) }
        panel.addView(find); panel.addView(replace)
        AlertDialog.Builder(this).setTitle("Find and replace in $sheetName").setView(panel)
            .setPositiveButton("Replace all") { _,_ ->
                val q=find.text.toString()
                if(q.isNotEmpty()) {
                    var changed=0
                    data.toMap().forEach { (address,value) ->
                        if(value.contains(q,true)) { data[address]=value.replace(q,replace.text.toString(),ignoreCase=true); changed++ }
                    }
                    render(); formula.setText(data[selected].orEmpty()); status.text="Replaced in $changed cell(s)"
                } else status.text="Enter text to find"
            }.setNeutralButton("Find next") { _,_ ->
                val q=find.text.toString()
                val match=data.entries.firstOrNull { (a,v) -> a>selected && v.contains(q,true) }
                    ?: data.entries.firstOrNull { (_,v) -> v.contains(q,true) }
                if(match!=null) { select(match.key); status.text="Found ${match.key}" } else status.text="No match found"
            }.setNegativeButton("Cancel",null).show()
    }
    private fun addSheet() {
        var n=sheets.size+1; var name="Sheet$n"
        while(sheets.containsKey(name)) { n++; name="Sheet$n" }
        sheets[name]=mutableMapOf(); sheetName=name; selected="A1"; render(); cellName.text=selected; formula.setText("")
    }
    private fun renameSheet(old:String) {
        val input=EditText(this).apply { setText(old) }
        AlertDialog.Builder(this).setTitle("Rename worksheet").setView(input)
            .setPositiveButton("Rename") { _,_ ->
                val name=input.text.toString().trim()
                if(name.isNotEmpty() && !sheets.containsKey(name)) {
                    val rebuilt=linkedMapOf<String,MutableMap<String,String>>()
                    sheets.forEach { (k,v) -> rebuilt[if(k==old) name else k]=v }
                    sheets.clear(); sheets.putAll(rebuilt); if(sheetName==old) sheetName=name; render()
                }
            }.setNegativeButton("Cancel",null).show()
    }
    private fun formulaHelp() {
        AlertDialog.Builder(this).setTitle("Formula examples")
            .setMessage("Arithmetic: =A1+B1, =B2*C2\nRanges: =SUM(A1:A10)\nFunctions: SUM, AVERAGE, MIN, MAX, COUNT, COUNTA, COUNTIF, COUNTBLANK, SUMIF, IF, AND, OR, PRODUCT, ABS, SQRT, INT, ROUND, ROUNDUP, ROUNDDOWN, MOD, POWER, CEILING, FLOOR")
            .setPositiveButton("OK",null).show()
    }
    private fun rangeToolsMenu() {
        val input = EditText(this).apply {
            setSingleLine(true)
            hint = "Example: A1:C5, B:B, or 2:8"
            setText(selected)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), dp(4))
            addView(TextView(this@MainActivity).apply {
                text = "Choose cells, rows, or columns. Examples: A1:C5, B:B, 2:8. Next opens text/fill colour and formatting options."
                textSize = 13f
                setTextColor(Color.DKGRAY)
            })
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Apply tools to range")
            .setView(panel)
            .setPositiveButton("Next") { _, _ ->
                val range = parseCellRange(input.text.toString())
                if (range.isEmpty()) {
                    status.text = "Invalid range. Try A1:C5, B:B, or 2:8"
                    Toast.makeText(this, "Invalid range", Toast.LENGTH_SHORT).show()
                } else {
                    showRangeActions(input.text.toString().trim().uppercase(Locale.ROOT), range)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun parseCellRange(raw: String): List<String> {
        val text = raw.trim().uppercase(Locale.ROOT).replace("$", "")
        if (text.isEmpty()) return emptyList()
        val cellPattern = Regex("^([A-Z]+)([0-9]+)$")
        val columnPattern = Regex("^([A-Z]+):([A-Z]+)$")
        val rowPattern = Regex("^([0-9]+):([0-9]+)$")
        val parts = text.split(":")
        if (parts.size == 1) {
            val m = cellPattern.matchEntire(text) ?: return emptyList()
            val col = columnIndex(m.groupValues[1])
            val row = m.groupValues[2].toIntOrNull() ?: return emptyList()
            return if (col in 0 until colLimit && row in 1..rowCount) listOf("${colName(col)}$row") else emptyList()
        }
        if (parts.size != 2) return emptyList()
        val cm = columnPattern.matchEntire(text)
        if (cm != null) {
            val c1 = columnIndex(cm.groupValues[1]); val c2 = columnIndex(cm.groupValues[2])
            if (c1 !in 0 until colLimit || c2 !in 0 until colLimit || c1 > c2) return emptyList()
            return buildList { for (r in 1..rowCount) for (c in c1..c2) add("${colName(c)}$r") }
        }
        val rm = rowPattern.matchEntire(text)
        if (rm != null) {
            val r1 = rm.groupValues[1].toIntOrNull() ?: return emptyList()
            val r2 = rm.groupValues[2].toIntOrNull() ?: return emptyList()
            if (r1 !in 1..rowCount || r2 !in 1..rowCount || r1 > r2) return emptyList()
            return buildList { for (r in r1..r2) for (c in 0 until colLimit) add("${colName(c)}$r") }
        }
        val a = cellPattern.matchEntire(parts[0]) ?: return emptyList()
        val b = cellPattern.matchEntire(parts[1]) ?: return emptyList()
        val c1 = columnIndex(a.groupValues[1]); val c2 = columnIndex(b.groupValues[1])
        val r1 = a.groupValues[2].toIntOrNull() ?: return emptyList()
        val r2 = b.groupValues[2].toIntOrNull() ?: return emptyList()
        if (c1 !in 0 until colLimit || c2 !in 0 until colLimit || r1 !in 1..rowCount || r2 !in 1..rowCount || c1 > c2 || r1 > r2) return emptyList()
        return buildList { for (r in r1..r2) for (c in c1..c2) add("${colName(c)}$r") }
    }

    private fun showRangeActions(
        rangeLabel: String,
        cells: List<String>,
        pendingStyles: MutableMap<String, CellStyle> = cells.associateWith { styleFor(it).copy() }.toMutableMap()
    ) {
        val actions = arrayOf(
            "Text color", "Cell fill color", "Text alignment", "Bold on/off",
            "Italic on/off", "Underline on/off", "Font size", "Number format",
            "Cell borders on/off", "Clear formatting", "Clear cell contents now"
        )
        AlertDialog.Builder(this)
            .setTitle("Range $rangeLabel · ${cells.size} cells")
            .setItems(actions) { _, which ->
                fun continueEditing(message: String) {
                    status.text = "$message · Pending changes for $rangeLabel (tap Apply to commit)"
                    showRangeActions(rangeLabel, cells, pendingStyles)
                }
                when (which) {
                    0 -> chooseColor("Text color for $rangeLabel") { color ->
                        cells.forEach { pendingStyles.getValue(it).textColor = color }
                        continueEditing("Text color selected")
                    }
                    1 -> chooseColor("Cell fill for $rangeLabel") { color ->
                        cells.forEach { pendingStyles.getValue(it).fillColor = color }
                        continueEditing("Cell fill selected")
                    }
                    2 -> {
                        val options = arrayOf("Left", "Center", "Right")
                        AlertDialog.Builder(this).setTitle("Alignment for $rangeLabel").setItems(options) { _, index ->
                            val gravity = when (index) { 1 -> Gravity.CENTER; 2 -> Gravity.CENTER_VERTICAL or Gravity.END; else -> Gravity.CENTER_VERTICAL or Gravity.START }
                            cells.forEach { pendingStyles.getValue(it).alignment = gravity }
                            continueEditing("Alignment selected")
                        }.setNegativeButton("Cancel", null).show()
                    }
                    3 -> { cells.forEach { val st = pendingStyles.getValue(it); st.bold = !st.bold }; continueEditing("Bold toggled") }
                    4 -> { cells.forEach { val st = pendingStyles.getValue(it); st.italic = !st.italic }; continueEditing("Italic toggled") }
                    5 -> { cells.forEach { val st = pendingStyles.getValue(it); st.underline = !st.underline }; continueEditing("Underline toggled") }
                    6 -> {
                        val input = EditText(this).apply {
                            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                            setText(pendingStyles[cells.first()]?.fontSize?.toInt()?.toString() ?: "13")
                            hint = "8–32"
                        }
                        AlertDialog.Builder(this).setTitle("Font size for $rangeLabel").setView(input)
                            .setPositiveButton("Set") { _, _ ->
                                val size = input.text.toString().toFloatOrNull()?.coerceIn(8f, 32f)
                                if (size != null) {
                                    cells.forEach { pendingStyles.getValue(it).fontSize = size }
                                    continueEditing("Font size selected")
                                } else {
                                    Toast.makeText(this, "Enter a font size from 8 to 32", Toast.LENGTH_SHORT).show()
                                    showRangeActions(rangeLabel, cells, pendingStyles)
                                }
                            }.setNegativeButton("Cancel") { _, _ -> showRangeActions(rangeLabel, cells, pendingStyles) }.show()
                    }
                    7 -> {
                        val opts = arrayOf("General", "Number (2 decimals)", "Percentage", "Currency", "Date (text display)")
                        AlertDialog.Builder(this).setTitle("Number format for $rangeLabel").setItems(opts) { _, index ->
                            cells.forEach { pendingStyles.getValue(it).numberFormat = opts[index] }
                            continueEditing("Number format selected")
                        }.setNegativeButton("Cancel") { _, _ -> showRangeActions(rangeLabel, cells, pendingStyles) }.show()
                    }
                    8 -> { cells.forEach { val st = pendingStyles.getValue(it); st.border = !st.border }; continueEditing("Borders toggled") }
                    9 -> { cells.forEach { pendingStyles[it] = CellStyle() }; continueEditing("Clear formatting selected") }
                    10 -> {
                        // Content clearing is intentionally immediate; only format changes are staged.
                        cells.forEach { data.remove(it) }
                        if (selected in cells) formula.setText("")
                        render()
                        status.text = "Contents cleared for $rangeLabel"
                    }
                }
            }
            .setPositiveButton("Apply") { _, _ ->
                cells.forEach { address ->
                    val staged = pendingStyles[address]
                    if (staged != null && staged == CellStyle()) sheetStyles.remove(address)
                    else if (staged != null) sheetStyles[address] = staged.copy()
                }
                render()
                status.text = "Applied all formatting changes to $rangeLabel"
                Toast.makeText(this, "Formatting applied to $rangeLabel", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun formattingMenu() {
        val rangeCells = selectedRangeCells.toList()
        val targetLabel = selectedRangeLabel ?: selected
        val options = arrayOf(
            "Bold on/off", "Italic on/off", "Underline on/off", "Font size", "Text color", "Cell fill color",
            "Alignment", "Number format", "Cell borders", "Clear formatting", "Clear cell", "Find / replace"
        )
        AlertDialog.Builder(this).setTitle("Format $targetLabel").setItems(options) { _, which ->
            val targets = if (rangeCells.isNotEmpty()) rangeCells else listOf(selected)
            fun refresh(message: String) { render(); status.text = "$message: $targetLabel" }
            when (which) {
                0 -> { targets.forEach { styleFor(it).bold = !styleFor(it).bold }; refresh("Bold updated") }
                1 -> { targets.forEach { styleFor(it).italic = !styleFor(it).italic }; refresh("Italic updated") }
                2 -> { targets.forEach { styleFor(it).underline = !styleFor(it).underline }; refresh("Underline updated") }
                3 -> if (rangeCells.isNotEmpty()) chooseRangeFontSize(targetLabel, targets) else chooseFontSize()
                4 -> chooseColor("Text color for $targetLabel") { color -> targets.forEach { styleFor(it).textColor = color }; refresh("Text color applied") }
                5 -> chooseColor("Cell fill color for $targetLabel") { color -> targets.forEach { styleFor(it).fillColor = color }; refresh("Cell fill applied") }
                6 -> chooseAlignmentForTargets(targets, targetLabel)
                7 -> if (rangeCells.isNotEmpty()) chooseRangeNumberFormat(targetLabel, targets) else chooseNumberFormat()
                8 -> { targets.forEach { styleFor(it).border = !styleFor(it).border }; refresh("Borders updated") }
                9 -> { targets.forEach { sheetStyles.remove(it) }; refresh("Formatting cleared") }
                10 -> { if (rangeCells.isNotEmpty()) { targets.forEach { data.remove(it) }; formula.setText(""); refresh("Contents cleared") } else { putCell(selected, ""); formula.setText(""); render(); status.text = "Cell cleared: $selected" } }
                11 -> findReplaceMenu()
            }
        }.show()
    }

    private fun chooseAlignmentForTargets(targets: List<String>, label: String) {
        val opts = arrayOf("Left", "Center", "Right")
        AlertDialog.Builder(this).setTitle("Alignment for $label").setItems(opts) { _, index ->
            val alignment = when (index) { 1 -> Gravity.CENTER; 2 -> Gravity.CENTER_VERTICAL or Gravity.END; else -> Gravity.CENTER_VERTICAL or Gravity.START }
            targets.forEach { styleFor(it).alignment = alignment }
            render(); status.text = "Alignment applied to $label"
        }.show()
    }

    private fun chooseRangeFontSize(label: String, targets: List<String>) {
        val currentSize = targets.firstOrNull()?.let { styleFor(it).fontSize } ?: 13f
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(currentSize.toInt().toString())
            hint = "8–32"
        }
        AlertDialog.Builder(this).setTitle("Font size for $label").setView(input)
            .setPositiveButton("Apply") { _, _ ->
                val size = input.text.toString().toFloatOrNull()
                if (size == null) {
                    Toast.makeText(this, "Enter a font size from 8 to 32", Toast.LENGTH_SHORT).show()
                } else {
                    targets.forEach { styleFor(it).fontSize = size.coerceIn(8f, 32f) }
                    render()
                    status.text = "Font size applied to $label"
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun chooseRangeNumberFormat(label: String, targets: List<String>) {
        val opts = arrayOf("General", "Number (2 decimals)", "Percentage", "Currency", "Date (text display)")
        AlertDialog.Builder(this).setTitle("Number format for $label").setItems(opts) { _, index ->
            targets.forEach { styleFor(it).numberFormat = opts[index] }
            render()
            status.text = "Number format applied to $label: ${opts[index]}"
        }.setNegativeButton("Cancel", null).show()
    }

    private fun chooseFontSize() {
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; setText(styleFor(selected).fontSize.toInt().toString()); hint = "8–32" }
        AlertDialog.Builder(this).setTitle("Font size").setView(input)
            .setPositiveButton("Apply") { _, _ -> input.text.toString().toFloatOrNull()?.let { styleFor(selected).fontSize = it.coerceIn(8f, 32f) }; render() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun chooseColor(title: String, apply: (Int) -> Unit) {
        val names = arrayOf("Black", "White", "Red", "Orange", "Yellow", "Green", "Blue", "Purple", "Gray", "Light blue")
        val colors = intArrayOf(Color.BLACK, Color.WHITE, Color.RED, 0xFFFF9800.toInt(), Color.YELLOW, 0xFF2E7D32.toInt(), Color.BLUE, 0xFF7B1FA2.toInt(), Color.GRAY, 0xFFBBDEFB.toInt())
        AlertDialog.Builder(this).setTitle(title).setItems(names) { _, which -> apply(colors[which]); status.text = "$title applied" }.show()
    }

    private fun chooseAlignment() {
        val opts = arrayOf("Left", "Center", "Right")
        AlertDialog.Builder(this).setTitle("Text alignment").setItems(opts) { _, alignmentIndex ->
            val alignment = when (alignmentIndex) {
                1 -> Gravity.CENTER
                2 -> Gravity.CENTER_VERTICAL or Gravity.END
                else -> Gravity.CENTER_VERTICAL or Gravity.START
            }
            AlertDialog.Builder(this)
                .setTitle("Apply alignment to")
                .setItems(arrayOf("Selected cell only", "Entire column")) { _, scope ->
                    if (scope == 0) {
                        styleFor(selected).alignment = alignment
                    } else {
                        val col = Regex("^([A-Z]+)").find(selected)?.groupValues?.get(1) ?: "A"
                        for (row in 1..rowCount) styleFor("$col$row").alignment = alignment
                    }
                    render()
                    status.text = if (scope == 0) "Alignment applied to $selected" else "Alignment applied to column ${Regex("^([A-Z]+)").find(selected)?.groupValues?.get(1) ?: "A"}"
                }.show()
        }.show()
    }

    private fun chooseNumberFormat() {
        val opts = arrayOf("General", "Number (2 decimals)", "Percentage", "Currency", "Date (text display)")
        AlertDialog.Builder(this).setTitle("Number format").setItems(opts) { _, i ->
            styleFor(selected).numberFormat = opts[i]
            render(); status.text = "Number format set: ${opts[i]}"
        }.show()
    }

    private fun tableMenu() {
        val opts = arrayOf(
            "Format first row as heading",
            "Apply table style to selected row",
            "Apply THICK borders to used data range",
            "Remove table styling"
        )
        AlertDialog.Builder(this).setTitle("Table and borders").setItems(opts) { _, i ->
            val row = Regex("[A-Z]+([0-9]+)").matchEntire(selected)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            when (i) {
                0 -> {
                    tableHeaderRow = row
                    val lastCol = usedRange().second
                    for (c in 0..lastCol) {
                        val a = "${colName(c)}$row"; val st = styleFor(a)
                        st.bold = true; st.fillColor = 0xFF1B5E20.toInt(); st.textColor = Color.WHITE; st.border = true
                    }
                    status.text = "Row $row formatted as table heading"
                }
                1 -> {
                    val lastCol = usedRange().second
                    for (c in 0..lastCol) {
                        val a = "${colName(c)}$row"; val st = styleFor(a)
                        st.fillColor = if (c % 2 == 0) 0xFFE8F5E9.toInt() else Color.WHITE; st.border = true
                    }
                }
                2 -> {
                    val (lastRow, lastCol) = usedRange()
                    for (r in 1..lastRow) for (c in 0..lastCol) {
                        val address = "${colName(c)}$r"
                        val st = styleFor(address)
                        st.border = true
                        st.thickBorder = true
                    }
                    status.text = "Thick borders applied to used range A1:${colName(lastCol)}$lastRow"
                }
                3 -> {
                    sheetStyles.values.forEach { st -> st.border = false; st.thickBorder = false }
                    sheetStyles.clear(); tableHeaderRow = null
                    status.text = "Table styling removed"
                }
            }
            render()
        }.show()
    }

    /** Returns the last used row and column (zero-based column), including formula cells. */
    private fun usedRange(): Pair<Int, Int> {
        val used = data.filterValues { it.isNotBlank() }.keys
        if (used.isEmpty()) {
            val match = Regex("([A-Z]+)([0-9]+)", RegexOption.IGNORE_CASE).matchEntire(selected)
            val row = match?.groupValues?.get(2)?.toIntOrNull() ?: 1
            val col = match?.groupValues?.get(1)?.uppercase(Locale.ROOT)?.let { columnIndex(it) } ?: 0
            return max(1, row) to col.coerceIn(0, colLimit - 1)
        }
        var lastRow = 1
        var lastCol = 0
        used.forEach { address ->
            val match = Regex("([A-Z]+)([0-9]+)", RegexOption.IGNORE_CASE).matchEntire(address) ?: return@forEach
            lastCol = max(lastCol, columnIndex(match.groupValues[1].uppercase(Locale.ROOT)))
            lastRow = max(lastRow, match.groupValues[2].toIntOrNull() ?: 1)
        }
        return lastRow.coerceAtLeast(1) to lastCol.coerceIn(0, colLimit - 1)
    }

    private fun columnIndex(column: String): Int {
        var result = 0
        column.forEach { result = result * 26 + (it.uppercaseChar() - 'A' + 1) }
        return (result - 1).coerceAtLeast(0)
    }

    private fun filterMenu() {
        val col = selected.takeWhile { it.isLetter() }.ifEmpty { "A" }
        val input = EditText(this).apply { hint = "Text contains… (blank clears filter)"; setText(filterText.orEmpty()) }
        AlertDialog.Builder(this).setTitle("Filter column $col").setMessage("Filters rows using the selected column.").setView(input)
            .setPositiveButton("Apply") { _, _ ->
                val q = input.text.toString().trim()
                if (q.isEmpty()) { filterColumn = null; filterText = null; status.text = "Filter cleared" }
                else { filterColumn = col; filterText = q; status.text = "Filter: $col contains '$q'" }
                render()
            }.setNeutralButton("Clear filter") { _, _ -> filterColumn = null; filterText = null; render(); status.text = "Filter cleared" }
            .setNegativeButton("Cancel", null).show()
    }

    private fun sortColumn() {
        val col=selected.takeWhile { it.isLetter() }
        val headerRow=tableHeaderRow ?: 1
        val rows=(headerRow+1..rowCount).map { row ->
            row to (0 until colLimit).map { c -> data["${colName(c)}$row"].orEmpty() }
        }.filter { (_,values) -> values.any { it.isNotEmpty() } }
        val sorted=rows.sortedWith(compareBy<Pair<Int,List<String>>> {
            val v=it.second.getOrNull(columnIndex(col)).orEmpty()
            v.toDoubleOrNull()?.let { n -> String.format(Locale.US,"%030.8f",n) } ?: v.lowercase(Locale.US)
        })
        val styleSnapshots = sorted.map { (oldRow, _) -> oldRow to (0 until colLimit).mapNotNull { c ->
            val oldAddress="${colName(c)}$oldRow"; sheetStyles[oldAddress]?.let { c to it.copy() }
        }.toMap() }
        rows.forEach { (oldRow,_) ->
            (0 until colLimit).forEach { c -> data.remove("${colName(c)}$oldRow"); sheetStyles.remove("${colName(c)}$oldRow") }
        }
        sorted.forEachIndexed { idx, pair ->
            val newRow=headerRow+1+idx
            pair.second.forEachIndexed { c,v -> if(v.isNotEmpty()) data["${colName(c)}$newRow"]=v }
            styleSnapshots[idx].second.forEach { (c,style) -> sheetStyles["${colName(c)}$newRow"]=style }
        }
        render(); status.text="Sorted complete rows by $col; related row data kept together"
    }
    private fun display(address:String):String {
        val raw=data[address] ?: return ""
        val st = sheetStyles[address]
        if (!raw.startsWith("=")) {
            val n = raw.toDoubleOrNull()
            return if (n != null && st != null) formatNumber(n, st.numberFormat, raw) else raw
        }
        return try {
            val expression = raw.drop(1)
            // Try the imported Apache POI evaluator first for broad Excel-function coverage.
            val imported = PoiFormulaBridge.evaluate(data, address, expression)
            when {
                imported?.error == null && imported?.text != null -> imported.text
                imported?.error == null && imported?.number != null -> formatNumber(imported.number, st?.numberFormat ?: "General", imported.number.toString())
                else -> runCatching {
                    val engine=FormulaEngine(data)
                    val textResult = engine.evalText(expression)
                    if (textResult != null) textResult
                    else {
                        val value=engine.eval(expression)
                        if(value.isNaN()||value.isInfinite()) "#NUM!" else formatNumber(value, st?.numberFormat ?: "General", value.toString())
                    }
                }.getOrElse { imported?.error ?: "#ERROR" }
            } ?: "#ERROR"
        } catch(_:Exception) { "#ERROR" }
    }
    private fun formatNumber(value: Double, format: String, fallback: String): String = when (format) {
        "Number (2 decimals)" -> String.format(Locale.US, "%.2f", value)
        "Percentage" -> String.format(Locale.US, "%.2f%%", value * 100.0)
        "Currency" -> String.format(Locale.US, "$%.2f", value)
        else -> if (value % 1.0 == 0.0) value.toLong().toString() else String.format(Locale.US, "%.4f", value)
    }

    private fun colName(index:Int):String {
        var n=index+1; var out=""
        while(n>0) { val r=(n-1)%26; out=('A'.code+r).toChar()+out; n=(n-1)/26 }
        return out
    }
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()

    private fun openMenu() {
        AlertDialog.Builder(this).setTitle("Open workbook")
            .setItems(arrayOf("Excel workbook (.xlsx)", "CSV file (.csv)")) { _, which ->
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = if (which == 0) "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" else "text/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, if (which == 0) arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/octet-stream", "application/zip") else arrayOf("text/csv", "text/plain", "application/octet-stream"))
                }
                startActivityForResult(i, if (which == 0) 14 else 12)
            }.setNegativeButton("Cancel", null).show()
    }
    private fun saveMenu() {
        AlertDialog.Builder(this).setTitle("Save workbook")
            .setItems(arrayOf("Excel workbook (.xlsx)", "CSV file (.csv)")) { _, which ->
                val xlsx = which == 0
                val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = if (xlsx) "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" else "text/csv"
                    putExtra(Intent.EXTRA_TITLE, if (xlsx) "NiceExcel.xlsx" else "$sheetName.csv")
                }
                startActivityForResult(i, if (xlsx) 13 else 11)
            }.setNegativeButton("Cancel", null).show()
    }
    private fun saveCsv() {
        pendingCsv=(1..rowCount).joinToString("\n") { r ->
            (0 until colLimit).joinToString(",") { c ->
                val raw=data["${colName(c)}$r"].orEmpty().replace("\"","\"\"")
                if(raw.contains(",")||raw.contains("\"")||raw.contains("\n")) "\"$raw\"" else raw
            }
        }
        val i=Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type="text/csv"; putExtra(Intent.EXTRA_TITLE,"${sheetName}.csv") }
        startActivityForResult(i,11)
    }
    @Deprecated("Deprecated")
    override fun onActivityResult(requestCode:Int,resultCode:Int,intent:Intent?) {
        super.onActivityResult(requestCode,resultCode,intent)
        if(resultCode!=RESULT_OK||intent?.data==null) return
        try {
            if(requestCode==11) {
                contentResolver.openOutputStream(intent.data!!)?.use { it.write(pendingCsv.toByteArray(Charsets.UTF_8)) }
                status.text="CSV saved"
            } else if(requestCode==13) {
                contentResolver.openOutputStream(intent.data!!)?.use { XlsxCodec.write(it, sheets, styles, reportingMetadata) }
                status.text="Excel workbook (.xlsx) saved"
            } else if(requestCode==12) {
                val text=contentResolver.openInputStream(intent.data!!)?.bufferedReader()?.use { it.readText() }.orEmpty()
                data.clear(); sheetStyles.clear()
                rowCount = max(50, text.lines().size)
                text.lines().take(rowCount).forEachIndexed { r,line ->
                    parseCsv(line).take(colLimit).forEachIndexed { c,v -> if(v.isNotEmpty()) data["${colName(c)}${r+1}"]=v }
                }
                select("A1"); status.text="CSV opened"
            } else if(requestCode==14) {
                val loaded = contentResolver.openInputStream(intent.data!!)?.use { XlsxCodec.read(it) } ?: error("Could not open workbook")
                sheets.clear(); sheets.putAll(loaded.sheets)
                styles.clear(); styles.putAll(loaded.styles)
                reportingMetadata = loaded.reportingMetadata
                rowCount = max(50, sheets.values.flatMap { it.keys }.mapNotNull { Regex("[A-Z]+([0-9]+)").matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 50)
                loaded.sheets.keys.forEach { styles.getOrPut(it) { mutableMapOf() } }
                if (sheets.isEmpty()) { sheets["Sheet1"] = mutableMapOf(); styles["Sheet1"] = mutableMapOf() }
                sheetName = sheets.keys.first(); selected = "A1"; render(); cellName.text=selected; formula.setText(data[selected].orEmpty())
                status.text="Excel workbook opened • ${sheets.size} sheet(s)"
            }
        } catch(e:Exception) { status.text="File error: ${e.message ?: "unknown"}" }
    }
    private fun parseCsv(line:String):List<String> {
        val out=mutableListOf<String>(); val cur=StringBuilder(); var quoted=false; var i=0
        while(i<line.length) {
            val ch=line[i]
            if(ch=='"') { if(quoted&&i+1<line.length&&line[i+1]=='"') { cur.append('"'); i++ } else quoted=!quoted }
            else if(ch==','&&!quoted) { out.add(cur.toString()); cur.setLength(0) } else cur.append(ch)
            i++
        }
        out.add(cur.toString()); return out
    }
}

internal class FormulaEngine(private val cells:Map<String,String>) {
    /**
     * Evaluates common text-producing formulas separately from numeric formulas.
     * Returns null when the expression should be handled by the numeric evaluator.
     */
    fun evalText(expr: String): String? {
        val s = expr.trim()
        if (s.startsWith("\"") && s.endsWith("\"") && s.length >= 2) return s.substring(1, s.length - 1).replace("\"\"", "\"")
        val ifErrorMatch = Regex("(?i)^(IFERROR|IFNA)\\((.*)\\)$").matchEntire(s)
        if (ifErrorMatch != null) {
            val errorFunction = ifErrorMatch.groupValues[1].uppercase(Locale.US)
            val args = splitArgs(ifErrorMatch.groupValues[2])
            if (args.size >= 2) {
                val first = args[0].trim()
                val result = runCatching { evalText(first) ?: formatSimple(eval(first)) }.getOrNull()
                if (result != null && result != "#ERROR" && result != "#NUM!" && (errorFunction == "IFERROR" || result != "#N/A")) return result
                return evalText(args[1]) ?: runCatching { formatSimple(eval(args[1])) }.getOrElse { args[1].trim().trim('"') }
            }
            return null
        }
        val ifMatch = Regex("(?i)^IF\\((.*)\\)$").matchEntire(s)
        if (ifMatch != null) {
            val args = splitArgs(ifMatch.groupValues[1])
            if (args.size >= 3) {
                val chosen = if (condition(args[0])) args[1] else args[2]
                val direct = evalText(chosen)
                if (direct != null) return direct
                // A cell reference in the selected branch may contain text.
                val ref = chosen.trim().uppercase(Locale.US)
                if (Regex("^[A-Z]+[0-9]+$").matches(ref)) {
                    val cell = cells[ref] ?: ""
                    if (cell.startsWith("=")) return evalText(cell.drop(1)) ?: eval(cell.drop(1)).let { formatSimple(it) }
                    if (cell.toDoubleOrNull() == null) return cell
                }
            }
            return null
        }
        val call = Regex("(?i)^(XLOOKUP|VLOOKUP|CONCATENATE|CONCAT|LEFT|RIGHT|MID|LEN|LOWER|UPPER|TRIM|PROPER|SUBSTITUTE|REPLACE|FIND|SEARCH|TEXTJOIN)\\((.*)\\)$").matchEntire(s) ?: return null
        val name = call.groupValues[1].uppercase(Locale.US)
        val args = splitArgs(call.groupValues[2])
        fun textArg(arg: String): String {
            val a = arg.trim()
            if (a.startsWith("\"") && a.endsWith("\"") && a.length >= 2) return a.substring(1, a.length - 1).replace("\"\"", "\"")
            val ref = a.uppercase(Locale.US)
            if (Regex("^[A-Z]+[0-9]+$").matches(ref)) {
                val raw = cells[ref] ?: ""
                if (raw.startsWith("=")) return evalText(raw.drop(1)) ?: runCatching { formatSimple(eval(raw.drop(1))) }.getOrDefault("#ERROR")
                return raw
            }
            return runCatching { formatSimple(eval(a)) }.getOrElse { a }
        }
        if (name == "XLOOKUP") {
            if (args.size !in 3..6) return "#VALUE!"
            val lookup = textArg(args[0])
            val lookupCells = range(args[1])
            val returnCells = range(args[2])
            if (lookupCells.size != returnCells.size) return "#VALUE!"
            val notFound = if (args.size >= 4) textArg(args[3]) else "#N/A"
            val matchMode = if (args.size >= 5) runCatching { eval(args[4]).toInt() }.getOrDefault(0) else 0
            val searchMode = if (args.size >= 6) runCatching { eval(args[5]).toInt() }.getOrDefault(1) else 1
            if (matchMode !in listOf(-1, 0, 1, 2) || searchMode !in listOf(-2, -1, 1, 2)) return "#VALUE!"
            fun normalized(v: String): String = v.trim().toDoubleOrNull()?.let { String.format(Locale.US, "%.12g", it) } ?: v.trim().lowercase(Locale.US)
            val indices = if (searchMode == -1 || searchMode == -2) lookupCells.indices.reversed() else lookupCells.indices
            for (i in indices) {
                val raw = cells[lookupCells[i]].orEmpty()
                val value = if (raw.startsWith("=")) evalText(raw.drop(1)) ?: runCatching { formatSimple(eval(raw.drop(1))) }.getOrDefault("#ERROR") else raw
                val exact = normalized(value) == normalized(lookup)
                val wildcard = matchMode == 2 && Regex("^" + Regex.escape(lookup).replace("\\*", ".*").replace("\\?", ".") + "$", RegexOption.IGNORE_CASE).matches(value)
                if (exact || wildcard) return textArg(returnCells[i])
            }
            if (matchMode == -1 || matchMode == 1) {
                val target = lookup.trim().toDoubleOrNull()
                if (target != null) {
                    val candidates = lookupCells.indices.mapNotNull { i ->
                        val raw = cells[lookupCells[i]].orEmpty()
                        val n = raw.trim().toDoubleOrNull()
                        if (n != null && (if (matchMode == -1) n <= target else n >= target)) Triple(n, i, returnCells[i]) else null
                    }
                    val chosen = if (matchMode == -1) candidates.maxByOrNull { it.first } else candidates.minByOrNull { it.first }
                    if (chosen != null) return textArg(chosen.third)
                }
            }
            return notFound
        }
        if (name == "VLOOKUP") {
            if (args.size !in 3..4) return "#VALUE!"
            val lookup = textArg(args[0])
            val tableCells = range(args[1])
            val bounds = args[1].trim().split(":")
            if (bounds.size != 2) return "#VALUE!"
            val start = runCatching { splitAddress(bounds[0]) }.getOrNull() ?: return "#VALUE!"
            val end = runCatching { splitAddress(bounds[1]) }.getOrNull() ?: return "#VALUE!"
            val width = kotlin.math.abs(end.first - start.first) + 1
            val height = kotlin.math.abs(end.second - start.second) + 1
            val colIndex = runCatching { eval(args[2]).toInt() }.getOrDefault(0)
            if (colIndex < 1 || colIndex > width) return "#REF!"
            val approximate = args.size == 4 && textArg(args[3]).equals("TRUE", true)
            fun norm(v: String): String = v.trim().toDoubleOrNull()?.let { String.format(Locale.US, "%.12g", it) } ?: v.trim().lowercase(Locale.US)
            var selectedRow = -1
            var bestNumeric = Double.NEGATIVE_INFINITY
            for (r in 0 until height) {
                val keyIndex = r * width
                val address = tableCells.getOrNull(keyIndex) ?: continue
                val raw = cells[address].orEmpty()
                val value = if (raw.startsWith("=")) evalText(raw.drop(1)) ?: runCatching { formatSimple(eval(raw.drop(1))) }.getOrDefault("#ERROR") else raw
                if (norm(value) == norm(lookup)) { selectedRow = r; break }
                if (approximate) {
                    val n = value.trim().toDoubleOrNull(); val target = lookup.trim().toDoubleOrNull()
                    if (n != null && target != null && n <= target && n > bestNumeric) { bestNumeric = n; selectedRow = r }
                }
            }
            if (selectedRow < 0) return "#N/A"
            return textArg(tableCells[selectedRow * width + colIndex - 1])
        }
        val joined = args.joinToString("") { textArg(it) }
        return when (name) {
            "CONCAT", "CONCATENATE" -> joined
            "LEFT" -> { val t = textArg(args.getOrElse(0) { "\\\"\\\"" }); t.take((args.getOrNull(1)?.let { runCatching { eval(it).toInt() }.getOrNull() } ?: 1).coerceAtLeast(0)) }
            "RIGHT" -> { val t = textArg(args.getOrElse(0) { "\\\"\\\"" }); t.takeLast((args.getOrNull(1)?.let { runCatching { eval(it).toInt() }.getOrNull() } ?: 1).coerceAtLeast(0)) }
            "MID" -> { val t = textArg(args.getOrElse(0) { "\\\"\\\"" }); val start = (args.getOrNull(1)?.let { runCatching { eval(it).toInt() }.getOrNull() } ?: 1).coerceAtLeast(1) - 1; val count = (args.getOrNull(2)?.let { runCatching { eval(it).toInt() }.getOrNull() } ?: 0).coerceAtLeast(0); t.drop(start).take(count) }
            "LEN" -> textArg(args.getOrElse(0) { "\\\"\\\"" }).length.toString()
            "LOWER" -> textArg(args.getOrElse(0) { "\\\"\\\"" }).lowercase(Locale.US)
            "UPPER" -> textArg(args.getOrElse(0) { "\\\"\\\"" }).uppercase(Locale.US)
            "TRIM" -> textArg(args.getOrElse(0) { "\\\"\\\"" }).trim().replace(Regex("\\s+"), " ")
            "PROPER" -> Regex("\\b\\w").replace(textArg(args.getOrElse(0) { "\"\"" }).lowercase(Locale.US)) { match -> match.value.uppercase(Locale.US) }
            "SUBSTITUTE" -> {
                val source = textArg(args.getOrElse(0) { "\"\"" })
                val oldText = textArg(args.getOrElse(1) { "\"\"" })
                val newText = textArg(args.getOrElse(2) { "\"\"" })
                if (oldText.isEmpty()) source else if (args.size >= 4) {
                    val instance = runCatching { eval(args[3]).toInt() }.getOrDefault(1).coerceAtLeast(1)
                    var seen = 0; var from = 0; var result = source
                    while (true) { val at = result.indexOf(oldText, from); if (at < 0) break; seen++; if (seen == instance) { result = result.substring(0, at) + newText + result.substring(at + oldText.length); break }; from = at + oldText.length }
                    result
                } else source.replace(oldText, newText)
            }
            "REPLACE" -> { val source = textArg(args.getOrElse(0) { "\"\"" }); val start = (runCatching { eval(args.getOrElse(1) { "1" }).toInt() }.getOrDefault(1) - 1).coerceAtLeast(0); val count = runCatching { eval(args.getOrElse(2) { "0" }).toInt() }.getOrDefault(0).coerceAtLeast(0); val replacement = textArg(args.getOrElse(3) { "\"\"" }); source.take(start) + replacement + source.drop((start + count).coerceAtMost(source.length)) }
            "FIND", "SEARCH" -> { val find = textArg(args.getOrElse(0) { "\"\"" }); val source = textArg(args.getOrElse(1) { "\"\"" }); val start = (runCatching { eval(args.getOrElse(2) { "1" }).toInt() }.getOrDefault(1) - 1).coerceAtLeast(0).coerceAtMost(source.length); val haystack = if (name == "SEARCH") source.lowercase(Locale.US) else source; val needle = if (name == "SEARCH") find.lowercase(Locale.US) else find; val at = haystack.indexOf(needle, start); if (at < 0) error("Text not found") else (at + 1).toString() }
            "TEXTJOIN" -> { val delimiter = textArg(args.getOrElse(0) { "\"\"" }); val ignoreEmpty = textArg(args.getOrElse(1) { "TRUE" }).equals("TRUE", true); args.drop(2).flatMap { a -> if (a.contains(":")) range(a).map { textArg(it) } else listOf(textArg(a)) }.let { values -> (if (ignoreEmpty) values.filter { it.isNotEmpty() } else values).joinToString(delimiter) } }
            else -> null
        }
    }

    private fun formatSimple(value: Double): String =
        if (value.isNaN() || value.isInfinite()) "#NUM!" else if (value % 1.0 == 0.0) value.toLong().toString() else String.format(Locale.US, "%.4f", value)

    fun eval(expr:String):Double {
        val s=expr.trim()
        if (Regex("(?i)^TODAY\\(\\)$").matches(s)) return ChronoUnit.DAYS.between(LocalDate.of(1899,12,30), LocalDate.now()).toDouble()
        if (Regex("(?i)^NOW\\(\\)$").matches(s)) {
            val now=LocalDateTime.now(); return ChronoUnit.DAYS.between(LocalDate.of(1899,12,30), now.toLocalDate()).toDouble() + (now.hour*3600+now.minute*60+now.second)/86400.0
        }
        val dateFn=Regex("(?i)^(DATE|YEAR|MONTH|DAY)\\((.*)\\)$").matchEntire(s)
        if(dateFn!=null) {
            val name=dateFn.groupValues[1].uppercase(Locale.US); val a=splitArgs(dateFn.groupValues[2])
            return when(name) {
                "DATE" -> { if(a.size!=3) error("DATE requires 3 arguments"); var year=eval(a[0]).toInt(); val month=eval(a[1]).toInt(); val day=eval(a[2]).toInt(); if(year<1900) year+=1900; LocalDate.of(year,1,1).plusMonths((month-1).toLong()).plusDays((day-1).toLong()).toEpochDay().minus(LocalDate.of(1899,12,30).toEpochDay()).toDouble() }
                else -> { if(a.size!=1) error("Date function requires 1 argument"); val date=LocalDate.of(1899,12,30).plusDays(eval(a[0]).toLong()); when(name) { "YEAR" -> date.year.toDouble(); "MONTH" -> date.monthValue.toDouble(); else -> date.dayOfMonth.toDouble() } }
            }
        }
        val fn=Regex("(?i)^(SUM|AVERAGE|MIN|MAX|MEDIAN|COUNT|COUNTA|COUNTIF|COUNTIFS|SUMIF|SUMIFS|AVERAGEIF|AVERAGEIFS|MINIFS|MAXIFS|PRODUCT|ABS|SQRT|INT|ROUND|ROUNDUP|ROUNDDOWN|CEILING|FLOOR|MOD|POWER|COUNTBLANK|SUBTOTAL)\\((.*)\\)$").matchEntire(s)
        if(fn!=null) {
            val name=fn.groupValues[1].uppercase(Locale.US)
            val args=splitArgs(fn.groupValues[2])
            if(name in listOf("SUM","AVERAGE","MIN","MAX","MEDIAN","COUNT","COUNTA","PRODUCT")) {
                val addresses = args.flatMap { arg -> if (arg.contains(":")) range(arg) else if (Regex("(?i)^[A-Z]+[0-9]+$").matches(arg.trim())) listOf(arg.trim().uppercase(Locale.US)) else emptyList() }
                val literals = args.filterNot { it.contains(":") || Regex("(?i)^[A-Z]+[0-9]+$").matches(it.trim()) }.mapNotNull { it.trim().toDoubleOrNull() }
                val rawValues = addresses.mapNotNull { cells[it] }
                val vals = addresses.mapNotNull { numeric(it) } + literals
                return when(name) {
                    "SUM" -> vals.sum()
                    "AVERAGE" -> if(vals.isEmpty()) 0.0 else vals.average()
                    "MIN" -> vals.minOrNull() ?: 0.0
                    "MAX" -> vals.maxOrNull() ?: 0.0
                    "MEDIAN" -> if (vals.isEmpty()) 0.0 else vals.sorted().let { sorted -> if (sorted.size % 2 == 1) sorted[sorted.size/2] else (sorted[sorted.size/2-1] + sorted[sorted.size/2]) / 2.0 }
                    "COUNT" -> vals.size.toDouble()
                    "COUNTA" -> (rawValues.count { it.isNotEmpty() } + literals.size).toDouble()
                    else -> vals.fold(1.0) { acc, v -> acc * v }
                }
            }
            if(name=="ABS" && args.size==1) return kotlin.math.abs(eval(args[0]))
            if(name=="SQRT" && args.size==1) return kotlin.math.sqrt(eval(args[0]))
            if(name=="INT" && args.size==1) return kotlin.math.floor(eval(args[0]))
            if(name=="ROUNDUP" && args.isNotEmpty()) { val n=if(args.size>1) eval(args[1]).toInt().coerceIn(0,10) else 0; val f=Math.pow(10.0,n.toDouble()); return kotlin.math.ceil(eval(args[0])*f)/f }
            if(name=="ROUNDDOWN" && args.isNotEmpty()) { val n=if(args.size>1) eval(args[1]).toInt().coerceIn(0,10) else 0; val f=Math.pow(10.0,n.toDouble()); return kotlin.math.floor(eval(args[0])*f)/f }
            if(name=="CEILING" && args.size==2) { val x=eval(args[0]); val sig=kotlin.math.abs(eval(args[1])); if(sig==0.0) return 0.0; return kotlin.math.ceil(x/sig)*sig }
            if(name=="FLOOR" && args.size==2) { val x=eval(args[0]); val sig=kotlin.math.abs(eval(args[1])); if(sig==0.0) return 0.0; return kotlin.math.floor(x/sig)*sig }
            if(name=="ROUND" && args.isNotEmpty()) { val n=if(args.size>1) eval(args[1]).toInt().coerceIn(0,10) else 0; val f=Math.pow(10.0,n.toDouble()); return kotlin.math.round(eval(args[0])*f)/f }
            if(name=="MOD" && args.size==2) return eval(args[0]) % eval(args[1])
            if(name=="POWER" && args.size==2) return Math.pow(eval(args[0]),eval(args[1]))
            if(name=="AVERAGEIF" && args.size in 2..3) {
                val criteriaRange=range(args[0]); val valueRange=if(args.size==3) range(args[2]) else criteriaRange
                if(criteriaRange.size!=valueRange.size) error("Range size mismatch")
                val nums=criteriaRange.indices.filter { matches(cells[criteriaRange[it]].orEmpty(),args[1]) }.mapNotNull { numeric(valueRange[it]) }
                return if(nums.isEmpty()) 0.0 else nums.average()
            }
            if(name in listOf("SUMIFS","COUNTIFS","AVERAGEIFS","MINIFS","MAXIFS")) {
                val hasValueRange=name in listOf("SUMIFS","AVERAGEIFS","MINIFS","MAXIFS")
                val offset=if(hasValueRange) 1 else 0
                if(args.size < offset+2 || (args.size-offset)%2!=0) error("Invalid criteria arguments")
                val valueRange=if(hasValueRange) range(args[0]) else emptyList()
                val pairs=args.drop(offset)
                val criteriaRanges=pairs.filterIndexed { i, _ -> i%2==0 }.map { range(it) }
                val criteria=pairs.filterIndexed { i, _ -> i%2==1 }
                val size=criteriaRanges.firstOrNull()?.size ?: 0
                if(criteriaRanges.any { it.size!=size } || (hasValueRange && valueRange.size!=size)) error("Range size mismatch")
                val selected=(0 until size).filter { i -> criteriaRanges.indices.all { j -> matches(cells[criteriaRanges[j][i]].orEmpty(),criteria[j]) } }
                if(name=="COUNTIFS") return selected.size.toDouble()
                val nums=selected.mapNotNull { numeric(valueRange[it]) }
                return when(name) { "SUMIFS" -> nums.sum(); "AVERAGEIFS" -> if(nums.isEmpty()) 0.0 else nums.average(); "MINIFS" -> nums.minOrNull()?:0.0; else -> nums.maxOrNull()?:0.0 }
            }
            if(name=="COUNTIF" && args.size==2) return range(args[0]).count { matches(cells[it].orEmpty(),args[1]) }.toDouble()
            if(name=="COUNTBLANK" && args.size==1) return range(args[0]).count { cells[it].isNullOrEmpty() }.toDouble()
            if(name=="SUBTOTAL" && args.size>=2) {
                val code = eval(args[0]).toInt()
                val values = range(args[1]).mapNotNull { numeric(it) }
                return when (code) { 1 -> values.average().takeUnless { it.isNaN() } ?: 0.0; 2 -> values.size.toDouble(); 4 -> values.maxOrNull() ?: 0.0; 5 -> values.minOrNull() ?: 0.0; 9 -> values.sum(); else -> error("Unsupported SUBTOTAL code") }
            }
            if(name=="SUMIF" && args.size>=2) {
                val criteria=range(args[0]); val sumRange=if(args.size>2) range(args[2]) else criteria
                return criteria.indices.filter { matches(cells[criteria[it]].orEmpty(),args[1]) }.sumOf { i -> numeric(sumRange.getOrElse(i){""})?:0.0 }
            }
        }
        if(s.uppercase(Locale.US).startsWith("IF(") && s.endsWith(")")) {
            val a=splitArgs(s.substring(3,s.length-1))
            if(a.size>=3) return if(condition(a[0])) eval(a[1]) else eval(a[2])
        }
        if(s.uppercase(Locale.US).startsWith("AND(")||s.uppercase(Locale.US).startsWith("OR(")) {
            val and=s.uppercase(Locale.US).startsWith("AND("); val a=splitArgs(s.substring(s.indexOf('(')+1,s.length-1))
            return (if(and) a.all{condition(it)} else a.any{condition(it)}).asNum()
        }
        val expanded=Regex("(?i)\\b([A-Z]+\\d+)\\b").replace(s) { numeric(it.value)?.toString() ?: "0" }
        return ArithmeticParser(expanded).parse()
    }
    private fun numeric(address:String):Double? {
        val raw=cells[address.uppercase(Locale.US)] ?: return null
        if(raw.startsWith("=")) return try { eval(raw.drop(1)) } catch(_:Exception) { null }
        return raw.toDoubleOrNull()
    }
    private fun range(spec:String):List<String> {
        val parts=spec.trim().split(":"); if(parts.size!=2) return listOf(spec.trim().uppercase(Locale.US))
        val a=splitAddress(parts[0]); val b=splitAddress(parts[1]); val out=mutableListOf<String>()
        for(r in minOf(a.second,b.second)..maxOf(a.second,b.second)) for(c in minOf(a.first,b.first)..maxOf(a.first,b.first)) out.add("${col(c)}$r")
        return out
    }
    private fun splitAddress(s:String):Pair<Int,Int> {
        val m=Regex("([A-Z]+)(\\d+)").matchEntire(s.trim().uppercase(Locale.US)) ?: error("Bad cell")
        var c=0; m.groupValues[1].forEach { c=c*26+(it-'A'+1) }; return c to m.groupValues[2].toInt()
    }
    private fun col(n0:Int):String { var n=n0; var out=""; while(n>0){val r=(n-1)%26;out=('A'.code+r).toChar()+out;n=(n-1)/26};return out }
    private fun splitArgs(s:String):List<String> {
        val out=mutableListOf<String>(); val cur=StringBuilder(); var depth=0; var quote=false
        s.forEach { ch ->
            if(ch=='"') quote=!quote
            if(!quote) { if(ch=='(') depth++; if(ch==')') depth-- }
            if(ch==','&&depth==0&&!quote) { out.add(cur.toString().trim());cur.setLength(0) } else cur.append(ch)
        }
        out.add(cur.toString().trim()); return out
    }
    private fun condition(s:String):Boolean {
        val m=Regex("(.+?)(>=|<=|<>|=|>|<)(.+)").matchEntire(s.trim())
        if(m!=null) {
            val a=atom(m.groupValues[1]); val b=atom(m.groupValues[3])
            return when(m.groupValues[2]) { "="->a==b; "<>"->a!=b; ">"->a>b; "<"->a<b; ">="->a>=b; else->a<=b }
        }
        return try { eval(s)!=0.0 } catch(_:Exception) { s.trim().equals("TRUE",true) }
    }
    private fun atom(s:String):Double {
        val t=s.trim().trim('"')
        return t.toDoubleOrNull() ?: numeric(t) ?: t.hashCode().toDouble()
    }
    private fun matches(value:String,criterion:String):Boolean {
        val c=criterion.trim().trim('"')
        val m=Regex("^(>=|<=|<>|>|<|=)(.*)$").matchEntire(c)
        if(m!=null) {
            val a=value.toDoubleOrNull()?:0.0; val b=m.groupValues[2].toDoubleOrNull()?:0.0
            return when(m.groupValues[1]) { ">"->a>b; "<"->a<b; ">="->a>=b; "<="->a<=b; "<>"->a!=b; else->a==b }
        }
        return value.equals(c,true) || value.toDoubleOrNull()==c.toDoubleOrNull()
    }
    private fun Boolean.asNum()=if(this) 1.0 else 0.0
}

private class ArithmeticParser(private val source:String) {
    private var pos=0
    fun parse():Double { val v=expression(); skip(); if(pos<source.length) error("Unexpected input"); return v }
    private fun expression():Double { var x=term(); while(true) { skip(); if(eat('+')) x+=term() else if(eat('-')) x-=term() else return x } }
    private fun term():Double { var x=factor(); while(true) { skip(); if(eat('*')) x*=factor() else if(eat('/')) x/=factor() else return x } }
    private fun factor():Double {
        skip(); if(eat('+')) return factor(); if(eat('-')) return -factor()
        if(eat('(')) { val v=expression(); if(!eat(')')) error("Missing )"); return v }
        val start=pos; while(pos<source.length&&(source[pos].isDigit()||source[pos]=='.')) pos++
        if(start==pos) error("Number expected")
        return source.substring(start,pos).toDouble()
    }
    private fun skip(){while(pos<source.length&&source[pos].isWhitespace())pos++}
    private fun eat(c:Char):Boolean{skip();if(pos<source.length&&source[pos]==c){pos++;return true};return false}
}

