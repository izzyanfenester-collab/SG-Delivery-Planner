package com.izzyan.sgdeliveryplanner

import java.io.OutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.ceil

const val EXCEL_MIME_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

/** The scheduled route date is stable even when an old route is exported from History. */
fun deliveryExcelFileName(plan: Plan): String =
    "Runner_Route_Planning_${plan.deliveryDate}.xlsx"

/**
 * Writes a complete SpreadsheetML workbook without requiring a desktop Excel library on Android.
 * Closes [output] after finishing the ZIP, including when writing fails. Text is always an inline
 * string: addresses, postal codes and other imported data can never become Excel formulas.
 */
fun writeDeliveryScheduleXlsx(plan: Plan, output: OutputStream) {
    val summary = deliverySummary(plan)
    val origin = plan.startLocation.asPlace()
    val endpointLabel = if (plan.startLocation.type == "WOODLANDS") plan.startLocation.displayName
        else plan.startLocation.reportLabel
    val headings = listOf(
        "Stop", "Postal Code", "Block", "Area", "Arrival", "Distance From Previous",
        "Base Drive", "Traffic Buffer", "Planned Travel", "Delivery", "Leave", "Cumulative KM", "Updated ETA", "Updated Finish", "Actual / Delivered At"
    )
    val rows = mutableListOf<List<ExcelCell>>()
    rows += listOf(
        textCell("START", ENDPOINT_STYLE), textCell(origin.postal), textCell(endpointLabel),
        textCell(origin.area), dateTimeCell(plan.start), textCell("—"), textCell("—"),
        textCell("—"), textCell("—"), textCell("—"), dateTimeCell(plan.start), kmCell(0.0), textCell("—"), textCell("—"), textCell("—")
    )
    plan.stops.forEachIndexed { index, stop ->
        rows += listOf(
            numberCell(index + 1), textCell(stop.place.postal), textCell(stop.place.block),
            textCell(stop.place.area), dateTimeCell(stop.arrival), kmCell(stop.leg.km),
            durationCell(stop.leg.baseSeconds), durationCell(stop.leg.bufferSeconds),
            durationCell(stop.leg.plannedSeconds),
            textCell("${deliveryPeriodTime(stop.arrival)} – ${deliveryPeriodTime(stop.leave)}"),
            dateTimeCell(stop.leave), kmCell(stop.cumulativeKm),
            stop.etaArrival?.let(::dateTimeCell) ?: textCell("—"),
            stop.etaLeave?.let(::dateTimeCell) ?: textCell("—"),
            stop.completedAt?.let(::dateTimeCell) ?: textCell("—")
        )
    }
    rows += listOf(
        textCell("END", ENDPOINT_STYLE), textCell(origin.postal), textCell(endpointLabel),
        textCell(origin.area), dateTimeCell(plan.returned), kmCell(plan.returnLeg.km),
        durationCell(plan.returnLeg.baseSeconds), durationCell(plan.returnLeg.bufferSeconds),
        durationCell(plan.returnLeg.plannedSeconds), textCell("—"), dateTimeCell(plan.returned),
        kmCell(plan.totalKm), plan.etaReturned?.let(::dateTimeCell) ?: textCell("—"), textCell("—"), textCell("—")
    )
    val summaryRows = listOf(
        "Date" to numericCell(excelDate(plan.deliveryDate), DATE_STYLE, plan.deliveryDate.toString()),
        "Start Location" to textCell(plan.startLocation.reportLabel),
        "End Location" to textCell(plan.startLocation.reportLabel),
        "Start Time" to dateTimeCell(summary.start),
        "Finish Time" to dateTimeCell(summary.finish),
        "Total Parcel" to numberCell(summary.totalParcel),
        "Delivered" to numberCell(summary.delivered),
        "On Hold" to numberCell(summary.onHold),
        "Skipped" to numberCell(summary.skipped),
        "Pending" to numberCell(summary.pending),
        "Success Rate" to numericCell(summary.successRate / 100.0, PERCENT_STYLE, "${summary.successRate}%"),
        "Total KM" to kmCell(summary.totalKm),
        "Cash on Hand" to moneyCell(plan.cashOnHand),
        "Tax" to moneyCell(plan.tax),
        "Tax MYR" to textCell("RM ${taxMyr(plan.tax, plan.exchangeRate)}"),
        "Rate" to textCell(plan.exchangeRate),
        "Remark" to textCell(plan.remark)
    )
    val lastRow = SCHEDULE_HEADER_ROW + rows.size
    val worksheet = buildString {
        append(XML_DECLARATION)
        append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        append("<dimension ref=\"A1:O$lastRow\"/>")
        append("<sheetViews><sheetView workbookViewId=\"0\">")
        append("<pane ySplit=\"$SCHEDULE_HEADER_ROW\" topLeftCell=\"A${SCHEDULE_HEADER_ROW + 1}\" activePane=\"bottomLeft\" state=\"frozen\"/>")
        append("<selection pane=\"bottomLeft\" activeCell=\"A${SCHEDULE_HEADER_ROW + 1}\" sqref=\"A${SCHEDULE_HEADER_ROW + 1}\"/>")
        append("</sheetView></sheetViews><sheetFormatPr defaultRowHeight=\"22\"/><cols>")
        headings.forEachIndexed { index, heading ->
            val longest = maxOf(heading.length, rows.maxOfOrNull { it[index].displayWidth } ?: 0)
            // Very long imported addresses wrap instead of producing an unusably wide sheet.
            val width = (longest + 3).coerceIn(if (index == 0) 18 else 12, if (index == 9) 42 else 34)
            append("<col min=\"${index + 1}\" max=\"${index + 1}\" width=\"$width\" bestFit=\"1\" customWidth=\"1\"/>")
        }
        append("</cols><sheetData>")
        appendExcelRow(1, listOf(textCell("Runner Route Planning - Complete Delivery Schedule", TITLE_STYLE)), 30)
        appendExcelRow(2, listOf(textCell("Runner Route Planning Route Summary", SUMMARY_TITLE_STYLE)), 26)
        summaryRows.forEachIndexed { index, (label, value) ->
            appendExcelRow(index + 3, listOf(textCell(label, SUMMARY_LABEL_STYLE), value), 22)
        }
        appendExcelRow(SCHEDULE_HEADER_ROW, headings.map { textCell(it, HEADER_STYLE) }, 42)
        rows.forEachIndexed { index, cells ->
            appendExcelRow(SCHEDULE_HEADER_ROW + index + 1, cells, 42)
        }
        append("</sheetData><autoFilter ref=\"A$SCHEDULE_HEADER_ROW:O$lastRow\"/>")
        append("<mergeCells count=\"${summaryRows.size + 2}\"><mergeCell ref=\"A1:O1\"/><mergeCell ref=\"A2:O2\"/>")
        summaryRows.indices.forEach { append("<mergeCell ref=\"B${it + 3}:D${it + 3}\"/>") }
        append("</mergeCells><pageMargins left=\"0.3\" right=\"0.3\" top=\"0.5\" bottom=\"0.5\" header=\"0.2\" footer=\"0.2\"/>")
        append("<pageSetup paperSize=\"9\" orientation=\"landscape\" fitToWidth=\"1\" fitToHeight=\"0\"/>")
        append("</worksheet>")
    }
    ZipOutputStream(output).use { zip ->
        zip.xmlEntry("[Content_Types].xml", CONTENT_TYPES)
        zip.xmlEntry("_rels/.rels", PACKAGE_RELATIONSHIPS)
        zip.xmlEntry("xl/workbook.xml", WORKBOOK)
        zip.xmlEntry("xl/_rels/workbook.xml.rels", WORKBOOK_RELATIONSHIPS)
        zip.xmlEntry("xl/styles.xml", STYLES)
        zip.xmlEntry("xl/worksheets/sheet1.xml", worksheet)
    }
}

