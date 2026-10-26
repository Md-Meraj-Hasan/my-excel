package com.meraj.niceexcel

import android.graphics.Color
import android.view.Gravity
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal data class CellStyle(
    var bold: Boolean = false,
    var italic: Boolean = false,
    var underline: Boolean = false,
    var textColor: Int = Color.rgb(24, 32, 39),
    var fillColor: Int = Color.WHITE,
    var fontSize: Float = 13f,
    var alignment: Int = Gravity.CENTER_VERTICAL or Gravity.START,
    var numberFormat: String = "General",
    var border: Boolean = false,
    var thickBorder: Boolean = false
)

internal data class XlsxWorkbook(
    val sheets: LinkedHashMap<String, MutableMap<String, String>>,
    val styles: LinkedHashMap<String, MutableMap<String, CellStyle>>
)

/** Lightweight OOXML codec. Preserves common cell styles; does not preserve every Excel feature. */
internal object XlsxCodec {
    private data class FontSpec(val bold:Boolean, val italic:Boolean, val underline:Boolean, val size:Float, val color:Int)
    private const val MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private val builtInFormats = mapOf(0 to "General", 1 to "Number (2 decimals)", 2 to "Number (2 decimals)", 9 to "Percentage", 10 to "Percentage", 14 to "Date (text display)", 22 to "Date (text display)", 164 to "Currency")

