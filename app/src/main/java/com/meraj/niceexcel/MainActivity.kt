package com.meraj.niceexcel

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.*
import java.util.Locale
import kotlin.math.max

class MainActivity : Activity() {
    private var rowCount = 50
    private val colLimit = 26
    private val sheets = linkedMapOf<String, MutableMap<String,String>>()
    private val styles = linkedMapOf<String, MutableMap<String, CellStyle>>()
    private var filterColumn: String? = null
    private var filterText: String? = null
    private var tableHeaderRow: Int? = null
    private var sheetName = "Sheet1"
    private var selected = "A1"
    private var undoState: Pair<String,String?>? = null
    private var redoState: Pair<String,String?>? = null
    private lateinit var grid: LinearLayout
    private lateinit var verticalScroll: ScrollView
    private lateinit var cellName: TextView
    private lateinit var formula: EditText
    private lateinit var status: TextView
    private lateinit var sheetTabs: LinearLayout
    private var pendingCsv = ""

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
        val toolbar = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(dp(12),dp(8),dp(6),dp(8)); setBackgroundColor(Color.rgb(17,94,57)) }
        toolbar.addView(TextView(this).apply { text="Nice Excel"; textSize=21f; typeface=Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) }, LinearLayout.LayoutParams(0,-2,1f))
        toolbar.addView(tool("＋") { addSheet() })
        toolbar.addView(tool("↶") { undo() })
        toolbar.addView(tool("↷") { redo() })
        toolbar.addView(tool("Open") { openMenu() })
        toolbar.addView(tool("Save") { saveMenu() })
        root.addView(toolbar)

        val ribbon = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(dp(5),dp(5),dp(5),dp(5)); setBackgroundColor(Color.WHITE) }
        listOf("Home","Format","Table","Formula","Sort","Filter","Find").forEach { label ->
            ribbon.addView(ribbonButton(label) {
                when(label) {
                    "Formula" -> formulaHelp()
                    "Sort" -> sortColumn()
                    "Filter" -> filterMenu()
                    "Find" -> findReplaceMenu()
                    "Table" -> tableMenu()
                    "Format" -> formattingMenu()
                    else -> formattingMenu()
                }
            }, LinearLayout.LayoutParams(0,dp(38),1f))
        }
        root.addView(ribbon)

        val formulaBar=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(dp(6),dp(4),dp(6),dp(4)); setBackgroundColor(Color.rgb(255,255,255)) }
        cellName=TextView(this).apply { text=selected; textSize=14f; typeface=Typeface.DEFAULT_BOLD; gravity=Gravity.CENTER; setTextColor(Color.rgb(17,94,57)); setPadding(dp(10),dp(11),dp(10),dp(11)); setBackgroundColor(Color.rgb(225,242,232)) }
        formulaBar.addView(cellName)
        formula=EditText(this).apply { singleLine=true; textSize=14f; hint="Enter value or formula"; inputType=InputType.TYPE_CLASS_TEXT; setPadding(dp(8),dp(3),dp(8),dp(3)); setBackgroundColor(Color.WHITE); setText(data[selected].orEmpty()); setOnEditorActionListener { _,_,_ -> applyFormula(); true } }
        formulaBar.addView(formula,LinearLayout.LayoutParams(0,-2,1f))
        formulaBar.addView(TextView(this).apply { text="✓"; textSize=23f; setTextColor(Color.rgb(17,125,72)); setPadding(dp(10),dp(4),dp(10),dp(4)); setOnClickListener { applyFormula() } })
        root.addView(formulaBar)

        val horizontal=HorizontalScrollView(this).apply { isFillViewport=false }
        grid=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        horizontal.addView(grid)
        verticalScroll=ScrollView(this).apply { isFillViewport=true; addView(horizontal) }
        root.addView(verticalScroll,LinearLayout.LayoutParams(-1,0,1f))

        sheetTabs=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(dp(4),dp(5),dp(4),dp(5)); setBackgroundColor(Color.rgb(231,237,233)) }
        root.addView(sheetTabs)
        status=TextView(this).apply { text="Ready • Offline workbook"; textSize=12f; setTextColor(Color.WHITE); setPadding(dp(10),dp(7),dp(10),dp(7)); setBackgroundColor(Color.rgb(20,30,40)) }
        root.addView(status)
        setContentView(root)
        render()
    }

    private fun tool(label:String, action:()->Unit)=TextView(this).apply {
        text=label; textSize=14f; gravity=Gravity.CENTER; setTextColor(Color.WHITE); setPadding(dp(9),dp(9),dp(9),dp(9)); setOnClickListener { action() }
    }
    private fun ribbonButton(label:String, action:()->Unit)=TextView(this).apply {
        text=label; textSize=12f; gravity=Gravity.CENTER; setTextColor(Color.rgb(34,75,54)); setBackgroundColor(Color.rgb(236,246,239)); setOnClickListener { action() }
    }

    private fun render() {
        grid.removeAllViews()
        val header=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        header.addView(makeCell("",48,true,null))
        for(c in 0 until colLimit) header.addView(makeCell(colName(c),104,true,null))
        grid.addView(header)
        for(r in 1..rowCount) {
            if (filterColumn != null && filterText != null && r > 1) {
                val raw = data["${filterColumn}$r"].orEmpty()
                if (!raw.contains(filterText!!, ignoreCase = true)) continue
            }
            val line=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
            line.addView(makeCell(r.toString(),48,true,null))
            for(c in 0 until colLimit) {
                val a="${colName(c)}$r"
                line.addView(makeCell(display(a),104,false,a))
            }
            grid.addView(line)
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
        grid.addView(addRowBar)
        renderTabs()
    }

    private fun makeCell(value:String,width:Int,header:Boolean,key:String?):TextView {
        val tv=TextView(this).apply {
            text=value; textSize=13f; gravity=if(header||key==null) Gravity.CENTER else Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(if(header) dp(3) else dp(7),0,dp(3),0)
            val st = if (key != null) sheetStyles[key] else null
            setTextColor(if(header) Color.rgb(55,67,77) else st?.textColor ?: Color.rgb(24,32,39))
            textSize = st?.fontSize ?: 13f
            typeface = Typeface.create(Typeface.DEFAULT, (if (st?.bold == true || header) Typeface.BOLD else Typeface.NORMAL) or (if (st?.italic == true) Typeface.ITALIC else Typeface.NORMAL))
            paint.isUnderlineText = st?.underline == true
            gravity = st?.alignment ?: if(header||key==null) Gravity.CENTER else Gravity.CENTER_VERTICAL or Gravity.START
            setBackgroundColor(when { header -> Color.rgb(228,235,240); key==selected -> Color.rgb(213,241,223); st != null -> st.fillColor; else -> Color.WHITE })
            if (st?.border == true) { background = android.graphics.drawable.GradientDrawable().apply { setColor(st.fillColor); setStroke(dp(if (st.thickBorder) 2 else 1), Color.rgb(70,80,88)) } }
            setOnClickListener { if(key!=null) select(key) }
            setOnLongClickListener {
                if(key!=null) {
                    val input=EditText(this@MainActivity).apply { setText(data[key].orEmpty()) }
                    AlertDialog.Builder(this@MainActivity).setTitle("Edit $key").setView(input)
                        .setPositiveButton("Apply") { _,_ -> putCell(key,input.text.toString()); select(key) }
                        .setNegativeButton("Cancel",null).show()
                }; true
            }
        }
        val lp=LinearLayout.LayoutParams(dp(width),dp(38)); lp.setMargins(0,0,dp(1),dp(1)); tv.layoutParams=lp
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
        render()
        status.text = "Added row $rowCount"
        verticalScroll.post { verticalScroll.smoothScrollTo(0, grid.height) }
    }

    private fun select(address:String) {
        selected=address; cellName.text=address; formula.setText(data[address].orEmpty()); render()
    }
    private fun putCell(address:String,value:String) {
        undoState=address to data[address]
        redoState=null
        if (value.isEmpty()) data.remove(address) else data[address]=value
    }
    private fun applyFormula() {
        putCell(selected,formula.text.toString()); render(); status.text="Updated $sheetName!$selected"
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(formula.windowToken,0)
    }
    private fun undo() {
        val u=undoState
        if(u==null) { status.text="Nothing to undo"; return }
        redoState=u.first to data[u.first]
        if(u.second==null) data.remove(u.first) else data[u.first]=u.second!!
        undoState=null; render(); formula.setText(data[selected].orEmpty()); status.text="Undo complete"
    }
    private fun redo() {
        val r=redoState
        if(r==null) { status.text="Nothing to redo"; return }
        undoState=r.first to data[r.first]
        if(r.second==null) data.remove(r.first) else data[r.first]=r.second!!
        redoState=null; render(); formula.setText(data[selected].orEmpty()); status.text="Redo complete"
    }
    private fun findReplaceMenu() {
        val panel=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(8),dp(20),0) }
        val find=EditText(this).apply { hint="Find text"; singleLine=true }
        val replace=EditText(this).apply { hint="Replace with"; singleLine=true }
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
    private fun formattingMenu() {
        val options = arrayOf(
            "Bold on/off", "Italic on/off", "Underline on/off", "Font size", "Text color", "Cell fill color",
            "Alignment", "Number format", "Cell borders", "Clear formatting", "Clear cell", "Find / replace"
        )
        AlertDialog.Builder(this).setTitle("Format $selected").setItems(options) { _, which ->
            val st = styleFor(selected)
            when (which) {
                0 -> st.bold = !st.bold
                1 -> st.italic = !st.italic
                2 -> st.underline = !st.underline
                3 -> chooseFontSize()
                4 -> chooseColor("Text color") { st.textColor = it; render() }
                5 -> chooseColor("Cell fill") { st.fillColor = it; render() }
                6 -> chooseAlignment()
                7 -> chooseNumberFormat()
                8 -> st.border = !st.border
                9 -> sheetStyles.remove(selected)
                10 -> { putCell(selected, ""); formula.setText("") }
                11 -> findReplaceMenu()
            }
            render(); status.text = "Formatting updated: $selected"
        }.show()
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
        AlertDialog.Builder(this).setTitle("Cell alignment").setItems(opts) { _, i ->
            styleFor(selected).alignment = when (i) { 1 -> Gravity.CENTER; 2 -> Gravity.CENTER_VERTICAL or Gravity.END; else -> Gravity.CENTER_VERTICAL or Gravity.START }
            render()
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
    private fun columnIndex(col:String):Int {
        var n=0; col.uppercase(Locale.US).forEach { n=n*26+(it-'A'+1) }; return (n-1).coerceAtLeast(0)
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
                contentResolver.openOutputStream(intent.data!!)?.use { XlsxCodec.write(it, sheets, styles) }
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
        val ifErrorMatch = Regex("(?i)^IFERROR\\((.*)\\)$").matchEntire(s)
        if (ifErrorMatch != null) {
            val args = splitArgs(ifErrorMatch.groupValues[1])
            if (args.size >= 2) {
                val first = args[0].trim()
                val result = runCatching { evalText(first) ?: formatSimple(eval(first)) }.getOrNull()
                if (result != null && result != "#ERROR" && result != "#NUM!") return result
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
        val call = Regex("(?i)^(CONCATENATE|CONCAT|LEFT|RIGHT|MID|LEN|LOWER|UPPER|TRIM|PROPER|SUBSTITUTE|REPLACE|FIND|SEARCH|TEXTJOIN)\\((.*)\\)$").matchEntire(s) ?: return null
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
            "PROPER" -> textArg(args.getOrElse(0) { "\\\"\\\"" }).lowercase(Locale.US).split(Regex("(\\\\s+)")).joinToString("") { part -> if (part.isBlank()) part else part.replaceFirstChar { it.titlecase(Locale.US) } }
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
        val fn=Regex("(?i)^(SUM|AVERAGE|MIN|MAX|COUNT|COUNTA|COUNTIF|SUMIF|PRODUCT|ABS|SQRT|INT|ROUND|ROUNDUP|ROUNDDOWN|CEILING|FLOOR|MOD|POWER|COUNTBLANK|SUBTOTAL)\\((.*)\\)$").matchEntire(s)
        if(fn!=null) {
            val name=fn.groupValues[1].uppercase(Locale.US)
            val args=splitArgs(fn.groupValues[2])
            if(name in listOf("SUM","AVERAGE","MIN","MAX","COUNT","COUNTA","PRODUCT")) {
                val addresses = args.flatMap { arg -> if (arg.contains(":")) range(arg) else if (Regex("(?i)^[A-Z]+[0-9]+$").matches(arg.trim())) listOf(arg.trim().uppercase(Locale.US)) else emptyList() }
                val literals = args.filterNot { it.contains(":") || Regex("(?i)^[A-Z]+[0-9]+$").matches(it.trim()) }.mapNotNull { it.trim().toDoubleOrNull() }
                val rawValues = addresses.mapNotNull { cells[it] }
                val vals = addresses.mapNotNull { numeric(it) } + literals
                return when(name) {
                    "SUM" -> vals.sum()
                    "AVERAGE" -> if(vals.isEmpty()) 0.0 else vals.average()
                    "MIN" -> vals.minOrNull() ?: 0.0
                    "MAX" -> vals.maxOrNull() ?: 0.0
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