private const val SCHEDULE_HEADER_ROW = 21
private const val TITLE_STYLE = 1
private const val SUMMARY_TITLE_STYLE = 2
private const val SUMMARY_LABEL_STYLE = 3
private const val DATE_STYLE = 4
private const val DATETIME_STYLE = 5
private const val KM_STYLE = 6
private const val DURATION_STYLE = 7
private const val MONEY_STYLE = 8
private const val PERCENT_STYLE = 9
private const val HEADER_STYLE = 10
private const val ENDPOINT_STYLE = 11
private const val XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"

private data class ExcelCell(val value: String, val style: Int, val isNumber: Boolean, val displayWidth: Int)

private fun textCell(value: String, style: Int = 0): ExcelCell =
    ExcelCell(value, style, false, value.lineSequence().maxOfOrNull { it.length } ?: 0)

private fun numericCell(value: Double, style: Int = 0, display: String = value.toString()): ExcelCell {
    require(value.isFinite()) { "The delivery schedule contains an invalid number." }
    return ExcelCell(value.toString(), style, true, display.length)
}

private fun numberCell(value: Int): ExcelCell = ExcelCell(value.toString(), 0, true, value.toString().length)

private fun kmCell(value: Double): ExcelCell =
    numericCell(value, KM_STYLE, String.format(Locale.ENGLISH, "%.1f KM", value))