    fun write(output: OutputStream, sheets: Map<String, out Map<String, String>>, allStyles: Map<String, out Map<String, CellStyle>>) {
        val safeSheets = if (sheets.isEmpty()) linkedMapOf("Sheet1" to mutableMapOf()) else LinkedHashMap(sheets)
        val styleKeys = linkedMapOf<String, Int>()
        val styleList = mutableListOf<CellStyle>()
        safeSheets.forEach { (name, cells) ->
            (cells.keys + allStyles[name].orEmpty().keys).distinct().forEach { address ->
                val st = allStyles[name]?.get(address) ?: return@forEach
                val key = styleKey(st)
                if (!styleKeys.containsKey(key)) { styleKeys[key] = styleList.size + 1; styleList.add(st.copy()) }
            }
        }
        ZipOutputStream(output).use { zip ->
            entry(zip, "[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>${safeSheets.keys.indices.joinToString("") { "<Override PartName=\"/xl/worksheets/sheet${it+1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" }}</Types>""")
            entry(zip, "_rels/.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            entry(zip, "xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="$MAIN" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>${safeSheets.keys.mapIndexed { i, n -> "<sheet name=\"${esc(n.take(31))}\" sheetId=\"${i+1}\" r:id=\"rId${i+1}\"/>" }.joinToString("")}</sheets><calcPr calcMode="auto" fullCalcOnLoad="1" forceFullCalc="1"/></workbook>""")
            entry(zip, "xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">${safeSheets.keys.indices.joinToString("") { "<Relationship Id=\"rId${it+1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${it+1}.xml\"/>" }}<Relationship Id="rId${safeSheets.size+1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
            entry(zip, "xl/styles.xml", makeStylesXml(styleList))
            safeSheets.entries.toList().forEachIndexed { index, sheetEntry ->
                val name = sheetEntry.key
                val cells = sheetEntry.value
                val addresses = (cells.keys + allStyles[name].orEmpty().keys).distinct().filter { cells[it].orEmpty().isNotEmpty() || allStyles[name]?.containsKey(it) == true }
                val sorted = addresses.associateWith { cells[it].orEmpty() }.toSortedMap(compareBy<String> { cellRow(it) }.thenBy { cellCol(it) })
                val rows = sorted.keys.map { cellRow(it) }.distinct().sorted()
                val xml = buildString {
                    append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"$MAIN\"><sheetData>")
                    rows.forEach { row ->
                        append("<row r=\"$row\">")
                        sorted.filterKeys { cellRow(it) == row }.forEach { (address, raw) ->
                            val styleId = allStyles[name]?.get(address)?.let { styleKeys[styleKey(it)] } ?: 0
                            val stAttr = if (styleId > 0) " s=\"$styleId\"" else ""
                            when {
                                raw.isEmpty() && styleId > 0 -> append("<c r=\"${esc(address)}\"$stAttr/>")
                                raw.startsWith("=") -> append("<c r=\"${esc(address)}\"$stAttr><f>${esc(raw.drop(1))}</f></c>")
                                raw.toDoubleOrNull() != null -> append("<c r=\"${esc(address)}\"$stAttr><v>${esc(raw)}</v></c>")
                                raw.equals("TRUE", true) || raw.equals("FALSE", true) -> append("<c r=\"${esc(address)}\" t=\"b\"$stAttr><v>${if(raw.equals("TRUE",true)) 1 else 0}</v></c>")
                                else -> append("<c r=\"${esc(address)}\" t=\"inlineStr\"$stAttr><is><t xml:space=\"preserve\">${esc(raw)}</t></is></c>")
                            }
                        }
                        append("</row>")
                    }
                    append("</sheetData></worksheet>")
                }
                entry(zip, "xl/worksheets/sheet${index+1}.xml", xml)
            }
        }
    }

    fun read(input: InputStream): XlsxWorkbook {
        val parts = linkedMapOf<String, ByteArray>()
        ZipInputStream(input).use { zip -> while (true) {
            val e = zip.nextEntry ?: break
            if (!e.isDirectory && (e.name == "xl/workbook.xml" || e.name == "xl/_rels/workbook.xml.rels" || e.name == "xl/sharedStrings.xml" || e.name == "xl/styles.xml" || e.name.startsWith("xl/worksheets/"))) parts[e.name] = zip.readBytes()
            zip.closeEntry()
        } }
        val workbook = parts["xl/workbook.xml"] ?: error("This is not a valid XLSX workbook")
        val rels = parts["xl/_rels/workbook.xml.rels"]?.let { parseRelationships(it) }.orEmpty()
        val shared = parts["xl/sharedStrings.xml"]?.let { parseSharedStrings(it) }.orEmpty()
        val styleTable = parts["xl/styles.xml"]?.let { parseStyles(it) } ?: listOf(CellStyle())
        val defs = parseSheets(workbook)
        if (defs.isEmpty()) error("Workbook has no worksheets")
        val sheets = linkedMapOf<String, MutableMap<String, String>>()
        val styles = linkedMapOf<String, MutableMap<String, CellStyle>>()
        defs.forEachIndexed { i, def ->
            val target = rels[def.second] ?: "worksheets/sheet${i+1}.xml"
            val path = normalizePath(if (target.startsWith("/")) target.removePrefix("/") else "xl/$target")
            val bytes = parts[path] ?: parts["xl/worksheets/sheet${i+1}.xml"]
            val cells = linkedMapOf<String, String>()
            val cellStyles = linkedMapOf<String, CellStyle>()
            if (bytes != null) parseWorksheet(bytes, shared, styleTable, cells, cellStyles)
            var name = def.first.ifBlank { "Sheet${i+1}" }; var suffix = 2
            while (sheets.containsKey(name)) { name = "${def.first} ($suffix)"; suffix++ }
            sheets[name] = cells; styles[name] = cellStyles
        }
        return XlsxWorkbook(sheets, styles)
    }

    private fun makeStylesXml(styles: List<CellStyle>): String {
        val fonts = styles.map { "<font>${if(it.bold) "<b/>" else ""}${if(it.italic) "<i/>" else ""}${if(it.underline) "<u/>" else ""}<sz val=\"${it.fontSize.coerceIn(8f,72f)}\"/><color rgb=\"${argb(it.textColor)}\"/><name val=\"Arial\"/></font>" }
        val fills = styles.map { "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"${argb(it.fillColor)}\"/><bgColor indexed=\"64\"/></patternFill></fill>" }
        val borders = styles.map {
            if (it.border) {
                val weight = if (it.thickBorder) "medium" else "thin"
                "<border><left style=\"$weight\"><color rgb=\"FF465058\"/></left><right style=\"$weight\"><color rgb=\"FF465058\"/></right><top style=\"$weight\"><color rgb=\"FF465058\"/></top><bottom style=\"$weight\"><color rgb=\"FF465058\"/></bottom><diagonal/></border>"
            } else "<border><left/><right/><top/><bottom/><diagonal/></border>"
        }
        val numFormats = styles.map { it.numberFormat }.distinct().filter { it !in listOf("General", "Number (2 decimals)", "Percentage", "Date (text display)") }
        val fmtId = numFormats.withIndex().associate { it.value to (164 + it.index) }
        val fmtXml = numFormats.map { fmt -> "<numFmt numFmtId=\"${fmtId[fmt]}\" formatCode=\"${esc(when(fmt){"Currency" -> "\$#,##0.00"; else -> "General"})}\"/>" }.joinToString("")
        val xfs = buildList {
            add("<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>")
            styles.forEachIndexed { i, st ->
                val fmt = when(st.numberFormat) { "Number (2 decimals)" -> 2; "Percentage" -> 10; "Date (text display)" -> 14; "Currency" -> fmtId[st.numberFormat] ?: 164; else -> 0 }
                val align = if (st.alignment == Gravity.CENTER) "center" else if (st.alignment == (Gravity.CENTER_VERTICAL or Gravity.END)) "right" else "left"
                add("<xf numFmtId=\"$fmt\" fontId=\"${i+1}\" fillId=\"${i+1}\" borderId=\"${i+1}\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"$align\" vertical=\"center\"/></xf>")
            }
        }.joinToString("")
        return """<?xml version="1.0" encoding="UTF-8"?><styleSheet xmlns="$MAIN">${if(fmtXml.isNotEmpty()) "<numFmts count=\"${numFormats.size}\">$fmtXml</numFmts>" else ""}<fonts count="${fonts.size+1}"><font><sz val="11"/><color theme="1"/><name val="Calibri"/></font>${fonts.joinToString("")}</fonts><fills count="${fills.size+2}"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill>${fills.joinToString("")}</fills><borders count="${borders.size+1}"><border><left/><right/><top/><bottom/><diagonal/></border>${borders.joinToString("")}</borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="${styles.size+1}">$xfs</cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>"""
    }

    private fun parseStyles(xml: ByteArray): List<CellStyle> {
        val fonts = mutableListOf<FontSpec>()
        val fills = mutableListOf<Int>(); val borders = mutableListOf<Boolean>(); val thickBorders = mutableListOf<Boolean>(); val formats = mutableMapOf<Int,String>()
        val xfs = mutableListOf<Triple<Int, Int, IntArray>>()
        val p = parser(xml); var section=""; var bold=false; var italic=false; var underline=false; var size=11f; var fontColor=Color.BLACK; var fillColor=Color.WHITE; var border=false; var thickBorder=false; var numId=0; var fontId=0; var fillId=0; var borderId=0; var align=Gravity.CENTER_VERTICAL or Gravity.START
        while(p.eventType != XmlPullParser.END_DOCUMENT) {
            if(p.eventType==XmlPullParser.START_TAG) when(p.name) {
                "fonts" -> section="fonts"; "fills" -> section="fills"; "borders" -> section="borders"; "cellXfs" -> section="xfs"; "numFmts" -> section="numFmts"
                "font" -> if(section=="fonts") { bold=false; italic=false; underline=false; size=11f; fontColor=Color.BLACK }
                "b" -> if(section=="fonts") bold=true; "i" -> if(section=="fonts") italic=true; "u" -> if(section=="fonts") underline=true
                "sz" -> if(section=="fonts") size=p.getAttributeValue(null,"val")?.toFloatOrNull()?:11f
                "color" -> if(section=="fonts") fontColor=parseColor(p.getAttributeValue(null,"rgb"))?:Color.BLACK
                "patternFill" -> if(section=="fills") fillColor=Color.WHITE
                "fgColor" -> if(section=="fills") fillColor=parseColor(p.getAttributeValue(null,"rgb"))?:Color.WHITE
                "border" -> if(section=="borders") { border=false; thickBorder=false }
                "left","right","top","bottom" -> if(section=="borders") {
                    val weight = p.getAttributeValue(null,"style")
                    if (weight != null) border=true
                    if (weight == "medium" || weight == "thick") thickBorder=true
                }
                "numFmt" -> { val id=p.getAttributeValue(null,"numFmtId")?.toIntOrNull(); val code=p.getAttributeValue(null,"formatCode"); if(id!=null&&code!=null) formats[id]=decodeFmt(code) }
                "xf" -> if(section=="xfs") { numId=p.getAttributeValue(null,"numFmtId")?.toIntOrNull()?:0; fontId=p.getAttributeValue(null,"fontId")?.toIntOrNull()?:0; fillId=p.getAttributeValue(null,"fillId")?.toIntOrNull()?:0; borderId=p.getAttributeValue(null,"borderId")?.toIntOrNull()?:0; align=Gravity.CENTER_VERTICAL or Gravity.START }
                "alignment" -> if(section=="xfs") align=when(p.getAttributeValue(null,"horizontal")){"center"->Gravity.CENTER;"right"->Gravity.CENTER_VERTICAL or Gravity.END;else->Gravity.CENTER_VERTICAL or Gravity.START}
            } else if(p.eventType==XmlPullParser.END_TAG) when(p.name) {
                "font" -> if(section=="fonts") fonts.add(FontSpec(bold,italic,underline,size,fontColor))
                "fill" -> if(section=="fills") fills.add(fillColor)
                "border" -> if(section=="borders") { borders.add(border); thickBorders.add(thickBorder) }
                "xf" -> if(section=="xfs") xfs.add(Triple(numId,fontId,intArrayOf(fillId,borderId,align)))
                "fonts","fills","borders","cellXfs","numFmts" -> section=""
            }
            p.next()
        }
        val out=mutableListOf(CellStyle())
        xfs.forEachIndexed { idx, xf ->
            if(idx==0) return@forEachIndexed
            val f=fonts.getOrNull(xf.second); val ids=xf.third
            out.add(CellStyle(bold=f?.bold?:false, italic=f?.italic?:false, underline=f?.underline?:false, textColor=f?.color?:Color.BLACK, fillColor=fills.getOrNull(ids[0])?:Color.WHITE, fontSize=f?.size?:11f, alignment=ids[2], numberFormat=formats[xf.first]?:builtInFormats[xf.first]?:"General", border=borders.getOrNull(ids[1])?:false, thickBorder=thickBorders.getOrNull(ids[1])?:false))
        }
        return out
    }
    private fun parseWorksheet(xml:ByteArray, shared:List<String>, styleTable:List<CellStyle>, cells:MutableMap<String,String>, styles:MutableMap<String,CellStyle>) {
        val p=parser(xml); var address=""; var type=""; var formula=""; var value=""; var inline=""; var inInline=false; var styleId=0
        while(p.eventType!=XmlPullParser.END_DOCUMENT) {
            if(p.eventType==XmlPullParser.START_TAG) when(p.name) {
                "c" -> { address=p.getAttributeValue(null,"r")?:""; type=p.getAttributeValue(null,"t")?:""; styleId=p.getAttributeValue(null,"s")?.toIntOrNull()?:0; formula="";value="";inline="" }
                "f" -> formula=readText(p); "v" -> value=readText(p); "is" -> inInline=true; "t" -> if(inInline) inline+=readText(p)
            } else if(p.eventType==XmlPullParser.END_TAG) when(p.name) {
                "is" -> inInline=false
                "c" -> if(address.isNotBlank()) {
                    val raw=when { formula.isNotBlank()->"=$formula"; type=="s"->shared.getOrNull(value.toIntOrNull()?:-1).orEmpty(); type=="inlineStr"->inline; type=="b"->if(value=="1")"TRUE" else "FALSE"; else->value }
                    if(raw.isNotEmpty()) cells[address.uppercase()]=raw
                    styleTable.getOrNull(styleId)?.let { if(styleId>0) styles[address.uppercase()]=it.copy() }
                }
            }
            p.next()
        }
    }
    private fun parseSheets(xml:ByteArray):List<Pair<String,String>> { val p=parser(xml); val out=mutableListOf<Pair<String,String>>(); while(p.eventType!=XmlPullParser.END_DOCUMENT){if(p.eventType==XmlPullParser.START_TAG&&p.name=="sheet"){val n=p.getAttributeValue(null,"name")?:"Sheet${out.size+1}";var id:String?=null;for(i in 0 until p.attributeCount)if(p.getAttributeName(i).endsWith(":id")||p.getAttributeName(i)=="id")id=p.getAttributeValue(i);out.add(n to (id?:"rId${out.size+1}"))};p.next()};return out }
    private fun parseRelationships(xml:ByteArray):Map<String,String>{val p=parser(xml);val out=mutableMapOf<String,String>();while(p.eventType!=XmlPullParser.END_DOCUMENT){if(p.eventType==XmlPullParser.START_TAG&&p.name=="Relationship"){val id=p.getAttributeValue(null,"Id");val t=p.getAttributeValue(null,"Target");if(id!=null&&t!=null&&(t.contains("worksheet")||t.contains("styles")))out[id]=t};p.next()};return out}
    private fun parseSharedStrings(xml:ByteArray):List<String>{val p=parser(xml);val out=mutableListOf<String>();var inside=false;var b=StringBuilder();while(p.eventType!=XmlPullParser.END_DOCUMENT){if(p.eventType==XmlPullParser.START_TAG&&p.name=="si"){inside=true;b=StringBuilder()}else if(inside&&p.eventType==XmlPullParser.START_TAG&&p.name=="t")b.append(readText(p))else if(p.eventType==XmlPullParser.END_TAG&&p.name=="si"){out.add(b.toString());inside=false};p.next()};return out}
    private fun styleKey(s:CellStyle)="${s.bold}|${s.italic}|${s.underline}|${s.textColor}|${s.fillColor}|${s.fontSize}|${s.alignment}|${s.numberFormat}|${s.border}|${s.thickBorder}"
    private fun parseColor(s:String?):Int? { if(s==null)return null; val h=s.removePrefix("#").uppercase(); return try { when(h.length){6->Color.parseColor("#$h");8->Color.parseColor("#${h.substring(2)}");else->null} }catch(_:Exception){null} }
    private fun argb(c:Int)="FF"+String.format("%06X", c and 0xFFFFFF)
    private fun decodeFmt(s:String)=when { s.contains('%')->"Percentage"; s.contains("yy",true)||s.contains("dd",true)->"Date (text display)"; s.contains("$",true)||s.contains("€")||s.contains("£")->"Currency"; s.contains("0.00")->"Number (2 decimals)"; else->"General" }
    private fun readText(p:XmlPullParser)=try{p.nextText()}catch(_:Exception){""}
    private fun parser(bytes:ByteArray)=Xml.newPullParser().apply{setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES,false);setInput(bytes.inputStream(),"UTF-8")}
    private fun entry(zip:ZipOutputStream,name:String,text:String){zip.putNextEntry(ZipEntry(name));zip.write(text.toByteArray(Charsets.UTF_8));zip.closeEntry()}
    private fun esc(s:String)=s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;")
    private fun normalizePath(path:String):String{val stack=mutableListOf<String>();path.split('/').forEach{part->when(part){"","."->Unit;".."->if(stack.isNotEmpty())stack.removeAt(stack.lastIndex);else->stack.add(part)}};return stack.joinToString("/")}
    private fun cellRow(s:String)=Regex("[A-Z]+([0-9]+)",RegexOption.IGNORE_CASE).matchEntire(s)?.groupValues?.get(1)?.toIntOrNull()?:0
    private fun cellCol(s:String):Int{var n=0;s.takeWhile{it.isLetter()}.uppercase().forEach{n=n*26+it-'A'+1};return n}
}
