package com.meraj.niceexcel

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.*
import android.widget.FrameLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.max
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : Activity() {
    private var rowCount = 50
    private val colLimit = 26
    private val sheets = linkedMapOf<String, MutableMap<String,String>>()
    private val styles = linkedMapOf<String, MutableMap<String, CellStyle>>()
    // OOXML merged ranges, keyed by worksheet name (e.g. A1:B3).
    private val mergedRangesBySheet = linkedMapOf<String, MutableList<String>>()
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
    // Cache each cell view's rendered background signature. Sheet switching often reuses
    // the same 1,300 TextViews; do not allocate a new GradientDrawable when its appearance
    // (fill, border, selection state) has not actually changed.
    private val appliedCellBackgrounds = java.util.WeakHashMap<TextView, String>()
    private val mergedOriginalTextSizes = java.util.WeakHashMap<TextView, Float>()
    // Formula results are expensive (POI + fallback evaluator); reuse them across style-only renders.
    private val formulaDisplayCache = mutableMapOf<String, String>()
    // CPU-heavy data operations run off the UI thread; Android views are updated only on main.
    // Bounded four-worker pool: independent CPU tasks may run in parallel without
    // allowing an unlimited backlog. Never use CallerRunsPolicy because it could
    // accidentally execute expensive work on Android's UI thread.
    private val backgroundExecutor = ThreadPoolExecutor(
        4, 4,
        30L, TimeUnit.SECONDS,
        ArrayBlockingQueue<Runnable>(64),
        { runnable ->
            Thread(runnable, "NiceExcel-Worker").apply { isDaemon = true }
        },
        ThreadPoolExecutor.AbortPolicy()
    )
    private val mainHandler = Handler(Looper.getMainLooper())
    private val filterJobId = AtomicInteger(0)
    private val sortJobId = AtomicInteger(0)
    private val formulaRecalcJobId = AtomicInteger(0)
    // Incremented whenever the UI rebuilds after a workbook mutation; workers must
    // validate this token before committing snapshots computed from older state.
    private val workbookRevision = AtomicInteger(0)
    // Cached pixel-independent column widths from the last full layout.
    private var lastColumnWidths: List<Int> = List(26) { 39 }
    // Widths are cached per sheet so switching sheets does not rescan every workbook cell.
    private val columnWidthsBySheet = mutableMapOf<String, List<Int>>()
    // Build row line counts in one pass and reuse them during grid layout. Null means invalidated.
    private var cachedExplicitLineCountsByRow: Map<Int, Int>? = null
    private val cellAddressRowPattern = Regex("^[A-Z]+(\\d+)$")
    private var lastTapAddress: String? = null
    private var lastTapAt = 0L
    private var editingAddress: String? = null
    // If a user taps another cell while an inline editor is still losing focus,
    // queue that target and start editing it after the previous editor commits.
    private var pendingEditAddress: String? = null
    private var keepEditingOnNextCellTap = false
    // Commits an active inline edit when the IME is dismissed or Android Back is pressed.
    private var activeInlineCommit: (() -> Unit)? = null
    private var activeInlineCommitForCellChange: (() -> Unit)? = null
    // Monotonic edit-session token prevents delayed IME/focus callbacks from committing
    // a newer cell after the user has already moved on.
    private var inlineEditGeneration = 0L
    private var keyboardWasVisible = false
    private var backInvokedCallback: OnBackInvokedCallback? = null
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
        val defaultWidths = List(colLimit) { c -> max(28, minOf(360, colName(c).length * 7 + 18)) }
        columnWidthsBySheet["Sheet1"] = defaultWidths
        columnWidthsBySheet["Sheet2"] = defaultWidths
        columnWidthsBySheet["Sheet3"] = defaultWidths
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
            setPadding(dp(6), dp(5), dp(6), dp(5))
            setBackgroundColor(Color.rgb(17, 94, 57))
        }
        listOf("File", "Home", "Insert", "Format", "Data", "Formula", "View").forEach { label ->
            val button = tool(label) { showTopMenu(label) }.apply {
                textSize = 13f
                minHeight = dp(34)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = roundedToolbarBackground(
                    if (label == activeTopSection) Color.rgb(209, 250, 229) else Color.rgb(17, 94, 57),
                    if (label == activeTopSection) Color.rgb(209, 250, 229) else Color.rgb(17, 94, 57),
                    10
                )
                setTextColor(if (label == activeTopSection) Color.rgb(6, 78, 59) else Color.WHITE)
                if (label == activeTopSection) typeface = Typeface.DEFAULT_BOLD
                else typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
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
                            val tabColor = if (active) Color.rgb(209, 250, 229) else Color.rgb(17, 94, 57)
                            tab.background = roundedToolbarBackground(tabColor, tabColor, 10)
                            tab.setTextColor(if (active) Color.rgb(6, 78, 59) else Color.WHITE)
                            tab.typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.create("sans-serif-medium", Typeface.NORMAL)
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
            setBackgroundColor(Color.rgb(232, 240, 235))
            clipToPadding = false
            overScrollMode = android.view.View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        ribbonTools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), dp(4), dp(7), dp(4))
            setBackgroundColor(Color.rgb(232, 240, 235))
        }
        toolScroll.addView(ribbonTools, android.widget.FrameLayout.LayoutParams(-2, dp(42)))
        root.addView(toolScroll, LinearLayout.LayoutParams(-1, dp(44)))
        toolScroll.visibility = android.view.View.GONE

        val formulaBar=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(dp(6),dp(4),dp(6),dp(4)); setBackgroundColor(Color.rgb(255,255,255)) }
        cellName=TextView(this).apply { text=selected; textSize=14f; typeface=Typeface.DEFAULT_BOLD; gravity=Gravity.CENTER; setTextColor(Color.rgb(17,94,57)); setPadding(dp(10),dp(11),dp(10),dp(11)); setBackgroundColor(Color.rgb(225,242,232)) }
        formulaBar.addView(cellName)
        formula=EditText(this).apply { setSingleLine(true); textSize=14f; hint="Enter value or formula"; inputType=InputType.TYPE_CLASS_TEXT; setPadding(dp(8),dp(3),dp(8),dp(3)); setBackgroundColor(Color.WHITE); setText(data[selected].orEmpty()); setOnEditorActionListener { _,_,_ -> applyFormula(); true } }
        formulaBar.addView(formula,LinearLayout.LayoutParams(0,-2,1f))
        formulaBar.addView(TextView(this).apply { text="ƒx ▾"; textSize=12f; setTextColor(Color.rgb(17,94,57)); setPadding(dp(7),dp(10),dp(7),dp(10)); setOnClickListener { formulaPickerMenu() } })
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
        grid=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
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

        val sheetTabsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = true
            isFillViewport = false
            overScrollMode = android.view.View.OVER_SCROLL_IF_CONTENT_SCROLLS
            setBackgroundColor(Color.rgb(231,237,233))
        }
        sheetTabs=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(6),dp(5),dp(6),dp(5))
            setBackgroundColor(Color.rgb(231,237,233))
            layoutParams = FrameLayout.LayoutParams(-2, -2)
        }
        sheetTabsScroll.addView(sheetTabs)
        root.addView(sheetTabsScroll, LinearLayout.LayoutParams(-1, -2))
        status=TextView(this).apply { text="Ready • Offline workbook"; textSize=12f; setTextColor(Color.WHITE); setPadding(dp(10),dp(7),dp(10),dp(7)); setBackgroundColor(Color.rgb(20,30,40)) }
        root.addView(status)
        setContentView(root)
        // Save inline edits when the keyboard is dismissed (including Android navigation Back).
        // Capture the current edit-session token: a delayed callback from an old editor must
        // never commit a newer editor opened while the IME was animating closed/open.
        root.viewTreeObserver.addOnGlobalLayoutListener {
            val visibleNow = isSoftKeyboardVisible()
            if (keyboardWasVisible && !visibleNow) {
                val closingGeneration = inlineEditGeneration
                mainHandler.postDelayed({
                    if (!isFinishing && !isDestroyed && !isSoftKeyboardVisible() &&
                        closingGeneration == inlineEditGeneration) {
                        activeInlineCommit?.invoke()
                    }
                }, 220L)
            }
            keyboardWasVisible = visibleNow
        }
        // Android 13+ uses the predictive/system back dispatcher. Handle it explicitly so
        // Back commits/closes an active cell editor rather than leaving a stale editor alive.
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            backInvokedCallback = OnBackInvokedCallback {
                if (editingAddress != null) {
                    activeInlineCommit?.invoke()
                } else {
                    finish()
                }
            }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                backInvokedCallback!!
            )
        }
        render()
    }

    /**
     * Shared visual styling for native dialogs. Only presentation changes here:
     * titles, surfaces, input fields and action buttons; dialog actions remain untouched.
     */
    private fun AlertDialog.Builder.showProfessional(): AlertDialog {
        val dialog = create()
        dialog.setOnShowListener {
            val deepGreen = Color.rgb(21, 93, 66)
            val paleGreen = Color.rgb(227, 244, 234)
            val ink = Color.rgb(31, 45, 38)
            dialog.window?.setBackgroundDrawable(
                GradientDrawable().apply {
                    setColor(Color.rgb(250, 252, 250))
                    cornerRadius = dp(18).toFloat()
                    setStroke(dp(1), Color.rgb(210, 225, 216))
                }
            )
            dialog.window?.decorView?.setPadding(dp(2), dp(2), dp(2), dp(2))

            val titleView = dialog.findViewById<TextView>(androidxResourceId("alertTitle"))
                ?: dialog.findViewById(android.R.id.title)
            titleView?.apply {
                setTextColor(deepGreen)
                textSize = 19f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(dp(22), dp(18), dp(22), dp(8))
            }
            val messageView = dialog.findViewById<TextView>(android.R.id.message)
            messageView?.apply {
                setTextColor(ink)
                textSize = 14f
                setLineSpacing(dp(2).toFloat(), 1.05f)
            }

            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            val neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            listOf(positive, negative, neutral).forEach { button ->
                button?.apply {
                    textSize = 13f
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    isAllCaps = false
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    if (this === positive) {
                        setTextColor(Color.WHITE)
                        background = GradientDrawable().apply {
                            setColor(deepGreen)
                            cornerRadius = dp(10).toFloat()
                        }
                    } else {
                        setTextColor(deepGreen)
                        background = GradientDrawable().apply {
                            setColor(paleGreen)
                            cornerRadius = dp(10).toFloat()
                            setStroke(dp(1), Color.rgb(190, 220, 202))
                        }
                    }
                }
            }
            styleDialogInputs(dialog.window?.decorView, ink, deepGreen)
            styleDialogList(dialog.window?.decorView, ink, deepGreen, paleGreen)
        }
        dialog.show()
        return dialog
    }

    private fun androidxResourceId(name: String): Int =
        resources.getIdentifier(name, "id", "android")

    private fun styleDialogInputs(view: android.view.View?, textColor: Int, accent: Int) {
        when (view) {
            is EditText -> view.apply {
                setTextColor(textColor)
                setHintTextColor(Color.rgb(126, 143, 133))
                textSize = 14f
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = dp(9).toFloat()
                    setStroke(dp(1), Color.rgb(198, 216, 204))
                }
            }
            is android.view.ViewGroup -> {
                for (i in 0 until view.childCount) styleDialogInputs(view.getChildAt(i), textColor, accent)
            }
        }
    }

    private fun styleDialogList(view: android.view.View?, textColor: Int, accent: Int, selectedFill: Int) {
        when (view) {
            is ListView -> view.apply {
                divider = ColorDrawable(Color.rgb(226, 235, 229))
                dividerHeight = dp(1)
                selector = GradientDrawable().apply {
                    setColor(selectedFill)
                    cornerRadius = dp(6).toFloat()
                }
                setPadding(dp(6), dp(4), dp(6), dp(4))
            }
            is android.view.ViewGroup -> {
                for (i in 0 until view.childCount) styleDialogList(view.getChildAt(i), textColor, accent, selectedFill)
            }
        }
    }

    private fun roundedToolbarBackground(fillColor: Int, strokeColor: Int, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fillColor)
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), strokeColor)
        }
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
                "Add row at bottom" to { addRows(1) },
                "Add 5 rows at bottom" to { addRows(5) },
                "Add 10 rows at bottom" to { addRows(10) },
                "Duplicate selected row to bottom" to { duplicateSelectedRowToBottom() },
                "Clear selected row…" to { clearSelectedRowConfirm() },
                "Merge Top to Bottom" to { mergeCellsDialog(vertical = true) },
                "Merge Left to Right" to { mergeCellsDialog(vertical = false) },
                "Unmerge selected range…" to { unmergeCellsDialog() },
                "AI Auto Fill…" to { autoFillDialog() },
                "Table tools…" to { tableMenu() }
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
                "Clear filter" to { filterColumn = null; filterText = null; refreshFilterVisibility() },
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
            "Find / Replace…" to "Find", "Table tools…" to "Table",
            "Merge Top to Bottom" to "Merge ↓", "Merge Left to Right" to "Merge →",
            "Unmerge selected range…" to "Unmerge",
            "Format cells…" to "Cells", "Range formatting…" to "Range", "Font size…" to "Size", "Text alignment…" to "Align",
            "Number format…" to "Number", "Selected row: Single / Double line…" to "↵ Wrap", "Table styles…" to "Styles",
            "Sort column A → Z" to "Sort A→Z", "Filter column…" to "Filter", "Clear filter" to "Clear filter", "Find / Replace…" to "Find",
            "Workbook data range info" to "Range info", "Insert function…" to "ƒx Insert", "Formula help" to "ƒx Help",
            "Web Engine" to "Web Excel", "Workbook info" to "Info"
        )
        items.forEach { item ->
            val label = compactLabels[item.first] ?: item.first
            val isEmphasis = item.first == "AI Auto Fill…"
            val button = TextView(this).apply {
                text = label
                contentDescription = item.first
                textSize = 10.5f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                maxLines = 1
                setTextColor(if (isEmphasis) Color.rgb(25, 74, 132) else Color.rgb(35, 70, 50))
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = roundedToolbarBackground(
                    if (isEmphasis) Color.rgb(219, 234, 254) else Color.WHITE,
                    if (isEmphasis) Color.rgb(147, 197, 253) else Color.rgb(204, 218, 209),
                    9
                )
                elevation = dp(1).toFloat()
                isClickable = true
                isFocusable = true
                setOnClickListener { item.second.invoke() }
            }
            ribbonTools.addView(button, LinearLayout.LayoutParams(-2, dp(32)).apply {
                marginEnd = dp(6)
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
            }.showProfessional()
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
                    .setPositiveButton("OK", null).showProfessional()
                return
            }
            if (startRow < 1 || startRow + rows.size - 1 > 10000) {
                status.text = "Pasted data exceeds the 10,000-row safety limit"
                return
            }

            // Keep one undo snapshot before making any changes.
            formulaDisplayCache.clear()
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
            .showProfessional()
    }

    private fun newWorkbook() {
        AlertDialog.Builder(this)
            .setTitle("New workbook")
            .setMessage("Create a blank workbook? Current unsaved changes will be cleared.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create") { _, _ ->
                sheets.clear(); styles.clear(); formulaDisplayCache.clear(); columnWidthsBySheet.clear()
                sheetName = "Sheet1"; selected = "A1"; rowCount = 50
                sheets["Sheet1"] = mutableMapOf(); styles["Sheet1"] = mutableMapOf()
                sheets["Sheet2"] = mutableMapOf(); styles["Sheet2"] = mutableMapOf()
                sheets["Sheet3"] = mutableMapOf(); styles["Sheet3"] = mutableMapOf()
                val defaultWidths = List(colLimit) { c -> max(28, minOf(360, colName(c).length * 7 + 18)) }
                columnWidthsBySheet["Sheet1"] = defaultWidths
                columnWidthsBySheet["Sheet2"] = defaultWidths
                columnWidthsBySheet["Sheet3"] = defaultWidths
                selectedRangeCells = emptyList(); selectedRangeLabel = null
                render(); cellName.text = selected; formula.setText("")
                status.text = "New blank workbook"
            }.showProfessional()
    }

    private fun tool(label:String, action:()->Unit)=TextView(this).apply {
        text=label; textSize=14f; gravity=Gravity.CENTER; setTextColor(Color.WHITE); setPadding(dp(9),dp(9),dp(9),dp(9)); setOnClickListener { action() }
    }
    private fun ribbonButton(label:String, action:()->Unit)=TextView(this).apply {
        text=label; textSize=12f; gravity=Gravity.CENTER; setTextColor(Color.rgb(34,75,54)); setBackgroundColor(Color.rgb(236,246,239)); setOnClickListener { action() }
    }

    /** Update only the affected cell views after formatting; avoid touching the whole 1,300-cell grid. */
    private fun refreshStyledCells(addresses: Collection<String>) {
        var needsFullLayout = false
        addresses.forEach { address ->
            val view = cellViews[address] ?: return@forEach
            val text = gridDisplay(address)
            val match = Regex("^([A-Z]+)(\\d+)$").matchEntire(address)
            val col = match?.groupValues?.get(1)?.let { columnIndex(it) } ?: -1
            if (col in lastColumnWidths.indices && text.length * 7 + 18 > lastColumnWidths[col]) {
                needsFullLayout = true
            }
            view.text = text
            val st = sheetStyles[address]
            view.setTextColor(st?.textColor ?: Color.rgb(24, 32, 39))
            view.textSize = st?.fontSize ?: 13f
            view.typeface = Typeface.create(
                Typeface.DEFAULT,
                (if (st?.bold == true) Typeface.BOLD else Typeface.NORMAL) or
                    (if (st?.italic == true) Typeface.ITALIC else Typeface.NORMAL)
            )
            view.paint.isUnderlineText = st?.underline == true
            view.gravity = st?.alignment ?: (Gravity.CENTER_VERTICAL or Gravity.START)
            applyCellBackground(view, address, false)
        }
        selectedCellView = cellViews[selected]
        if (needsFullLayout) render()
    }

    /** Compute filter matches on a worker thread, then apply only if its input is still current. */
    private fun refreshFilterVisibility() {
        val jobId = filterJobId.incrementAndGet()
        val capturedSheet = sheetName
        val capturedRevision = workbookRevision.get()
        val col = filterColumn
        val query = filterText
        val rowLimit = rowCount
        // Worker receives immutable values only; it never reads workbook maps or Android Views.
        val columnValues = if (col != null && query != null) {
            (2..rowLimit).associateWith { row -> data["${col}$row"].orEmpty() }
        } else emptyMap()
        status.text = if (query.isNullOrEmpty()) "Clearing filter…" else "Applying filter…"
        try {
            backgroundExecutor.execute {
                try {
                    val visibleRows = if (col == null || query == null) {
                        (2..rowLimit).toSet()
                    } else {
                        columnValues.asSequence()
                            .filter { (_, value) -> value.contains(query, ignoreCase = true) }
                            .map { it.key }
                            .toSet()
                    }
                    mainHandler.post {
                        // Drop stale results after a sheet switch, newer filter, row-count change,
                        // or edits to the filtered column. Never apply yesterday's answer to new data.
                        if (isFinishing || isDestroyed || jobId != filterJobId.get() ||
                            sheetName != capturedSheet || rowCount != rowLimit ||
                            workbookRevision.get() != capturedRevision ||
                            filterColumn != col || filterText != query) return@post
                        if (col != null && query != null && columnValues.any { (row, value) ->
                                data["${col}$row"].orEmpty() != value
                            }) return@post
                        for (i in 0 until grid.childCount) {
                            val rowView = grid.getChildAt(i) as? LinearLayout ?: continue
                            val row = rowView.tag as? Int ?: (i + 2)
                            val wanted = if (row in visibleRows) android.view.View.VISIBLE else android.view.View.GONE
                            if (rowView.visibility != wanted) rowView.visibility = wanted
                        }
                        frozenRowHorizontal.visibility = android.view.View.VISIBLE
                        grid.requestLayout()
                        status.text = if (query.isNullOrEmpty()) "Filter cleared" else "Filter: $col contains '$query'"
                    }
                } catch (error: Exception) {
                    mainHandler.post {
                        if (!isFinishing && !isDestroyed && jobId == filterJobId.get() && sheetName == capturedSheet) {
                            status.text = "Filter failed safely: ${error.message ?: "please retry"}"
                        }
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            if (!isFinishing && !isDestroyed) status.text = "Filter cancelled; please retry"
        }
    }

    /**
     * Fast post-edit refresh for a single column. Full-sheet refresh scans every stored cell
     * and rewrites all visible layouts; doing that on every edit can make newly typed text
     * appear late on slower phones. Keep auto-width behavior, but scope the work to the edited
     * column and update formulas separately on the background executor.
     */
    private fun refreshEditedCellColumn(address: String) {
        val match = Regex("^([A-Z]+)(\\d+)$").matchEntire(address) ?: return
        val columnName = match.groupValues[1]
        val column = columnIndex(columnName)
        if (column !in 0 until colLimit) return

        var longest = columnName.length
        val addresses = ArrayList<Pair<String, Int>>()
        data.keys.forEach { candidate ->
            val m = Regex("^([A-Z]+)(\\d+)$").matchEntire(candidate) ?: return@forEach
            if (columnIndex(m.groupValues[1]) != column) return@forEach
            val row = m.groupValues[2].toIntOrNull() ?: return@forEach
            addresses.add(candidate to row)
            if (row !in wrappedRows) longest = max(longest, gridDisplay(candidate).length)
        }
        val widthPx = dp(max(28, minOf(360, longest * 7 + 18)))
        if (columnHeader.childCount > column + 1) {
            val header = columnHeader.getChildAt(column + 1)
            header.layoutParams = (header.layoutParams ?: LinearLayout.LayoutParams(widthPx, dp(24))).apply { width = widthPx }
        }
        if (frozenFirstRow.childCount > column + 1) {
            val frozen = frozenFirstRow.getChildAt(column + 1)
            frozen.layoutParams = (frozen.layoutParams ?: LinearLayout.LayoutParams(widthPx, dp(24))).apply { width = widthPx }
        }
        addresses.forEach { (cellAddress, row) ->
            val view = cellViews[cellAddress] ?: return@forEach
            view.layoutParams = (view.layoutParams ?: LinearLayout.LayoutParams(widthPx, dp(rowHeightPx(row)))).apply { width = widthPx }
            view.text = gridDisplay(cellAddress)
            view.maxLines = rowLineCount(row)
            view.ellipsize = if (view.maxLines == 2) null else android.text.TextUtils.TruncateAt.END
            applyCellBackground(view, cellAddress, false)
        }
        lastColumnWidths = lastColumnWidths.toMutableList().also { if (column < it.size) it[column] = max(28, minOf(360, longest * 7 + 18)) }
        columnWidthsBySheet[sheetName] = lastColumnWidths
        applyMergedCellPresentation()
    }

    /** Refresh existing cells in place, including widths, instead of rebuilding 1,300 TextViews. */
    private fun refreshVisibleCells() {
        val perfStarted = SystemClock.elapsedRealtime()
        val longestByColumn = IntArray(colLimit) { c -> colName(c).length }
        data.keys.forEach { address ->
            val match = cellAddressRowPattern.matchEntire(address) ?: return@forEach
            val column = columnIndex(match.groupValues[1])
            val row = match.groupValues[2].toIntOrNull() ?: return@forEach
            if (column in 0 until colLimit && row !in wrappedRows) {
                longestByColumn[column] = max(longestByColumn[column], gridDisplay(address).length)
            }
        }
        val columnWidths = longestByColumn.map { max(28, minOf(360, it * 7 + 18)) }
        lastColumnWidths = columnWidths
        columnWidthsBySheet[sheetName] = columnWidths

        for (c in 0 until colLimit) {
            val width = dp(columnWidths[c])
            if (columnHeader.childCount > c + 1) {
                val headerCell = columnHeader.getChildAt(c + 1)
                headerCell.layoutParams = (headerCell.layoutParams ?: LinearLayout.LayoutParams(width, dp(24))).apply { this.width = width }
            }
            if (frozenFirstRow.childCount > c + 1) {
                val frozenCell = frozenFirstRow.getChildAt(c + 1)
                frozenCell.layoutParams = (frozenCell.layoutParams ?: LinearLayout.LayoutParams(width, dp(24))).apply {
                    this.width = width
                    height = dp(rowHeightPx(1))
                }
            }
        }
        for (i in 0 until grid.childCount) {
            val rowView = grid.getChildAt(i) as? LinearLayout ?: continue
            val row = rowView.tag as? Int ?: (i + 2)
            val rowHeight = rowHeightPx(row)
            rowView.visibility = if (rowPassesFilter(row)) android.view.View.VISIBLE else android.view.View.GONE
            rowView.layoutParams = (rowView.layoutParams ?: LinearLayout.LayoutParams(-2, dp(rowHeight))).apply { height = dp(rowHeight) }
            rowView.minimumHeight = dp(rowHeight)
            for (c in 0 until minOf(colLimit + 1, rowView.childCount)) {
                val child = rowView.getChildAt(c)
                val width = if (c == 0) dp(48) else dp(columnWidths[c - 1])
                child.layoutParams = (child.layoutParams ?: LinearLayout.LayoutParams(width, dp(rowHeight))).apply {
                    this.width = width
                    height = dp(rowHeight)
                }
            }
        }
        cellViews.forEach { (address, view) ->
            view.text = gridDisplay(address)
            val st = sheetStyles[address]
            view.setTextColor(st?.textColor ?: Color.rgb(24, 32, 39))
            view.textSize = st?.fontSize ?: 13f
            view.typeface = Typeface.create(
                Typeface.DEFAULT,
                (if (st?.bold == true) Typeface.BOLD else Typeface.NORMAL) or
                    (if (st?.italic == true) Typeface.ITALIC else Typeface.NORMAL)
            )
            view.paint.isUnderlineText = st?.underline == true
            view.gravity = st?.alignment ?: (Gravity.CENTER_VERTICAL or Gravity.START)
            val row = Regex("^[A-Z]+(\\d+)$").matchEntire(address)?.groupValues?.get(1)?.toIntOrNull()
            view.maxLines = if (row != null) rowLineCount(row) else 1
            view.ellipsize = if (view.maxLines == 2) null else android.text.TextUtils.TruncateAt.END
            applyCellBackground(view, address, false)
        }
        selectedCellView = cellViews[selected]
        applyMergedCellPresentation()
        Log.d("NiceExcelPerf", "refreshVisibleCells sheet=$sheetName cells=${cellViews.size} ms=${SystemClock.elapsedRealtime() - perfStarted}")
    }

    /**
     * Fast sheet switch path: reuse existing grid and cached widths instead of rescanning
     * every workbook address and rewriting every layout parameter. All View mutations stay
     * on the Android main thread. Returns false when a structural render is required.
     */
    private fun refreshSheetInPlaceFast(): Boolean {
        if (cellViews.size != rowCount * colLimit ||
            columnHeader.childCount != colLimit + 1 ||
            frozenFirstRow.childCount != colLimit + 1 ||
            grid.childCount != (rowCount - 1).coerceAtLeast(0)) return false

        val widths = columnWidthsBySheet[sheetName] ?: return false
        if (widths.size != colLimit) return false
        val started = SystemClock.elapsedRealtime()
        workbookRevision.incrementAndGet()
        val previousWidths = lastColumnWidths
        val widthsChanged = previousWidths != widths
        lastColumnWidths = widths

        // Update column widths only when the target sheet's cached widths differ.
        for (c in 0 until colLimit) {
            val width = dp(widths[c])
            if (columnHeader.childCount > c + 1) {
                val v = columnHeader.getChildAt(c + 1)
                if (v.layoutParams.width != width) {
                    v.layoutParams = v.layoutParams.apply { this.width = width }
                }
            }
            if (frozenFirstRow.childCount > c + 1) {
                val v = frozenFirstRow.getChildAt(c + 1)
                val h = dp(rowHeightPx(1))
                if (v.layoutParams.width != width || v.layoutParams.height != h) {
                    v.layoutParams = v.layoutParams.apply { this.width = width; height = h }
                }
            }
        }

        // For a newly created blank sheet, avoid formula evaluation and expensive style lookups.
        val blankSheet = data.isEmpty() && sheetStyles.isEmpty() && wrappedRows.isEmpty()
        val cellRefreshStarted = SystemClock.elapsedRealtime()
        val addressPattern = if (blankSheet) null else Regex("^[A-Z]+(\\d+)$")
        for ((address, view) in cellViews) {
            // Blank-sheet creation is the hottest path: avoid parsing 1,300 cell
            // addresses and touching every TextView property when most are already default.
            if (blankSheet) {
                if (view.text.isNotEmpty()) view.text = ""
                if (view.currentTextColor != Color.rgb(24, 32, 39)) view.setTextColor(Color.rgb(24, 32, 39))
                if (view.textSize != 13f) view.textSize = 13f
                if (view.typeface?.style != Typeface.NORMAL) view.typeface = Typeface.DEFAULT
                if (view.paint.isUnderlineText) view.paint.isUnderlineText = false
                val defaultGravity = Gravity.CENTER_VERTICAL or Gravity.START
                if (view.gravity != defaultGravity) view.gravity = defaultGravity
                if (view.maxLines != 1) view.maxLines = 1
                if (view.ellipsize != android.text.TextUtils.TruncateAt.END) view.ellipsize = android.text.TextUtils.TruncateAt.END
                applyCellBackground(view, address, false)
                continue
            }
            val match = addressPattern!!.matchEntire(address)
            val row = match?.groupValues?.get(1)?.toIntOrNull() ?: continue
            val text = gridDisplay(address)
            if (view.text.toString() != text) view.text = text
            val st = sheetStyles[address]
            val textColor = st?.textColor ?: Color.rgb(24, 32, 39)
            if (view.currentTextColor != textColor) view.setTextColor(textColor)
            val textSize = st?.fontSize ?: 13f
            if (view.textSize != textSize) view.textSize = textSize
            val style = (if (st?.bold == true) Typeface.BOLD else Typeface.NORMAL) or
                (if (st?.italic == true) Typeface.ITALIC else Typeface.NORMAL)
            if (view.typeface?.style != style) view.typeface = Typeface.create(Typeface.DEFAULT, style)
            if (view.paint.isUnderlineText != (st?.underline == true)) view.paint.isUnderlineText = st?.underline == true
            val gravity = st?.alignment ?: (Gravity.CENTER_VERTICAL or Gravity.START)
            if (view.gravity != gravity) view.gravity = gravity
            val maxLines = rowLineCount(row)
            if (view.maxLines != maxLines) view.maxLines = maxLines
            val desiredEllipsize = if (maxLines == 2) null else android.text.TextUtils.TruncateAt.END
            if (view.ellipsize != desiredEllipsize) view.ellipsize = desiredEllipsize
            applyCellBackground(view, address, false)
        }

        val filterActive = filterColumn != null && filterText != null
        for (i in 0 until grid.childCount) {
            val rowView = grid.getChildAt(i) as? LinearLayout ?: continue
            val row = rowView.tag as? Int ?: (i + 2)
            val height = dp(rowHeightPx(row))
            val rowHeightChanged = rowView.layoutParams.height != height
            if (rowHeightChanged) rowView.layoutParams = rowView.layoutParams.apply { this.height = height }
            if (rowView.minimumHeight != height) rowView.minimumHeight = height
            if (filterActive) {
                val visibility = if (rowPassesFilter(row)) android.view.View.VISIBLE else android.view.View.GONE
                if (rowView.visibility != visibility) rowView.visibility = visibility
            } else if (rowView.visibility != android.view.View.VISIBLE) {
                rowView.visibility = android.view.View.VISIBLE
            }
            // Width checks are only needed when switching between sheets with different cached widths.
            if (widthsChanged || wrappedRows.isNotEmpty() || rowHeightChanged) {
                for (c in 0 until minOf(colLimit + 1, rowView.childCount)) {
                    val child = rowView.getChildAt(c)
                    val width = dp(if (c == 0) 48 else widths[c - 1])
                    if (child.layoutParams.width != width || child.layoutParams.height != height) {
                        child.layoutParams = child.layoutParams.apply { this.width = width; this.height = height }
                    }
                }
            }
        }
        val cellRefreshMs = SystemClock.elapsedRealtime() - cellRefreshStarted
        frozenRowHorizontal.visibility = android.view.View.VISIBLE
        selectedCellView = cellViews[selected]
        val tabsStarted = SystemClock.elapsedRealtime()
        renderTabs()
        val tabsMs = SystemClock.elapsedRealtime() - tabsStarted
        grid.requestLayout()
        Log.d("NiceExcelPerf", "sheetSwitchFast sheet=$sheetName blank=$blankSheet cellRefreshMs=$cellRefreshMs tabsMs=$tabsMs totalMs=${SystemClock.elapsedRealtime() - started}")
        return true
    }

    private fun render() {
        cachedExplicitLineCountsByRow = null
        val perfStarted = SystemClock.elapsedRealtime()
        // Treat a full/in-place render as a workbook-state boundary. Any background
        // snapshot started before this boundary must revalidate before committing.
        workbookRevision.incrementAndGet()
        val canReuseGrid = cellViews.size == rowCount * colLimit &&
            columnHeader.childCount == colLimit + 1 &&
            frozenFirstRow.childCount == colLimit + 1 &&
            grid.childCount == (rowCount - 1).coerceAtLeast(0)
        if (canReuseGrid) {
            refreshVisibleCells()
            renderTabs()
            scheduleFormulaRecalculation()
            Log.d("NiceExcelPerf", "render sheet=$sheetName mode=reuse ms=${SystemClock.elapsedRealtime() - perfStarted}")
            return
        }
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
        val cellAddressPattern = Regex("^([A-Z]+)(\\d+)$")
        data.keys.forEach { address ->
            val match = cellAddressPattern.matchEntire(address) ?: return@forEach
            val column = columnIndex(match.groupValues[1])
            val row = match.groupValues[2].toIntOrNull() ?: return@forEach
            if (column in 0 until colLimit && row !in wrappedRows) {
                val shownLength = gridDisplay(address).length
                if (shownLength > longestByColumn[column]) longestByColumn[column] = shownLength
            }
        }
        val columnWidths = longestByColumn.map { longest ->
            max(28, minOf(360, longest * 7 + 18))
        }
        lastColumnWidths = columnWidths
        columnWidthsBySheet[sheetName] = columnWidths
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
            val rowView = createRowView(r, columnWidths)
            rowView.visibility = if (rowPassesFilter(r)) android.view.View.VISIBLE else android.view.View.GONE
            grid.addView(rowView)
        }
        // Bottom Add Row bar removed: row insertion remains available in the Insert ribbon.
        applyMergedCellPresentation()
        renderTabs()
        scheduleFormulaRecalculation()
        Log.d("NiceExcelPerf", "render sheet=$sheetName mode=rebuild cells=${cellViews.size} ms=${SystemClock.elapsedRealtime() - perfStarted}")
    }

    private fun rowPassesFilter(row: Int): Boolean {
        if (filterColumn == null || filterText == null || row <= 1) return true
        return data["${filterColumn}$row"].orEmpty().contains(filterText!!, ignoreCase = true)
    }

    private fun createRowView(row: Int, columnWidths: List<Int>): LinearLayout {
        val rowHeight = rowHeightPx(row)
        // Give the row itself an explicit height matching every child cell. Otherwise
        // Android can measure a wrapped TextView taller than its siblings and expose
        // an inset-looking second rectangle around only the multi-line cells.
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            // Do not baseline-align cells in a row. A two-line TextView has a different
            // baseline from its one-line siblings; baseline alignment vertically offsets
            // its background and creates the apparent extra/inset rectangle.
            setBaselineAligned(false)
            clipChildren = false
            clipToPadding = false
            gravity = Gravity.TOP
            layoutParams = LinearLayout.LayoutParams(-2, dp(rowHeight))
            minimumHeight = dp(rowHeight)
            tag = row
        }
        val rowHeader = makeCell(row.toString(), 48, true, null, rowHeight)
        rowHeader.setOnClickListener { selectRow(row) }
        line.addView(rowHeader)
        for (c in 0 until colLimit) {
            val address = "${colName(c)}$row"
            line.addView(makeCell(gridDisplay(address), columnWidths[c], false, address, rowHeight))
        }
        return line
    }

    private fun refreshAfterCellEdit(address: String, value: String): Boolean {
        // Keep the existing grid and keyboard responsive for ordinary edits. Only rebuild
        // the 1,300-view grid when a value needs more width than the current column provides.
        val editedText = gridDisplay(address)
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
            val formulaText = gridDisplay(formulaAddress)
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
            .map { gridDisplay(it.key).length }
            .maxOrNull() ?: name.length
        // Three-character default; auto-expand by one character-space per extra character.
        return max(28, minOf(360, longest * 7 + 18))
    }

    /** Row height follows explicit newlines in cell values, while preserving the existing
     * two-line row mode. Cap at six visible lines to keep the grid usable on mobile. */
    /**
     * Counts explicit newlines by row with one workbook scan, then serves repeated layout
     * queries in O(1). The cache is invalidated by render() and putCell().
     */
    private fun rowLineCount(row: Int): Int {
        var lineCounts = cachedExplicitLineCountsByRow
        if (lineCounts == null) {
            val counts = HashMap<Int, Int>()
            data.forEach { (address, value) ->
                val match = cellAddressRowPattern.matchEntire(address) ?: return@forEach
                val cellRow = match.groupValues[1].toIntOrNull() ?: return@forEach
                val lines = value.count { ch -> ch == '\n' } + 1
                if (lines > (counts[cellRow] ?: 1)) counts[cellRow] = lines
            }
            lineCounts = counts
            cachedExplicitLineCountsByRow = lineCounts
        }
        val explicitLines = lineCounts[row] ?: 1
        return max(if (row in wrappedRows) 2 else 1, explicitLines).coerceIn(1, 6)
    }

    private fun rowHeightPx(row: Int): Int = if (rowLineCount(row) == 1) 24 else 24 * rowLineCount(row) + 4

    /**
     * Display value used only inside the grid. In double-line mode, split at the first
     * whitespace so a row explicitly set to wrap actually displays two lines even when
     * another cell in the same column has caused that column to auto-expand.
     */
    private fun gridDisplay(address: String): String {
        val raw = displayForGrid(address)
        val row = Regex("^[A-Z]+(\\d+)$").matchEntire(address)
            ?.groupValues?.get(1)?.toIntOrNull()
        if (row == null || row !in wrappedRows || raw.contains('\n')) return raw
        val splitAt = raw.indexOfFirst { it.isWhitespace() }
        return if (splitAt > 0 && splitAt < raw.lastIndex) {
            raw.substring(0, splitAt) + "\n" + raw.substring(splitAt + 1).trimStart()
        } else raw
    }

    private fun makeCell(value:String,width:Int,header:Boolean,key:String?,cellHeight:Int = 24):TextView {
        val tv=TextView(this).apply {
            text=value; textSize=13f; gravity=if(header||key==null) Gravity.CENTER else Gravity.CENTER_VERTICAL or Gravity.START
            setSingleLine(false)
            val cellRow = key?.let { Regex("^[A-Z]+(\\d+)$").matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
            maxLines = if (cellRow != null) rowLineCount(cellRow) else 1
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
                    val keyboardAlreadyOpen = isSoftKeyboardVisible() || keepEditingOnNextCellTap
                    keepEditingOnNextCellTap = false
                    val previousEditorAddress = editingAddress

                    // Route taps during an active edit through one hand-off path.
                    // Do not enqueue a separate startInlineEdit callback for every tap:
                    // rapid taps can race and leave stale editors or IME requests alive.
                    if (previousEditorAddress != null) {
                        if (previousEditorAddress != key) {
                            pendingEditAddress = key
                            lastTapAddress = null
                            lastTapAt = 0L
                            select(key)
                            activeInlineCommitForCellChange?.invoke()
                        }
                        return@setOnClickListener
                    }

                    select(key)
                    if (keyboardAlreadyOpen || isDoubleTap) {
                        lastTapAddress = null
                        lastTapAt = 0L
                        // Open only the most recently tapped cell. A previously queued
                        // hand-off checks editingAddress before opening its target.
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
        sheetTabs.addView(tool("+") { sheetOptionsMenu() }.apply { setTextColor(Color.rgb(17,94,57)) })
    }

    private fun addOneRow() = addRows(1)

    /** Add rows at the end in one operation, rendering at most once for a batch. */
    private fun addRows(count: Int) {
        if (count <= 0) return
        val started = SystemClock.elapsedRealtime()
        val oldCount = rowCount
        rowCount += count
        // Batch updates use one render so Add 5/10 does not repeatedly rebuild the UI.
        // If filtering is active, the full render keeps row visibility consistent.
        val fast = filterColumn == null || filterText == null
        if (fast) {
            for (row in (oldCount + 1)..rowCount) {
                // The add-row footer was removed; append after the last real grid row.
                grid.addView(createRowView(row, lastColumnWidths), grid.childCount)
            }
        } else {
            render()
        }
        status.text = if (count == 1) "Added row $rowCount" else "Added $count rows ($oldCount → $rowCount)"
        Log.d("NiceExcelPerf", "addRows count=$count totalMs=${SystemClock.elapsedRealtime() - started} fastPath=$fast")
        verticalScroll.post { verticalScroll.smoothScrollTo(0, grid.height) }
    }

    private fun duplicateSelectedRowToBottom() {
        val match = Regex("^[A-Z]+(\\d+)$").matchEntire(selected)
        val sourceRow = match?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (sourceRow == null || sourceRow !in 1..rowCount) {
            status.text = "Select a valid cell first"
            return
        }
        val targetRow = rowCount + 1
        val copiedValues = mutableMapOf<String, String>()
        val copiedStyles = mutableMapOf<String, CellStyle>()
        for (column in 0 until colLimit) {
            val col = colName(column)
            val sourceAddress = "$col$sourceRow"
            val targetAddress = "$col$targetRow"
            data[sourceAddress]?.let { copiedValues[targetAddress] = it }
            sheetStyles[sourceAddress]?.let { copiedStyles[targetAddress] = it.copy() }
        }
        // Prepare row data first, then rebuild once so copied values/styles appear together.
        rowCount = targetRow
        data.putAll(copiedValues)
        sheetStyles.putAll(copiedStyles)
        formulaDisplayCache.clear()
        selected = "A$targetRow"
        cellName.text = selected
        formula.setText(data[selected].orEmpty())
        render()
        status.text = "Duplicated row $sourceRow to row $targetRow"
    }

    private fun clearSelectedRowConfirm() {
        val row = Regex("^[A-Z]+(\\d+)$").matchEntire(selected)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (row == null || row !in 1..rowCount) {
            status.text = "Select a valid cell first"
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Clear row $row?")
            .setMessage("This clears values and formatting in row $row. This action cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ ->
                for (column in 0 until colLimit) {
                    val address = "${colName(column)}$row"
                    data.remove(address)
                    sheetStyles.remove(address)
                }
                wrappedRows.remove(row)
                formulaDisplayCache.clear()
                render()
                formula.setText(data[selected].orEmpty())
                status.text = "Cleared row $row"
            }
            .showProfessional()
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
        val signature = "$fill|$strokeColor|$strokeWidth"
        if (appliedCellBackgrounds[view] == signature) return
        view.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(fill)
            setStroke(if (strokeWidth == 1) 1 else dp(strokeWidth), strokeColor)
        }
        appliedCellBackgrounds[view] = signature
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
            // Ordinary text uses a multiline editor: keyboard Enter inserts a newline.
            // Formula input keeps a Done action so Enter commits/evaluates the formula.
            setSingleLine(false)
            textSize = cell.textSize / resources.displayMetrics.scaledDensity
            setText(data[address].orEmpty())
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setHorizontallyScrolling(false)
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(3), 0, dp(3), 0)
            setBackgroundColor(Color.rgb(207, 239, 220))
            imeOptions = (if (text.toString().trimStart().startsWith("=")) android.view.inputmethod.EditorInfo.IME_ACTION_DONE else android.view.inputmethod.EditorInfo.IME_ACTION_NONE) or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            minHeight = dp(18)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val action = if (s?.toString()?.trimStart()?.startsWith("=") == true) android.view.inputmethod.EditorInfo.IME_ACTION_DONE else android.view.inputmethod.EditorInfo.IME_ACTION_NONE
                    val nextOptions = action or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
                    if (imeOptions != nextOptions) imeOptions = nextOptions
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        editor.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // Let the editing cell grow as the user adds more lines so the caret
                // remains visible instead of being clipped by the original row height.
                val lineCount = editor.lineCount.coerceIn(1, 6)
                val wantedHeight = dp(if (lineCount == 1) 24 else 24 * lineCount + 4)
                val current = editor.layoutParams
                if (current != null && current.height != wantedHeight) {
                    current.height = wantedHeight
                    editor.layoutParams = current
                }
                val rowView = parent
                if (rowView != null && rowView.layoutParams.height != wantedHeight) {
                    rowView.layoutParams = rowView.layoutParams.apply { height = wantedHeight }
                    for (i in 0 until rowView.childCount) {
                        val child = rowView.getChildAt(i)
                        if (child !== editor && child.layoutParams.height != wantedHeight) {
                            child.layoutParams = child.layoutParams.apply { height = wantedHeight }
                        }
                    }
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        editingAddress = address
        val editGeneration = ++inlineEditGeneration
        parent.removeViewAt(index)
        parent.addView(editor, index, params)
        editor.requestFocus()
        editor.post {
            // Rapid cell switching can finish/remove this editor before the post runs.
            // Do not reopen the IME for a stale editor, even if a cell address is reused.
            if (editGeneration == inlineEditGeneration && editingAddress == address &&
                editor.parent === parent && editor.isAttachedToWindow && !isFinishing && !isDestroyed) {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        var finished = false
        fun finish(save: Boolean, keepKeyboard: Boolean = false) {
            if (finished || editGeneration != inlineEditGeneration) return
            finished = true
            // Invalidate any queued callbacks before changing focus or touching the IME.
            inlineEditGeneration++
            val newValue = editor.text.toString()
            if (save) {
                putCell(address, newValue)
                val row = Regex("^[A-Z]+(\\d+)$").matchEntire(address)?.groupValues?.get(1)?.toIntOrNull()
                if (row != null && newValue.contains('\n')) wrappedRows.add(row)
            }
            editingAddress = null
            activeInlineCommit = null
            activeInlineCommitForCellChange = null
            keepEditingOnNextCellTap = keepKeyboard
            if (!keepKeyboard) {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(editor.windowToken, 0)
            }

            // Restore the original TextView before trying the fast in-place refresh.
            // Previously the editor replaced this view, but the fast path updated the
            // detached TextView and skipped render(), leaving the cell stuck in edit mode.
            val editorIndex = parent.indexOfChild(editor)
            if (editorIndex >= 0) {
                parent.removeViewAt(editorIndex)
                parent.addView(cell, editorIndex, params)
            }
            // If the user tapped another cell while the keyboard is open, prioritize
            // handing focus to that cell. Full width scanning and formula refresh can be
            // comparatively expensive, so do them only after the next editor has opened.
            val nextAddress = pendingEditAddress
            pendingEditAddress = null
            val handoffToNextCell = keepKeyboard && nextAddress != null && nextAddress != address

            if (save) {
                if (handoffToNextCell) {
                    // Update the previous cell's visible value immediately without scanning
                    // every populated cell before the next cursor appears.
                    cell.text = gridDisplay(address)
                } else {
                    refreshEditedCellColumn(address)
                    scheduleFormulaRecalculation(setOf("$sheetName!$address"))
                }
            } else {
                cell.text = gridDisplay(address)
            }
            select(address)
            status.text = if (save) "Updated $sheetName!$address" else "Edit cancelled"

            if (handoffToNextCell) {
                mainHandler.post {
                    val target = cellViews[nextAddress!!]
                    if (target != null && editingAddress == null) {
                        select(nextAddress)
                        startInlineEdit(target)
                        // Preserve auto-width and formula recalculation, but do them after
                        // the destination editor has been created and requested focus.
                        mainHandler.post {
                                        refreshEditedCellColumn(address)
                            scheduleFormulaRecalculation(setOf("$sheetName!$address"))
                        }
                    } else {
                        // If the target view disappeared, refresh only the edited column.
                        refreshEditedCellColumn(address)
                        scheduleFormulaRecalculation(setOf("$sheetName!$address"))
                    }
                }
            }
        }
        // This callback is also used if the keyboard is hidden with the system Back button.
        activeInlineCommit = { finish(true) }
        activeInlineCommitForCellChange = { finish(true, keepKeyboard = true) }
        editor.setOnEditorActionListener { _, actionId, event ->
            val enterPressed =
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO ||
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT ||
                (event != null && event.keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                    event.action == android.view.KeyEvent.ACTION_UP)
            if (enterPressed && editor.text.toString().trimStart().startsWith("=")) {
                // Enter on a formula commits/evaluates it; for ordinary text, Enter inserts a newline.
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
        editor.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && editGeneration == inlineEditGeneration) {
                // Focus changes can be emitted during cell hand-off. The session guard ensures
                // this callback cannot finish an editor that has already been replaced.
                finish(true, keepKeyboard = true)
            }
        }
    }
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (editingAddress != null) {
            activeInlineCommit?.invoke()
            return
        }
        super.onBackPressed()
    }

    private fun isSoftKeyboardVisible(): Boolean {
        // getWindowVisibleDisplayFrame can be unreliable with edge-to-edge layouts on
        // recent Android versions. Prefer the IME inset API where available.
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val insets = window.decorView.rootWindowInsets
            if (insets != null && insets.isVisible(android.view.WindowInsets.Type.ime())) return true
        }
        val visible = android.graphics.Rect()
        window.decorView.getWindowVisibleDisplayFrame(visible)
        val totalHeight = window.decorView.rootView.height
        return totalHeight - visible.bottom > dp(120)
    }

    /**
     * Finds cell references used by a formula. Range references are expanded so edits
     * anywhere inside a referenced range invalidate the dependent formula.
     */
    private fun formulaDependencies(
        formulaSheet: String,
        formula: String,
        workbook: Map<String, Map<String, String>>
    ): Set<String> {
        val expression = formula.replace(Regex("\"(?:\"\"|[^\"])*\""), " ")
        val pattern = Regex(
            "(?i)(?:'([^']+)'|([A-Z0-9_]+))!\\$?([A-Z]{1,3})\\$?(\\d+)(?::\\$?([A-Z]{1,3})\\$?(\\d+))?" +
                "|(?<![A-Z0-9_!])\\$?([A-Z]{1,3})\\$?(\\d+)(?::\\$?([A-Z]{1,3})\\$?(\\d+))?"
        )
        val result = linkedSetOf<String>()
        pattern.findAll(expression).forEach { match ->
            val after = expression.substring(match.range.last + 1).trimStart()
            if (after.startsWith("(")) return@forEach // Ignore function names such as LOG10(
            val explicitSheet = match.groupValues[1].ifBlank { match.groupValues[2] }
            val requestedSheet = explicitSheet.ifBlank { formulaSheet }
            val targetSheet = workbook.keys.firstOrNull { it.equals(requestedSheet, ignoreCase = true) } ?: return@forEach
            val startColText = match.groupValues[3].ifBlank { match.groupValues[7] }
            val startRowText = match.groupValues[4].ifBlank { match.groupValues[8] }
            val endColText = match.groupValues[5].ifBlank { match.groupValues[9] }
            val endRowText = match.groupValues[6].ifBlank { match.groupValues[10] }
            val startCol = columnIndex(startColText)
            val startRow = startRowText.toIntOrNull() ?: return@forEach
            val endCol = if (endColText.isBlank()) startCol else columnIndex(endColText)
            val endRow = if (endRowText.isBlank()) startRow else endRowText.toIntOrNull() ?: startRow
            val minCol = minOf(startCol, endCol)
            val maxCol = maxOf(startCol, endCol)
            val minRow = minOf(startRow, endRow)
            val maxRow = maxOf(startRow, endRow)
            if (minRow < 1 || maxRow - minRow > 10000 || maxCol - minCol > 100) return@forEach
            for (row in minRow..maxRow) {
                for (col in minCol..maxCol) result.add("$targetSheet!${colName(col)}$row")
            }
        }
        return result
    }

    /**
     * Recalculate formulas off the UI thread. A null changedCells set is used for
     * initial workbook/sheet calculation. After an edit, only that cell and its
     * transitive formula dependents are recalculated; unrelated formulas are skipped.
     */
    private fun scheduleFormulaRecalculation(changedCells: Set<String>? = null) {
        val jobId = formulaRecalcJobId.incrementAndGet()
        val revision = workbookRevision.get()
        val activeSheet = sheetName
        val allSheetsSnapshot = sheets.mapValues { (_, cells) -> cells.toMap() }
        val stylesSnapshot = styles.mapValues { (_, map) -> map.toMap() }
        val allFormulas = allSheetsSnapshot.flatMap { (name, cells) ->
            cells.filterValues { it.trimStart().startsWith("=") }.map { (address, raw) -> Triple(name, address, raw) }
        }
        if (allFormulas.isEmpty()) return
        val formulas = if (changedCells == null) {
            allFormulas
        } else {
            val dependencies = allFormulas.associate { (name, address, raw) ->
                "$name!$address" to formulaDependencies(name, raw.drop(1), allSheetsSnapshot)
            }
            val affected = changedCells.mapTo(linkedSetOf()) { key ->
                val split = key.lastIndexOf('!')
                if (split >= 0) "${key.substring(0, split)}!${key.substring(split + 1).uppercase(Locale.US)}"
                else "$activeSheet!${key.uppercase(Locale.US)}"
            }
            var expanded: Boolean
            do {
                expanded = false
                dependencies.forEach { (formulaKey, refs) ->
                    if (formulaKey !in affected && refs.any { it in affected }) {
                        affected.add(formulaKey)
                        expanded = true
                    }
                }
            } while (expanded)
            allFormulas.filter { (name, address, _) -> "$name!$address" in affected }
        }
        if (formulas.isEmpty()) return
        // Invalidate only results that will actually be recalculated. Cached values for
        // unrelated formulas remain visible and are not needlessly refreshed.
        formulas.forEach { (formulaSheet, address, _) ->
            val prefix = "$formulaSheet|$address|"
            formulaDisplayCache.keys.removeAll { it.startsWith(prefix) }
        }
        try {
            backgroundExecutor.execute {
                val results = linkedMapOf<String, Pair<String, String>>()
                formulas.forEach { (formulaSheet, address, raw) ->
                    if (jobId != formulaRecalcJobId.get() || isFinishing || isDestroyed) return@forEach
                    val st = stylesSnapshot[formulaSheet]?.get(address)
                    val cacheKey = "$formulaSheet|$address|$raw|${st?.numberFormat ?: "General"}"
                    val rendered = runCatching {
                        val expression = raw.drop(1)
                        val formulaCells = allSheetsSnapshot[formulaSheet].orEmpty()
                        val localEngine = FormulaEngine(formulaCells)
                        val preserveReferencedValue = shouldUseLocalReferenceResult(expression, formulaCells)
                        val localSpecial = if (preserveReferencedValue || Regex("(?i)^(XOR|VLOOKUP|MATCH|NA|INDEX)\\s*\\(").containsMatchIn(expression))
                            localEngine.evalText(expression) else null
                        if (localSpecial != null) localSpecial else {
                        val imported = PoiFormulaBridge.evaluate(allSheetsSnapshot, formulaSheet, address, expression)
                        when {
                            imported?.error == null && imported?.text != null -> imported.text
                            imported?.error == null && imported?.number != null -> formatNumber(imported.number, st?.numberFormat ?: "General", imported.number.toString())
                            else -> {
                                val engine = FormulaEngine(allSheetsSnapshot[formulaSheet].orEmpty())
                                val textResult = engine.evalText(raw.drop(1))
                                textResult ?: engine.eval(raw.drop(1)).let { value ->
                                    if (value.isNaN() || value.isInfinite()) "#NUM!" else formatNumber(value, st?.numberFormat ?: "General", value.toString())
                                }
                            }
                        } ?: imported?.error ?: "#ERROR"
                        }
                    }.getOrElse { error -> error.message?.takeIf { it.startsWith("#") } ?: "#ERROR" }
                    results["$formulaSheet!$address"] = cacheKey to rendered
                }
                mainHandler.post {
                    if (isFinishing || isDestroyed || jobId != formulaRecalcJobId.get() || revision != workbookRevision.get()) return@post
                    results.forEach { (sheetAddress, result) ->
                        val splitAt = sheetAddress.lastIndexOf('!')
                        if (splitAt <= 0) return@forEach
                        val formulaSheet = sheetAddress.substring(0, splitAt)
                        val address = sheetAddress.substring(splitAt + 1)
                        formulaDisplayCache[result.first] = result.second
                        if (formulaSheet == sheetName) cellViews[address]?.text = result.second
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            // Never block typing if the bounded worker queue is full; a later edit can retry.
        }
    }

    private fun putCell(address:String,value:String) {
        cachedExplicitLineCountsByRow = null
        workbookRevision.incrementAndGet()
        // Do not clear the whole formula cache here. The edit handler immediately
        // schedules dependency-based recalculation, which invalidates only the
        // edited formula and its transitive dependents. Keeping other entries avoids
        // flashing unrelated formula cells to the pending placeholder (…)
        // and avoids needless memory/CPU work on large workbooks.
        undoHistory.getOrPut(sheetName) { mutableListOf() }.add(data.toMap())
        redoHistory.getOrPut(sheetName) { mutableListOf() }.clear()
        if (value.isEmpty()) data.remove(address) else data[address]=value
    }

    /** Fill a destination range by detecting a numeric/text series or copying formulas with relative references. */
    private fun autoFillDialog() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        val source = EditText(this).apply {
            hint = "Pattern/source range, e.g. A1:A2"
            setSingleLine(true)
            setText(selectedRangeLabel ?: selected)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        val destination = EditText(this).apply {
            hint = "Fill range, e.g. A1:A20"
            setSingleLine(true)
            setText(selectedRangeLabel ?: selected)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        panel.addView(TextView(this).apply { text = "Source range contains the pattern or formula. Fill range is the complete area to fill; source cells are kept unchanged." })
        panel.addView(source)
        panel.addView(destination)
        AlertDialog.Builder(this)
            .setTitle("AI Auto Fill")
            .setView(panel)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Fill") { _, _ ->
                val sourceLabel = source.text.toString().trim().uppercase(Locale.ROOT)
                val targetLabel = destination.text.toString().trim().uppercase(Locale.ROOT)
                val sourceCells = parseCellRange(sourceLabel)
                val targetCells = parseCellRange(targetLabel)
                if (sourceCells.isEmpty() || targetCells.isEmpty()) {
                    Toast.makeText(this, "Enter valid ranges within the current sheet", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val sourceParts = sourceLabel.split(":")
                if (sourceParts.size != 2 && sourceCells.size > 1) {
                    Toast.makeText(this, "Use a source range such as A1:A2 or A1:B1", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val sourceSet = sourceCells.toSet()
                if (!sourceSet.all { it in targetCells }) {
                    Toast.makeText(this, "Fill range must include the source range", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val sourceValues = sourceCells.map { data[it].orEmpty() }
                if (sourceValues.isEmpty() || sourceValues.all { it.isBlank() }) {
                    Toast.makeText(this, "Source range has no values or formulas", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val srcFirst = cellPosition(sourceCells.first())
                val srcLast = cellPosition(sourceCells.last())
                val targetPositions = targetCells.mapNotNull { address -> cellPosition(address)?.let { it to address } }
                val vertical = srcFirst != null && srcLast != null && srcFirst.second == srcLast.second && srcFirst.first <= srcLast.first
                val horizontal = srcFirst != null && srcLast != null && srcFirst.first == srcLast.first && srcFirst.second <= srcLast.second
                if (sourceCells.size > 1 && !vertical && !horizontal) {
                    Toast.makeText(this, "Source pattern must be one row or one column", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val orderedTargets = if (vertical) targetPositions.sortedBy { it.first.first } else if (horizontal) targetPositions.sortedBy { it.first.second } else targetPositions.sortedWith(compareBy({ it.first.first }, { it.first.second }))
                val seedNumeric = sourceValues.map { it.toDoubleOrNull() }
                val numericSeries = sourceValues.size >= 2 && seedNumeric.all { it != null } && (vertical || horizontal)
                val numericStep = if (numericSeries) seedNumeric.last()!! - seedNumeric[seedNumeric.lastIndex - 1]!! else 0.0
                val trailingNumber = Regex("^(.*?)(-?\\d+(?:\\.\\d+)?)$")
                val textMatches = sourceValues.map { trailingNumber.matchEntire(it) }
                val textSeries = sourceValues.size >= 2 && textMatches.all { it != null } && (vertical || horizontal) &&
                    textMatches.map { it!!.groupValues[1] }.distinct().size == 1
                val textStep = if (textSeries) (textMatches.last()!!.groupValues[2].toDoubleOrNull() ?: 0.0) - (textMatches[textMatches.lastIndex - 1]!!.groupValues[2].toDoubleOrNull() ?: 0.0) else 0.0
                var filled = 0
                val lastSeedAddress = sourceCells.last()
                val lastSeedPos = cellPosition(lastSeedAddress)
                val lastSeedValue = sourceValues.last()
                undoHistory.getOrPut(sheetName) { mutableListOf() }.add(data.toMap())
                redoHistory.getOrPut(sheetName) { mutableListOf() }.clear()
                orderedTargets.forEachIndexed { index, pair ->
                    val (pos, address) = pair
                    if (address in sourceSet) return@forEachIndexed
                    val value = when {
                        lastSeedValue.trimStart().startsWith("=") && lastSeedPos != null -> translateFormula(lastSeedValue, lastSeedPos, pos)
                        numericSeries && lastSeedPos != null -> {
                            val distance = if (vertical) pos.first - lastSeedPos.first else pos.second - lastSeedPos.second
                            if (distance <= 0) data[address].orEmpty() else formatAutoFillNumber((seedNumeric.last()!! + numericStep * distance))
                        }
                        textSeries && lastSeedPos != null -> {
                            val distance = if (vertical) pos.first - lastSeedPos.first else pos.second - lastSeedPos.second
                            if (distance <= 0) data[address].orEmpty() else textMatches.last()!!.groupValues[1] + formatAutoFillNumber((textMatches.last()!!.groupValues[2].toDoubleOrNull() ?: 0.0) + textStep * distance)
                        }
                        else -> {
                            // Repeat a single value, or repeat the seed pattern when no trend can be inferred.
                            val seedIndex = if (vertical) (pos.first - srcFirst!!.first).coerceAtLeast(0) % sourceValues.size
                                else if (horizontal) (pos.second - srcFirst!!.second).coerceAtLeast(0) % sourceValues.size
                                else index % sourceValues.size
                            val seedAddress = sourceCells[seedIndex]
                            val seedPos = cellPosition(seedAddress)
                            val valueAtSeed = sourceValues[seedIndex]
                            if (valueAtSeed.trimStart().startsWith("=") && seedPos != null) translateFormula(valueAtSeed, seedPos, pos) else valueAtSeed
                        }
                    }
                    if (value.isNotEmpty()) { data[address] = value; filled++ }
                }
                workbookRevision.incrementAndGet()
                formulaDisplayCache.clear()
                render()
                scheduleFormulaRecalculation()
                status.text = "AI Auto Fill completed: $filled cell(s) filled in $targetLabel"
            }.showProfessional()
    }

    private fun cellPosition(address: String): Pair<Int, Int>? {
        val match = Regex("^([A-Z]+)([0-9]+)$").matchEntire(address.uppercase(Locale.ROOT)) ?: return null
        val row = match.groupValues[2].toIntOrNull()?.minus(1) ?: return null
        val col = columnIndex(match.groupValues[1])
        return if (row >= 0 && col in 0 until colLimit) row to col else null
    }

    private fun translateFormula(formulaText: String, from: Pair<Int, Int>, to: Pair<Int, Int>): String {
        val rowDelta = to.first - from.first
        val colDelta = to.second - from.second
        val reference = Regex("(?<![A-Za-z0-9_.])(?:(\\$?)([A-Za-z]{1,3})(\\$?)([0-9]+))")
        return reference.replace(formulaText) { match ->
            val colAbsolute = match.groupValues[1] == "\$"
            val colName = match.groupValues[2].uppercase(Locale.ROOT)
            val rowAbsolute = match.groupValues[3] == "\$"
            val row = match.groupValues[4].toIntOrNull() ?: return@replace match.value
            val newCol = if (colAbsolute) columnIndex(colName) else columnIndex(colName) + colDelta
            val newRow = if (rowAbsolute) row - 1 else row - 1 + rowDelta
            if (newCol !in 0 until colLimit || newRow < 0) match.value
            else (if (colAbsolute) "\$" else "") + colNameFromIndex(newCol) + (if (rowAbsolute) "\$" else "") + (newRow + 1)
        }
    }

    private fun colNameFromIndex(index: Int): String {
        var n = index + 1
        val out = StringBuilder()
        while (n > 0) { val rem = (n - 1) % 26; out.append(('A'.code + rem).toChar()); n = (n - 1) / 26 }
        return out.reverse().toString()
    }

    private fun formatAutoFillNumber(value: Double): String =
        if (value.isFinite() && value == kotlin.math.floor(value)) value.toLong().toString() else value.toString()

    private fun formulaPickerMenu() {
        val formulas = arrayOf("SUM", "AVERAGE", "COUNT", "COUNTA", "MAX", "MIN", "MEDIAN", "PRODUCT", "IF", "IFERROR", "IFNA", "AND", "OR", "ROUND", "ROUNDUP", "ROUNDDOWN", "ABS", "SQRT", "POWER", "SUMIF", "SUMIFS", "COUNTIF", "COUNTIFS", "AVERAGEIF", "AVERAGEIFS", "MINIFS", "MAXIFS", "CONCAT", "CONCATENATE", "XLOOKUP", "VLOOKUP", "HLOOKUP", "LOOKUP", "INDEX", "MATCH", "CHOOSE", "OFFSET", "INDIRECT", "LEFT", "RIGHT", "MID", "LEN", "TRIM", "UPPER", "LOWER", "PROPER", "TODAY", "NOW", "DATE", "YEAR", "MONTH", "DAY")
        AlertDialog.Builder(this).setTitle("Choose formula")
            .setItems(formulas) { _, which ->
                formula.setText("=" + formulas[which] + "(")
                formula.setSelection(formula.text.length)
                formula.requestFocus()
                status.text = "Formula inserted: ${formulas[which]}"
            }.showProfessional()
    }
    private fun applyFormula() {
        val editedAddress = selected
        putCell(editedAddress, formula.text.toString())
        // Formula-bar edits use the same dependency-only path as inline cell edits.
        // Avoid render(), because a full render schedules a workbook-wide calculation.
        refreshEditedCellColumn(editedAddress)
        scheduleFormulaRecalculation(setOf("$sheetName!$editedAddress"))
        status.text = "Updated $sheetName!$editedAddress"
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(formula.windowToken, 0)
    }
    private fun undo() {
        val stack = undoHistory.getOrPut(sheetName) { mutableListOf() }
        if (stack.isEmpty()) { status.text = "Nothing to undo"; return }
        redoHistory.getOrPut(sheetName) { mutableListOf() }.add(data.toMap())
        val previous = stack.removeAt(stack.lastIndex)
        data.clear(); data.putAll(previous)
        formulaDisplayCache.clear()
        render(); formula.setText(data[selected].orEmpty()); status.text = "Undo complete"
    }
    private fun redo() {
        val stack = redoHistory.getOrPut(sheetName) { mutableListOf() }
        if (stack.isEmpty()) { status.text = "Nothing to redo"; return }
        undoHistory.getOrPut(sheetName) { mutableListOf() }.add(data.toMap())
        val next = stack.removeAt(stack.lastIndex)
        data.clear(); data.putAll(next)
        formulaDisplayCache.clear()
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
                    if (changed > 0) formulaDisplayCache.clear()
                    render(); formula.setText(data[selected].orEmpty()); status.text="Replaced in $changed cell(s)"
                } else status.text="Enter text to find"
            }.setNeutralButton("Find next") { _,_ ->
                val q=find.text.toString()
                val match=data.entries.firstOrNull { (a,v) -> a>selected && v.contains(q,true) }
                    ?: data.entries.firstOrNull { (_,v) -> v.contains(q,true) }
                if(match!=null) { select(match.key); status.text="Found ${match.key}" } else status.text="No match found"
            }.setNegativeButton("Cancel",null).showProfessional()
    }
    private fun addSheet() {
        val started = SystemClock.elapsedRealtime()
        var n=sheets.size+1; var name="Sheet$n"
        while(sheets.containsKey(name)) { n++; name="Sheet$n" }
        // Formula cache keys include the sheet name, so adding a blank sheet must not
        // invalidate formula results cached for every existing sheet.
        sheets[name]=mutableMapOf()
        styles[name]=mutableMapOf()
        mergedRangesBySheet.remove(name)
        wrappedRowsBySheet[name]=mutableSetOf()
        columnWidthsBySheet[name] = List(colLimit) { c -> max(28, minOf(360, (colName(c).length * 7 + 18))) }
        sheetName=name; selected="A1"
        lastColumnWidths = columnWidthsBySheet[name]!!
        val fast = refreshSheetInPlaceFast()
        if (!fast) render()
        cellName.text=selected; formula.setText("")
        status.text = "Added $name"
        Log.d("NiceExcelPerf", "addSheet totalMs=${SystemClock.elapsedRealtime() - started} fastPath=$fast")
    }
    private fun sheetOptionsMenu() {
        val options = arrayOf("+ Sheet", "Rename current sheet", "Delete current sheet")
        AlertDialog.Builder(this)
            .setTitle("Sheet options • $sheetName")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> addSheet()
                    1 -> renameSheet(sheetName)
                    2 -> deleteCurrentSheet()
                }
            }
            .setNegativeButton("Cancel", null)
            .showProfessional()
    }

    private fun deleteCurrentSheet() {
        if (sheets.size <= 1) {
            status.text = "At least one sheet must remain"
            return
        }
        val deleting = sheetName
        AlertDialog.Builder(this)
            .setTitle("Delete sheet?")
            .setMessage("Delete '$deleting' and its cell data? This cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                val started = SystemClock.elapsedRealtime()
                sheets.remove(deleting)
                styles.remove(deleting)
                mergedRangesBySheet.remove(deleting)
                wrappedRowsBySheet.remove(deleting)
                columnWidthsBySheet.remove(deleting)
                // Remove only the deleted sheet's cached formula results; keep other sheets warm.
                val cachePrefix = "$deleting|"
                formulaDisplayCache.keys.removeAll { it.startsWith(cachePrefix) }
                sheetName = sheets.keys.first()
                selected = "A1"
                cellName.text = selected
                formula.setText(data[selected].orEmpty())
                val fast = refreshSheetInPlaceFast()
                if (!fast) render()
                status.text = "Deleted sheet: $deleting"
                Log.d("NiceExcelPerf", "deleteSheet totalMs=${SystemClock.elapsedRealtime() - started} fastPath=$fast")
            }
            .showProfessional()
    }

    private fun renameSheet(old:String) {
        val input=EditText(this).apply { setText(old) }
        AlertDialog.Builder(this).setTitle("Rename worksheet").setView(input)
            .setPositiveButton("Rename") { _,_ ->
                val name=input.text.toString().trim()
                if(name.isNotEmpty() && !sheets.containsKey(name)) {
                    val rebuilt=linkedMapOf<String,MutableMap<String,String>>()
                    sheets.forEach { (k,v) -> rebuilt[if(k==old) name else k]=v }
                    formulaDisplayCache.clear()
                    val oldMerges = mergedRangesBySheet.remove(old)
                    if (oldMerges != null) mergedRangesBySheet[name] = oldMerges
                    sheets.clear(); sheets.putAll(rebuilt); if(sheetName==old) sheetName=name; renderTabs()
                }
            }.setNegativeButton("Cancel",null).showProfessional()
    }
    private fun formulaHelp() {
        AlertDialog.Builder(this).setTitle("Formula examples")
            .setMessage("Arithmetic: =A1+B1, =B2*C2\nRanges: =SUM(A1:A10)\nFunctions: SUM, AVERAGE, MIN, MAX, COUNT, COUNTA, COUNTIF, COUNTBLANK, SUMIF, IF, AND, OR, PRODUCT, ABS, SQRT, INT, ROUND, ROUNDUP, ROUNDDOWN, MOD, POWER, CEILING, FLOOR")
            .setPositiveButton("OK",null).showProfessional()
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
            .showProfessional()
    }

    private fun mergeCellsDialog(vertical: Boolean) {
        val input = EditText(this).apply {
            hint = if (vertical) "Example: A1:A3" else "Example: A1:C1"
            setSingleLine(true)
            setText(selectedRangeLabel ?: selected)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
            addView(TextView(this@MainActivity).apply {
                text = if (vertical) "Enter one column range. All cells must contain the same non-empty value." else "Enter one row range. All cells must contain the same non-empty value."
                textSize = 12f
            })
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(if (vertical) "Merge Top to Bottom" else "Merge Left to Right")
            .setView(wrap)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Merge") { _, _ ->
                val range = input.text.toString().trim().uppercase(Locale.ROOT).replace("\$", "")
                val cells = parseCellRange(range)
                val parts = range.split(":")
                val endpoints = if (parts.size == 2) parts else listOf(range, range)
                val m1 = Regex("^([A-Z]+)([0-9]+)$").matchEntire(endpoints[0])
                val m2 = Regex("^([A-Z]+)([0-9]+)$").matchEntire(endpoints[1])
                if (cells.size < 2 || m1 == null || m2 == null) {
                    Toast.makeText(this, "Enter a valid range such as A1:A3 or A1:C1", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val c1 = columnIndex(m1.groupValues[1]); val c2 = columnIndex(m2.groupValues[1])
                val r1 = m1.groupValues[2].toIntOrNull() ?: 0; val r2 = m2.groupValues[2].toIntOrNull() ?: 0
                val shapeOkay = if (vertical) c1 == c2 && r1 < r2 else r1 == r2 && c1 < c2
                if (!shapeOkay) {
                    Toast.makeText(this, if (vertical) "Use a vertical range like A1:A3" else "Use a horizontal range like A1:C1", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val rawValues = cells.map { data[it].orEmpty() }
                if (rawValues.first().isBlank() || rawValues.any { it != rawValues.first() }) {
                    Toast.makeText(this, "Merge cancelled: every adjacent cell must have the same non-empty value", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val targetRange = "${endpoints[0]}:${endpoints[1]}"
                val existing = mergedRangesBySheet.getOrPut(sheetName) { mutableListOf() }
                val overlaps = existing.any { old ->
                    val oldCells = parseCellRange(old)
                    oldCells.any { it in cells }
                }
                if (overlaps) {
                    Toast.makeText(this, "Range overlaps an existing merge. Unmerge it first.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                existing.add(targetRange)
                // Keep a clean in-memory backup of identical values/styles so Unmerge can
                // restore the cells. XLSX writers may omit subordinate values for OOXML compatibility.
                cells.drop(1).forEach { address ->
                    data.remove(address)
                    sheetStyles.remove(address)
                }
                selected = endpoints[0]
                selectedRangeLabel = targetRange
                selectedRangeCells = cells
                cellName.text = selected
                formula.setText(data[selected].orEmpty())
                render()
                status.text = "Merged $targetRange"
            }.showProfessional()
    }

    private fun unmergeCellsDialog() {
        val input = EditText(this).apply {
            hint = "Example: A1:A3"
            setSingleLine(true)
            setText(selectedRangeLabel ?: selected)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        AlertDialog.Builder(this).setTitle("Unmerge range")
            .setMessage("Enter the exact merged range to unmerge.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Unmerge") { _, _ ->
                val range = input.text.toString().trim().uppercase(Locale.ROOT).replace("$", "")
                val removed = mergedRangesBySheet[sheetName]?.remove(range) == true
                if (removed) {
                    val parts = range.split(":")
                    val cells = parseCellRange(range)
                    val anchorAddress = parts.firstOrNull().orEmpty()
                    val anchorValue = data[anchorAddress].orEmpty()
                    val anchorStyle = sheetStyles[anchorAddress]?.copy()
                    // Excel stores only the anchor value in a merged range. Recreate the
                    // identical values on unmerge, matching this app's merge validation rule.
                    if (anchorValue.isNotEmpty()) cells.drop(1).forEach { address ->
                        data[address] = anchorValue
                        if (anchorStyle != null) sheetStyles[address] = anchorStyle.copy()
                    }
                    selectedRangeCells = emptyList(); selectedRangeLabel = null
                    render(); status.text = "Unmerged $range"
                } else {
                    Toast.makeText(this, "Exact merged range not found", Toast.LENGTH_LONG).show()
                }
            }.showProfessional()
    }

    private fun applyMergedCellPresentation() {
        if (cellViews.isEmpty()) return
        // Reset transforms first. Never change layout width/height: each cell lives inside
        // a fixed row/column LinearLayout, and resizing it shifts the grid geometry.
        cellViews.forEach { (address, view) ->
            mergedOriginalTextSizes.putIfAbsent(view, view.textSize)
            view.visibility = android.view.View.VISIBLE
            view.scaleX = 1f
            view.scaleY = 1f
            view.pivotX = 0f
            view.pivotY = 0f
            view.translationX = 0f
            view.translationY = 0f
            view.translationZ = 0f
            view.setTextScaleX(1f)
            val originalTextSize = mergedOriginalTextSizes[view] ?: view.textSize
            view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, originalTextSize)
        }
        for (i in 0 until grid.childCount) {
            (grid.getChildAt(i) as? LinearLayout)?.translationZ = 0f
        }
        mergedRangesBySheet[sheetName].orEmpty().forEach { range ->
            val parts = range.split(":")
            if (parts.size != 2) return@forEach
            val start = Regex("^([A-Z]+)([0-9]+)$").matchEntire(parts[0]) ?: return@forEach
            val end = Regex("^([A-Z]+)([0-9]+)$").matchEntire(parts[1]) ?: return@forEach
            val c1 = columnIndex(start.groupValues[1]); val c2 = columnIndex(end.groupValues[1])
            val r1 = start.groupValues[2].toIntOrNull() ?: return@forEach
            val r2 = end.groupValues[2].toIntOrNull() ?: return@forEach
            if (c1 !in 0 until colLimit || c2 !in 0 until colLimit || r1 !in 1..rowCount || r2 !in 1..rowCount || c1 > c2 || r1 > r2) return@forEach
            val horizontal = r1 == r2 && c2 > c1
            val vertical = c1 == c2 && r2 > r1
            if (!horizontal && !vertical) return@forEach
            val anchor = cellViews[parts[0]] ?: return@forEach
            val baseWidth = dp(lastColumnWidths.getOrElse(c1) { 39 }).coerceAtLeast(1)
            val baseHeight = dp(rowHeightPx(r1)).coerceAtLeast(1)
            val mergedWidth = if (horizontal) (c1..c2).sumOf { dp(lastColumnWidths.getOrElse(it) { 39 }) } else baseWidth
            val mergedHeight = if (vertical) (r1..r2).sumOf { dp(rowHeightPx(it)) } else baseHeight
            val scaleX = mergedWidth.toFloat() / baseWidth
            val scaleY = mergedHeight.toFloat() / baseHeight
            anchor.pivotX = 0f
            anchor.pivotY = 0f
            anchor.scaleX = scaleX
            anchor.scaleY = scaleY
            // Counter-scale the glyphs so the merged cell grows but its text remains normal-sized.
            anchor.setTextScaleX(1f / scaleX.coerceAtLeast(0.01f))
            val originalTextSize = mergedOriginalTextSizes[anchor] ?: anchor.textSize
            anchor.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, originalTextSize / scaleY.coerceAtLeast(0.01f))
            anchor.translationZ = dp(8).toFloat()
            anchor.bringToFront()
            if (horizontal) {
                for (c in c1 + 1..c2) cellViews["${colName(c)}$r1"]?.visibility = android.view.View.INVISIBLE
            } else {
                val rowView = if (r1 == 1) frozenFirstRow else
                    (0 until grid.childCount).asSequence().mapNotNull { grid.getChildAt(it) as? LinearLayout }
                        .firstOrNull { (it.tag as? Int) == r1 }
                rowView?.translationZ = dp(8).toFloat()
                for (r in r1 + 1..r2) cellViews["${colName(c1)}$r"]?.visibility = android.view.View.INVISIBLE
            }
        }
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
                        }.setNegativeButton("Cancel", null).showProfessional()
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
                            }.setNegativeButton("Cancel") { _, _ -> showRangeActions(rangeLabel, cells, pendingStyles) }.showProfessional()
                    }
                    7 -> {
                        val opts = arrayOf("General", "Number (2 decimals)", "Percentage", "Currency", "Date (text display)")
                        AlertDialog.Builder(this).setTitle("Number format for $rangeLabel").setItems(opts) { _, index ->
                            cells.forEach { pendingStyles.getValue(it).numberFormat = opts[index] }
                            continueEditing("Number format selected")
                        }.setNegativeButton("Cancel") { _, _ -> showRangeActions(rangeLabel, cells, pendingStyles) }.showProfessional()
                    }
                    8 -> { cells.forEach { val st = pendingStyles.getValue(it); st.border = !st.border }; continueEditing("Borders toggled") }
                    9 -> { cells.forEach { pendingStyles[it] = CellStyle() }; continueEditing("Clear formatting selected") }
                    10 -> {
                        // Content clearing is intentionally immediate; only format changes are staged.
                        cells.forEach { data.remove(it) }
                        formulaDisplayCache.clear()
                        if (selected in cells) formula.setText("")
                        refreshStyledCells(cells)
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
                refreshStyledCells(cells)
                status.text = "Applied all formatting changes to $rangeLabel"
                Toast.makeText(this, "Formatting applied to $rangeLabel", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .showProfessional()
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
            fun refresh(message: String) { refreshStyledCells(targets); status.text = "$message: $targetLabel" }
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
                10 -> { if (rangeCells.isNotEmpty()) { targets.forEach { data.remove(it) }; formulaDisplayCache.clear(); formula.setText(""); refresh("Contents cleared") } else { putCell(selected, ""); formula.setText(""); render(); status.text = "Cell cleared: $selected" } }
                11 -> findReplaceMenu()
            }
        }.showProfessional()
    }

    private fun chooseAlignmentForTargets(targets: List<String>, label: String) {
        val opts = arrayOf("Left", "Center", "Right")
        AlertDialog.Builder(this).setTitle("Alignment for $label").setItems(opts) { _, index ->
            val alignment = when (index) { 1 -> Gravity.CENTER; 2 -> Gravity.CENTER_VERTICAL or Gravity.END; else -> Gravity.CENTER_VERTICAL or Gravity.START }
            targets.forEach { styleFor(it).alignment = alignment }
            refreshStyledCells(targets); status.text = "Alignment applied to $label"
        }.showProfessional()
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
                    refreshStyledCells(targets)
                    status.text = "Font size applied to $label"
                }
            }
            .setNegativeButton("Cancel", null).showProfessional()
    }

    private fun chooseRangeNumberFormat(label: String, targets: List<String>) {
        val opts = arrayOf("General", "Number (2 decimals)", "Percentage", "Currency", "Date (text display)")
        AlertDialog.Builder(this).setTitle("Number format for $label").setItems(opts) { _, index ->
            targets.forEach { styleFor(it).numberFormat = opts[index] }
            refreshStyledCells(targets)
            status.text = "Number format applied to $label: ${opts[index]}"
        }.setNegativeButton("Cancel", null).showProfessional()
    }

    private fun chooseFontSize() {
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; setText(styleFor(selected).fontSize.toInt().toString()); hint = "8–32" }
        AlertDialog.Builder(this).setTitle("Font size").setView(input)
            .setPositiveButton("Apply") { _, _ -> input.text.toString().toFloatOrNull()?.let { styleFor(selected).fontSize = it.coerceIn(8f, 32f) }; refreshStyledCells(listOf(selected)) }
            .setNegativeButton("Cancel", null).showProfessional()
    }

    private fun chooseColor(title: String, apply: (Int) -> Unit) {
        val names = arrayOf("Black", "White", "Red", "Orange", "Yellow", "Green", "Blue", "Purple", "Gray", "Light blue")
        val colors = intArrayOf(Color.BLACK, Color.WHITE, Color.RED, 0xFFFF9800.toInt(), Color.YELLOW, 0xFF2E7D32.toInt(), Color.BLUE, 0xFF7B1FA2.toInt(), Color.GRAY, 0xFFBBDEFB.toInt())
        AlertDialog.Builder(this).setTitle(title).setItems(names) { _, which -> apply(colors[which]); status.text = "$title applied" }.showProfessional()
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
                    val targets = if (scope == 0) {
                        listOf(selected).also { styleFor(selected).alignment = alignment }
                    } else {
                        val col = Regex("^([A-Z]+)").find(selected)?.groupValues?.get(1) ?: "A"
                        (1..rowCount).map { "$col$it" }.also { addresses -> addresses.forEach { styleFor(it).alignment = alignment } }
                    }
                    refreshStyledCells(targets)
                    status.text = if (scope == 0) "Alignment applied to $selected" else "Alignment applied to column ${Regex("^([A-Z]+)").find(selected)?.groupValues?.get(1) ?: "A"}"
                }.showProfessional()
        }.showProfessional()
    }

    private fun chooseNumberFormat() {
        val opts = arrayOf("General", "Number (2 decimals)", "Percentage", "Currency", "Date (text display)")
        AlertDialog.Builder(this).setTitle("Number format").setItems(opts) { _, i ->
            styleFor(selected).numberFormat = opts[i]
            refreshStyledCells(listOf(selected)); status.text = "Number format set: ${opts[i]}"
        }.showProfessional()
    }

    private fun tableMenu() {
        val opts = arrayOf(
            "Format first row as heading",
            "Apply table style to selected row",
            "Apply THICK borders to used data range",
            "Remove table styling",
            "Clear first-row heading"
        )
        AlertDialog.Builder(this).setTitle("Table and borders").setItems(opts) { _, i ->
            val affected = linkedSetOf<String>()
            val row = Regex("[A-Z]+([0-9]+)").matchEntire(selected)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            when (i) {
                0 -> {
                    tableHeaderRow = row
                    val lastCol = usedRange().second
                    for (c in 0..lastCol) {
                        val a = "${colName(c)}$row"; affected.add(a); val st = styleFor(a)
                        st.bold = true; st.fillColor = 0xFF1B5E20.toInt(); st.textColor = Color.WHITE; st.border = true
                    }
                    status.text = "Row $row formatted as table heading"
                }
                1 -> {
                    val lastCol = usedRange().second
                    for (c in 0..lastCol) {
                        val a = "${colName(c)}$row"; affected.add(a); val st = styleFor(a)
                        st.fillColor = if (c % 2 == 0) 0xFFE8F5E9.toInt() else Color.WHITE; st.border = true
                    }
                }
                2 -> {
                    val (lastRow, lastCol) = usedRange()
                    for (r in 1..lastRow) for (c in 0..lastCol) {
                        val address = "${colName(c)}$r"
                        affected.add(address)
                        val st = styleFor(address)
                        st.border = true
                        st.thickBorder = true
                    }
                    status.text = "Thick borders applied to used range A1:${colName(lastCol)}$lastRow"
                }
                3 -> {
                    affected.addAll(sheetStyles.keys)
                    sheetStyles.values.forEach { st -> st.border = false; st.thickBorder = false }
                    sheetStyles.clear(); tableHeaderRow = null
                    status.text = "Table styling removed"
                }
                4 -> {
                    val headingRow = tableHeaderRow ?: row
                    val lastCol = usedRange().second
                    for (c in 0..lastCol) {
                        val address = "${colName(c)}$headingRow"
                        affected.add(address)
                        val st = sheetStyles[address] ?: continue
                        st.bold = false
                        st.fillColor = Color.WHITE
                        st.textColor = Color.rgb(24, 32, 39)
                        st.border = false
                        st.thickBorder = false
                        st.alignment = Gravity.CENTER_VERTICAL or Gravity.START
                        if (st == CellStyle()) sheetStyles.remove(address)
                    }
                    tableHeaderRow = null
                    status.text = "Heading formatting cleared for row $headingRow"
                }
            }
            refreshStyledCells(affected)
        }.showProfessional()
    }

    /** Returns the last used row and column (zero-based column), including formula cells. */
    private fun usedRange(): Pair<Int, Int> {
        var foundUsedCell = false
        var lastRow = 1
        var lastCol = 0
        val addressPattern = Regex("([A-Z]+)([0-9]+)", RegexOption.IGNORE_CASE)
        data.forEach { (address, value) ->
            if (value.isBlank()) return@forEach
            val match = addressPattern.matchEntire(address) ?: return@forEach
            foundUsedCell = true
            lastCol = max(lastCol, columnIndex(match.groupValues[1].uppercase(Locale.ROOT)))
            lastRow = max(lastRow, match.groupValues[2].toIntOrNull() ?: 1)
        }
        if (!foundUsedCell) {
            val match = addressPattern.matchEntire(selected)
            val row = match?.groupValues?.get(2)?.toIntOrNull() ?: 1
            val col = match?.groupValues?.get(1)?.uppercase(Locale.ROOT)?.let { columnIndex(it) } ?: 0
            return max(1, row) to col.coerceIn(0, colLimit - 1)
        }
        return lastRow.coerceAtLeast(1) to lastCol.coerceIn(0, colLimit - 1)
    }

    private fun columnIndex(column: String): Int {
        var result = 0
        column.forEach { result = result * 26 + (it.uppercaseChar() - 'A' + 1) }
        return (result - 1).coerceAtLeast(0)
    }

    private fun filterMenu() {
        val selectedCol = selected.takeWhile { it.isLetter() }.ifEmpty { "A" }
        val columnInput = EditText(this).apply {
            hint = "Column letter, e.g. A, B, D"
            setSingleLine(true)
            setText(filterColumn ?: selectedCol)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        val queryInput = EditText(this).apply {
            hint = "Text contains… (blank clears filter)"
            setSingleLine(true)
            setText(filterText.orEmpty())
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(4))
            addView(TextView(this@MainActivity).apply {
                text = "Filter column (use the column containing your data, e.g. D for City):"
                textSize = 13f
                setTextColor(Color.rgb(45, 65, 54))
            })
            addView(columnInput, LinearLayout.LayoutParams(-1, -2))
            addView(TextView(this@MainActivity).apply {
                text = "Show rows where the selected column contains:"
                textSize = 13f
                setTextColor(Color.rgb(45, 65, 54))
                setPadding(0, dp(10), 0, 0)
            })
            addView(queryInput, LinearLayout.LayoutParams(-1, -2))
        }
        AlertDialog.Builder(this)
            .setTitle("Filter rows")
            .setView(content)
            .setPositiveButton("Apply") { _, _ ->
                val col = columnInput.text.toString().trim().uppercase(Locale.ROOT)
                val q = queryInput.text.toString().trim()
                if (q.isEmpty()) {
                    filterColumn = null
                    filterText = null
                } else if (!Regex("^[A-Z]{1,3}$").matches(col) || columnIndex(col) !in 0 until colLimit) {
                    status.text = "Invalid column. Enter a valid column letter."
                } else {
                    filterColumn = col
                    filterText = q
                }
                refreshFilterVisibility()
            }
            .setNeutralButton("Clear filter") { _, _ ->
                filterColumn = null
                filterText = null
                refreshFilterVisibility()
            }
            .setNegativeButton("Cancel", null)
            .showProfessional()
    }

    private fun sortColumn() {
        val col = selected.takeWhile { it.isLetter() }
        val sortColumnIndex = columnIndex(col)
        val headerRow = tableHeaderRow ?: 1
        val jobId = sortJobId.incrementAndGet()
        // A sort changes row-to-value mapping; any in-flight filter snapshot must be discarded.
        filterJobId.incrementAndGet()
        val capturedSheet = sheetName
        val capturedRowCount = rowCount
        val capturedRevision = workbookRevision.get()
        // Snapshot values and styles together on main. A row containing only formatting
        // is still a real row for sort/cleanup purposes and must not leave stale styles behind.
        val allRowsSnapshot = (headerRow + 1..capturedRowCount).map { row ->
            row to (0 until colLimit).map { c -> data["${colName(c)}$row"].orEmpty() }
        }
        val styleSnapshot = allRowsSnapshot.associate { (oldRow, _) ->
            oldRow to (0 until colLimit).mapNotNull { c ->
                sheetStyles["${colName(c)}$oldRow"]?.let { c to it.copy() }
            }.toMap()
        }
        val rowSnapshot = allRowsSnapshot.filter { (row, values) ->
            values.any { it.isNotEmpty() } || styleSnapshot[row].orEmpty().isNotEmpty()
        }
        status.text = "Sorting rows in background…"
        try {
            backgroundExecutor.execute {
                try {
                    val sorted = rowSnapshot.sortedWith(
                        compareBy<Pair<Int, List<String>>> { it.second.all { value -> value.isEmpty() } }
                            .thenBy {
                                val value = it.second.getOrNull(sortColumnIndex).orEmpty()
                                value.toDoubleOrNull()?.let { number -> String.format(Locale.US, "%030.8f", number) }
                                    ?: value.lowercase(Locale.US)
                            }
                    )
                    mainHandler.post {
                        if (isFinishing || isDestroyed || jobId != sortJobId.get() ||
                            sheetName != capturedSheet || rowCount != capturedRowCount ||
                            workbookRevision.get() != capturedRevision ||
                            (tableHeaderRow ?: 1) != headerRow) {
                            if (!isFinishing && !isDestroyed && jobId == sortJobId.get() && sheetName == capturedSheet) {
                                status.text = "Sort cancelled: workbook changed. Please retry."
                            }
                            return@post
                        }

                        // Optimistic concurrency check: if any source row changed while sorting,
                        // discard the result instead of overwriting newer edits/styles.
                        val valuesStillMatch = allRowsSnapshot.all { (row, values) ->
                            values.indices.all { c -> data["${colName(c)}$row"].orEmpty() == values[c] }
                        }
                        val stylesStillMatch = styleSnapshot.all { (row, snapshot) ->
                            (0 until colLimit).all { c -> sheetStyles["${colName(c)}$row"] == snapshot[c] }
                        }
                        if (!valuesStillMatch || !stylesStillMatch) {
                            status.text = "Sort cancelled: cells changed during sorting. Please retry."
                            return@post
                        }

                        // Sorting changes row-to-value mapping. Invalidate filter jobs that may
                        // have started while this sort was being calculated, before committing.
                        filterJobId.incrementAndGet()
                        // Commit as one main-thread operation only after every stale-result guard passes.
                        // Clear the entire sortable region, not just non-empty rows. This
                        // removes stale formatting from blank rows and prevents ghost styles.
                        for (oldRow in (headerRow + 1)..capturedRowCount) {
                            for (c in 0 until colLimit) {
                                val address = "${colName(c)}$oldRow"
                                data.remove(address)
                                sheetStyles.remove(address)
                            }
                        }
                        sorted.forEachIndexed { index, pair ->
                            val newRow = headerRow + 1 + index
                            pair.second.forEachIndexed { c, value ->
                                if (value.isNotEmpty()) data["${colName(c)}$newRow"] = value
                            }
                            styleSnapshot[pair.first].orEmpty().forEach { (c, style) ->
                                sheetStyles["${colName(c)}$newRow"] = style.copy()
                            }
                        }
                        formulaDisplayCache.clear()
                        render()
                        status.text = "Sorted complete rows by $col; related row data kept together"
                    }
                } catch (error: Exception) {
                    mainHandler.post {
                        if (!isFinishing && !isDestroyed && jobId == sortJobId.get() && sheetName == capturedSheet) {
                            status.text = "Sort failed safely: ${error.message ?: "please retry"}"
                        }
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            if (!isFinishing && !isDestroyed) status.text = "Sort cancelled; please retry"
        }
    }

    override fun onDestroy() {
        // Invalidate pending UI callbacks and stop background work when this Activity is gone.
        inlineEditGeneration++
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            backInvokedCallback?.let { callback ->
                onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback)
            }
        }
        backInvokedCallback = null
        filterJobId.incrementAndGet()
        sortJobId.incrementAndGet()
        formulaRecalcJobId.incrementAndGet()
        backgroundExecutor.shutdownNow()
        super.onDestroy()
    }
    /**
     * Grid rendering must not evaluate formulas synchronously on Android's UI thread.
     * Formula values are populated by scheduleFormulaRecalculation(); until then a
     * lightweight placeholder is shown. Literal values remain immediately visible.
     */
    private fun displayForGrid(address: String): String {
        val raw = data[address] ?: return ""
        val st = sheetStyles[address]
        if (!raw.trimStart().startsWith("=")) {
            val number = raw.toDoubleOrNull()
            return if (number != null && st != null) formatNumber(number, st.numberFormat, raw) else raw
        }
        val cacheKey = "$sheetName|$address|$raw|${st?.numberFormat ?: "General"}"
        return formulaDisplayCache[cacheKey] ?: "…"
    }

    private fun display(address:String):String {
        val raw=data[address] ?: return ""
        val st = sheetStyles[address]
        if (!raw.startsWith("=")) {
            val n = raw.toDoubleOrNull()
            return if (n != null && st != null) formatNumber(n, st.numberFormat, raw) else raw
        }
        val cacheKey = "$sheetName|$address|$raw|${st?.numberFormat ?: "General"}"
        formulaDisplayCache[cacheKey]?.let { return it }
        val result = try {
            val expression = raw.drop(1)
            // Keep local text/error semantics for XOR and VLOOKUP. POI may coerce
            // Boolean/error results to numeric zero in this workbook bridge.
            val localEngine = FormulaEngine(data)
            // Only bypass POI for a direct reference when its source is a Boolean/error
            // literal or a formula whose result needs local Boolean/error preservation.
            // Numeric and ordinary text formulas must keep the established POI-first path.
            val preserveReferencedValue = shouldUseLocalReferenceResult(expression, data)
            val localSpecial = if (preserveReferencedValue || Regex("(?i)^(XOR|VLOOKUP|MATCH|NA|INDEX)\\s*\\(").containsMatchIn(expression))
                localEngine.evalText(expression) else null
            if (localSpecial != null) {
                formulaDisplayCache[cacheKey] = localSpecial
                return localSpecial
            }
            // Try Apache POI first for all other formulas to preserve existing coverage.
            val imported = PoiFormulaBridge.evaluate(sheets, sheetName, address, expression)
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
                }.getOrElse { error -> error.message?.takeIf { it.startsWith("#") } ?: imported?.error ?: "#ERROR" }
            } ?: "#ERROR"
        } catch(error:Exception) { error.message?.takeIf { it.startsWith("#") } ?: "#ERROR" }
        formulaDisplayCache[cacheKey] = result
        return result
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
            }.setNegativeButton("Cancel", null).showProfessional()
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
            }.setNegativeButton("Cancel", null).showProfessional()
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
                contentResolver.openOutputStream(intent.data!!)?.use { XlsxCodec.write(it, sheets, styles, reportingMetadata, mergedRangesBySheet) }
                status.text="Excel workbook (.xlsx) saved"
            } else if(requestCode==12) {
                val text=contentResolver.openInputStream(intent.data!!)?.bufferedReader()?.use { it.readText() }.orEmpty()
                data.clear(); sheetStyles.clear(); formulaDisplayCache.clear()
                rowCount = max(50, text.lines().size)
                text.lines().take(rowCount).forEachIndexed { r,line ->
                    parseCsv(line).take(colLimit).forEachIndexed { c,v -> if(v.isNotEmpty()) data["${colName(c)}${r+1}"]=v }
                }
                select("A1"); status.text="CSV opened"
            } else if(requestCode==14) {
                val loaded = contentResolver.openInputStream(intent.data!!)?.use { XlsxCodec.read(it) } ?: error("Could not open workbook")
                sheets.clear(); sheets.putAll(loaded.sheets)
                styles.clear(); styles.putAll(loaded.styles)
                mergedRangesBySheet.clear()
                loaded.mergedRanges.forEach { (name, ranges) -> mergedRangesBySheet[name] = ranges.toMutableList() }
                formulaDisplayCache.clear()
                reportingMetadata = loaded.reportingMetadata
                val lastCellRow = sheets.values.flatMap { it.keys }.mapNotNull { Regex("[A-Z]+([0-9]+)").matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 50
                val lastMergeRow = mergedRangesBySheet.values.flatten().flatMap { it.split(":") }.mapNotNull { Regex("[A-Z]+([0-9]+)").matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 50
                rowCount = max(50, max(lastCellRow, lastMergeRow))
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

/**
 * Restrict local evaluation of direct references to values whose type/error marker
 * POI's workbook bridge may coerce. Ordinary formulas remain on the POI-first path.
 */
internal fun shouldUseLocalReferenceResult(expression: String, cells: Map<String, String>): Boolean {
    val match = Regex("(?i)^\\$?([A-Z]{1,3})\\$?(\\d+)$").matchEntire(expression.trim()) ?: return false
    val address = match.groupValues[1].uppercase(Locale.US) + match.groupValues[2]
    val source = cells[address] ?: return false
    if (source.equals("TRUE", ignoreCase = true) || source.equals("FALSE", ignoreCase = true) || source.startsWith("#")) return true
    if (!source.trimStart().startsWith("=")) return false
    val nestedExpression = source.trimStart().drop(1).trim()
    return Regex("(?i)^(XOR|VLOOKUP|MATCH|NA|INDEX)\\s*\\(").containsMatchIn(nestedExpression)
}

internal class FormulaEngine(private val cells:Map<String,String>) {
    /**
     * Evaluates common text-producing formulas separately from numeric formulas.
     * Returns null when the expression should be handled by the numeric evaluator.
     */
    fun evalText(expr: String): String? {
        val s = expr.trim()
        // Preserve text, Boolean and Excel-error values when a formula directly
        // references another cell (for example =D29 or =D31).
        // Numeric references fall through to the numeric evaluator so cell number
        // formatting and existing numeric behavior remain unchanged.
        val directReference = Regex("(?i)^\\$?([A-Z]{1,3})\\$?(\\d+)$").matchEntire(s)
        if (directReference != null) {
            val address = directReference.groupValues[1].uppercase(Locale.US) + directReference.groupValues[2]
            val referenced = cells[address] ?: return ""
            if (referenced.startsWith("=")) {
                val nested = referenced.drop(1)
                return evalText(nested) ?: runCatching { formatSimple(eval(nested)) }
                    .getOrElse { error -> error.message?.takeIf { it.startsWith("#") } ?: "#ERROR" }
            }
            if (referenced.startsWith("#")) return referenced
            if (referenced.equals("TRUE", true) || referenced.equals("FALSE", true)) return referenced.uppercase(Locale.US)
            if (referenced.toDoubleOrNull() == null) return referenced
            return null
        }
        // Preserve explicit Excel error literals and VALUE conversion errors.
        if (s.equals("#REF!", ignoreCase = true) || s.equals("#REF", ignoreCase = true)) return "#REF!"
        val valueCall = Regex("(?i)^VALUE\\((.*)\\)$").matchEntire(s)
        if (valueCall != null) {
            val argument = valueCall.groupValues[1].trim()
            val textValue = if (argument.startsWith("\"") && argument.endsWith("\"") && argument.length >= 2)
                argument.substring(1, argument.length - 1).replace("\"\"", "\"")
            else argument
            return textValue.trim().toDoubleOrNull()?.let { formatSimple(it) } ?: "#VALUE!"
        }
        if (s.startsWith("\"") && s.endsWith("\"") && s.length >= 2) return s.substring(1, s.length - 1).replace("\"\"", "\"")
        val ifErrorMatch = Regex("(?i)^(IFERROR|IFNA)\\((.*)\\)$").matchEntire(s)
        if (ifErrorMatch != null) {
            val errorFunction = ifErrorMatch.groupValues[1].uppercase(Locale.US)
            val args = splitArgs(ifErrorMatch.groupValues[2])
            if (args.size >= 2) {
                val first = args[0].trim()
                val result = runCatching { evalText(first) ?: formatSimple(eval(first)) }.getOrNull()
                val isError = result != null && (result.startsWith("#") || result == "#ERROR")
                if (result != null && (errorFunction == "IFERROR" && !isError || errorFunction == "IFNA" && result != "#N/A")) return result
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
        val call = Regex("(?i)^(XLOOKUP|VLOOKUP|MATCH|NA|INDEX|XOR|CONCATENATE|CONCAT|LEFT|RIGHT|MID|LEN|LOWER|UPPER|TRIM|PROPER|SUBSTITUTE|REPLACE|FIND|SEARCH|TEXTJOIN)\\((.*)\\)$").matchEntire(s) ?: return null
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
        // NA() is an explicit Excel error value, not an unrecognized formula.
        if (name == "NA") {
            // splitArgs("") returns a single empty item, so args.isEmpty()
            // incorrectly classified the valid NA() call as an invalid argument.
            // Excel NA takes no arguments; whitespace inside the parentheses is OK.
            return if (call.groupValues[2].isBlank()) "#N/A" else "#VALUE!"
        }
        // INDEX(reference, row_num, [column_num]) for a cell range.
        // Return Excel's #REF! when the requested row/column is outside the range.
        if (name == "INDEX") {
            if (args.size !in 2..3) return "#VALUE!"
            val rangeSpec = args[0].trim().replace("$", "")
            val bounds = rangeSpec.split(":")
            if (bounds.size !in 1..2) return "#REF!"
            val start = runCatching { splitAddress(bounds[0]) }.getOrNull() ?: return "#REF!"
            val end = if (bounds.size == 2) {
                runCatching { splitAddress(bounds[1]) }.getOrNull() ?: return "#REF!"
            } else start
            val minCol = minOf(start.first, end.first)
            val maxCol = maxOf(start.first, end.first)
            val minRow = minOf(start.second, end.second)
            val maxRow = maxOf(start.second, end.second)
            val rowCount = maxRow - minRow + 1
            val colCount = maxCol - minCol + 1
            val rowNum = runCatching { eval(args[1]).toInt() }.getOrElse { return "#VALUE!" }
            val colNum = if (args.size >= 3) {
                runCatching { eval(args[2]).toInt() }.getOrElse { return "#VALUE!" }
            } else 1
            if (rowNum < 1 || colNum < 1 || rowNum > rowCount || colNum > colCount) return "#REF!"
            val address = col(minCol + colNum - 1) + (minRow + rowNum - 1)
            val raw = cells[address].orEmpty()
            if (!raw.startsWith("=")) return raw
            return evalText(raw.drop(1))
                ?: runCatching { formatSimple(eval(raw.drop(1))) }.getOrElse { error ->
                    error.message?.takeIf { it.startsWith("#") } ?: "#ERROR"
                }
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
        if (name == "MATCH") {
            // Excel MATCH(lookup_value, lookup_array, [match_type]).
            // The lookup array must be a single row or a single column.
            if (args.size !in 2..3) return "#VALUE!"

            val lookup = textArg(args[0])
            val rangeSpec = args[1].trim().replace("$", "")
            val lookupCells = runCatching { range(rangeSpec) }.getOrElse { return "#REF!" }
            if (lookupCells.isEmpty()) return "#N/A"

            val bounds = rangeSpec.split(":")
            if (bounds.size > 2) return "#REF!"
            val start = runCatching { splitAddress(bounds[0]) }.getOrNull() ?: return "#REF!"
            val end = if (bounds.size == 2) {
                runCatching { splitAddress(bounds[1]) }.getOrNull() ?: return "#REF!"
            } else start
            if (start.first != end.first && start.second != end.second) return "#N/A"

            val matchType = if (args.size == 3) {
                runCatching { eval(args[2]).toInt() }.getOrElse { return "#VALUE!" }
            } else 1
            if (matchType !in listOf(-1, 0, 1)) return "#VALUE!"

            fun cellValue(address: String): String {
                val raw = cells[address].orEmpty()
                if (!raw.startsWith("=")) return raw
                return evalText(raw.drop(1))
                    ?: runCatching { formatSimple(eval(raw.drop(1))) }.getOrDefault("#ERROR")
            }

            fun normalized(value: String): String {
                val trimmed = value.trim()
                return trimmed.toDoubleOrNull()?.let {
                    String.format(Locale.US, "%.12g", it)
                } ?: trimmed.lowercase(Locale.US)
            }

            // Exact match: return the first matching position; missing values are #N/A.
            if (matchType == 0) {
                val wanted = normalized(lookup)
                val index = lookupCells.indexOfFirst { normalized(cellValue(it)) == wanted }
                return if (index >= 0) (index + 1).toString() else "#N/A"
            }

            // Approximate numeric modes: MATCH type 1 chooses the largest value
            // <= lookup_value; type -1 chooses the smallest value >= lookup_value.
            val target = lookup.trim().toDoubleOrNull() ?: return "#N/A"
            val candidates = lookupCells.mapIndexedNotNull { index, address ->
                val value = cellValue(address).trim().toDoubleOrNull() ?: return@mapIndexedNotNull null
                val qualifies = if (matchType == 1) value <= target else value >= target
                if (qualifies) Triple(value, index, address) else null
            }
            val chosen = if (matchType == 1) {
                candidates.maxWithOrNull(compareBy<Triple<Double, Int, String>> { it.first }.thenBy { it.second })
            } else {
                candidates.minWithOrNull(compareBy<Triple<Double, Int, String>> { it.first }.thenBy { it.second })
            }
            return chosen?.second?.plus(1)?.toString() ?: "#N/A"
        }
        if (name == "XOR") {
            if (args.isEmpty()) return "#VALUE!"
            return if (args.count { condition(it) } % 2 == 1) "TRUE" else "FALSE"
        }
        if (name == "VLOOKUP") {
            if (args.size !in 3..4) return "#VALUE!"
            val lookup = textArg(args[0])
            // Normalize absolute-reference markers only for this VLOOKUP range.
            val tableSpec = args[1].trim().replace("$", "")
            val tableCells = range(tableSpec)
            val bounds = tableSpec.split(":")
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
        val fn=Regex("(?i)^(AVERAGEIFS|COUNTIFS|SUMIFS|MINIFS|MAXIFS|AVERAGEIF|COUNTIF|SUMIF|SUM|AVERAGE|MIN|MAX|MEDIAN|COUNT|COUNTA|PRODUCT|ABS|SQRT|INT|ROUNDUP|ROUNDDOWN|ROUND|CEILING|FLOOR|MOD|POWER|COUNTBLANK|SUBTOTAL)\\((.*)\\)$").matchEntire(s)
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
        val literal = s.trim()
        if (literal.equals("TRUE", ignoreCase = true)) return true
        if (literal.equals("FALSE", ignoreCase = true)) return false
        val m=Regex("(.+?)(>=|<=|<>|=|>|<)(.+)").matchEntire(literal)
        if(m!=null) {
            val a=atom(m.groupValues[1]); val b=atom(m.groupValues[3])
            return when(m.groupValues[2]) { "="->a==b; "<>"->a!=b; ">"->a>b; "<"->a<b; ">="->a>=b; else->a<=b }
        }
        return try { eval(s)!=0.0 } catch(_:Exception) { s.trim().equals("TRUE",true) }
    }
    private fun atom(s:String):Double {
        val token = s.trim()
        val t = token.trim('"')
        val ref = token.uppercase(Locale.US)
        if (Regex("^\\$?[A-Z]{1,3}\\$?\\d+$").matches(ref)) {
            val address = ref.replace("$", "")
            val raw = cells[address].orEmpty()
            val resolved = if (raw.startsWith("=")) {
                evalText(raw.drop(1)) ?: runCatching { formatSimple(eval(raw.drop(1))) }.getOrDefault("#ERROR")
            } else raw
            return resolved.toDoubleOrNull() ?: resolved.hashCode().toDouble()
        }
        return t.toDoubleOrNull() ?: numeric(t) ?: t.hashCode().toDouble()
    }
    private fun matches(value:String,criterion:String):Boolean {
        val c=criterion.trim().trim('"')
        val m=Regex("^(>=|<=|<>|>|<|=)(.*)$").matchEntire(c)
        if(m!=null) {
            val rhs=m.groupValues[2].trim()
            val left=value.toDoubleOrNull()
            val right=rhs.toDoubleOrNull()
            if(left!=null && right!=null) {
                return when(m.groupValues[1]) { ">"->left>right; "<"->left<right; ">="->left>=right; "<="->left<=right; "<>"->left!=right; else->left==right }
            }
            return when(m.groupValues[1]) { "=" -> value.equals(rhs,true); "<>" -> !value.equals(rhs,true); else -> false }
        }
        val leftNumber=value.toDoubleOrNull(); val rightNumber=c.toDoubleOrNull()
        return value.equals(c,true) || (leftNumber!=null && rightNumber!=null && leftNumber==rightNumber)
    }
    private fun Boolean.asNum()=if(this) 1.0 else 0.0
}

private class ArithmeticParser(private val source:String) {
    private var pos=0
    fun parse():Double { val v=expression(); skip(); if(pos<source.length) error("Unexpected input"); return v }
    private fun expression():Double { var x=term(); while(true) { skip(); if(eat('+')) x+=term() else if(eat('-')) x-=term() else return x } }
    private fun term():Double {
        var x=factor()
        while(true) {
            skip()
            if(eat('*')) x*=factor()
            else if(eat('/')) {
                val divisor = factor()
                if (divisor == 0.0) throw IllegalStateException("#DIV/0!")
                x /= divisor
            } else return x
        }
    }
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