private fun durationCell(seconds: Double): ExcelCell {
    // Match the app's separate upward rounding of base, buffer and planned driving minutes.
    val minutes = ceil(seconds.coerceAtLeast(0.0) / 60.0)
    return numericCell(minutes / 1440.0, DURATION_STYLE, "${minutes.toLong() / 60} hr ${minutes.toLong() % 60} min")
}

private fun moneyCell(value: String): ExcelCell {
    val amount = (normalizedCurrency(value) ?: "0.00").toBigDecimal()
    return ExcelCell(amount.toPlainString(), MONEY_STYLE, true, "SGD ${amount.toPlainString()}".length)
}

private fun dateTimeCell(value: String): ExcelCell {
    val parsed = runCatching { LocalDateTime.parse(value) }.getOrNull() ?: return textCell(value)
    val timeOfDay = (parsed.toLocalTime().toSecondOfDay() + parsed.nano / 1_000_000_000.0) / 86400.0
    return numericCell(excelDate(parsed.toLocalDate()) + timeOfDay, DATETIME_STYLE, deliveryPeriodTime(value))
}

private fun excelDate(date: LocalDate): Double {
    // Excel's 1900 date system includes a fictitious 29 February 1900; modern dates need +1.
    val days = ChronoUnit.DAYS.between(LocalDate.of(1899, 12, 31), date)
    return (days + if (date >= LocalDate.of(1900, 3, 1)) 1 else 0).toDouble()
}

private fun deliveryPeriodTime(value: String): String = runCatching {
    LocalDateTime.parse(value).format(DateTimeFormatter.ofPattern("yyyy-MM-dd h:mm a", Locale.ENGLISH))
}.getOrDefault(value)

private fun StringBuilder.appendExcelRow(row: Int, cells: List<ExcelCell>, height: Int) {
    append("<row r=\"$row\" ht=\"$height\" customHeight=\"1\">")
    cells.forEachIndexed { index, cell ->
        val reference = "${('A'.code + index).toChar()}$row"
        append("<c r=\"$reference\" s=\"${cell.style}\"")
        if (cell.isNumber) {
            append("><v>${cell.value}</v></c>")
        } else {
            append(" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xmlText(cell.value)}</t></is></c>")
        }
    }
    append("</row>")
}

/** XML 1.0 excludes most control characters and unpaired UTF-16 surrogates. */
private fun xmlText(value: String): String = buildString {
    // SpreadsheetML interprets this notation as escaped characters. Escape its initial
    // underscore so an address containing a literal _xNNNN_ sequence round-trips unchanged.
    val literal = value.replace(Regex("_x[0-9A-Fa-f]{4}_")) { "_x005F_${it.value.drop(1)}" }
    var index = 0
    while (index < literal.length) {
        val codePoint = Character.codePointAt(literal, index)
        index += Character.charCount(codePoint)
        when (codePoint) {
            '&'.code -> append("&amp;")
            '<'.code -> append("&lt;")
            '>'.code -> append("&gt;")
            '"'.code -> append("&quot;")
            '\''.code -> append("&apos;")
            9, 10 -> append(codePoint.toChar())
            13 -> append("&#13;")
            in 0x20..0xD7FF, in 0xE000..0xFFFD, in 0x10000..0x10FFFF -> append(String(Character.toChars(codePoint)))
            else -> append('\uFFFD')
        }
    }
}

private fun ZipOutputStream.xmlEntry(name: String, xml: String) {
    putNextEntry(ZipEntry(name))
    write(xml.toByteArray(Charsets.UTF_8))
    closeEntry()
}

private val CONTENT_TYPES = XML_DECLARATION + """
    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
      <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
      <Default Extension="xml" ContentType="application/xml"/>
      <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
      <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
      <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
    </Types>
""".trimIndent()

private val PACKAGE_RELATIONSHIPS = XML_DECLARATION + """
    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
    </Relationships>
""".trimIndent()

private val WORKBOOK = XML_DECLARATION + """
    <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
      <bookViews><workbookView/></bookViews>
      <sheets><sheet name="Delivery Schedule" sheetId="1" r:id="rId1"/></sheets>
      <definedNames><definedName name="_xlnm.Print_Titles" localSheetId="0">'Delivery Schedule'!${'$'}21:${'$'}21</definedName></definedNames>
      <calcPr calcId="191029"/>
    </workbook>
""".trimIndent()

private val WORKBOOK_RELATIONSHIPS = XML_DECLARATION + """
    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
      <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
    </Relationships>
""".trimIndent()

private val STYLES = XML_DECLARATION + """
    <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
      <numFmts count="6">
        <numFmt numFmtId="164" formatCode="yyyy-mm-dd"/>
        <numFmt numFmtId="165" formatCode="yyyy-mm-dd h:mm AM/PM"/>
        <numFmt numFmtId="166" formatCode="0.0&quot; KM&quot;"/>
        <numFmt numFmtId="167" formatCode="[h]&quot; hr &quot;mm&quot; min&quot;"/>
        <numFmt numFmtId="168" formatCode="&quot;SGD &quot;#,##0.00"/>
        <numFmt numFmtId="169" formatCode="0.0%"/>
      </numFmts>
      <fonts count="4">
        <font><sz val="11"/><color rgb="FF07172D"/><name val="Calibri"/><family val="2"/></font>
        <font><b/><sz val="18"/><color rgb="FFD6B36A"/><name val="Calibri"/><family val="2"/></font>
        <font><b/><sz val="12"/><color rgb="FFD6B36A"/><name val="Calibri"/><family val="2"/></font>
        <font><b/><sz val="11"/><color rgb="FF07172D"/><name val="Calibri"/><family val="2"/></font>
      </fonts>
      <fills count="4">
        <fill><patternFill patternType="none"/></fill>
        <fill><patternFill patternType="gray125"/></fill>
        <fill><patternFill patternType="solid"><fgColor rgb="FF07172D"/><bgColor indexed="64"/></patternFill></fill>
        <fill><patternFill patternType="solid"><fgColor rgb="FFEEF2F8"/><bgColor indexed="64"/></patternFill></fill>
      </fills>
      <borders count="2">
        <border><left/><right/><top/><bottom/><diagonal/></border>
        <border><left style="thin"><color rgb="FFD9E1EF"/></left><right style="thin"><color rgb="FFD9E1EF"/></right><top style="thin"><color rgb="FFD9E1EF"/></top><bottom style="thin"><color rgb="FFD9E1EF"/></bottom><diagonal/></border>
      </borders>
      <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
      <cellXfs count="12">
        <xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyAlignment="1"><alignment vertical="center" wrapText="1"/></xf>
        <xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1" applyAlignment="1"><alignment vertical="center"/></xf>
        <xf numFmtId="0" fontId="2" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1" applyAlignment="1"><alignment vertical="center"/></xf>
        <xf numFmtId="0" fontId="3" fillId="0" borderId="0" xfId="0" applyFont="1" applyAlignment="1"><alignment vertical="center"/></xf>
        <xf numFmtId="164" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyAlignment="1"><alignment vertical="center"/></xf>
        <xf numFmtId="165" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyAlignment="1"><alignment vertical="center" wrapText="1"/></xf>
        <xf numFmtId="166" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyAlignment="1"><alignment vertical="center"/></xf>
        <xf numFmtId="167" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyAlignment="1"><alignment vertical="center"/></xf>
        <xf numFmtId="168" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyAlignment="1"><alignment vertical="center"/></xf>
        <xf numFmtId="169" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyAlignment="1"><alignment vertical="center"/></xf>
        <xf numFmtId="0" fontId="2" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyAlignment="1"><alignment vertical="center" wrapText="1"/></xf>
        <xf numFmtId="0" fontId="3" fillId="3" borderId="1" xfId="0" applyFont="1" applyFill="1" applyAlignment="1"><alignment vertical="center" wrapText="1"/></xf>
      </cellXfs>
      <cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
      <dxfs count="0"/>
      <tableStyles count="0" defaultTableStyle="TableStyleMedium2" defaultPivotStyle="PivotStyleLight16"/>
    </styleSheet>
""".trimIndent()
